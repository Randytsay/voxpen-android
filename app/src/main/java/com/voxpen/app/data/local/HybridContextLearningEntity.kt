package com.voxpen.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "hybrid_context_learning",
    primaryKeys = ["contextText", "continuation"],
    indices = [Index(value = ["contextText", "selectionCount", "lastSelectedAt"])],
)
data class HybridContextLearningEntity(
    val contextText: String,
    val continuation: String,
    val selectionCount: Int,
    val lastSelectedAt: Long,
)
