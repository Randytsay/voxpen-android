package com.voxpen.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun migration3To4AddsFailedRetryColumns() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS transcriptions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    fileName TEXT NOT NULL,
                    originalText TEXT NOT NULL,
                    refinedText TEXT,
                    language TEXT NOT NULL,
                    durationMs INTEGER,
                    fileSizeBytes INTEGER,
                    segmentsJson TEXT,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO transcriptions (
                    fileName, originalText, language, createdAt
                ) VALUES ('old.wav', 'hello', 'en', 100)
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS dictionary_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    word TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dictionary_entries_word ON dictionary_entries (word)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)
        val cursor =
            db.query("SELECT status, errorMessage, audioPath, provider FROM transcriptions WHERE fileName = 'old.wav'")
        try {
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo(TranscriptionEntity.STATUS_COMPLETED)
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.isNull(2)).isTrue()
            assertThat(cursor.isNull(3)).isTrue()
        } finally {
            cursor.close()
        }
    }

    @Test
    fun migration9To10AddsToneMetadataAndToneAwareUniqueness() {
        val db = helper.createDatabase(TONE_MIGRATION_DB, 9)
        db.execSQL(
            """
            CREATE TABLE hybrid_lexicon (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                phrase TEXT NOT NULL,
                code TEXT NOT NULL,
                normalizedCode TEXT NOT NULL,
                initials TEXT NOT NULL,
                source TEXT NOT NULL,
                baseWeight INTEGER NOT NULL,
                usageCount INTEGER NOT NULL,
                lastUsedAt INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                personalKind TEXT NOT NULL DEFAULT 'LEGACY',
                frequencyWeight REAL DEFAULT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX index_hybrid_lexicon_phrase_normalizedCode_source " +
                "ON hybrid_lexicon (phrase, normalizedCode, source)",
        )

        AppDatabase.MIGRATION_9_10.migrate(db)

        val columns = db.query("PRAGMA table_info(hybrid_lexicon)")
        try {
            val names =
                buildList {
                    while (columns.moveToNext()) add(columns.getString(1))
                }
            assertThat(names).contains("toneCode")
        } finally {
            columns.close()
        }
        val index =
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE '%toneCode'",
            )
        try {
            assertThat(index.moveToFirst()).isTrue()
            assertThat(index.getString(0)).isEqualTo(
                "index_hybrid_lexicon_phrase_normalizedCode_source_toneCode",
            )
        } finally {
            index.close()
            db.close()
        }
    }

    @Test
    fun migration10To11AddsContextLearningWithoutRemovingExistingData() {
        val db = helper.createDatabase(CONTEXT_MIGRATION_DB, 10)
        db.execSQL(
            "CREATE TABLE dictionary_entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "word TEXT NOT NULL, createdAt INTEGER NOT NULL)",
        )
        db.execSQL("INSERT INTO dictionary_entries (word, createdAt) VALUES ('姍靈', 100)")

        AppDatabase.MIGRATION_10_11.migrate(db)
        db.execSQL(
            "INSERT INTO hybrid_context_learning (contextText, continuation, selectionCount, lastSelectedAt) " +
                "VALUES ('台', '達', 3, 200)",
        )
        val words = db.query("SELECT word FROM dictionary_entries")
        try {
            assertThat(words.moveToFirst()).isTrue()
            assertThat(words.getString(0)).isEqualTo("姍靈")
        } finally {
            words.close()
        }
        val transitions =
            db.query(
                "SELECT continuation, selectionCount FROM hybrid_context_learning WHERE contextText = '台'",
            )
        try {
            assertThat(transitions.moveToFirst()).isTrue()
            assertThat(transitions.getString(0)).isEqualTo("達")
            assertThat(transitions.getInt(1)).isEqualTo(3)
        } finally {
            transitions.close()
            db.close()
        }
    }

    @Test
    fun migration13To14AddsSeparateFavoriteAndLastUsedFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(null)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(13) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL(
                                    """
                                    CREATE TABLE clipboard_entries (
                                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                        text TEXT NOT NULL,
                                        type TEXT NOT NULL,
                                        groupName TEXT NOT NULL,
                                        isPinned INTEGER NOT NULL,
                                        usageCount INTEGER NOT NULL,
                                        createdAt INTEGER NOT NULL,
                                        updatedAt INTEGER NOT NULL,
                                        shortcut TEXT NOT NULL DEFAULT '',
                                        label TEXT NOT NULL DEFAULT ''
                                    )
                                    """.trimIndent(),
                                )
                                db.execSQL(
                                    "INSERT INTO clipboard_entries " +
                                        "(text, type, groupName, isPinned, usageCount, createdAt, updatedAt) " +
                                        "VALUES ('工作 Email', 'COMMON', 'Email', 0, 3, 100, 250)",
                                )
                                db.execSQL(
                                    "INSERT INTO clipboard_entries " +
                                        "(text, type, groupName, isPinned, usageCount, createdAt, updatedAt) " +
                                        "VALUES ('歷史項目', 'HISTORY', '', 0, 0, 100, 300)",
                                )
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_13_14.migrate(db)

        val cursor = db.query("SELECT text, isFavorite, lastUsedAt FROM clipboard_entries ORDER BY id")
        try {
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("工作 Email")
            assertThat(cursor.getInt(1)).isEqualTo(0)
            assertThat(cursor.getLong(2)).isEqualTo(250)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("歷史項目")
            assertThat(cursor.getInt(1)).isEqualTo(0)
            assertThat(cursor.getLong(2)).isEqualTo(0)
        } finally {
            cursor.close()
            helper.close()
        }
    }

    @Test
    fun migration14To15AddsCandidateLookupIndexesWithoutRemovingData() {
        helper.createDatabase(CANDIDATE_INDEX_MIGRATION_DB, 14).apply {
            execSQL(
                """
                INSERT INTO hybrid_lexicon (
                    phrase, code, normalizedCode, initials, source, baseWeight,
                    frequencyWeight, usageCount, lastUsedAt, createdAt, personalKind, toneCode
                ) VALUES ('台達', 'tai da', 'taida', 'td', 'PERSONAL', 0, NULL, 2, 100, 50, 'MANUAL', '')
                """.trimIndent(),
            )
            close()
        }

        val db =
            helper.runMigrationsAndValidate(
                CANDIDATE_INDEX_MIGRATION_DB,
                15,
                true,
                AppDatabase.MIGRATION_14_15,
            )
        val indexes = db.query("PRAGMA index_list(hybrid_lexicon)")
        val names = mutableSetOf<String>()
        try {
            while (indexes.moveToNext()) names += indexes.getString(1)
        } finally {
            indexes.close()
        }
        assertThat(names).contains("index_hybrid_lexicon_source_normalizedCode")
        assertThat(names).contains("index_hybrid_lexicon_source_initials")

        val data = db.query("SELECT phrase, usageCount FROM hybrid_lexicon WHERE normalizedCode = 'taida'")
        try {
            assertThat(data.moveToFirst()).isTrue()
            assertThat(data.getString(0)).isEqualTo("台達")
            assertThat(data.getInt(1)).isEqualTo(2)
        } finally {
            data.close()
            db.close()
        }
    }

    @Test
    fun migration15To16AddsRefinedSegmentsWithoutRemovingTranscriptionData() {
        helper.createDatabase(SRT_REFINEMENT_MIGRATION_DB, 15).apply {
            execSQL(
                """
                INSERT INTO transcriptions (
                    fileName, originalText, refinedText, language, durationMs,
                    fileSizeBytes, segmentsJson, status, errorMessage, audioPath,
                    provider, createdAt
                ) VALUES (
                    'demo.wav', '原文', '潤飾後', 'zh', NULL,
                    1234, '[{"s":0,"e":1000,"t":"原文"}]', 'completed', NULL, NULL,
                    'groq', 100
                )
                """.trimIndent(),
            )
            close()
        }

        val db =
            helper.runMigrationsAndValidate(
                SRT_REFINEMENT_MIGRATION_DB,
                16,
                true,
                AppDatabase.MIGRATION_15_16,
            )
        val data =
            db.query(
                "SELECT fileName, originalText, refinedSegmentsJson FROM transcriptions WHERE fileName = 'demo.wav'",
            )
        try {
            assertThat(data.moveToFirst()).isTrue()
            assertThat(data.getString(0)).isEqualTo("demo.wav")
            assertThat(data.getString(1)).isEqualTo("原文")
            assertThat(data.isNull(2)).isTrue()
        } finally {
            data.close()
            db.close()
        }
    }

    private companion object {
        private const val TEST_DB = "migration-test"
        private const val TONE_MIGRATION_DB = "migration-tone-test"
        private const val CONTEXT_MIGRATION_DB = "migration-context-test"
        private const val CANDIDATE_INDEX_MIGRATION_DB = "migration-candidate-index-test"
        private const val SRT_REFINEMENT_MIGRATION_DB = "migration-srt-refinement-test"
    }
}
