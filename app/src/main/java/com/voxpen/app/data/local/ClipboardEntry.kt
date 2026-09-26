package com.voxpen.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ClipboardEntryType {
    HISTORY,
    COMMON,
    SYMBOL,
}

@Entity(
    tableName = "clipboard_entries",
    indices = [
        Index(value = ["type", "updatedAt"]),
        Index(value = ["type", "text", "groupName"], unique = true),
    ],
)
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val type: String,
    val groupName: String = "",
    val isPinned: Boolean = false,
    val usageCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)
