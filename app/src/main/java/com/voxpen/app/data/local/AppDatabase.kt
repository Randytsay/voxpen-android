package com.voxpen.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TranscriptionEntity::class,
        DictionaryEntry::class,
        CorrectionMemoryEntity::class,
        HybridLexiconEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao

    abstract fun dictionaryDao(): DictionaryDao

    abstract fun correctionMemoryDao(): CorrectionMemoryDao

    abstract fun hybridLexiconDao(): HybridLexiconDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS dictionary_entries (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            word TEXT NOT NULL,
                            createdAt INTEGER NOT NULL
                        )""",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_dictionary_entries_word ON dictionary_entries (word)",
                    )
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE transcriptions ADD COLUMN segmentsJson TEXT DEFAULT NULL")
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE transcriptions ADD COLUMN status TEXT NOT NULL DEFAULT 'completed'")
                    db.execSQL("ALTER TABLE transcriptions ADD COLUMN errorMessage TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE transcriptions ADD COLUMN audioPath TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE transcriptions ADD COLUMN provider TEXT DEFAULT NULL")
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS correction_memory (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            wrongText TEXT NOT NULL,
                            correctText TEXT NOT NULL,
                            hitCount INTEGER NOT NULL,
                            autoConfidence REAL NOT NULL,
                            manualLevel TEXT NOT NULL,
                            scope TEXT NOT NULL,
                            packageName TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            lastCorrectedAt INTEGER NOT NULL,
                            lastAppliedAt INTEGER DEFAULT NULL,
                            enabled INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_correction_memory_wrongText ON correction_memory (wrongText)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_correction_memory_enabled ON correction_memory (enabled)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_correction_memory_scope_packageName ON correction_memory (scope, packageName)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_correction_memory_wrongText_correctText_scope_packageName ON correction_memory (wrongText, correctText, scope, packageName)",
                    )
                }
            }

        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS hybrid_lexicon (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            phrase TEXT NOT NULL,
                            code TEXT NOT NULL,
                            normalizedCode TEXT NOT NULL,
                            initials TEXT NOT NULL,
                            source TEXT NOT NULL,
                            baseWeight INTEGER NOT NULL,
                            usageCount INTEGER NOT NULL,
                            lastUsedAt INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_hybrid_lexicon_normalizedCode ON hybrid_lexicon (normalizedCode)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_hybrid_lexicon_initials ON hybrid_lexicon (initials)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_hybrid_lexicon_source ON hybrid_lexicon (source)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_hybrid_lexicon_phrase_normalizedCode_source ON hybrid_lexicon (phrase, normalizedCode, source)",
                    )
                }
            }
    }
}
