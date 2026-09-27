package com.voxpen.app.ime.hybrid

/** A visual line range reported by the active editor. Offsets use editor text indices. */
internal data class VisualLineRange(
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val centerY: Float,
)

/** Pure offset/line decisions shared by the IME implementation and unit tests. */
internal object VisualLineSelection {
    /** Finds the line containing the caret, then returns its adjacent visual line. */
    fun adjacentLine(
        lines: List<VisualLineRange>,
        activeOffset: Int,
        down: Boolean,
    ): VisualLineRange? {
        if (lines.isEmpty()) return null

        // At a soft-wrap boundary, assign the caret to the line starting at that offset.
        // This makes both up and down advance from the same current visual row.
        val currentIndex =
            lines.indexOfFirst { activeOffset >= it.startOffset && activeOffset < it.endOffsetExclusive }
                .takeIf { it >= 0 }
                ?: lines.indexOfLast { activeOffset == it.endOffsetExclusive }

        if (currentIndex < 0) return null
        val targetIndex = currentIndex + if (down) 1 else -1
        return lines.getOrNull(targetIndex)
    }

    /** Keeps an offset within the visual line reported by the target editor. */
    fun clampOffset(
        offset: Int,
        line: VisualLineRange,
    ): Int = offset.coerceIn(line.startOffset, line.endOffsetExclusive)
}
