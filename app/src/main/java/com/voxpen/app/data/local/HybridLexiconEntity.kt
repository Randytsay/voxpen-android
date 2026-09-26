package com.voxpen.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hybrid_lexicon",
    indices = [
        Index(value = ["normalizedCode"]),
        Index(value = ["initials"]),
        Index(value = ["source"]),
        Index(
            value = ["phrase", "normalizedCode", "source"],
            unique = true,
        ),
    ],
)
data class HybridLexiconEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phrase: String,
    val code: String,
    val normalizedCode: String,
    val initials: String = "",
    val source: String,
    val baseWeight: Int = 0,
    @ColumnInfo(defaultValue = "NULL")
    val frequencyWeight: Double? = null,
    val usageCount: Int = 0,
    val lastUsedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'LEGACY'")
    val personalKind: String = "NONE",
)

@Entity(
    tableName = "hybrid_learning",
    primaryKeys = ["phrase", "normalizedCode", "kind"],
    indices = [Index(value = ["normalizedCode", "kind"])],
)
data class HybridLearningEntity(
    val phrase: String,
    val normalizedCode: String,
    val reading: String,
    val kind: String,
    val selectionCount: Int,
    val lastSelectedAt: Long,
)

enum class HybridLearningKind {
    SELECTION,
    COMPOSITION,
}

enum class PersonalLexiconKind {
    NONE,
    MANUAL,
    AUTO_PROMOTED,
    LEGACY,
}

enum class HybridLexiconSource {
    BOSHIAMY,
    PINYIN,
    MIXTYPE,
    BAIDU,
    PERSONAL,
}

data class HybridCandidate(
    val id: Long,
    val phrase: String,
    val code: String,
    val normalizedCode: String,
    val initials: String,
    val source: HybridLexiconSource,
    val usageCount: Int,
    val lastUsedAt: Long,
    val baseWeight: Int,
    val personalKind: String = "NONE",
    val frequencyWeight: Double? = null,
)
