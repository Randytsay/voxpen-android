package com.voxpen.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.repository.HybridInputRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HybridContextLearningIntegrationTest {
    @Test
    fun repeatedSelectionsPredictAndContinueFourCharacterPhrase() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val repository = HybridInputRepository(database, context)
                repeat(2) {
                    repository.recordContextSelection("台", "達")
                    repository.recordContextSelection("台達", "能")
                    repository.recordContextSelection("台達能", "源")
                }
                assertThat(repository.queryContextSuggestions("台")).isEmpty()
                repository.recordContextSelection("台", "達")
                repository.recordContextSelection("台達", "能")
                repository.recordContextSelection("台達能", "源")
                assertThat(repository.queryContextSuggestions("台")).contains("達")
                assertThat(repository.queryContextSuggestions("台")).contains("達能源")
                assertThat(repository.queryContextSuggestions("台達")).contains("能")
                assertThat(repository.queryContextSuggestions("台達")).contains("能源")
                assertThat(repository.queryContextSuggestions("台達能")).contains("源")

                assertThat(repository.addPersonalPhrase("台達能源", "tai da neng yuan")).isTrue()
                assertThat(repository.queryContextSuggestions("台")).contains("達能源")
                assertThat(repository.queryContextSuggestions("台達")).contains("能源")

                repository.clearAutomaticLearning()
                assertThat(database.hybridContextLearningDao().findTransitions(listOf("台"), 3, 10)).isEmpty()
                assertThat(repository.queryContextSuggestions("台")).contains("達能源")
            } finally {
                database.close()
            }
        }

    @Test
    fun wholePhraseSelectionLearnsItsNextSegments() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val repository = HybridInputRepository(database, context)
                repeat(3) { repository.recordContextSelection("", "台達能源") }
                assertThat(repository.queryContextSuggestions("台")).containsAtLeast("達", "達能源")
                assertThat(repository.queryContextSuggestions("台達")).containsAtLeast("能", "能源")
            } finally {
                database.close()
            }
        }
}
