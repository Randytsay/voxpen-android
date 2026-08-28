package com.voxpen.app.ime

import com.voxpen.app.billing.ProStatus
import com.voxpen.app.billing.UsageLimiter
import com.voxpen.app.data.local.ApiKeyManager
import com.voxpen.app.data.local.ContextMemoryManager
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.local.RecordingStore
import com.voxpen.app.data.model.LlmProvider
import com.voxpen.app.data.model.RefinementContext
import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.model.SttProvider
import com.voxpen.app.data.model.ToneStyle
import com.voxpen.app.data.remote.ChirpStreamingConfig
import com.voxpen.app.data.remote.StreamingRecognitionController
import com.voxpen.app.data.remote.StreamingRecognitionListener
import com.voxpen.app.data.remote.StreamingStatus
import com.voxpen.app.data.repository.CorrectionMemoryRepository
import com.voxpen.app.data.repository.DictionaryRepository
import com.voxpen.app.data.repository.TranscriptionRepository
import com.voxpen.app.domain.usecase.RefineTextUseCase
import com.voxpen.app.domain.usecase.TranscribeAudioUseCase
import com.voxpen.app.util.ChirpAdaptationBuilder
import com.voxpen.app.util.ChirpLanguageMapper
import com.voxpen.app.util.RecordingValidator
import com.voxpen.app.util.VocabularyPromptBuilder
import com.voxpen.app.util.VocabularySelector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class RecordingController(
    private val transcribeUseCase: TranscribeAudioUseCase,
    private val refineTextUseCase: RefineTextUseCase,
    private val apiKeyManager: ApiKeyManager,
    private val preferencesManager: PreferencesManager,
    private val dictionaryRepository: DictionaryRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val recordingStore: RecordingStore,
    private val usageLimiter: UsageLimiter,
    private val proStatusProvider: () -> ProStatus,
    private val ioDispatcher: CoroutineDispatcher,
    private val correctionMemoryRepository: CorrectionMemoryRepository? = null,
    private val messages: RecordingMessages = RecordingMessages.English,
    private val contextMemoryManager: ContextMemoryManager? = null,
    private val streamingRecognitionController: StreamingRecognitionController? = null,
) {
    private val scope =
        CoroutineScope(
            SupervisorJob() + ioDispatcher,
        )

    private var refinementEnabled: Boolean =
        PreferencesManager.DEFAULT_REFINEMENT_ENABLED

    private var sttModel: String =
        PreferencesManager.DEFAULT_STT_MODEL

    private var sttProvider: SttProvider =
        SttProvider.DEFAULT

    private var llmModel: String =
        PreferencesManager.DEFAULT_LLM_MODEL

    private var toneStyle: ToneStyle =
        ToneStyle.DEFAULT

    private var llmProvider: LlmProvider =
        LlmProvider.DEFAULT

    private var customLlmModel: String =
        ""

    private var customSttBaseUrl: String =
        ""

    private var translationEnabled: Boolean =
        PreferencesManager.DEFAULT_TRANSLATION_ENABLED

    private var translationTargetLanguage: SttLanguage =
        PreferencesManager.DEFAULT_TRANSLATION_TARGET_LANGUAGE

    private var streamingLivePreview: Boolean =
        PreferencesManager.DEFAULT_STREAMING_LIVE_PREVIEW

    private var streamingFallbackToGroq: Boolean =
        PreferencesManager.DEFAULT_STREAMING_FALLBACK_TO_GROQ

    private var activeStreamingSession: StreamingRecognitionController.Session? = null

    init {
        scope.launch {
            preferencesManager.refinementEnabledFlow.collect {
                refinementEnabled = it
            }
        }

        scope.launch {
            preferencesManager.sttModelFlow.collect {
                sttModel = it
            }
        }

        scope.launch {
            preferencesManager.sttProviderFlow.collect {
                sttProvider = it
            }
        }

        scope.launch {
            preferencesManager.llmModelFlow.collect {
                llmModel = it
            }
        }

        scope.launch {
            preferencesManager.toneStyleFlow.collect {
                toneStyle = it
            }
        }

        scope.launch {
            preferencesManager.llmProviderFlow.collect {
                llmProvider = it
            }
        }

        scope.launch {
            preferencesManager.customLlmModelFlow.collect {
                customLlmModel = it
            }
        }

        scope.launch {
            preferencesManager.customSttBaseUrlFlow.collect {
                customSttBaseUrl = it
            }
        }

        scope.launch {
            preferencesManager.translationEnabledFlow.collect {
                translationEnabled = it
            }
        }

        scope.launch {
            preferencesManager.translationTargetLanguageFlow.collect {
                translationTargetLanguage = it
            }
        }

        scope.launch {
            preferencesManager.streamingLivePreviewFlow.collect {
                streamingLivePreview = it
            }
        }

        scope.launch {
            preferencesManager.streamingFallbackToGroqFlow.collect {
                streamingFallbackToGroq = it
            }
        }
    }

    private val _uiState =
        MutableStateFlow<ImeUiState>(
            ImeUiState.Idle,
        )

    val uiState: StateFlow<ImeUiState> =
        _uiState.asStateFlow()

    /** Compatibility entry point for the existing batch-recognition tests and callers. */
    fun onStartRecording(startRecording: () -> Unit) {
        val proStatus = proStatusProvider()
        if (!proStatus.isPro && !usageLimiter.canUseVoiceInput()) {
            _uiState.value =
                ImeUiState.Error(
                    "Daily limit reached (${usageLimiter.remainingVoiceInputs()} remaining). " +
                        "Upgrade to Pro for unlimited use.",
                )
            return
        }
        startRecording()
        _uiState.value = ImeUiState.Recording
    }

    suspend fun onStartRecording(
        startRecording: (PcmFrameSink?) -> Boolean,
    ): Boolean {
        val proStatus =
            proStatusProvider()

        if (
            !proStatus.isPro &&
            !usageLimiter.canUseVoiceInput()
        ) {
            val remaining =
                usageLimiter.remainingVoiceInputs()

            _uiState.value =
                ImeUiState.Error(
                    "Daily limit reached ($remaining remaining). " +
                        "Upgrade to Pro for unlimited use.",
                )

            return false
        }

        val currentProvider = sttProvider
        val sessionResult =
            if (currentProvider == SttProvider.Chirp3Streaming) {
                prepareStreamingSession()
            } else {
                Result.success(null)
            }
        if (sessionResult.isFailure) {
            _uiState.value = ImeUiState.Error(sessionResult.exceptionOrNull()?.message.orEmpty())
            return false
        }
        val session = sessionResult.getOrNull()

        if (!startRecording(session)) {
            session?.cancel()
            _uiState.value = ImeUiState.Error("Unable to start microphone")
            return false
        }

        activeStreamingSession = session

        _uiState.value =
            if (session == null) {
                ImeUiState.Recording
            } else {
                ImeUiState.Streaming("", "Connecting…")
            }
        return true
    }

    private suspend fun prepareStreamingSession(): Result<StreamingRecognitionController.Session> {
        val controller = streamingRecognitionController
        val gatewayUrl = apiKeyManager.getVertexGatewayUrl().orEmpty()
        val gatewayToken = apiKeyManager.getSttApiKey(SttProvider.Chirp3Streaming).orEmpty()
        if (controller == null || gatewayUrl.isBlank() || gatewayToken.isBlank()) {
            return Result.failure(IllegalStateException("Chirp Gateway URL and token are required"))
        }
        val language = preferencesManager.languageFlow.first()
        val languageCode =
            runCatching { ChirpLanguageMapper.map(language) }
                .getOrElse {
                    return Result.failure(IllegalStateException("Selected language is not supported by Chirp 3"))
                }
        val vocabulary = loadVocabulary()
        return runCatching {
            controller.start(
                config =
                    ChirpStreamingConfig(
                        gatewayUrl = gatewayUrl,
                        gatewayToken = gatewayToken,
                        languageCode = languageCode,
                        adaptationPhrases =
                            ChirpAdaptationBuilder
                                .build(vocabulary.important, vocabulary.ordinary)
                                .phrases,
                    ),
                listener = streamingListener(),
            )
        }
    }

    fun onStopRecording(
        stopRecording: () -> ByteArray,
        language: SttLanguage,
        editMode: Boolean = false,
        toneOverride: ToneStyle? = null,
        packageName: String = "",
        allowCorrectionMemory: Boolean = true,
        contextPackageName: String? = null,
        useContext: Boolean = false,
    ) {
        val currentSttProvider = sttProvider
        val streamingSession = activeStreamingSession
        activeStreamingSession = null
        val pcmData =
            stopRecording()

        when (
            RecordingValidator.validate(
                pcmData,
            )
        ) {
            RecordingValidator.Result.TooShort -> {
                streamingSession?.cancel()
                _uiState.value =
                    ImeUiState.Error(
                        messages.recordingTooShort(),
                    )

                return
            }

            RecordingValidator.Result.Silent -> {
                streamingSession?.cancel()
                _uiState.value =
                    ImeUiState.Error(
                        messages.recordingTooQuiet(),
                    )

                return
            }

            RecordingValidator.Result.Valid ->
                Unit
        }

        val apiKey =
            apiKeyManager.getSttApiKey(
                currentSttProvider,
            )

        if (
            apiKey.isNullOrBlank() &&
            currentSttProvider != SttProvider.Custom
        ) {
            streamingSession?.cancel()
            _uiState.value =
                ImeUiState.Error(
                    messages.apiKeyNotConfigured(),
                )

            return
        }

        if (currentSttProvider == SttProvider.Chirp3Streaming &&
            apiKeyManager.getVertexGatewayUrl().isNullOrBlank()
        ) {
            streamingSession?.cancel()
            _uiState.value = ImeUiState.Error("Chirp Gateway URL is required")
            return
        }

        val effectiveTone =
            toneOverride ?: toneStyle

        _uiState.value =
            if (currentSttProvider == SttProvider.Chirp3Streaming) {
                ImeUiState.Finalizing(streamingSession?.previewText().orEmpty())
            } else {
                ImeUiState.Processing
            }

        scope.launch {
            val proStatus =
                proStatusProvider()

            val vocabularySelection = loadVocabulary()
            val importantVocabulary = vocabularySelection.important
            val ordinaryVocabulary = vocabularySelection.ordinary
            val vocabulary = vocabularySelection.all

            val whisperPrompt =
                if (vocabulary.isNotEmpty()) {
                    VocabularyPromptBuilder
                        .buildWhisperPrompt(
                            language,
                            vocabulary,
                        )
                } else {
                    null
                }

            val sttBaseUrl =
                customSttBaseUrl
                    .ifBlank { null }

            val result =
                if (currentSttProvider == SttProvider.Chirp3Streaming) {
                    val streamResult =
                        streamingSession?.finish()
                            ?: Result.failure(IllegalStateException("Streaming session was not started"))
                    if (streamResult.isSuccess) {
                        streamResult.map { it.text }
                    } else if (streamingFallbackToGroq) {
                        val groqKey = apiKeyManager.getGroqApiKey().orEmpty()
                        if (groqKey.isBlank()) {
                            Result.failure(
                                IllegalStateException("Streaming failed and Groq fallback is not configured"),
                            )
                        } else {
                            transcribeUseCase(
                                pcmData = pcmData,
                                language = language,
                                apiKey = groqKey,
                                model = SttProvider.Groq.defaultModelId,
                                vocabularyHint = whisperPrompt,
                                provider = SttProvider.Groq,
                                customSttBaseUrl = null,
                            )
                        }
                    } else {
                        streamResult.map { it.text }
                    }
                } else {
                    transcribeUseCase(
                        pcmData = pcmData,
                        language = language,
                        apiKey = apiKey.orEmpty(),
                        model = sttModel,
                        vocabularyHint = whisperPrompt,
                        provider = currentSttProvider,
                        customSttBaseUrl = sttBaseUrl,
                    )
                }

            result.fold(
                onSuccess = { originalText ->
                    if (!proStatus.isPro) {
                        usageLimiter
                            .incrementVoiceInput()
                    }

                    if (editMode) {
                        _uiState.value =
                            ImeUiState.EditInstruction(
                                originalText,
                            )

                        return@launch
                    }

                    val command =
                        VoiceCommandRecognizer
                            .recognize(
                                originalText,
                            )

                    if (command != null) {
                        _uiState.value =
                            ImeUiState.CommandDetected(
                                command,
                            )

                        return@launch
                    }

                    val correctionPreparation =
                        correctionMemoryRepository
                            ?.prepareForText(
                                text = originalText,
                                packageName = packageName,
                                allowMemory = allowCorrectionMemory,
                            )

                    val correctedOriginalText =
                        correctionPreparation?.correctedText
                            ?: originalText

                    val correctionHints =
                        correctionPreparation?.hints
                            ?: emptyList()

                    val shouldRefine =
                        refinementEnabled &&
                            canUseRefinement(
                                proStatus,
                            )

                    if (!shouldRefine) {
                        _uiState.value =
                            ImeUiState.Result(
                                correctedOriginalText,
                            )

                        return@launch
                    }

                    _uiState.value =
                        ImeUiState.Refining(
                            correctedOriginalText,
                        )

                    if (!proStatus.isPro) {
                        usageLimiter
                            .incrementRefinement()
                    }

                    val relevantVocabulary = VocabularySelector.selectRelevant(
                        transcription = originalText,
                        ordinaryVocabulary = ordinaryVocabulary,
                    )
                    val recentContext = if (
                        useContext &&
                        !contextPackageName.isNullOrBlank() &&
                        contextMemoryManager != null
                    ) {
                        contextMemoryManager.getRecentInputs(contextPackageName)
                    } else {
                        emptyList()
                    }
                    val refinementContext = RefinementContext(
                        importantTerms = importantVocabulary,
                        relevantTerms = relevantVocabulary,
                        recentContext = recentContext,
                    )
                    val allVocabulary = vocabulary

                    val langKey =
                        PreferencesManager
                            .languageToKey(
                                language,
                            )

                    val customPrompt =
                        preferencesManager
                            .customPromptFlow(
                                langKey,
                            )
                            .first()

                    val resolvedModel =
                        if (llmProvider == LlmProvider.Custom) {
                            customLlmModel
                                .ifBlank {
                                    llmModel
                                }
                        } else {
                            llmModel
                        }

                    val customBaseUrl =
                        when (llmProvider) {
                            LlmProvider.Custom -> apiKeyManager.getCustomBaseUrl()
                            LlmProvider.Vertex -> apiKeyManager.getVertexGatewayUrl()
                            else -> null
                        }

                    val llmApiKey =
                        apiKeyManager
                            .getApiKey(
                                llmProvider,
                            )
                            .orEmpty()

                    val refinedResult =
                        refineTextUseCase(
                            text = correctedOriginalText,
                            language = language,
                            apiKey = llmApiKey,
                            model = resolvedModel,
                            vocabulary = allVocabulary,
                            customPrompt = customPrompt,
                            tone = effectiveTone,
                            provider = llmProvider,
                            customBaseUrl = customBaseUrl,
                            translationEnabled = translationEnabled,
                            targetLanguage = translationTargetLanguage,
                            correctionHints = correctionHints,
                            refinementContext = refinementContext,
                        )

                    _uiState.value =
                        refinedResult.fold(
                            onSuccess = {
                                ImeUiState.Refined(
                                    correctedOriginalText,
                                    it,
                                )
                            },
                            onFailure = {
                                ImeUiState.Result(
                                    correctedOriginalText,
                                )
                            },
                        )
                },

                onFailure = {
                    val message =
                        messages
                            .transcriptionFailed(
                                it.message,
                            )

                    runCatching {
                        val audioPath =
                            recordingStore
                                .saveLiveRecording(
                                    pcmData,
                                )

                        transcriptionRepository
                            .insertFailedLive(
                                audioPath = audioPath,
                                provider = currentSttProvider,
                                language = language,
                                errorMessage = message,
                            )
                    }.onFailure { saveError ->
                        Timber.w(
                            saveError,
                            "failed_recording_save_failed provider=%s",
                            currentSttProvider.key,
                        )
                    }

                    _uiState.value =
                        ImeUiState.Error(
                            message,
                        )
                },
            )
        }
    }

    private suspend fun loadVocabulary(): VocabularySelection =
        withContext(ioDispatcher) {
            val recentVocabulary = dictionaryRepository.getWords(500)
            val importantWords = preferencesManager.importantWordsFlow.first()
            val importantVocabulary =
                VocabularySelector.prioritizeImportant(
                    importantWords = importantWords,
                    recentVocabulary = recentVocabulary,
                )
            val ordinaryVocabulary = recentVocabulary.filterNot { it in importantWords }
            VocabularySelection(
                important = importantVocabulary,
                ordinary = ordinaryVocabulary,
                all = (importantVocabulary + ordinaryVocabulary).distinct(),
            )
        }

    private fun streamingListener(): StreamingRecognitionListener =
        object : StreamingRecognitionListener {
            private var statusLabel = "Connecting…"

            override fun onPreview(snapshot: StreamingTranscriptSnapshot) {
                if (streamingLivePreview) {
                    _uiState.value = ImeUiState.Streaming(snapshot.previewText, statusLabel)
                }
            }

            override fun onStatus(
                status: StreamingStatus,
                message: String?,
            ) {
                statusLabel =
                    when (status) {
                        StreamingStatus.Connecting -> "Connecting…"
                        StreamingStatus.Ready -> "Streaming…"
                        StreamingStatus.Interrupted -> "Streaming interrupted; recording continues"
                        StreamingStatus.Finalizing -> "Finalizing…"
                        StreamingStatus.Completed -> "Completed"
                        StreamingStatus.Error -> message ?: "Streaming interrupted"
                    }
                if (status != StreamingStatus.Completed) {
                    val snapshot = activeStreamingSession?.snapshot() ?: return
                    _uiState.value =
                        if (status == StreamingStatus.Finalizing) {
                            ImeUiState.Finalizing(snapshot.previewText)
                        } else {
                            ImeUiState.Streaming(
                                if (streamingLivePreview) snapshot.previewText else "",
                                statusLabel,
                            )
                        }
                }
            }
        }

    private data class VocabularySelection(
        val important: List<String>,
        val ordinary: List<String>,
        val all: List<String>,
    )

    private fun canUseRefinement(
        proStatus: ProStatus,
    ): Boolean =
        proStatus.isPro ||
            usageLimiter.canUseRefinement()

    fun dismiss() {
        activeStreamingSession?.cancel()
        activeStreamingSession = null
        _uiState.value =
            ImeUiState.Idle
    }

    fun destroy() {
        activeStreamingSession?.cancel()
        activeStreamingSession = null
        scope.cancel()
    }
}
