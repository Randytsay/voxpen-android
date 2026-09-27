package com.voxpen.app.data.repository

internal object ContextPredictionText {
    const val MAX_CONTEXT_LENGTH = 6
    const val MAX_CONTINUATION_LENGTH = 8

    fun isChinesePhrase(text: String): Boolean {
        val codePoints = text.codePoints().toArray()
        return codePoints.isNotEmpty() &&
            codePoints.size <= MAX_CONTINUATION_LENGTH &&
            codePoints.all { codePoint ->
                codePoint in 0x3400..0x4DBF ||
                    codePoint in 0x4E00..0x9FFF ||
                    codePoint in 0x20000..0x2FA1F
            }
    }

    fun append(
        context: String,
        selected: String,
    ): String {
        val codePoints = (context + selected).codePoints().toArray()
        val offset = (codePoints.size - MAX_CONTEXT_LENGTH).coerceAtLeast(0)
        return String(codePoints, offset, codePoints.size - offset)
    }

    fun suffixes(context: String): List<String> {
        val codePoints = context.codePoints().toArray()
        val maximum = minOf(codePoints.size, MAX_CONTEXT_LENGTH)
        return (maximum downTo 1).map { length ->
            String(codePoints, codePoints.size - length, length)
        }
    }

    fun transitions(
        previousContext: String,
        selected: String,
    ): List<Pair<String, String>> {
        if (!isChinesePhrase(selected)) return emptyList()
        val transitions = linkedSetOf<Pair<String, String>>()
        suffixes(previousContext).forEach { context ->
            transitions += context to selected
        }

        val previousPoints = previousContext.codePoints().toArray()
        val recentLength = minOf(previousPoints.size, 3)
        val recent = String(previousPoints, previousPoints.size - recentLength, recentLength)
        addPrefixContinuations(recent, selected, transitions)
        addPrefixContinuations(selected, "", transitions)
        return transitions.toList()
    }

    private fun addPrefixContinuations(
        prefixSource: String,
        ending: String,
        transitions: MutableSet<Pair<String, String>>,
    ) {
        val points = prefixSource.codePoints().toArray()
        for (split in 1 until points.size) {
            val context = String(points, 0, split)
            val continuation = String(points, split, points.size - split) + ending
            if (isChinesePhrase(continuation)) transitions += context to continuation
        }
    }
}
