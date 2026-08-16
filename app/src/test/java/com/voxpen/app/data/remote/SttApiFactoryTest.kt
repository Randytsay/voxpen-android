package com.voxpen.app.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

class SttApiFactoryTest {
    private val factory = SttApiFactory(OkHttpClient(), Json { ignoreUnknownKeys = true })

    @Test
    fun `should create API instance for custom base URL`() {
        val api = factory.createForCustom("https://my-whisper.example.com/")
        assertThat(api).isNotNull()
    }

    @Test
    fun `should cache instances for same base URL`() {
        val api1 = factory.createForCustom("https://my-whisper.example.com/")
        val api2 = factory.createForCustom("https://my-whisper.example.com/")
        assertThat(api1).isSameInstanceAs(api2)
    }

    @Test
    fun `normalizeBaseUrl appends trailing slash to bare host`() {
        assertThat(SttApiFactory.normalizeBaseUrl("http://100.102.183.27:8001"))
            .isEqualTo("http://100.102.183.27:8001/")
    }

    @Test
    fun `normalizeBaseUrl strips trailing v1 path segment`() {
        assertThat(SttApiFactory.normalizeBaseUrl("http://100.102.183.27:8001/v1"))
            .isEqualTo("http://100.102.183.27:8001/")
    }

    @Test
    fun `normalizeBaseUrl keeps existing trailing slash`() {
        assertThat(SttApiFactory.normalizeBaseUrl("http://100.102.183.27:8001/"))
            .isEqualTo("http://100.102.183.27:8001/")
    }

    @Test
    fun `should create separate instances for different URLs`() {
        val api1 = factory.createForCustom("https://server-a.example.com/")
        val api2 = factory.createForCustom("https://server-b.example.com/")
        assertThat(api1).isNotSameInstanceAs(api2)
    }
}
