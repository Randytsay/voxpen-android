package com.voxpen.app.util

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.model.SttLanguage
import org.junit.jupiter.api.Test

class ChirpStreamingSupportTest {
    @Test
    fun `important adaptation phrases are first and capped`() {
        val result =
            ChirpAdaptationBuilder.build(
                importantTerms = listOf("戴師兄", "兜率天", "戴師兄"),
                ordinaryTerms = List(300) { "ordinary-$it" },
            )

        assertThat(result.phrases.first()).isEqualTo("戴師兄")
        assertThat(result.phrases[1]).isEqualTo("兜率天")
        assertThat(result.phrases).hasSize(200)
    }

    @Test
    fun `chirp language mapping is explicit`() {
        assertThat(ChirpLanguageMapper.map(SttLanguage.Chinese)).isEqualTo("cmn-Hant-TW")
        assertThat(ChirpLanguageMapper.map(SttLanguage.English)).isEqualTo("en-US")
        assertThat(ChirpLanguageMapper.map(SttLanguage.Auto)).isEqualTo("auto")
    }
}
