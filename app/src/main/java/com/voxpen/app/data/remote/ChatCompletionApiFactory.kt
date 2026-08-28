package com.voxpen.app.data.remote

import com.voxpen.app.data.model.LlmProvider
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatCompletionApiFactory
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val json: Json,
    ) {
        private val cache = ConcurrentHashMap<String, ChatCompletionApi>()

        fun create(provider: LlmProvider): ChatCompletionApi {
            require(provider.baseUrl.isNotBlank()) { "Use createForCustom() for gateway provider" }
            return cache.getOrPut(provider.key) {
                buildApi(provider.baseUrl)
            }
        }

        fun createForCustom(baseUrl: String): ChatCompletionApi {
            return cache.getOrPut("custom:${normalizeBaseUrl(baseUrl)}") {
                buildApi(baseUrl)
            }
        }

        private fun buildApi(baseUrl: String): ChatCompletionApi {
            return Retrofit.Builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ChatCompletionApi::class.java)
        }

        companion object {
            /**
             * Normalizes a user-entered OpenAI-compatible base URL for Retrofit:
             * trims whitespace, guarantees a single trailing slash, and de-dups a
             * trailing `/v1` segment (the API path already starts with `v1/...`).
             * Mirrors desktop `api_url()` in voxpen-core/src/api/groq.rs.
             * Example: `http://host:4000/v1` -> `http://host:4000/`.
             */
            fun normalizeBaseUrl(raw: String): String {
                var base = raw.trim().trimEnd('/')
                if (base.endsWith("/v1", ignoreCase = true)) {
                    base = base.substring(0, base.length - 3)
                }
                return "$base/"
            }
        }
    }
