package com.voxpen.app.ime

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voxpen.app.billing.ProSource
import com.voxpen.app.billing.ProStatus
import com.voxpen.app.billing.UsageLimiter
import com.voxpen.app.data.local.ApiKeyManager
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.local.RecordingStore
import com.voxpen.app.data.model.LlmProvider
import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.model.SttProvider
import com.voxpen.app.data.model.ToneStyle
import com.voxpen.app.data.model.VoiceCommand
import com.voxpen.app.data.remote.ChatChoice
import com.voxpen.app.data.remote.ChatCompletionApi
import com.voxpen.app.data.remote.ChatCompletionApiFactory
import com.voxpen.app.data.remote.ChatCompletionResponse
import com.voxpen.app.data.remote.ChatMessage
import com.voxpen.app.data.remote.SttApi
import com.voxpen.app.data.remote.SttApiFactory
import com.voxpen.app.data.remote.WhisperResponse
import com.voxpen.app.data.repository.DictionaryRepository
import com.voxpen.app.data.repository.LlmRepository
import com.voxpen.app.data.repository.SttRepository
import com.voxpen.app.data.repository.TranscriptionRepository
import com.voxpen.app.domain.usecase.RefineTextUseCase
import com.voxpen.app.domain.usecase.TranscribeAudioUseCase
import com.voxpen.app.util.AudioSilenceDetectorTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingControllerTest {
    private val sttApi: SttApi =
        mockk()

    private val sttApiFactory: SttApiFactory =
        mockk()

    private val chatCompletionApi: ChatCompletionApi =
        mockk()

    private val apiFactory: ChatCompletionApiFactory =
        mockk()

    private val apiKeyManager: ApiKeyManager =
        mockk()

    private val preferencesManager: PreferencesManager =
        mockk()

    private val dictionaryRepository: DictionaryRepository =
        mockk()

    private val transcriptionRepository: TranscriptionRepository =
        mockk(relaxed = true)

    private val recordingStore: RecordingStore =
        mockk(relaxed = true)

    private val testDispatcher =
        UnconfinedTestDispatcher()

    private val usageLimiter =
        UsageLimiter()

    private var proStatus: ProStatus =
        ProStatus.Free

    private lateinit var controller:
        RecordingController

    private val refinementEnabledFlow =
        MutableStateFlow(true)

    private val sttModelFlow =
        MutableStateFlow(
            PreferencesManager.DEFAULT_STT_MODEL,
        )

    private val sttProviderFlow =
        MutableStateFlow<SttProvider>(
            SttProvider.Groq,
        )

    private val llmModelFlow =
        MutableStateFlow(
            PreferencesManager.DEFAULT_LLM_MODEL,
        )

    private val toneStyleFlow =
        MutableStateFlow<ToneStyle>(
            ToneStyle.Casual,
        )

    private val llmProviderFlow =
        MutableStateFlow<LlmProvider>(
            LlmProvider.Groq,
        )

    private val customLlmModelFlow =
        MutableStateFlow("")

    private val customSttBaseUrlFlow =
        MutableStateFlow("")

    private val translationEnabledFlow =
        MutableStateFlow(false)

    private val translationTargetLanguageFlow =
        MutableStateFlow<SttLanguage>(
            SttLanguage.English,
        )

    private val streamingLivePreviewFlow = MutableStateFlow(true)

    private val streamingFallbackToGroqFlow = MutableStateFlow(false)

    /*
     * 新增：
     * 模擬 App 中使用者設定的 ⭐ 重要詞。
     *
     * 預設沒有重要詞，
     * 各測試若需要可自行改變 value。
     */
    private val importantWordsFlow =
        MutableStateFlow<Set<String>>(
            emptySet(),
        )

    private var fakeRecordedAudio: ByteArray =
        AudioSilenceDetectorTest.generateSineWave(
            durationMs = 500,
        )

    private var isRecording =
        false

    private val startRecording: () -> Unit = {
        isRecording = true
    }

    private val stopRecording: () -> ByteArray = {
        isRecording = false
        fakeRecordedAudio
    }

    @BeforeEach
    fun setUp() {
        /*
         * 每個 test 開始前恢復共同狀態，
         * 避免上一個 test 的值污染下一個。
         */
        proStatus =
            ProStatus.Free

        refinementEnabledFlow.value =
            true

        sttModelFlow.value =
            PreferencesManager.DEFAULT_STT_MODEL

        sttProviderFlow.value =
            SttProvider.Groq

        llmModelFlow.value =
            PreferencesManager.DEFAULT_LLM_MODEL

        toneStyleFlow.value =
            ToneStyle.Casual

        llmProviderFlow.value =
            LlmProvider.Groq

        customLlmModelFlow.value =
            ""

        customSttBaseUrlFlow.value =
            ""

        translationEnabledFlow.value =
            false

        translationTargetLanguageFlow.value =
            SttLanguage.English

        streamingLivePreviewFlow.value = true
        streamingFallbackToGroqFlow.value = false

        importantWordsFlow.value =
            emptySet()

        fakeRecordedAudio =
            AudioSilenceDetectorTest.generateSineWave(
                durationMs = 500,
            )

        isRecording =
            false

        every {
            apiKeyManager.getGroqApiKey()
        } returns "test-key"

        every {
            apiKeyManager.getApiKey(
                any(),
            )
        } returns "test-key"

        every {
            apiKeyManager.getSttApiKey(
                any(),
            )
        } returns "test-key"

        every {
            preferencesManager.refinementEnabledFlow
        } returns refinementEnabledFlow

        every {
            preferencesManager.sttModelFlow
        } returns sttModelFlow

        every {
            preferencesManager.sttProviderFlow
        } returns sttProviderFlow

        every {
            preferencesManager.llmModelFlow
        } returns llmModelFlow

        every {
            preferencesManager.toneStyleFlow
        } returns toneStyleFlow

        every {
            preferencesManager.llmProviderFlow
        } returns llmProviderFlow

        every {
            preferencesManager.customLlmModelFlow
        } returns customLlmModelFlow

        every {
            preferencesManager.customSttBaseUrlFlow
        } returns customSttBaseUrlFlow

        every {
            preferencesManager.translationEnabledFlow
        } returns translationEnabledFlow

        every {
            preferencesManager.translationTargetLanguageFlow
        } returns translationTargetLanguageFlow

        every {
            preferencesManager.streamingLivePreviewFlow
        } returns streamingLivePreviewFlow

        every {
            preferencesManager.streamingFallbackToGroqFlow
        } returns streamingFallbackToGroqFlow

        /*
         * 新增：
         * RecordingController 現在一定會讀這個 Flow。
         */
        every {
            preferencesManager.importantWordsFlow
        } returns importantWordsFlow

        every {
            preferencesManager.customPromptFlow(
                any(),
            )
        } returns MutableStateFlow(null)

        coEvery {
            dictionaryRepository.getWords(
                any(),
            )
        } returns listOf(
            "語墨",
            "Claude",
        )

        every {
            apiFactory.create(
                any(),
            )
        } returns chatCompletionApi

        every {
            sttApiFactory.createForProvider(
                any(),
            )
        } returns sttApi

        val sttRepository =
            SttRepository(
                sttApiFactory,
            )

        val llmRepository =
            LlmRepository(
                apiFactory,
            )

        val transcribeUseCase =
            TranscribeAudioUseCase(
                sttRepository,
            )

        val refineTextUseCase =
            RefineTextUseCase(
                llmRepository,
            )

        controller =
            RecordingController(
                transcribeUseCase =
                    transcribeUseCase,
                refineTextUseCase =
                    refineTextUseCase,
                apiKeyManager =
                    apiKeyManager,
                preferencesManager =
                    preferencesManager,
                dictionaryRepository =
                    dictionaryRepository,
                transcriptionRepository =
                    transcriptionRepository,
                recordingStore =
                    recordingStore,
                usageLimiter =
                    usageLimiter,
                proStatusProvider = {
                    proStatus
                },
                ioDispatcher =
                    testDispatcher,
            )
    }

    @Test
    fun `should start in Idle state`() =
        runTest {
            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )
            }
        }

    @Test
    fun `should transition to Recording on start`() =
        runTest {
            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Recording,
                )
            }
        }

    @Test
    fun `should transition through Refining to Refined when enabled`() =
        runTest {
            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "嗯那個明天開會",
            )

            coEvery {
                chatCompletionApi.chatCompletion(
                    any(),
                    any(),
                )
            } returns chatResponse(
                "明天開會",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Recording,
                )

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Chinese,
                )

                /*
                 * StateFlow 可能合併中間狀態，
                 * Refining 有可能不單獨被觀察到。
                 */
                val states =
                    mutableListOf(
                        awaitItem(),
                    )

                states.add(
                    awaitItem(),
                )

                val finalState =
                    states.last()

                assertThat(
                    finalState,
                ).isEqualTo(
                    ImeUiState.Refined(
                        "嗯那個明天開會",
                        "明天開會",
                    ),
                )
            }
        }

    @Test
    fun `should use LLM provider API key for refinement when STT provider differs`() =
        runTest {
            sttProviderFlow.value =
                SttProvider.OpenAI

            llmProviderFlow.value =
                LlmProvider.Groq

            every {
                apiKeyManager.getSttApiKey(
                    SttProvider.OpenAI,
                )
            } returns "stt-openai-key"

            every {
                apiKeyManager.getApiKey(
                    LlmProvider.Groq,
                )
            } returns "llm-groq-key"

            val authSlot =
                slot<String>()

            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "raw text",
            )

            coEvery {
                chatCompletionApi.chatCompletion(
                    capture(authSlot),
                    any(),
                )
            } returns chatResponse(
                "refined text",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.English,
                )

                val states =
                    mutableListOf(
                        awaitItem(),
                    )

                states.add(
                    awaitItem(),
                )

                assertThat(
                    states.last(),
                ).isEqualTo(
                    ImeUiState.Refined(
                        "raw text",
                        "refined text",
                    ),
                )
            }

            assertThat(
                authSlot.captured,
            ).isEqualTo(
                "Bearer llm-groq-key",
            )
        }

    @Test
    fun `should go to Result when refinement disabled`() =
        runTest {
            refinementEnabledFlow.value =
                false

            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "hello world",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.English,
                )

                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Processing,
                )

                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Result(
                        "hello world",
                    ),
                )
            }
        }

    @Test
    fun `should fall back to Result when refinement fails`() =
        runTest {
            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "raw text",
            )

            coEvery {
                chatCompletionApi.chatCompletion(
                    any(),
                    any(),
                )
            } throws IOException(
                "LLM error",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Auto,
                )

                val states =
                    mutableListOf(
                        awaitItem(),
                    )

                states.add(
                    awaitItem(),
                )

                val finalState =
                    states.last()

                assertThat(
                    finalState,
                ).isEqualTo(
                    ImeUiState.Result(
                        "raw text",
                    ),
                )
            }
        }

    @Test
    fun `should transition to Error on transcription failure`() =
        runTest {
            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } throws IOException(
                "API error",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Auto,
                )

                advanceUntilIdle()

                val states =
                    mutableListOf(
                        awaitItem(),
                    )

                if (
                    states.last() ==
                    ImeUiState.Processing
                ) {
                    states.add(
                        awaitItem(),
                    )
                }

                val finalState =
                    states.last()

                assertThat(
                    finalState,
                ).isInstanceOf(
                    ImeUiState.Error::class.java,
                )

                assertThat(
                    (finalState as ImeUiState.Error)
                        .message,
                ).contains(
                    "API error",
                )
            }
        }

    @Test
    fun `should show error when API key not configured`() =
        runTest {
            every {
                apiKeyManager.getApiKey(
                    any(),
                )
            } returns null

            every {
                apiKeyManager.getSttApiKey(
                    any(),
                )
            } returns null

            every {
                apiKeyManager.getGroqApiKey()
            } returns null

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Auto,
                )

                val state =
                    awaitItem()

                assertThat(
                    state,
                ).isInstanceOf(
                    ImeUiState.Error::class.java,
                )

                assertThat(
                    (state as ImeUiState.Error)
                        .message,
                ).contains(
                    "API key",
                )
            }
        }

    @Test
    fun `should not use Groq key when selected STT provider key is missing`() =
        runTest {
            sttProviderFlow.value =
                SttProvider.OpenAI

            every {
                apiKeyManager.getSttApiKey(
                    SttProvider.OpenAI,
                )
            } returns null

            every {
                apiKeyManager.getGroqApiKey()
            } returns "legacy-groq-key"

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Auto,
                )

                val state =
                    awaitItem()

                assertThat(
                    state,
                ).isInstanceOf(
                    ImeUiState.Error::class.java,
                )

                assertThat(
                    (state as ImeUiState.Error)
                        .message,
                ).contains(
                    "API key",
                )
            }

            coVerify(
                exactly = 0,
            ) {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `should return to Idle on dismiss`() =
        runTest {
            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.dismiss()

                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )
            }
        }

    @Test
    fun `should block recording when voice input limit reached for Free users`() =
        runTest {
            repeat(
                UsageLimiter.FREE_VOICE_INPUT_LIMIT,
            ) {
                usageLimiter.incrementVoiceInput()
            }

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                val state =
                    awaitItem()

                assertThat(
                    state,
                ).isInstanceOf(
                    ImeUiState.Error::class.java,
                )

                assertThat(
                    (state as ImeUiState.Error)
                        .message,
                ).contains(
                    "Daily limit",
                )
            }
        }

    @Test
    fun `should allow recording when Pro even at limit`() =
        runTest {
            proStatus =
                ProStatus.Pro(
                    ProSource.GOOGLE_PLAY,
                )

            repeat(
                UsageLimiter.FREE_VOICE_INPUT_LIMIT,
            ) {
                usageLimiter.incrementVoiceInput()
            }

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Recording,
                )
            }
        }

    @Test
    fun `should skip refinement when refinement limit reached for Free users`() =
        runTest {
            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "hello",
            )

            repeat(
                UsageLimiter.FREE_REFINEMENT_LIMIT,
            ) {
                usageLimiter.incrementRefinement()
            }

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Auto,
                )

                skipItems(1)

                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Result(
                        "hello",
                    ),
                )
            }
        }

    @Test
    fun `should fetch vocabulary and pass to transcription and refinement`() =
        runTest {
            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "語末你好",
            )

            coEvery {
                chatCompletionApi.chatCompletion(
                    any(),
                    any(),
                )
            } returns chatResponse(
                "語墨你好",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Chinese,
                )

                val states =
                    mutableListOf(
                        awaitItem(),
                    )

                states.add(
                    awaitItem(),
                )

                val finalState =
                    states.last()

                assertThat(
                    finalState,
                ).isEqualTo(
                    ImeUiState.Refined(
                        "語末你好",
                        "語墨你好",
                    ),
                )
            }

            coVerify {
                dictionaryRepository.getWords(
                    any(),
                )
            }
        }

    /*
     * 新增測試：
     * 有重要詞時也能正常完成語音流程。
     */
    @Test
    fun `should support important vocabulary`() =
        runTest {
            importantWordsFlow.value =
                setOf(
                    "重要專有名詞",
                )

            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "重要專有名詞",
            )

            refinementEnabledFlow.value =
                false

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Chinese,
                )

                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Processing,
                )

                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Result(
                        "重要專有名詞",
                    ),
                )
            }
        }

    @Test
    fun `should emit CommandDetected when transcribed text is a voice command`() =
        runTest {
            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "送出",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Chinese,
                )

                skipItems(1)

                val state =
                    awaitItem()

                assertThat(
                    state,
                ).isInstanceOf(
                    ImeUiState.CommandDetected::class.java,
                )

                assertThat(
                    (
                        state as
                            ImeUiState.CommandDetected
                        ).command,
                ).isEqualTo(
                    VoiceCommand.Enter,
                )
            }
        }

    @Test
    fun `should emit EditInstruction when editMode is true`() =
        runTest {
            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "讓它更正式",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Chinese,
                    editMode = true,
                )

                skipItems(1)

                val state =
                    awaitItem()

                assertThat(
                    state,
                ).isInstanceOf(
                    ImeUiState.EditInstruction::class.java,
                )

                assertThat(
                    (
                        state as
                            ImeUiState.EditInstruction
                        ).instruction,
                ).isEqualTo(
                    "讓它更正式",
                )
            }
        }

    @Test
    fun `should pass translationEnabled to refineTextUseCase when translation is on`() =
        runTest {
            translationEnabledFlow.value =
                true

            translationTargetLanguageFlow.value =
                SttLanguage.English

            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text = "你好世界",
            )

            coEvery {
                chatCompletionApi.chatCompletion(
                    any(),
                    any(),
                )
            } returns chatResponse(
                "Hello world",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Chinese,
                )

                val states =
                    mutableListOf(
                        awaitItem(),
                    )

                states.add(
                    awaitItem(),
                )

                val finalState =
                    states.last()

                assertThat(
                    finalState,
                ).isEqualTo(
                    ImeUiState.Refined(
                        "你好世界",
                        "Hello world",
                    ),
                )
            }
        }

    @Test
    fun `toneOverride is used in refinement instead of flow value`() =
        runTest {
            toneStyleFlow.value =
                ToneStyle.Casual

            coEvery {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns WhisperResponse(
                text =
                    "let's schedule a meeting",
            )

            coEvery {
                chatCompletionApi.chatCompletion(
                    any(),
                    any(),
                )
            } returns chatResponse(
                "Let's schedule a meeting.",
            )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.English,
                    toneOverride =
                        ToneStyle.Professional,
                )

                val states =
                    mutableListOf(
                        awaitItem(),
                    )

                states.add(
                    awaitItem(),
                )

                val finalState =
                    states.last()

                assertThat(
                    finalState,
                ).isInstanceOf(
                    ImeUiState.Refined::class.java,
                )
            }

            coVerify {
                chatCompletionApi.chatCompletion(
                    any(),
                    match { request ->
                        request.messages.any { msg ->
                            msg.role == "system" &&
                                msg.content.contains(
                                    "professional",
                                    ignoreCase = true,
                                )
                        }
                    },
                )
            }
        }

    @Test
    fun `should show error without calling STT when audio is silent`() =
        runTest {
            fakeRecordedAudio =
                ByteArray(
                    32000,
                )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Chinese,
                )

                val state =
                    awaitItem()

                assertThat(
                    state,
                ).isInstanceOf(
                    ImeUiState.Error::class.java,
                )

                assertThat(
                    (state as ImeUiState.Error)
                        .message,
                ).contains(
                    "too quiet",
                )
            }

            coVerify(
                exactly = 0,
            ) {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `should show error without calling STT when audio is too short`() =
        runTest {
            fakeRecordedAudio =
                ByteArray(
                    1000,
                )

            controller.uiState.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    ImeUiState.Idle,
                )

                controller.onStartRecording(
                    startRecording,
                )

                skipItems(1)

                controller.onStopRecording(
                    stopRecording,
                    SttLanguage.Auto,
                )

                val state =
                    awaitItem()

                assertThat(
                    state,
                ).isInstanceOf(
                    ImeUiState.Error::class.java,
                )

                assertThat(
                    (state as ImeUiState.Error)
                        .message,
                ).contains(
                    "too short",
                )
            }

            coVerify(
                exactly = 0,
            ) {
                sttApi.transcribe(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    private fun chatResponse(
        content: String,
    ) =
        ChatCompletionResponse(
            id = "test",
            choices =
                listOf(
                    ChatChoice(
                        message =
                            ChatMessage(
                                "assistant",
                                content,
                            ),
                    ),
                ),
        )
}
