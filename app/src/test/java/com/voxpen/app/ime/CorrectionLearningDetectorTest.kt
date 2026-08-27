package com.voxpen.app.ime

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CorrectionLearningDetectorTest {
    @Test
    fun `detects local CJK correction with safe suffix context`() {
        val baseline = "今天帶師兄會來"
        val current = "今天戴師兄會來"

        val result =
            CorrectionLearningDetector.detect(
                baselineText = baseline,
                currentText = current,
                committedStart = 0,
                committedEnd = baseline.length,
            )

        assertThat(result).isEqualTo(
            CorrectionLearningCandidate(
                wrongText = "帶師兄",
                correctText = "戴師兄",
            ),
        )
    }

    @Test
    fun `ignores edits outside committed range`() {
        val baseline = "前文｜今天帶師兄會來"
        val current = "前聞｜今天帶師兄會來"
        val committedStart = baseline.indexOf("今天")

        val result =
            CorrectionLearningDetector.detect(
                baselineText = baseline,
                currentText = current,
                committedStart = committedStart,
                committedEnd = baseline.length,
            )

        assertThat(result).isNull()
    }

    @Test
    fun `ignores numeric corrections`() {
        val baseline = "下午3點開會"
        val current = "下午4點開會"

        val result =
            CorrectionLearningDetector.detect(
                baseline,
                current,
                0,
                baseline.length,
            )

        assertThat(result).isNull()
    }

    @Test
    fun `ignores punctuation only edits`() {
        val baseline = "你好，"
        val current = "你好。"

        val result =
            CorrectionLearningDetector.detect(
                baseline,
                current,
                0,
                baseline.length,
            )

        assertThat(result).isNull()
    }

    @Test
    fun `returns null when nothing changed`() {
        val text = "戴師兄"
        assertThat(
            CorrectionLearningDetector.detect(text, text, 0, text.length),
        ).isNull()
    }
}
