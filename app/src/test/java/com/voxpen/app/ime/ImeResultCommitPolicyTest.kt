package com.voxpen.app.ime

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.model.VoiceCommand
import org.junit.jupiter.api.Test

class ImeResultCommitPolicyTest {
    @Test
    fun `Result is not auto inserted when setting is off`() {
        assertThat(
            ImeResultCommitPolicy.textToCommit(
                ImeUiState.Result("original"),
                autoInsertEnabled = false,
            ),
        ).isNull()
    }

    @Test
    fun `Result auto inserts original text when setting is on`() {
        assertThat(
            ImeResultCommitPolicy.textToCommit(
                ImeUiState.Result("original"),
                autoInsertEnabled = true,
            ),
        ).isEqualTo("original")
    }

    @Test
    fun `Refined auto inserts refined text when setting is on`() {
        assertThat(
            ImeResultCommitPolicy.textToCommit(
                ImeUiState.Refined("original", "refined"),
                autoInsertEnabled = true,
            ),
        ).isEqualTo("refined")
    }

    @Test
    fun `Refining does not auto insert original text`() {
        assertThat(
            ImeResultCommitPolicy.textToCommit(
                ImeUiState.Refining("original"),
                autoInsertEnabled = true,
            ),
        ).isNull()
    }

    @Test
    fun `CommandDetected does not auto insert command text`() {
        assertThat(
            ImeResultCommitPolicy.textToCommit(
                ImeUiState.CommandDetected(VoiceCommand.Enter),
                autoInsertEnabled = true,
            ),
        ).isNull()
    }

    @Test
    fun `EditInstruction does not auto insert instruction text`() {
        assertThat(
            ImeResultCommitPolicy.textToCommit(
                ImeUiState.EditInstruction("make this shorter"),
                autoInsertEnabled = true,
            ),
        ).isNull()
    }

    @Test
    fun `Error does not auto insert error text`() {
        assertThat(
            ImeResultCommitPolicy.textToCommit(
                ImeUiState.Error("recognition failed"),
                autoInsertEnabled = true,
            ),
        ).isNull()
    }
}
