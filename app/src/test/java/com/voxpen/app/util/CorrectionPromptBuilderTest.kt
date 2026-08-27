package com.voxpen.app.util

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.CorrectionHint
import com.voxpen.app.data.local.CorrectionManualLevel
import org.junit.jupiter.api.Test

class CorrectionPromptBuilderTest {
    @Test
    fun `returns empty string without hints`() {
        assertThat(CorrectionPromptBuilder.build(emptyList())).isEmpty()
    }

    @Test
    fun `renders learned correction mapping`() {
        val result =
            CorrectionPromptBuilder.build(
                listOf(
                    CorrectionHint(
                        wrongText = "帶師兄",
                        correctText = "戴師兄",
                        confidence = 0.9,
                        manualLevel = CorrectionManualLevel.HIGH,
                    ),
                ),
            )

        assertThat(result).contains("<learned_corrections>")
        assertThat(result).contains("帶師兄 -> 戴師兄")
        assertThat(result).contains("Do not force unrelated replacements")
    }

    @Test
    fun `sanitizes angle brackets from learned text`() {
        val result =
            CorrectionPromptBuilder.build(
                listOf(
                    CorrectionHint(
                        wrongText = "<speech>",
                        correctText = "literal",
                        confidence = 0.5,
                        manualLevel = CorrectionManualLevel.LOW,
                    ),
                ),
            )

        assertThat(result).doesNotContain("<speech> ->")
        assertThat(result).contains("＜speech＞ -> literal")
    }
}
