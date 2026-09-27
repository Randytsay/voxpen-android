package com.voxpen.app.ime.hybrid

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VisualLineSelectionTest {
    private val lines =
        listOf(
            VisualLineRange(startOffset = 0, endOffsetExclusive = 5, centerY = 10f),
            VisualLineRange(startOffset = 5, endOffsetExclusive = 9, centerY = 30f),
            VisualLineRange(startOffset = 9, endOffsetExclusive = 14, centerY = 50f),
        )

    @Test
    fun `down selects the next visual line from current line`() {
        assertThat(VisualLineSelection.adjacentLine(lines, activeOffset = 2, down = true))
            .isEqualTo(lines[1])
    }

    @Test
    fun `up selects previous visual line from current line`() {
        assertThat(VisualLineSelection.adjacentLine(lines, activeOffset = 7, down = false))
            .isEqualTo(lines[0])
    }

    @Test
    fun `soft-wrap boundary is assigned according to arrow direction`() {
        assertThat(VisualLineSelection.adjacentLine(lines, activeOffset = 5, down = true))
            .isEqualTo(lines[2])
        assertThat(VisualLineSelection.adjacentLine(lines, activeOffset = 5, down = false))
            .isEqualTo(lines[0])
    }

    @Test
    fun `movement stops at first and last visual lines`() {
        assertThat(VisualLineSelection.adjacentLine(lines, activeOffset = 0, down = false)).isNull()
        assertThat(VisualLineSelection.adjacentLine(lines, activeOffset = 14, down = true)).isNull()
    }

    @Test
    fun `target offset is clamped to line boundaries`() {
        assertThat(VisualLineSelection.clampOffset(-5, lines[1])).isEqualTo(5)
        assertThat(VisualLineSelection.clampOffset(20, lines[1])).isEqualTo(9)
    }
}
