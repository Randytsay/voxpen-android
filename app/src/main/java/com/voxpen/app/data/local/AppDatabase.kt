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
        HybridLearningEntity::class,
        HybridContextLearningEntity::class,
        ClipboardEntry::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao

    abstract fun dictionaryDao(): DictionaryDao

    abstract fun correctionMemoryDao(): CorrectionMemoryDao

    abstract fun hybridLexiconDao(): HybridLexiconDao

    abstract fun hybridContextLearningDao(): HybridContextLearningDao

    abstract fun clipboardDao(): ClipboardDao

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

        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS clipboard_entries (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            text TEXT NOT NULL,
                            type TEXT NOT NULL,
                            groupName TEXT NOT NULL,
                            isPinned INTEGER NOT NULL,
                            usageCount INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_clipboard_entries_type_updatedAt " +
                            "ON clipboard_entries (type, updatedAt)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_clipboard_entries_type_text_groupName " +
                            "ON clipboard_entries (type, text, groupName)",
                    )
                }
            }

        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE hybrid_lexicon ADD COLUMN personalKind TEXT NOT NULL DEFAULT 'LEGACY'")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS hybrid_learning (
                            phrase TEXT NOT NULL,
                            normalizedCode TEXT NOT NULL,
                            reading TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            selectionCount INTEGER NOT NULL,
                            lastSelectedAt INTEGER NOT NULL,
                            PRIMARY KEY (phrase, normalizedCode, kind)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_hybrid_learning_normalizedCode_kind " +
                            "ON hybrid_learning (normalizedCode, kind)",
                    )
                }
            }

        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE hybrid_lexicon ADD COLUMN frequencyWeight REAL DEFAULT NULL")
                }
            }

        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE hybrid_lexicon ADD COLUMN toneCode TEXT NOT NULL DEFAULT ''")
                    db.execSQL(
                        "DROP INDEX IF EXISTS index_hybrid_lexicon_phrase_normalizedCode_source",
                    )
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS index_hybrid_lexicon_phrase_normalizedCode_source_toneCode
                        ON hybrid_lexicon (phrase, normalizedCode, source, toneCode)
                        """.trimIndent(),
                    )
                }
            }

        val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS hybrid_context_learning (
                            contextText TEXT NOT NULL,
                            continuation TEXT NOT NULL,
                            selectionCount INTEGER NOT NULL,
                            lastSelectedAt INTEGER NOT NULL,
                            PRIMARY KEY (contextText, continuation)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_hybrid_context_learning_contextText_selectionCount_lastSelectedAt " +
                            "ON hybrid_context_learning (contextText, selectionCount, lastSelectedAt)",
                    )
                }
            }

        val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE clipboard_entries ADD COLUMN shortcut TEXT NOT NULL DEFAULT ''")
                }
            }

        val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE clipboard_entries ADD COLUMN label TEXT NOT NULL DEFAULT ''")
                }
            }

        val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE clipboard_entries ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE clipboard_entries ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE clipboard_entries SET lastUsedAt = updatedAt WHERE type = 'COMMON'")
                }
            }

        val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_hybrid_lexicon_source_normalizedCode " +
                            "ON hybrid_lexicon (source, normalizedCode)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_hybrid_lexicon_source_initials " +
                            "ON hybrid_lexicon (source, initials)",
                    )
                }
            }
    }
}
