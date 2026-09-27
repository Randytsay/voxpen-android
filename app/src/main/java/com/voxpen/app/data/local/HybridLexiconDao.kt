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
          AND normalizedCode >= :prefix
          AND normalizedCode < (:prefix || '{')
        ORDER BY
          CASE WHEN normalizedCode = :prefix THEN 0 ELSE 1 END,
          CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC,
          frequencyWeight DESC,
          baseWeight DESC,
          id ASC
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
          AND normalizedCode >= :prefix
          AND normalizedCode < (:prefix || '{')
        ORDER BY
          CASE WHEN normalizedCode = :prefix THEN 0 ELSE 1 END,
          CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC,
          frequencyWeight DESC,
          baseWeight DESC,
          id ASC
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
        WHERE source = 'PERSONAL'
          AND (
            (normalizedCode >= :prefix AND normalizedCode < (:prefix || '{'))
            OR (initials >= :prefix AND initials < (:prefix || '{'))
          )
        ORDER BY
          CASE WHEN normalizedCode = :prefix OR initials = :prefix THEN 0 ELSE 1 END,
          CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC,
          frequencyWeight DESC,
          baseWeight DESC,
          id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchPersonalPrefix(
        prefix: String,
        limit: Int,
    ): List<HybridLexiconEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE source != 'BOSHIAMY'
          AND normalizedCode = :normalizedCode
          AND length(phrase) = 1
          AND (:toneFilter = 0 OR substr(toneCode, 1, 1) = CAST(:toneFilter AS TEXT))
        ORDER BY
          CASE WHEN source = 'PERSONAL' THEN 0 ELSE 1 END,
          CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC,
          frequencyWeight DESC,
          baseWeight DESC,
          id ASC
        """,
    )
    suspend fun searchSingleSyllableCharacters(
        normalizedCode: String,
        toneFilter: Int,
    ): List<HybridLexiconEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE source != 'BOSHIAMY'
          AND initials >= :prefix
          AND initials < (:prefix || '{')
        ORDER BY
          CASE WHEN initials = :prefix THEN 0 ELSE 1 END,
          CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC,
          frequencyWeight DESC,
          baseWeight DESC,
          id ASC
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
        WHERE source != 'BOSHIAMY'
          AND initials = :initials
        ORDER BY CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC, frequencyWeight DESC, baseWeight DESC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchInitialsExact(
        initials: String,
        limit: Int,
    ): List<HybridLexiconEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE source != 'BOSHIAMY'
          AND normalizedCode IN (:codes)
        ORDER BY CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC, frequencyWeight DESC, baseWeight DESC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchPinyinCodes(
        codes: List<String>,
        limit: Int,
    ): List<HybridLexiconEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE source != 'BOSHIAMY'
          AND initials IN (:keys)
        ORDER BY CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC, frequencyWeight DESC, baseWeight DESC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchInitialsKeys(
        keys: List<String>,
        limit: Int,
    ): List<HybridLexiconEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE source != 'BOSHIAMY'
          AND length(initials) = :initialLength
          AND initials LIKE :pattern
        ORDER BY CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC, frequencyWeight DESC, baseWeight DESC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun searchInitialsPattern(
        pattern: String,
        initialLength: Int,
        limit: Int,
    ): List<HybridLexiconEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE phrase = :phrase
          AND source != 'BOSHIAMY'
          AND normalizedCode != ''
        ORDER BY CASE WHEN usageCount >= 3 THEN usageCount ELSE 0 END DESC, frequencyWeight DESC, baseWeight DESC
        LIMIT 1
        """,
    )
    suspend fun findPinyinForPhrase(phrase: String): HybridLexiconEntity?

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE phrase = :phrase
          AND normalizedCode = :normalizedCode
          AND source = 'PERSONAL'
        LIMIT 1
        """,
    )
    suspend fun findExactPersonal(
        phrase: String,
        normalizedCode: String,
    ): HybridLexiconEntity?

    @Query("SELECT * FROM hybrid_lexicon WHERE source = 'PERSONAL' ORDER BY phrase COLLATE NOCASE, id")
    suspend fun getPersonalPhrases(): List<HybridLexiconEntity>

    @Query("SELECT * FROM hybrid_lexicon WHERE source = :source")
    suspend fun getBySource(source: String): List<HybridLexiconEntity>

    @Query("SELECT * FROM hybrid_lexicon WHERE id = :id AND source = 'PERSONAL' LIMIT 1")
    suspend fun findPersonalPhrase(id: Long): HybridLexiconEntity?

    @Query(
        """
        UPDATE hybrid_lexicon
        SET phrase = :phrase,
            code = :code,
            normalizedCode = :normalizedCode,
            initials = :initials,
            baseWeight = :baseWeight,
            personalKind = :personalKind
        WHERE id = :id AND source = 'PERSONAL'
        """,
    )
    suspend fun updatePersonalPhrase(
        id: Long,
        phrase: String,
        code: String,
        normalizedCode: String,
        initials: String,
        baseWeight: Int,
        personalKind: String,
    ): Int

    @Query("DELETE FROM hybrid_lexicon WHERE id = :id AND source = 'PERSONAL'")
    suspend fun deletePersonalPhrase(id: Long): Int

    @Query(
        """
        INSERT INTO hybrid_learning (phrase, normalizedCode, reading, kind, selectionCount, lastSelectedAt)
        VALUES (:phrase, :normalizedCode, :reading, :kind, 1, :now)
        ON CONFLICT(phrase, normalizedCode, kind) DO UPDATE SET
          reading = excluded.reading,
          selectionCount = hybrid_learning.selectionCount + 1,
          lastSelectedAt = excluded.lastSelectedAt
        """,
    )
    suspend fun recordLearningSelection(
        phrase: String,
        normalizedCode: String,
        reading: String,
        kind: String,
        now: Long,
    )

    @Query(
        """
        SELECT * FROM hybrid_learning
        WHERE phrase = :phrase AND normalizedCode = :normalizedCode AND kind = :kind
        LIMIT 1
        """,
    )
    suspend fun findLearning(
        phrase: String,
        normalizedCode: String,
        kind: String,
    ): HybridLearningEntity?

    @Query("SELECT * FROM hybrid_learning WHERE normalizedCode IN (:codes) AND kind = :kind")
    suspend fun getLearningForCodes(
        codes: List<String>,
        kind: String,
    ): List<HybridLearningEntity>

    @Query("DELETE FROM hybrid_learning WHERE phrase = :phrase AND normalizedCode = :normalizedCode")
    suspend fun deleteLearning(
        phrase: String,
        normalizedCode: String,
    ): Int

    @Query("DELETE FROM hybrid_learning")
    suspend fun clearLearning()

    @Query("DELETE FROM hybrid_lexicon WHERE source = 'PERSONAL' AND personalKind = 'AUTO_PROMOTED'")
    suspend fun deleteAutomaticPersonalPhrases(): Int

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
