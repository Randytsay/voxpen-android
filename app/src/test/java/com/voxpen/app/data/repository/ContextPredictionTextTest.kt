package com.voxpen.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ContextPredictionTextTest {
    @Test
    fun `suffixes preserve multi-character context for continuing predictions`() {
        assertThat(ContextPredictionText.suffixes("台達能源"))
            .containsExactly("台達能源", "達能源", "能源", "源")
            .inOrder()
    }

    @Test
    fun `appending keeps only recent context`() {
        assertThat(ContextPredictionText.append("台達能源", "有限公司"))
            .isEqualTo("能源有限公司")
    }

    @Test
    fun `punctuation is not learned as a Chinese continuation`() {
        assertThat(ContextPredictionText.isChinesePhrase("達能源")).isTrue()
        assertThat(ContextPredictionText.isChinesePhrase("達，能源")).isFalse()
    }

    @Test
    fun `sequential selections also learn the remaining four character phrase`() {
        assertThat(ContextPredictionText.transitions("台達能", "源"))
            .containsAtLeast("台" to "達能源", "台達" to "能源", "台達能" to "源")
    }

    @Test
    fun `single whole phrase selection learns internal completions`() {
        assertThat(ContextPredictionText.transitions("", "台達能源"))
            .containsAtLeast("台" to "達能源", "台達" to "能源", "台達能" to "源")
    }
}
