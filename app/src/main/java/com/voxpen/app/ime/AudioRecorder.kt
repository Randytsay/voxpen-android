package com.voxpen.app.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.io.ByteArrayOutputStream

class AudioRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmOutput: ByteArrayOutputStream? = null

    @Volatile
    private var isRecording = false

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("ReturnCount")
    fun startRecording(frameSink: PcmFrameSink? = null): Boolean {
        if (!hasPermission()) {
            Timber.w("RECORD_AUDIO permission not granted")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Timber.e("Invalid buffer size: %d", bufferSize)
            return false
        }

        val recorder = createAudioRecord(bufferSize) ?: return false
        audioRecord = recorder

        pcmOutput = ByteArrayOutputStream()
        isRecording = true
        recorder.startRecording()

        recordingThread =
            Thread {
                val buffer = ByteArray(bufferSize)
                val frameAccumulator = ByteArrayOutputStream(FRAME_BYTES)
                while (isRecording) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        pcmOutput?.write(buffer, 0, bytesRead)
                        if (frameSink != null) {
                            frameAccumulator.write(buffer, 0, bytesRead)
                            while (frameAccumulator.size() >= FRAME_BYTES) {
                                val frame = frameAccumulator.toByteArray()
                                frameSink.offer(frame.copyOf(FRAME_BYTES))
                                frameAccumulator.reset()
                                if (frame.size > FRAME_BYTES) {
                                    frameAccumulator.write(frame, FRAME_BYTES, frame.size - FRAME_BYTES)
                                }
                            }
                        }
                    }
                }
                if (frameSink != null && frameAccumulator.size() > 0) {
                    frameSink.offer(frameAccumulator.toByteArray())
                }
            }.apply { start() }

        Timber.d("Recording started")
        return true
    }

    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val recorder =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize,
                )
            } catch (e: SecurityException) {
                Timber.e(e, "SecurityException creating AudioRecord")
                return null
            }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord failed to initialize")
            recorder.release()
            return null
        }
        return recorder
    }

    fun stopRecording(): ByteArray {
        isRecording = false
        recordingThread?.join(STOP_TIMEOUT_MS)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcmData = pcmOutput?.toByteArray() ?: ByteArray(0)
        pcmOutput?.close()
        pcmOutput = null

        Timber.d("Recording stopped, captured %d bytes", pcmData.size)
        return pcmData
    }

    fun release() {
        if (isRecording) stopRecording()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_DURATION_MS = 100
        const val FRAME_BYTES = SAMPLE_RATE * FRAME_DURATION_MS / 1000 * 2
        private const val STOP_TIMEOUT_MS = 2000L
    }
}
