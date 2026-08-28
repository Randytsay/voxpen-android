package com.voxpen.app.ime

data class CorrectionLearningCandidate(
    val wrongText: String,
    val correctText: String,
)

/**
 * Conservative detector for manual edits made after VoxPen committed text.
 *
 * The detector intentionally prefers missing a learning opportunity over learning a bad rule.
 */
object CorrectionLearningDetector {
    private const val MAX_CHANGED_TEXT = 8
    private const val MAX_DOCUMENT_DELTA = 96
    private const val CJK_CONTEXT_SUFFIX = 2

    fun detect(
        baselineText: String,
        currentText: String,
        committedStart: Int,
        committedEnd: Int,
    ): CorrectionLearningCandidate? {
        if (baselineText == currentText) return null
        if (committedStart < 0 || committedEnd < committedStart || committedEnd > baselineText.length) return null
        if (kotlin.math.abs(currentText.length - baselineText.length) > MAX_DOCUMENT_DELTA) return null

        val prefixLength = commonPrefixLength(baselineText, currentText)
        val suffixLength = commonSuffixLength(
            baselineText,
            currentText,
            prefixLength,
        )

        val baselineChangeEnd = baselineText.length - suffixLength
        val currentChangeEnd = currentText.length - suffixLength

        if (prefixLength >= baselineChangeEnd && prefixLength >= currentChangeEnd) return null

        val changedOld = baselineText.substring(prefixLength, baselineChangeEnd)
        val changedNew = currentText.substring(prefixLength, currentChangeEnd)

        if (changedOld.isBlank() || changedNew.isBlank()) return null
        if (changedOld.length > MAX_CHANGED_TEXT || changedNew.length > MAX_CHANGED_TEXT) return null
        if (changedOld == changedNew) return null
        if (containsDigit(changedOld) || containsDigit(changedNew)) return null
        if (isPunctuationOnly(changedOld) || isPunctuationOnly(changedNew)) return null

        val changeOverlapsCommit =
            prefixLength < committedEnd &&
                baselineChangeEnd > committedStart

        if (!changeOverlapsCommit) return null

        // Reject broad rewrites: learned corrections should be local substitutions, not a changed idea.
        val larger = maxOf(changedOld.length, changedNew.length)
        val smaller = minOf(changedOld.length, changedNew.length)
        if (larger > 8 && smaller * 2 < larger) return null

        val contextual = addSafeCjkSuffixContext(
            baselineText = baselineText,
            currentText = currentText,
            oldText = changedOld,
            newText = changedNew,
            oldChangeEnd = baselineChangeEnd,
            newChangeEnd = currentChangeEnd,
            committedEnd = committedEnd,
        )

        val wrong = contextual.first.trim()
        val correct = contextual.second.trim()

        if (wrong.isBlank() || correct.isBlank() || wrong == correct) return null
        if (wrong.length > MAX_CHANGED_TEXT || correct.length > MAX_CHANGED_TEXT) return null

        return CorrectionLearningCandidate(
            wrongText = wrong,
            correctText = correct,
        )
    }

    private fun addSafeCjkSuffixContext(
        baselineText: String,
        currentText: String,
        oldText: String,
        newText: String,
        oldChangeEnd: Int,
        newChangeEnd: Int,
        committedEnd: Int,
    ): Pair<String, String> {
        if (oldText.length > 2 || newText.length > 2) return oldText to newText
        if (!oldText.all(::isCjkLike) || !newText.all(::isCjkLike)) return oldText to newText

        var oldResult = oldText
        var newResult = newText
        var oldIndex = oldChangeEnd
        var newIndex = newChangeEnd
        var added = 0

        while (
            added < CJK_CONTEXT_SUFFIX &&
            oldIndex < baselineText.length &&
            newIndex < currentText.length &&
            oldIndex < committedEnd
        ) {
            val oldChar = baselineText[oldIndex]
            val newChar = currentText[newIndex]
            if (oldChar != newChar || !isCjkLike(oldChar)) break

            oldResult += oldChar
            newResult += newChar
            oldIndex++
            newIndex++
            added++
        }

        return oldResult to newResult
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    private fun commonSuffixLength(
        a: String,
        b: String,
        prefixLength: Int,
    ): Int {
        val max = minOf(a.length, b.length) - prefixLength
        var i = 0
        while (i < max && a[a.lastIndex - i] == b[b.lastIndex - i]) i++
        return i
    }

    private fun containsDigit(text: String): Boolean =
        text.any { it.isDigit() }

    private fun isPunctuationOnly(text: String): Boolean =
        text.all { ch -> ch.isWhitespace() || (!ch.isLetterOrDigit() && !isCjkLike(ch)) }

    private fun isCjkLike(ch: Char): Boolean =
        ch.code in 0x3400..0x4DBF ||
            ch.code in 0x4E00..0x9FFF ||
            ch.code in 0x3040..0x30FF ||
            ch.code in 0xAC00..0xD7AF
}
