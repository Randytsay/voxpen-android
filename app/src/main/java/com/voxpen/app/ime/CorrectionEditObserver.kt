package com.voxpen.app.ime

/**
 * Tracks the editor state after VoxPen commits a candidate and conservatively
 * classifies later editor changes. It contains no Android or coroutine code so
 * the state transitions can be tested without an InputMethodService.
 */
class CorrectionEditObserver {
    data class CommittedSnapshot(
        val baselineText: String,
        val committedStart: Int,
        val committedEnd: Int,
        val packageName: String,
        val inputType: Int,
        val generation: Long,
    )

    sealed interface Observation {
        data object NoPending : Observation
        data object Unchanged : Observation
        data object Ignored : Observation
        data class CandidateDetected(val candidate: CorrectionLearningCandidate) : Observation
        data class Cleared(val reason: ClearReason) : Observation
    }

    enum class ClearReason {
        PACKAGE_CHANGED,
        INVALID_RANGE,
        NUMBER_EDIT,
        BROAD_REWRITE,
    }

    private var nextGeneration = 0L
    private var snapshot: CommittedSnapshot? = null

    val currentSnapshot: CommittedSnapshot?
        get() = snapshot

    fun onCommitted(
        baselineText: String,
        committedStart: Int,
        committedEnd: Int,
        packageName: String,
        inputType: Int,
    ): CommittedSnapshot? {
        if (baselineText.isBlank() || committedStart < 0 || committedEnd <= committedStart) {
            clear()
            return null
        }
        if (committedEnd > baselineText.length) {
            clear()
            return null
        }

        val committed =
            CommittedSnapshot(
                baselineText = baselineText,
                committedStart = committedStart,
                committedEnd = committedEnd,
                packageName = packageName,
                inputType = inputType,
                generation = ++nextGeneration,
            )
        snapshot = committed
        return committed
    }

    fun observe(
        currentText: String,
        packageName: String,
    ): Observation {
        val pending = snapshot
        return when {
            pending == null -> Observation.NoPending
            pending.packageName != packageName -> {
                clear()
                Observation.Cleared(ClearReason.PACKAGE_CHANGED)
            }
            pending.baselineText == currentText -> Observation.Unchanged
            !isValidRange(pending) -> {
                clear()
                Observation.Cleared(ClearReason.INVALID_RANGE)
            }
            else -> observeChangedText(pending, currentText)
        }
    }

    private fun observeChangedText(
        pending: CommittedSnapshot,
        currentText: String,
    ): Observation {
        val changed = diff(pending.baselineText, currentText)
        val observation =
            when {
                changed.oldText.isBlank() && changed.newText.isBlank() -> Observation.Unchanged
                changed.oldText.any(Char::isDigit) || changed.newText.any(Char::isDigit) -> {
                    clear()
                    Observation.Cleared(ClearReason.NUMBER_EDIT)
                }
                else -> {
                    val candidate =
                        CorrectionLearningDetector.detect(
                            baselineText = pending.baselineText,
                            currentText = currentText,
                            committedStart = pending.committedStart,
                            committedEnd = pending.committedEnd,
                        )
                    if (candidate != null) {
                        snapshot = advance(pending, currentText, changed)
                        Observation.CandidateDetected(candidate)
                    } else {
                        val overlapsCommittedText =
                            changed.oldStart < pending.committedEnd &&
                                (changed.oldEnd > pending.committedStart ||
                                    changed.oldText.isEmpty())
                        if (isBroadRewrite(changed) && overlapsCommittedText) {
                            clear()
                            Observation.Cleared(ClearReason.BROAD_REWRITE)
                        } else {
                            // Formatting edits and unrelated text edits update the baseline/range,
                            // allowing a later typo in the same committed utterance to be learned.
                            snapshot = advance(pending, currentText, changed)
                            Observation.Ignored
                        }
                    }
                }
            }
        return observation
    }

    fun clear() {
        snapshot = null
        nextGeneration++
    }

    private fun advance(
        previous: CommittedSnapshot,
        currentText: String,
        diff: TextDiff,
    ): CommittedSnapshot {
        val delta = diff.newText.length - diff.oldText.length
        val start =
            when {
                diff.oldEnd <= previous.committedStart -> previous.committedStart + delta
                diff.oldStart < previous.committedEnd -> minOf(previous.committedStart, diff.oldStart)
                else -> previous.committedStart
            }.coerceIn(0, currentText.length)
        val end =
            when {
                diff.oldEnd <= previous.committedStart -> previous.committedEnd + delta
                diff.oldStart < previous.committedEnd -> previous.committedEnd + delta
                else -> previous.committedEnd
            }.coerceIn(start, currentText.length)

        return previous.copy(
            baselineText = currentText,
            committedStart = start,
            committedEnd = end,
        )
    }

    private fun isValidRange(snapshot: CommittedSnapshot): Boolean =
        snapshot.committedStart >= 0 &&
            snapshot.committedEnd > snapshot.committedStart &&
            snapshot.committedEnd <= snapshot.baselineText.length

    private fun isBroadRewrite(diff: TextDiff): Boolean {
        if (kotlin.math.abs(diff.newText.length - diff.oldText.length) > MAX_DOCUMENT_DELTA) return true
        if (diff.oldText.length > MAX_LOCAL_CHANGED_TEXT || diff.newText.length > MAX_LOCAL_CHANGED_TEXT) return true
        val larger = maxOf(diff.oldText.length, diff.newText.length)
        val smaller = minOf(diff.oldText.length, diff.newText.length)
        return larger > 8 && smaller * 2 < larger
    }

    private data class TextDiff(
        val oldStart: Int,
        val oldEnd: Int,
        val oldText: String,
        val newText: String,
    )

    private fun diff(oldText: String, newText: String): TextDiff {
        val prefix = commonPrefixLength(oldText, newText)
        val suffix = commonSuffixLength(oldText, newText, prefix)
        val oldEnd = oldText.length - suffix
        val newEnd = newText.length - suffix
        return TextDiff(
            oldStart = prefix,
            oldEnd = oldEnd,
            oldText = oldText.substring(prefix, oldEnd),
            newText = newText.substring(prefix, newEnd),
        )
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) index++
        return index
    }

    private fun commonSuffixLength(a: String, b: String, prefixLength: Int): Int {
        val maxLength = minOf(a.length, b.length) - prefixLength
        var index = 0
        while (index < maxLength && a[a.lastIndex - index] == b[b.lastIndex - index]) index++
        return index
    }

    private companion object {
        const val MAX_LOCAL_CHANGED_TEXT = 8
        const val MAX_DOCUMENT_DELTA = 96
    }
}
