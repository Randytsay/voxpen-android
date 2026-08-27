package com.voxpen.app.ui.correction

import com.voxpen.app.data.local.CorrectionMemoryEntity

data class CorrectionMemoryUiState(
    val entries: List<CorrectionMemoryEntity> = emptyList(),
    val searchQuery: String = "",
    val learningEnabled: Boolean = true,
    val statusMessage: String? = null,
) {
    val visibleEntries: List<CorrectionMemoryEntity>
        get() {
            val query = searchQuery.trim()
            if (query.isBlank()) return entries
            return entries.filter { entry ->
                entry.wrongText.contains(query, ignoreCase = true) ||
                    entry.correctText.contains(query, ignoreCase = true) ||
                    entry.packageName.contains(query, ignoreCase = true)
            }
        }

    val pendingCount: Int
        get() = entries.count { it.manualLevel == "AUTO" && it.hitCount < 3 }

    val fixedCount: Int
        get() = entries.count { it.manualLevel == "FIXED" }

    val disabledCount: Int
        get() = entries.count { !it.enabled || it.manualLevel == "DISABLED" }
}
