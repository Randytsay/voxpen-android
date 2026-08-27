package com.voxpen.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A durable, user-specific correction learned from manual edits or added explicitly.
 *
 * Room stores this in SQLite. Global rules use an empty [packageName]; APP rules store
 * the Android package name that the rule belongs to.
 */
@Entity(
    tableName = "correction_memory",
    indices = [
        Index(value = ["wrongText"]),
        Index(value = ["enabled"]),
        Index(value = ["scope", "packageName"]),
        Index(
            value = ["wrongText", "correctText", "scope", "packageName"],
            unique = true,
        ),
    ],
)
data class CorrectionMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val wrongText: String,
    val correctText: String,
    val hitCount: Int = 1,
    val autoConfidence: Double = 0.45,
    val manualLevel: String = CorrectionManualLevel.AUTO.name,
    val scope: String = CorrectionScope.APP.name,
    val packageName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastCorrectedAt: Long = System.currentTimeMillis(),
    val lastAppliedAt: Long? = null,
    val enabled: Boolean = true,
)

enum class CorrectionManualLevel {
    AUTO,
    LOW,
    MEDIUM,
    HIGH,
    FIXED,
    DISABLED,
}

enum class CorrectionScope {
    GLOBAL,
    APP,
}

data class CorrectionHint(
    val wrongText: String,
    val correctText: String,
    val confidence: Double,
    val manualLevel: CorrectionManualLevel,
)
