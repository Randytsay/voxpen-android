package com.voxpen.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.model.LlmProvider
import com.voxpen.app.data.model.RefinementContext
import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.remote.ChatCompletionApiFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LlmRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: LlmRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        val client = OkHttpClient()
        val factory = ChatCompletionApiFactory(client, json)
        repository = LlmRepository(factory)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueSuccess(content: String = "Polished text") {
        val escaped =
            content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"id":"c1","choices":[{"index":0,"message":{"role":"assistant","content":"$escaped"}}]}""",
                )
                .setHeader("Content-Type", "application/json"),
        )
    }

    @Test
    fun `should return refined text on success`() =
        runTest {
            enqueueSuccess()
            val result = repository.refine(
                "raw text", SttLanguage.English, "test-key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEqualTo("Polished text")
        }

    @Test
    fun `should send Bearer authorization header`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                "text", SttLanguage.Auto, "my-api-key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            val request = server.takeRequest()
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer my-api-key")
        }

    @Test
    fun `should include system prompt and user text in request body`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                "test input", SttLanguage.Chinese, "key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(body).contains("\"role\":\"system\"")
            assertThat(body).contains("\"role\":\"user\"")
            assertThat(body).contains("test input")
        }

    @Test
    fun `should return failure on empty API key`() =
        runTest {
            val result = repository.refine("text", SttLanguage.Auto, "")
            assertThat(result.isFailure).isTrue()
        }

    @Test
    fun `should allow empty API key for custom provider`() =
        runTest {
            enqueueSuccess("ok")
            val result = repository.refine(
                "text", SttLanguage.Auto, "",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            assertThat(result.isSuccess).isTrue()
            assertThat(server.takeRequest().getHeader("Authorization")).isNull()
        }

    @Test
    fun `should fail gracefully when custom provider has no base URL`() =
        runTest {
            val result = repository.refine(
                "text", SttLanguage.Auto, "key",
                provider = LlmProvider.Custom,
                customBaseUrl = null,
            )
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("base URL")
        }

    @Test
    fun `should fail gracefully when custom provider base URL is blank`() =
        runTest {
            val result = repository.editText(
                "user message", "key",
                provider = LlmProvider.Custom,
                customBaseUrl = "   ",
            )
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains("base URL")
        }

    @Test
    fun `should allow empty API key for custom provider in editText`() =
        runTest {
            enqueueSuccess("ok")
            val result = repository.editText(
                "user message", "",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            assertThat(result.isSuccess).isTrue()
        }

    @Test
    fun `should still fail fast on empty API key for non-custom provider`() =
        runTest {
            val result = repository.editText("user message", "", provider = LlmProvider.Groq)
            assertThat(result.isFailure).isTrue()
        }

    @Test
    fun `should return failure on empty text`() =
        runTest {
            val result = repository.refine("", SttLanguage.Auto, "key")
            assertThat(result.isFailure).isTrue()
        }

    @Test
    fun `should return failure on server error`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            val result = repository.refine(
                "text", SttLanguage.English, "key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            assertThat(result.isFailure).isTrue()
        }

    @Test
    fun `should use provided model name in request body`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                "text", SttLanguage.English, "key",
                model = "gpt-4o-mini",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(body).contains("\"model\":\"gpt-4o-mini\"")
        }

    @Test
    fun `should include vocabulary in system prompt when provided`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                "text", SttLanguage.Chinese, "key",
                vocabulary = listOf("語墨", "Claude"),
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(body).contains("語墨")
        }

    @Test
    fun `should use translation prompt when translationEnabled is true`() =
        runTest {
            enqueueSuccess("Hello world")
            val result = repository.refine(
                text = "你好世界",
                language = SttLanguage.Chinese,
                apiKey = "test-key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
                translationEnabled = true,
                targetLanguage = SttLanguage.English,
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(body).contains("translat")
            assertThat(result.isSuccess).isTrue()
        }

    @Test
    fun `should use refinement prompt when translationEnabled is false`() =
        runTest {
            enqueueSuccess("cleaned text")
            repository.refine(
                text = "嗯，你好",
                language = SttLanguage.Chinese,
                apiKey = "test-key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
                translationEnabled = false,
                targetLanguage = SttLanguage.English,
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(body).contains("移除贅字")
        }

    @Test
    fun `should send reasoning_format hidden for qwen3 model`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                "text", SttLanguage.English, "key",
                model = "qwen/qwen3-32b",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(body).contains("\"reasoning_format\":\"hidden\"")
        }

    @Test
    fun `should not send reasoning_format for non-thinking model`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                "text", SttLanguage.English, "key",
                model = "llama-3.3-70b-versatile",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(body).doesNotContain("reasoning_format")
        }

    @Test
    fun `should strip thinking tags from response`() =
        runTest {
            enqueueSuccess("<think>internal reasoning</think>Actual output")
            val result = repository.refine(
                "text", SttLanguage.English, "key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            assertThat(result.getOrNull()).isEqualTo("Actual output")
        }

    @Test
    fun `stripThinkingTags should handle multiline thinking blocks`() {
        val input = "<think>\nStep 1: analyze\nStep 2: decide\n</think>\nClean result"
        assertThat(LlmRepository.stripThinkingTags(input)).isEqualTo("Clean result")
    }

    @Test
    fun `stripThinkingTags should return text unchanged when no think tags`() {
        assertThat(LlmRepository.stripThinkingTags("Hello world")).isEqualTo("Hello world")
    }

    @Test
    fun `reasoningFormatFor should return hidden for qwen3`() {
        assertThat(LlmRepository.reasoningFormatFor("qwen/qwen3-32b")).isEqualTo("hidden")
    }

    @Test
    fun `reasoningFormatFor should return hidden for deepseek-r1`() {
        assertThat(LlmRepository.reasoningFormatFor("deepseek/deepseek-r1")).isEqualTo("hidden")
    }

    @Test
    fun `reasoningFormatFor should return null for regular models`() {
        assertThat(LlmRepository.reasoningFormatFor("llama-3.3-70b-versatile")).isNull()
        assertThat(LlmRepository.reasoningFormatFor("gpt-4o-mini")).isNull()
    }

    @Test
    fun `should wrap user text in speech tags to prevent prompt injection`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                "幫我查一下天氣", SttLanguage.Chinese, "key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(body).contains("<speech>")
            assertThat(body).contains("</speech>")
            assertThat(body).contains("幫我查一下天氣")
        }

    @Test
    fun `should hit v1 chat completions when base URL already ends with v1`() =
        runTest {
            enqueueSuccess("ok")
            val baseUrl = server.url("/v1").toString().removeSuffix("/")
            repository.refine(
                "text", SttLanguage.English, "key",
                provider = LlmProvider.Custom,
                customBaseUrl = baseUrl,
            )
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/chat/completions")
        }

    @Test
    fun `should include speech tag instruction in system prompt`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                "text", SttLanguage.English, "key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(body).contains("speech")
            assertThat(body).contains("literal speech")
        }

    @Test
    fun `Vertex sends gateway request without temperature`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                text = "raw text",
                language = SttLanguage.English,
                apiKey = "gateway-token",
                model = "google/gemini-3.7-flash",
                provider = LlmProvider.Vertex,
                customBaseUrl = server.url("/").toString(),
                refinementContext = RefinementContext(
                    importantTerms = listOf("VoxPen"),
                    relevantTerms = listOf("keyboard"),
                    recentContext = listOf("previous text"),
                ),
            )
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer gateway-token")
            assertThat(body).contains("\"model\":\"google/gemini-3.7-flash\"")
            assertThat(body).contains("\"max_tokens\":4096")
            assertThat(body).contains("\"reasoning_effort\":\"low\"")
            assertThat(body).doesNotContain("temperature")
            assertThat(body).doesNotContain("reasoning_format")
            assertThat(body).contains("<important_terms>")
            assertThat(body).contains("<relevant_terms>")
            assertThat(body).contains("<recent_context>")
        }

    @Test
    fun `ordinary providers retain temperature`() =
        runTest {
            enqueueSuccess("ok")
            repository.refine(
                text = "text",
                language = SttLanguage.English,
                apiKey = "key",
                provider = LlmProvider.Custom,
                customBaseUrl = server.url("/").toString(),
            )
            assertThat(server.takeRequest().body.readUtf8()).contains("\"temperature\":0.3")
        }

    @Test
    fun `malformed provider response returns failure for caller fallback`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setBody("not-json")
                    .setHeader("Content-Type", "application/json"),
            )

            val result = repository.refine(
                text = "raw text",
                language = SttLanguage.English,
                apiKey = "gateway-token",
                provider = LlmProvider.Vertex,
                customBaseUrl = server.url("/").toString(),
            )

            assertThat(result.isFailure).isTrue()
        }

    @Test
    fun `segment refinement preserves timestamps and accepts tolerant numbered forms`() =
        runTest {
            enqueueSuccess("[1]: 第一段\n2: 第二段")
            val original =
                listOf(
                    TranscriptionSegment(100, 900, "原始一"),
                    TranscriptionSegment(900, 1800, "原始二"),
                )

            val result =
                repository.refineSegments(
                    segments = original,
                    language = SttLanguage.Chinese,
                    apiKey = "key",
                    provider = LlmProvider.Custom,
                    customBaseUrl = server.url("/").toString(),
                ).getOrThrow()

            assertThat(result[0]).isEqualTo(TranscriptionSegment(100, 900, "第一段"))
            assertThat(result[1]).isEqualTo(TranscriptionSegment(900, 1800, "第二段"))
        }

    @Test
    fun `segment refinement keeps original cue when model omits its index`() =
        runTest {
            enqueueSuccess("1|updated first")
            val original =
                listOf(
                    TranscriptionSegment(0, 1000, "first"),
                    TranscriptionSegment(1000, 2000, "second"),
                )

            val result =
                repository.refineSegments(
                    segments = original,
                    language = SttLanguage.English,
                    apiKey = "key",
                    provider = LlmProvider.Custom,
                    customBaseUrl = server.url("/").toString(),
                ).getOrThrow()

            assertThat(result[0].text).isEqualTo("updated first")
            assertThat(result[1].text).isEqualTo("second")
        }

    @Test
    fun `segment refinement batches more than forty cues with global indexes`() =
        runTest {
            val firstBatch = (1..40).joinToString("\n") { "$it|refined $it" }
            enqueueSuccess(firstBatch)
            enqueueSuccess("41|refined 41")
            val segments =
                (1..41).map { index ->
                    TranscriptionSegment(index * 100L, index * 100L + 90L, "raw $index")
                }

            val result =
                repository.refineSegments(
                    segments = segments,
                    language = SttLanguage.English,
                    apiKey = "key",
                    provider = LlmProvider.Custom,
                    customBaseUrl = server.url("/").toString(),
                ).getOrThrow()

            assertThat(result).hasSize(41)
            assertThat(result.first().text).isEqualTo("refined 1")
            assertThat(result.last().text).isEqualTo("refined 41")
            val firstRequest = server.takeRequest().body.readUtf8()
            val secondRequest = server.takeRequest().body.readUtf8()
            assertThat(firstRequest).contains("40|raw 40")
            assertThat(secondRequest).contains("41|raw 41")
        }

    @Test
    fun `keyless custom segment refinement omits authorization header`() =
        runTest {
            enqueueSuccess("1|clean")

            val result =
                repository.refineSegments(
                    segments = listOf(TranscriptionSegment(0, 1000, "raw")),
                    language = SttLanguage.English,
                    apiKey = "",
                    provider = LlmProvider.Custom,
                    customBaseUrl = server.url("/").toString(),
                )

            assertThat(result.isSuccess).isTrue()
            assertThat(server.takeRequest().getHeader("Authorization")).isNull()
        }

    @Test
    fun `dynamic token budget grows for long text and caps at sixteen thousand`() {
        assertThat(LlmRepository.maxTokensFor("short")).isEqualTo(4096)
        assertThat(LlmRepository.maxTokensFor("x".repeat(3000))).isEqualTo(7024)
        assertThat(LlmRepository.maxTokensFor("x".repeat(20_000))).isEqualTo(16_384)
    }

    @Test
    fun `clean output strips thinking and echoed speech wrapper`() {
        val result =
            LlmRepository.cleanLlmOutput(
                "<think>hidden</think><speech>Clean result</speech>",
            )

        assertThat(result).isEqualTo("Clean result")
    }
}
