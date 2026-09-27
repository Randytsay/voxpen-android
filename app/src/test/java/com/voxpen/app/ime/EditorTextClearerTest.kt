package com.voxpen.app.ime

import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EditorTextClearerTest {
    private lateinit var connection: InputConnection

    @BeforeEach
    fun setUp() {
        connection = mockk(relaxed = true)
        every { connection.beginBatchEdit() } returns true
        every { connection.deleteSurroundingTextInCodePoints(any(), any()) } returns true
    }

    @Test
    fun `clear before cursor preserves text after cursor`() {
        EditorTextClearer.clearBeforeCursor(connection)

        verify(exactly = 1) { connection.finishComposingText() }
        verify(exactly = 1) { connection.deleteSurroundingTextInCodePoints(1_000_000, 0) }
        verify(exactly = 1) { connection.endBatchEdit() }
    }

    @Test
    fun `clear after cursor preserves text before cursor`() {
        EditorTextClearer.clearAfterCursor(connection)

        verify(exactly = 1) { connection.finishComposingText() }
        verify(exactly = 1) { connection.deleteSurroundingTextInCodePoints(0, 1_000_000) }
        verify(exactly = 1) { connection.endBatchEdit() }
    }
}
