package com.voxpen.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PinyinInputSegmentorTest {
    @Test
    fun `continuous pinyin returns competing legal syllable boundaries`() {
        val paths = PinyinInputSegmentor.segment("xian")

        assertThat(paths.map { it.fullPinyin }).containsAtLeast("xian", "xi an")
    }

    @Test
    fun `continuous pinyin finds words across ambiguous boundaries`() {
        val paths = PinyinInputSegmentor.segment("fangan")

        assertThat(paths.map { it.fullPinyin }).containsAtLeast("fang an", "fan gan")
    }

    @Test
    fun `explicit apostrophe preserves the requested syllable boundary`() {
        val paths = PinyinInputSegmentor.segment("xi'an")

        assertThat(paths.map { it.fullPinyin }).containsExactly("xi an")
    }

    @Test
    fun `initial abbreviation can be combined with a full syllable`() {
        val paths = PinyinInputSegmentor.segment("nhao")

        assertThat(paths.map { it.tokens.map(PinyinInputToken::value) })
            .contains(listOf("n", "hao"))
    }

    @Test
    fun `d plus sao can represent the initials and shortened retroflex syllable in dsao`() {
        val path =
            PinyinInputSegmentor.segment("dsao")
                .first { it.tokens.map(PinyinInputToken::value) == listOf("d", "sao") }

        assertThat(path.tokens.map(PinyinInputToken::kind)).containsExactly(
            PinyinInputToken.Kind.INITIAL,
            PinyinInputToken.Kind.SYLLABLE,
        ).inOrder()
    }

    @Test
    fun `dictionary readings support compact and umlaut spellings`() {
        assertThat(PinyinInputSegmentor.dictionarySyllables("yaowen")).containsExactly("yao", "wen").inOrder()
        assertThat(PinyinInputSegmentor.dictionarySyllables("nü")).containsExactly("nv")
    }

    @Test
    fun `tone marked pinyin normalizes for both input and dictionary readings`() {
        assertThat(PinyinInputSegmentor.normalizeInput("nǐ hǎo")).isEqualTo("ni\u0000hao")
        assertThat(PinyinInputSegmentor.dictionarySyllables("nǐ hǎo")).containsExactly("ni", "hao").inOrder()
        assertThat(PinyinInputSegmentor.dictionarySyllables("lǜ")).containsExactly("lv")
    }

    @Test
    fun `tone metadata is preserved for marked numeric and unmarked readings`() {
        assertThat(PinyinToneCode.fromReading("nǐ hǎo")).isEqualTo("33")
        assertThat(PinyinToneCode.fromReading("yao4")).isEqualTo("4")
        assertThat(PinyinToneCode.fromReading("yao")).isEqualTo("0")
    }
}
