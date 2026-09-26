package com.voxpen.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChinesePunctuationNormalizerTest {
    @Test
    fun `converts basic punctuation in Chinese sentence to full-width`() {
        val input = "你好,你在哪裡?我已經到了!這是一個測試."
        val expected = "你好，你在哪裡？我已經到了！這是一個測試。"
        assertThat(ChinesePunctuationNormalizer.normalize(input)).isEqualTo(expected)
    }

    @Test
    fun `converts colons and semicolons in Chinese context`() {
        val input = "會議結論如下:第一條通過;第二條保留."
        val expected = "會議結論如下：第一條通過；第二條保留。"
        assertThat(ChinesePunctuationNormalizer.normalize(input)).isEqualTo(expected)
    }

    @Test
    fun `converts parentheses and brackets in Chinese context`() {
        val input = "請參考 (附件一) 以及 [說明表格]."
        val expected = "請參考（附件一）以及【說明表格】。"
        assertThat(ChinesePunctuationNormalizer.normalize(input)).isEqualTo(expected)
    }

    @Test
    fun `preserves thousands separators in numbers`() {
        val input = "這項預算大約是 1,000,000 美元,你覺得呢?"
        val expected = "這項預算大約是 1,000,000 美元，你覺得呢？"
        assertThat(ChinesePunctuationNormalizer.normalize(input)).isEqualTo(expected)
    }

    @Test
    fun `preserves decimal points in numbers`() {
        val input = "圓周率是 3.14159,重力加速度是 9.8 左右."
        val expected = "圓周率是 3.14159，重力加速度是 9.8 左右。"
        assertThat(ChinesePunctuationNormalizer.normalize(input)).isEqualTo(expected)
    }

    @Test
    fun `preserves time notation`() {
        val input = "我們今天下午 14:30 開會: 請準時到場."
        val expected = "我們今天下午 14:30 開會：請準時到場。"
        assertThat(ChinesePunctuationNormalizer.normalize(input)).isEqualTo(expected)
    }

    @Test
    fun `preserves domain names in Chinese context`() {
        val input = "可以到 google.com 搜尋,或是查閱 github.com."
        val expected = "可以到 google.com 搜尋，或是查閱 github.com。"
        assertThat(ChinesePunctuationNormalizer.normalize(input)).isEqualTo(expected)
    }

    @Test
    fun `leaves pure English text completely unchanged`() {
        val input = "Hello, how are you? I'm fine, thank you! It's 10:30 a.m. at google.com."
        assertThat(ChinesePunctuationNormalizer.normalize(input)).isEqualTo(input)
    }

    @Test
    fun `cleans up awkward spacing after full-width punctuation before Chinese`() {
        val input = "好的, 沒問題! 我們明天見."
        val expected = "好的，沒問題！我們明天見。"
        assertThat(ChinesePunctuationNormalizer.normalize(input)).isEqualTo(expected)
    }
}
