package com.voxpen.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ClipboardDao {
    @Query(
        """
        SELECT * FROM clipboard_entries
        WHERE type = :type
        ORDER BY isPinned DESC, updatedAt DESC, usageCount DESC
        """,
    )
    suspend fun getByType(type: String): List<ClipboardEntry>

    @Query(
        """
        SELECT * FROM clipboard_entries
        WHERE type = :type AND text = :text AND groupName = :groupName
        LIMIT 1
        """,
    )
    suspend fun findExact(
        type: String,
        text: String,
        groupName: String,
    ): ClipboardEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ClipboardEntry): Long

    @Query(
        """
        UPDATE clipboard_entries
        SET updatedAt = :now,
            usageCount = usageCount + 1
        WHERE id = :id
        """,
    )
    suspend fun touch(
        id: Long,
        now: Long,
    )

    @Query(
        """
        UPDATE clipboard_entries
        SET text = :text,
            updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun updateText(
        id: Long,
        text: String,
        now: Long,
    )

    @Query("UPDATE clipboard_entries SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(
        id: Long,
        pinned: Boolean,
    )

    @Query("DELETE FROM clipboard_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        DELETE FROM clipboard_entries
        WHERE type = :type
          AND isPinned = 0
          AND id NOT IN (
              SELECT id FROM clipboard_entries
              WHERE type = :type
              ORDER BY updatedAt DESC
              LIMIT :limit
          )
        """,
    )
    suspend fun trimUnpinned(
        type: String,
        limit: Int,
    )
}
