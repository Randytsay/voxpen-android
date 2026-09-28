package com.voxpen.app.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.model.LlmProvider
import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.remote.ChatChoice
import com.voxpen.app.data.remote.ChatCompletionApi
import com.voxpen.app.data.remote.ChatCompletionApiFactory
import com.voxpen.app.data.remote.ChatCompletionResponse
import com.voxpen.app.data.remote.ChatMessage
import com.voxpen.app.data.repository.LlmRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImportSrtUseCaseTest {
    private lateinit var chatCompletionApi: ChatCompletionApi
    private lateinit var useCase: ImportSrtUseCase

    private fun chatResponse(content: String) =
        ChatCompletionResponse(
            choices = listOf(ChatChoice(message = ChatMessage(role = "assistant", content = content))),
        )

    @BeforeEach
    fun setUp() {
        chatCompletionApi = mockk()
        val apiFactory = mockk<ChatCompletionApiFactory>()
        every { apiFactory.create(any()) } returns chatCompletionApi
        useCase = ImportSrtUseCase(RefineSegmentsUseCase(LlmRepository(apiFactory)))
    }

    @Test
    fun `refines cue text without changing timestamps`() =
        runTest {
            val content =
                """
                1
                00:00:01,000 --> 00:00:02,000
                um, hello world

                2
                00:00:03,000 --> 00:00:04,000
                this is a test
                """.trimIndent()
            coEvery { chatCompletionApi.chatCompletion(any(), any()) } returns
                chatResponse("1|Hello world.\n2|This is a test.")

            val result =
                useCase(
                    content = content,
                    fileName = "movie.srt",
                    language = SttLanguage.English,
                    apiKey = "key",
                    provider = LlmProvider.OpenAI,
                )

            assertThat(result.isSuccess).isTrue()
            val imported = result.getOrThrow()
            assertThat(imported.refinedSrt).contains("00:00:01,000 --> 00:00:02,000")
            assertThat(imported.refinedSrt).contains("00:00:03,000 --> 00:00:04,000")
            assertThat(imported.refinedSrt).contains("Hello world.")
            assertThat(imported.refinedText).isEqualTo("Hello world. This is a test.")
        }

    @Test
    fun `rejects invalid and oversized SRT`() =
        runTest {
            val invalid = useCase("not an srt", "bad.srt", SttLanguage.English, "key")
            val huge = "x".repeat(5 * 1024 * 1024 + 1)
            val oversized = useCase(huge, "huge.srt", SttLanguage.English, "key")

            assertThat(invalid.isFailure).isTrue()
            assertThat(invalid.exceptionOrNull()?.message).contains("Invalid SRT")
            assertThat(oversized.isFailure).isTrue()
            assertThat(oversized.exceptionOrNull()?.message).contains("too large")
        }

    @Test
    fun `propagates refinement failures`() =
        runTest {
            coEvery { chatCompletionApi.chatCompletion(any(), any()) } throws IOException("provider unavailable")

            val result =
                useCase(
                    content = "1\n00:00:01,000 --> 00:00:02,000\ncue",
                    fileName = "a.srt",
                    language = SttLanguage.English,
                    apiKey = "key",
                )

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).isEqualTo("provider unavailable")
        }
}
