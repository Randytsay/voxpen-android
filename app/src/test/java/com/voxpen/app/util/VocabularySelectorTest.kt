package com.voxpen.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VocabularySelectorTest {
    @Test
    fun `prioritizes recent important terms then alphabetizes missing important terms`() {
        val result = VocabularySelector.prioritizeImportant(
            importantWords = setOf("zeta", "alpha", "recent-important", "later-important"),
            recentVocabulary = listOf("ordinary", "recent-important", "later-important"),
        )

        assertThat(result).containsExactly(
            "recent-important", "later-important", "alpha", "zeta",
        ).inOrder()
    }

    @Test
    fun `selects exact and case insensitive matches before recent fill`() {
        val result = VocabularySelector.selectRelevant(
            transcription = "Use Claude and anthropic today",
            ordinaryVocabulary = listOf("recent-one", "Claude", "Anthropic", "recent-two"),
            limit = 4,
        )

        assertThat(result).containsExactly("Claude", "Anthropic", "recent-one", "recent-two").inOrder()
    }

    @Test
    fun `selects meaningful overlap before unrelated recent terms`() {
        val result = VocabularySelector.selectRelevant(
            transcription = "the keyboard shortcut is useful",
            ordinaryVocabulary = listOf("unrelated", "keyboard shortcut guide", "later"),
            limit = 2,
        )

        assertThat(result.first()).isEqualTo("keyboard shortcut guide")
        assertThat(result).contains("unrelated")
    }

    @Test
    fun `deduplicates and respects limit`() {
        val result = VocabularySelector.selectRelevant(
            transcription = "VoxPen",
            ordinaryVocabulary = listOf("VoxPen", "VoxPen", "one", "two"),
            limit = 2,
        )

        assertThat(result).containsExactly("VoxPen", "one").inOrder()
    }
}
