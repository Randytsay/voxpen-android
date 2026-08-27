package com.voxpen.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CorrectionMemoryDao {
    @Query("SELECT * FROM correction_memory ORDER BY lastCorrectedAt DESC, id DESC")
    fun observeAll(): Flow<List<CorrectionMemoryEntity>>

    @Query("SELECT * FROM correction_memory ORDER BY lastCorrectedAt DESC, id DESC")
    suspend fun getAllOnce(): List<CorrectionMemoryEntity>

    @Query("SELECT * FROM correction_memory WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CorrectionMemoryEntity?

    @Query(
        """
        SELECT * FROM correction_memory
        WHERE wrongText = :wrongText
          AND correctText = :correctText
          AND scope = :scope
          AND packageName = :packageName
        LIMIT 1
        """,
    )
    suspend fun findExact(
        wrongText: String,
        correctText: String,
        scope: String,
        packageName: String,
    ): CorrectionMemoryEntity?

    @Query(
        """
        SELECT * FROM correction_memory
        WHERE enabled = 1
          AND manualLevel != 'DISABLED'
          AND (scope = 'GLOBAL' OR (scope = 'APP' AND packageName = :packageName))
        ORDER BY lastCorrectedAt DESC, id DESC
        """,
    )
    suspend fun getEnabledForPackage(packageName: String): List<CorrectionMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CorrectionMemoryEntity): Long

    @Update
    suspend fun update(entity: CorrectionMemoryEntity)

    @Query(
        """
        UPDATE correction_memory
        SET manualLevel = :manualLevel,
            enabled = :enabled
        WHERE id = :id
        """,
    )
    suspend fun setManualLevel(
        id: Long,
        manualLevel: String,
        enabled: Boolean,
    )

    @Query(
        """
        UPDATE correction_memory
        SET scope = :scope,
            packageName = :packageName
        WHERE id = :id
        """,
    )
    suspend fun setScopeFields(
        id: Long,
        scope: String,
        packageName: String,
    )

    @Delete
    suspend fun delete(entity: CorrectionMemoryEntity)

    @Query("DELETE FROM correction_memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM correction_memory")
    suspend fun deleteAll()
}
