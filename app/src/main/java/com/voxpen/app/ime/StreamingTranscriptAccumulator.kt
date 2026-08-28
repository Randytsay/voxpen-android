package com.voxpen.app.ime

data class StreamingTranscriptSnapshot(
    val finalText: String,
    val interimText: String,
) {
    val previewText: String
        get() = TranscriptOverlapMerger.merge(finalText, interimText)
}

/** Keeps final segments and replaces the current interim hypothesis. */
class StreamingTranscriptAccumulator {
    private val seenFinalSegmentIds = mutableSetOf<String>()
    private var finalText = ""
    private var interimText = ""

    @Synchronized
    fun acceptInterim(text: String) {
        interimText = text.trim()
    }

    @Synchronized
    fun acceptFinal(
        text: String,
        segmentId: String? = null,
    ) {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return
        if (segmentId != null && !seenFinalSegmentIds.add(segmentId)) return
        finalText = TranscriptOverlapMerger.merge(finalText, cleaned)
        interimText = ""
    }

    @Synchronized
    fun snapshot(): StreamingTranscriptSnapshot =
        StreamingTranscriptSnapshot(
            finalText = finalText,
            interimText = interimText,
        )

    @Synchronized
    fun stableFinalText(): String = finalText.trim()
}

/** Deterministic suffix/prefix merge for rollover and replayed audio. */
object TranscriptOverlapMerger {
    fun merge(
        existing: String,
        incoming: String,
    ): String {
        val left = existing.trim()
        val right = incoming.trim()
        return when {
            left.isBlank() -> right
            right.isBlank() -> left
            right.startsWith(left) -> right
            left.endsWith(right) -> left
            else -> {
                val maxOverlap = minOf(left.length, right.length)
                val overlap =
                    (maxOverlap downTo 1).firstOrNull {
                        left.takeLast(it).equals(right.take(it), ignoreCase = true)
                    }
                val separator = if (needsWhitespace(left.last(), right.first())) " " else ""
                if (overlap == null) {
                    left + separator + right
                } else {
                    left + right.drop(overlap)
                }
            }
        }
    }

    private fun needsWhitespace(
        left: Char,
        right: Char,
    ): Boolean {
        if (left.isWhitespace() || right.isWhitespace()) return false
        if (isCjk(left) || isCjk(right)) return false
        if (right in "，。！？、；：)]}〉》」』】" || left in "([{〈《「『【") return false
        return true
    }

    private fun isCjk(char: Char): Boolean =
        char in '\u3400'..'\u4DBF' ||
            char in '\u4E00'..'\u9FFF' ||
            char in '\uF900'..'\uFAFF'
}
