package com.voxpen.app.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SttProviderTest {
    @Test
    fun `providers expose correct keys and defaults`() {
        assertThat(SttProvider.Groq.key).isEqualTo("groq")
        assertThat(SttProvider.Groq.baseUrl).isEqualTo("https://api.groq.com/openai/")
        assertThat(SttProvider.Groq.defaultModelId).isEqualTo("whisper-large-v3")

        assertThat(SttProvider.OpenAI.key).isEqualTo("openai")
        assertThat(SttProvider.OpenAI.baseUrl).isEqualTo("https://api.openai.com/")
        assertThat(SttProvider.OpenAI.defaultModelId).isEqualTo("whisper-1")

        assertThat(SttProvider.Custom.key).isEqualTo("custom")
        assertThat(SttProvider.DEFAULT).isEqualTo(SttProvider.Groq)
    }

    @Test
    fun `openai models include current audio transcription models`() {
        assertThat(SttProvider.OpenAI.models.map { it.id }).containsExactly(
            "whisper-1",
            "gpt-4o-transcribe",
            "gpt-4o-mini-transcribe",
        ).inOrder()
    }

    @Test
    fun `fromKey defaults to Groq`() {
        assertThat(SttProvider.fromKey("openai")).isEqualTo(SttProvider.OpenAI)
        assertThat(SttProvider.fromKey("custom")).isEqualTo(SttProvider.Custom)
        assertThat(SttProvider.fromKey("unknown")).isEqualTo(SttProvider.Groq)
    }

    @Test
    fun `Groq keeps explicit turbo as an available non-default model`() {
        assertThat(SttProvider.Groq.models.map { it.id }).containsExactly(
            "whisper-large-v3",
            "whisper-large-v3-turbo",
        ).inOrder()
        assertThat(SttProvider.Groq.models.last().isDefault).isFalse()
    }
}
