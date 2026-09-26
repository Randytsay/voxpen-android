package com.voxpen.app.data.repository

import com.voxpen.app.data.local.HybridLexiconSource

/** Parser for the unchanged Traditional-Chinese source files shipped by VanguardLexicon. */
object MixTypeLexiconImporter {
    // VanguardLexicon 4.8.4 data revision; bump when the bundled corpus changes.
    const val VERSION = 4_080_004
    const val ASSET_NAME = "mixtype-vanguard-cht-4.8.4.zip"

    fun isSupportedFile(filename: String): Boolean =
        (filename.startsWith("phrases-") && filename.endsWith("-cht.txt")) ||
            filename == "char-kanji-core.txt" ||
            filename == "char-kanji-cns.txt" ||
            filename == "char-kanji-gbex.txt"

    fun parseLine(
        filename: String,
        original: String,
    ): HybridLexiconImporter.ParsedEntry? {
        val line = original.trim()
        if (line.isBlank() || line.startsWith("#")) return null

        return when {
            filename.startsWith("phrases-") && filename.endsWith("-cht.txt") -> parsePhrase(line)
            filename == "char-kanji-core.txt" -> parseCoreCharacter(line)
            filename == "char-kanji-cns.txt" || filename == "char-kanji-gbex.txt" -> parseSupplementaryCharacter(line)
            else -> null
        }
    }

    fun zhuyinToPinyin(reading: String): String? {
        val syllables = reading.trim().split(Regex("[\\s-]+")).filter(String::isNotBlank)
        if (syllables.isEmpty()) return null
        val pinyin = syllables.map { zhuyinSyllableToPinyin(it) ?: return null }
        val result = pinyin.joinToString(" ")
        return result.takeIf(PinyinInputSegmentor::isValidReading)
    }

    private fun parsePhrase(line: String): HybridLexiconImporter.ParsedEntry? {
        val columns = line.split(Regex("\\s+"))
        if (columns.size < 3) return null
        val phrase = columns[0]
        val frequency = columns[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val code = zhuyinToPinyin(columns.drop(2).joinToString(" ")) ?: return null
        return entry(phrase, code, frequency)
    }

    private fun parseCoreCharacter(line: String): HybridLexiconImporter.ParsedEntry? {
        val columns = line.split('\t')
        if (columns.size < 4 || columns[0].codePointCount(0, columns[0].length) != 1) return null
        val frequency = columns[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val code = zhuyinToPinyin(columns.drop(3).joinToString(" ")) ?: return null
        return entry(columns[0], code, frequency)
    }

    private fun parseSupplementaryCharacter(line: String): HybridLexiconImporter.ParsedEntry? {
        val columns = line.split(Regex("\\s+"))
        if (columns.size < 2 || columns[0].codePointCount(0, columns[0].length) != 1) return null
        val code = zhuyinToPinyin(columns.drop(1).joinToString(" ")) ?: return null
        return entry(columns[0], code, frequency = 0.0)
    }

    private fun entry(
        phrase: String,
        code: String,
        frequency: Double,
    ) = HybridLexiconImporter.ParsedEntry(
        phrase = phrase,
        code = code,
        source = HybridLexiconSource.MIXTYPE,
        frequencyWeight = frequency.coerceAtLeast(0.0),
    )

    private fun zhuyinSyllableToPinyin(raw: String): String? {
        val syllable = raw.filterNot { it in TONES }
        if (syllable.isEmpty()) return null

        val initial = INITIALS.entries.firstOrNull { syllable.startsWith(it.key) }
        val onset = initial?.value.orEmpty()
        val phoneticBody = if (initial == null) syllable else syllable.removePrefix(initial.key)
        if (phoneticBody.any { it !in FINALS }) return null
        var body = phoneticBody.mapNotNull(FINALS::get).joinToString("")
        if (body.isEmpty()) {
            if (onset in APICAL_INITIALS && initial != null) return "${onset}i"
            return null
        }

        if (initial == null) {
            body = when (body) {
                "ien" -> "in"
                "ieng" -> "ing"
                else -> body
            }
            return zeroInitial(body)
        }

        body = when {
            onset in PALATAL_INITIALS -> when (body) {
                "v" -> "u"
                "ve" -> "ue"
                "van" -> "uan"
                "ven", "vn" -> "un"
                "veng" -> "iong"
                else -> body
            }
            onset in FRONT_ROUNDED_INITIALS -> body
            else -> body
        }
        body = when (body) {
            "ien" -> "in"
            "ieng" -> "ing"
            "ueng" -> "ong"
            "iou" -> "iu"
            "uei" -> "ui"
            "uen" -> "un"
            else -> body
        }
        return (onset + body).takeIf(PinyinInputSegmentor::isValidReading)
    }

    private fun zeroInitial(body: String): String = when (body) {
        "i" -> "yi"
        "ia" -> "ya"
        "ie" -> "ye"
        "io" -> "yo"
        "iao" -> "yao"
        "iou" -> "you"
        "ian" -> "yan"
        "in" -> "yin"
        "iang" -> "yang"
        "ing" -> "ying"
        "iong" -> "yong"
        "u" -> "wu"
        "ua" -> "wa"
        "uo" -> "wo"
        "uai" -> "wai"
        "uei" -> "wei"
        "uan" -> "wan"
        "uen" -> "wen"
        "uang" -> "wang"
        "ueng" -> "weng"
        "v" -> "yu"
        "ve" -> "yue"
        "van" -> "yuan"
        "vn" -> "yun"
        "ven" -> "yun"
        "veng" -> "yong"
        else -> body
    }

    private val TONES = setOf('ˊ', 'ˇ', 'ˋ', '˙')
    private val APICAL_INITIALS = setOf("zh", "ch", "sh", "r", "z", "c", "s")
    private val PALATAL_INITIALS = setOf("j", "q", "x")
    private val FRONT_ROUNDED_INITIALS = setOf("n", "l")

    private val INITIALS = linkedMapOf(
        "ㄓ" to "zh", "ㄔ" to "ch", "ㄕ" to "sh", "ㄖ" to "r", "ㄗ" to "z", "ㄘ" to "c", "ㄙ" to "s",
        "ㄅ" to "b", "ㄆ" to "p", "ㄇ" to "m", "ㄈ" to "f", "ㄉ" to "d", "ㄊ" to "t", "ㄋ" to "n", "ㄌ" to "l",
        "ㄍ" to "g", "ㄎ" to "k", "ㄏ" to "h", "ㄐ" to "j", "ㄑ" to "q", "ㄒ" to "x",
    )

    private val FINALS = mapOf(
        'ㄧ' to "i", 'ㄨ' to "u", 'ㄩ' to "v",
        'ㄚ' to "a", 'ㄛ' to "o", 'ㄜ' to "e", 'ㄝ' to "e", 'ㄞ' to "ai", 'ㄟ' to "ei",
        'ㄠ' to "ao", 'ㄡ' to "ou", 'ㄢ' to "an", 'ㄣ' to "en", 'ㄤ' to "ang", 'ㄥ' to "eng", 'ㄦ' to "er",
    )
}
