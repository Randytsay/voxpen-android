package com.voxpen.app.util

object ChinesePunctuationNormalizer {

    /**
     * Checks whether the given text contains any CJK ideographs (Chinese characters).
     */
    fun containsChinese(text: String): Boolean =
        text.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }

    /**
     * Converts half-width punctuation marks into standard full-width Chinese punctuation
     * when the text is in a Chinese context.
     *
     * Protected patterns:
     * - Thousands separator / numbers (e.g. 1,000)
     * - Decimal numbers (e.g. 3.14)
     * - Domain names or identifiers (e.g. google.com)
     * - Time representations (e.g. 10:30)
     * - Ellipsis (...)
     */
    fun normalize(text: String): String {
        if (text.isBlank() || !containsChinese(text)) return text

        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val prev = if (i > 0) text[i - 1] else null
            val next = if (i + 1 < text.length) text[i + 1] else null

            when (ch) {
                ',' -> {
                    if (prev?.isDigit() == true && next?.isDigit() == true) {
                        sb.append(',')
                    } else {
                        sb.append('，')
                    }
                }
                '?' -> sb.append('？')
                '!' -> sb.append('！')
                ':' -> {
                    if (prev?.isDigit() == true && next?.isDigit() == true) {
                        sb.append(':')
                    } else {
                        sb.append('：')
                    }
                }
                ';' -> sb.append('；')
                '(' -> sb.append('（')
                ')' -> sb.append('）')
                '[' -> sb.append('【')
                ']' -> sb.append('】')
                '~' -> sb.append('～')
                '.' -> {
                    if (prev?.isDigit() == true && next?.isDigit() == true) {
                        sb.append('.')
                    } else if (prev?.isLetter() == true && next?.isLetter() == true) {
                        sb.append('.')
                    } else if (next == '.' || prev == '.') {
                        sb.append('.')
                    } else {
                        sb.append('。')
                    }
                }
                else -> sb.append(ch)
            }
            i++
        }

        var result = sb.toString()
        result = result.replace(Regex("\\s+([，。？！：；）】～])"), "$1")
        result = result.replace(
            Regex("([，。？！：；（【～])\\s+(?=[\\u4E00-\\u9FFF\\u3400-\\u4DBF，。？！：；（）【】～])"),
            "$1",
        )

        return result
    }
}
