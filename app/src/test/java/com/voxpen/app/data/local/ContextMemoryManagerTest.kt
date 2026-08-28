package com.voxpen.app.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ContextMemoryManagerTest {
    @Test
    fun `keeps five committed entries independently for each app`(@TempDir tempDir: File) = runTest {
        val manager = createManager(tempDir)
        (1..6).forEach { manager.append("com.example.editor", "entry-$it") }
        manager.append("com.example.chat", "chat-entry")

        assertThat(manager.getRecentInputs("com.example.editor")).containsExactly(
            "entry-2", "entry-3", "entry-4", "entry-5", "entry-6",
        ).inOrder()
        assertThat(manager.getRecentInputs("com.example.chat")).containsExactly("chat-entry")
        assertThat(manager.getRecentInputs("com.example.other")).isEmpty()
    }

    @Test
    fun `ignores blank package and blank committed text`(@TempDir tempDir: File) = runTest {
        val manager = createManager(tempDir)
        manager.append(" ", "text")
        manager.append("com.example.editor", " \n")

        assertThat(manager.getRecentInputs("com.example.editor")).isEmpty()
    }

    private fun createManager(tempDir: File): ContextMemoryManager {
        val dispatcher = UnconfinedTestDispatcher()
        val scope = TestScope(dispatcher)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tempDir, "context.preferences_pb") },
        )
        return ContextMemoryManager(dataStore, Json { encodeDefaults = true; explicitNulls = false })
    }
}
