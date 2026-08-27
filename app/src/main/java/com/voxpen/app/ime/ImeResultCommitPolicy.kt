package com.voxpen.app.ime

/**
 * Decides which completed recognition result may be auto-inserted.
 * Intermediate states and command/edit states must never be auto-inserted here.
 */
object ImeResultCommitPolicy {
    fun textToCommit(
        state: ImeUiState,
        autoInsertEnabled: Boolean,
    ): String? {
        if (!autoInsertEnabled) return null

        return when (state) {
            is ImeUiState.Result -> state.text
            is ImeUiState.Refined -> state.refined
            else -> null
        }
    }
}
