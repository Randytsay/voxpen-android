package com.voxpen.app.data.remote

import com.voxpen.app.ime.PcmFrameSink
import com.voxpen.app.ime.StreamingTranscriptAccumulator
import com.voxpen.app.ime.StreamingTranscriptSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class ChirpStreamingConfig(
    val gatewayUrl: String,
    val gatewayToken: String,
    val languageCode: String,
    val model: String = "chirp_3",
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val encoding: String = "LINEAR16",
    val automaticPunctuation: Boolean = true,
    val adaptationPhrases: List<String> = emptyList(),
)

enum class StreamingStatus {
    Connecting,
    Ready,
    Interrupted,
    Finalizing,
    Completed,
    Error,
}

interface StreamingRecognitionListener {
    fun onPreview(snapshot: StreamingTranscriptSnapshot)

    fun onStatus(
        status: StreamingStatus,
        message: String? = null,
    )
}

data class StreamingRecognitionResult(
    val text: String,
    val usedInterimFallback: Boolean,
    val reconnectAttempts: Int,
    val droppedFrames: Int,
)

@Singleton
class StreamingRecognitionController
    @Inject
    constructor(
        private val client: okhttp3.OkHttpClient,
        private val json: Json,
    ) {
        fun start(
            config: ChirpStreamingConfig,
            listener: StreamingRecognitionListener,
        ): Session {
            require(config.gatewayToken.isNotBlank()) { "Gateway token is required" }
            require(config.languageCode.isNotBlank()) { "Chirp language is required" }
            return Session(config, listener).also { it.connect() }
        }

        @Suppress("SpreadOperator")
        inner class Session internal constructor(
            private val config: ChirpStreamingConfig,
            private val listener: StreamingRecognitionListener,
        ) : PcmFrameSink {
            private val accumulator = StreamingTranscriptAccumulator()
            private val queue = ArrayBlockingQueue<Outbound>(MAX_PENDING_FRAMES)
            private val recentFrames = ArrayDeque<ByteArray>(REPLAY_FRAME_COUNT)
            private val senderExecutor = Executors.newSingleThreadExecutor()
            private val scheduler = Executors.newSingleThreadScheduledExecutor()
            private val completed = CountDownLatch(1)
            private val reconnectAttempts = AtomicInteger(0)
            private val droppedFrames = AtomicInteger(0)
            private val closed = AtomicBoolean(false)
            private val cancelled = AtomicBoolean(false)
            private val stopping = AtomicBoolean(false)
            private val hasOpened = AtomicBoolean(false)
            private val socketMonitor = Object()
            private val sendLock = Any()

            @Volatile private var socket: WebSocket? = null
            @Volatile private var socketReady = false
            @Volatile private var failureMessage: String? = null

            init {
                senderExecutor.execute(::sendLoop)
            }

            override fun offer(frame: ByteArray): Boolean {
                if (closed.get() || stopping.get() || frame.isEmpty()) return false
                val copy = frame.copyOf()
                synchronized(recentFrames) {
                    if (recentFrames.size == REPLAY_FRAME_COUNT) recentFrames.removeFirst()
                    recentFrames.addLast(copy)
                }
                val accepted = queue.offer(Outbound.Audio(copy))
                if (!accepted) droppedFrames.incrementAndGet()
                return accepted
            }

            fun snapshot(): StreamingTranscriptSnapshot = accumulator.snapshot()

            fun previewText(): String = accumulator.snapshot().previewText

            fun finish(): Result<StreamingRecognitionResult> {
                if (!stopping.compareAndSet(false, true)) {
                    return Result.failure(IllegalStateException("Streaming session already finished"))
                }
                notifyStatus(StreamingStatus.Finalizing)
                if (!queue.offer(Outbound.Stop, STOP_ENQUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    queue.clear()
                    queue.offer(Outbound.Stop)
                }
                completed.await(FINALIZATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                val snapshot = accumulator.snapshot()
                val failure = failureMessage
                shutdown()
                if (failure != null) return Result.failure(IllegalStateException(failure))

                val finalText = snapshot.finalText.ifBlank { snapshot.interimText }
                if (finalText.isBlank()) {
                    return Result.failure(IllegalStateException("Streaming gateway returned no transcript"))
                }
                notifyStatus(StreamingStatus.Completed)
                return Result.success(
                    StreamingRecognitionResult(
                        text = finalText.trim(),
                        usedInterimFallback = snapshot.finalText.isBlank() && snapshot.interimText.isNotBlank(),
                        reconnectAttempts = reconnectAttempts.get(),
                        droppedFrames = droppedFrames.get(),
                    ),
                )
            }

            fun cancel() {
                if (!cancelled.compareAndSet(false, true)) return
                closed.set(true)
                stopping.set(true)
                synchronized(socketMonitor) { socketMonitor.notifyAll() }
                queue.clear()
                socket?.close(CANCEL_CODE, "cancelled")
                completed.countDown()
                shutdown()
            }

            internal fun connect() {
                if (closed.get() || cancelled.get()) return
                notifyStatus(StreamingStatus.Connecting)
                val request =
                    Request.Builder()
                        .url(toWebSocketUrl(config.gatewayUrl))
                        .header("Authorization", "Bearer ${config.gatewayToken}")
                        .build()
                socket = client.newWebSocket(request, socketListener)
            }

            private val socketListener =
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        if (closed.get()) {
                            webSocket.close(CANCEL_CODE, "closed")
                            return
                        }
                        synchronized(sendLock) {
                            val isReconnect = hasOpened.getAndSet(true)
                            socket = webSocket
                            webSocket.send(startMessage())
                            if (isReconnect) {
                                synchronized(recentFrames) {
                                    recentFrames.forEach { frame ->
                                        webSocket.send(ByteString.of(*frame))
                                    }
                                }
                            }
                            socketReady = true
                        }
                        synchronized(socketMonitor) { socketMonitor.notifyAll() }
                        notifyStatus(StreamingStatus.Ready)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        handleMessage(text)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        socketReady = false
                        if (stopping.get() || cancelled.get()) {
                            completed.countDown()
                        } else {
                            handleDisconnect("Streaming gateway closed")
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        socketReady = false
                        if (!cancelled.get()) handleDisconnect("Streaming connection interrupted")
                    }
                }

            private fun handleMessage(text: String) {
                val message =
                    runCatching { json.parseToJsonElement(text).jsonObject }
                        .getOrNull()
                        ?: return
                when (message["type"]?.jsonPrimitive?.contentOrNull) {
                    "ready" -> notifyStatus(StreamingStatus.Ready)
                    "interim" -> {
                        accumulator.acceptInterim(message["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        notifyPreview()
                    }
                    "final" -> {
                        accumulator.acceptFinal(
                            text = message["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            segmentId = message["segmentId"]?.jsonPrimitive?.contentOrNull,
                        )
                        notifyPreview()
                    }
                    "closed" -> completed.countDown()
                    "error" -> {
                        failureMessage = "Streaming recognition failed"
                        notifyStatus(StreamingStatus.Error, failureMessage)
                        completed.countDown()
                    }
                }
            }

            private fun notifyPreview() {
                runCatching { listener.onPreview(accumulator.snapshot()) }
            }

            private fun notifyStatus(
                status: StreamingStatus,
                message: String? = null,
            ) {
                runCatching { listener.onStatus(status, message) }
            }

            private fun handleDisconnect(message: String) {
                socketReady = false
                if (stopping.get() && accumulator.stableFinalText().isBlank()) {
                    failureMessage = message
                }
                val attempt = reconnectAttempts.incrementAndGet()
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    failureMessage = message
                    notifyStatus(StreamingStatus.Error, message)
                    completed.countDown()
                    return
                }
                notifyStatus(StreamingStatus.Interrupted, message)
                scheduler.schedule(
                    { connect() },
                    reconnectDelayMillis(attempt),
                    TimeUnit.MILLISECONDS,
                )
            }

            private fun sendLoop() {
                try {
                    while (!closed.get()) {
                        val outbound = queue.take()
                        synchronized(socketMonitor) {
                            while (!closed.get() && !socketReady) socketMonitor.wait(WAIT_FOR_SOCKET_MS)
                        }
                        if (closed.get()) return
                        val webSocket = socket ?: continue
                        synchronized(sendLock) {
                            when (outbound) {
                                is Outbound.Audio -> webSocket.send(ByteString.of(*outbound.bytes))
                                Outbound.Stop -> {
                                    webSocket.send(stopMessage())
                                    return
                                }
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            private fun shutdown() {
                if (!closed.compareAndSet(false, true)) return
                synchronized(socketMonitor) { socketMonitor.notifyAll() }
                socket?.close(NORMAL_CODE, "complete")
                senderExecutor.shutdownNow()
                scheduler.shutdownNow()
            }

            private fun startMessage(): String =
                buildJsonObject {
                    put("type", "start")
                    put("model", config.model)
                    put("languageCode", config.languageCode)
                    put("sampleRateHz", config.sampleRateHz)
                    put("channels", config.channels)
                    put("encoding", config.encoding)
                    put("automaticPunctuation", config.automaticPunctuation)
                    putJsonArray("adaptationPhrases") {
                        config.adaptationPhrases.forEach { add(JsonPrimitive(it)) }
                    }
                }.toString()

            private fun stopMessage(): String =
                buildJsonObject { put("type", "stop") }.toString()
        }

        private sealed interface Outbound {
            data class Audio(val bytes: ByteArray) : Outbound

            data object Stop : Outbound
        }

        companion object {
            private const val MAX_PENDING_FRAMES = 30
            private const val REPLAY_FRAME_COUNT = 10
            private const val MAX_RECONNECT_ATTEMPTS = 2
            private const val FINALIZATION_TIMEOUT_MS = 2_200L
            private const val STOP_ENQUEUE_TIMEOUT_MS = 500L
            private const val WAIT_FOR_SOCKET_MS = 100L
            private const val NORMAL_CODE = 1000
            private const val CANCEL_CODE = 1001

            fun toWebSocketUrl(raw: String): String {
                var value = raw.trim().trimEnd('/')
                require(value.isNotBlank()) { "Gateway URL is required" }
                val scheme =
                    when {
                        value.startsWith("https://", ignoreCase = true) -> "wss://"
                        value.startsWith("wss://", ignoreCase = true) -> "wss://"
                        else -> error("Chirp Gateway URL must use HTTPS/WSS")
                    }
                value = value.replaceFirst(Regex("^(https?|wss?)://"), "")
                if (value.endsWith("/v1", ignoreCase = true)) value = value.dropLast(3)
                return "$scheme$value/v1/speech/stream"
            }

            private fun reconnectDelayMillis(attempt: Int): Long =
                when (attempt) {
                    1 -> 250L
                    else -> 750L
                }
        }
    }
