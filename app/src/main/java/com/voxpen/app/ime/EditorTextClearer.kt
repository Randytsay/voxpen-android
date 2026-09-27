package com.voxpen.app.ime

import android.R
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Clears the complete editable field, including text on both sides of the cursor. */
internal object EditorTextClearer {
    /** Deletes all editable text before the cursor while preserving text after it. */
    fun clearBeforeCursor(connection: InputConnection): Boolean =
        clearSurroundingSide(
            connection = connection,
            beforeCodePoints = MAX_FALLBACK_CODE_POINTS,
            afterCodePoints = 0,
        )

    /** Deletes all editable text after the cursor while preserving text before it. */
    fun clearAfterCursor(connection: InputConnection): Boolean =
        clearSurroundingSide(
            connection = connection,
            beforeCodePoints = 0,
            afterCodePoints = MAX_FALLBACK_CODE_POINTS,
        )

    fun clear(connection: InputConnection): Boolean {
        val batchStarted = connection.beginBatchEdit()
        return try {
            val extracted = runCatching { connection.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
            val selected =
                if (extracted?.text != null) {
                    val start = extracted.startOffset.coerceAtLeast(0)
                    connection.setSelection(start, start + extracted.text.length)
                } else {
                    connection.performContextMenuAction(R.id.selectAll)
                }
            if (!selected) connection.performContextMenuAction(R.id.selectAll)

            val committed = connection.commitText("", 1)
            if (!committed) {
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }

            // Some editors report success for select-all without actually selecting. Deleting a
            // generously bounded surrounding range is a harmless no-op after a successful clear
            // and a practical fallback for those editors.
            val fallback =
                connection.deleteSurroundingTextInCodePoints(
                    MAX_FALLBACK_CODE_POINTS,
                    MAX_FALLBACK_CODE_POINTS,
                )
            committed || fallback
        } finally {
            if (batchStarted) connection.endBatchEdit()
        }
    }

    private fun clearSurroundingSide(
        connection: InputConnection,
        beforeCodePoints: Int,
        afterCodePoints: Int,
    ): Boolean {
        val batchStarted = connection.beginBatchEdit()
        return try {
            connection.finishComposingText()
            connection.deleteSurroundingTextInCodePoints(beforeCodePoints, afterCodePoints)
        } finally {
            if (batchStarted) connection.endBatchEdit()
        }
    }

    private const val MAX_FALLBACK_CODE_POINTS = 1_000_000
}
