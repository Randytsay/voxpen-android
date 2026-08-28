package com.voxpen.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HybridLexiconDao {
    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE source = :source
          AND normalizedCode LIKE :prefix || '%'
        LIMIT :limit
        """,
    )
    suspend fun searchSourcePrefix(
        source: String,
        prefix: String,
        limit: Int,
    ): List<HybridLexiconEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE source != 'BOSHIAMY'
          AND normalizedCode LIKE :prefix || '%'
        LIMIT :limit
        """,
    )
    suspend fun searchPinyinPrefix(
        prefix: String,
        limit: Int,
    ): List<HybridLexiconEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE source != 'BOSHIAMY'
          AND initials LIKE :prefix || '%'
        LIMIT :limit
        """,
    )
    suspend fun searchInitialsPrefix(
        prefix: String,
        limit: Int,
    ): List<HybridLexiconEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE phrase = :phrase
          AND source != 'BOSHIAMY'
          AND normalizedCode != ''
        ORDER BY usageCount DESC, baseWeight DESC
        LIMIT 1
        """,
    )
    suspend fun findPinyinForPhrase(phrase: String): HybridLexiconEntity?

    @Query("SELECT COUNT(*) FROM hybrid_lexicon WHERE source = :source")
    suspend fun countSource(source: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<HybridLexiconEntity>): List<Long>

    @Query("DELETE FROM hybrid_lexicon WHERE source = :source")
    suspend fun deleteBySource(source: String)

    @Query(
        """
        UPDATE hybrid_lexicon
        SET usageCount = usageCount + 1,
            lastUsedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun recordSelection(
        id: Long,
        now: Long,
    )
}
