package com.voxpen.app.data.local

import androidx.room.Dao
import androidx.room.Query

@Dao
interface HybridContextLearningDao {
    @Query(
        """
        INSERT INTO hybrid_context_learning (contextText, continuation, selectionCount, lastSelectedAt)
        VALUES (:contextText, :continuation, 1, :now)
        ON CONFLICT(contextText, continuation) DO UPDATE SET
          selectionCount = hybrid_context_learning.selectionCount + 1,
          lastSelectedAt = excluded.lastSelectedAt
        """,
    )
    suspend fun recordTransition(
        contextText: String,
        continuation: String,
        now: Long,
    )

    @Query(
        """
        SELECT * FROM hybrid_context_learning
        WHERE contextText IN (:contexts) AND selectionCount >= :minimumCount
        ORDER BY length(contextText) DESC, selectionCount DESC, lastSelectedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun findTransitions(
        contexts: List<String>,
        minimumCount: Int,
        limit: Int,
    ): List<HybridContextLearningEntity>

    @Query(
        """
        SELECT * FROM hybrid_lexicon
        WHERE source = 'PERSONAL'
          AND phrase LIKE (:prefix || '%')
          AND length(phrase) > length(:prefix)
          AND length(phrase) <= length(:prefix) + :maximumContinuationLength
        ORDER BY
          CASE WHEN personalKind = 'MANUAL' THEN 0 ELSE 1 END,
          usageCount DESC,
          lastUsedAt DESC,
          id ASC
        LIMIT :limit
        """,
    )
    suspend fun findPersonalPhraseContinuations(
        prefix: String,
        maximumContinuationLength: Int,
        limit: Int,
    ): List<HybridLexiconEntity>

    @Query("DELETE FROM hybrid_context_learning")
    suspend fun clearTransitions()
}
