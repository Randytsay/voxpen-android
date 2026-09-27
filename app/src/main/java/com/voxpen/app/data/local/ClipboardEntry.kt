package com.voxpen.app.data.local

import androidx.room.ColumnInfo
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
    @ColumnInfo(defaultValue = "''") val shortcut: String = "",
    @ColumnInfo(defaultValue = "''") val label: String = "",
    val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    val usageCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val lastUsedAt: Long = 0,
)
