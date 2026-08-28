package com.voxpen.app.data.local

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
    val usageCount: Int = 0,
    val lastUsedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class HybridLexiconSource {
    BOSHIAMY,
    PINYIN,
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
)
