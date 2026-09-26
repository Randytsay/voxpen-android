package com.voxpen.app.data.repository

import com.voxpen.app.data.local.HybridLexiconEntity
import com.voxpen.app.data.local.HybridLexiconSource

object HybridLexiconImporter {
    data class ParsedEntry(
        val phrase: String,
        val code: String,
        val source: HybridLexiconSource,
        val baseWeight: Int = 0,
        val frequencyWeight: Double? = null,
        val personalKind: String = "NONE",
    )

    fun parseRimeDictionary(
        raw: String,
        presetVocabulary: String? = null,
    ): List<ParsedEntry> {
        val presetWeights = presetVocabulary?.let(::parsePresetVocabulary).orEmpty()
        var inDataSection = false
        var usesPresetVocabulary = false
        return raw.lineSequence().mapNotNull { original ->
            val line = original.trim()
            if (line == "...") {
                inDataSection = true
                return@mapNotNull null
            }
            if (!inDataSection && line.startsWith("use_preset_vocabulary:")) {
                usesPresetVocabulary = line.substringAfter(':').trim().equals("true", ignoreCase = true)
            }
            if (!inDataSection || line.isBlank() || line.startsWith("#")) {
                return@mapNotNull null
            }

            val columns = original.split('\t')
            if (columns.size < 2) return@mapNotNull null
            val phrase = columns[0].trim()
            val code = columns[1].trim()
            if (phrase.isBlank() || code.isBlank()) return@mapNotNull null
            val explicitWeight = columns.getOrNull(2)?.let(::parseWeight)
            ParsedEntry(
                phrase = phrase,
                code = code,
                source = HybridLexiconSource.PINYIN,
                baseWeight = explicitWeight?.toInt() ?: 0,
                frequencyWeight =
                    explicitWeight ?: presetWeights[phrase]
                        ?: if (usesPresetVocabulary) 0.0 else null,
            )
        }.toList()
    }

    private fun parsePresetVocabulary(raw: String): Map<String, Double> =
        raw.lineSequence().mapNotNull { original ->
            val line = original.trim().removePrefix("\uFEFF")
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val columns = line.split('\t', limit = 2)
            if (columns.size != 2) return@mapNotNull null
            val phrase = columns[0].trim()
            val weight = parseWeight(columns[1])
            if (phrase.isBlank() || weight == null) return@mapNotNull null
            phrase to weight
        }.toMap()

    private fun parseWeight(raw: String): Double? =
        raw.trim().removeSuffix("%").toDoubleOrNull()?.takeIf(Double::isFinite)

    fun parseBoshiamyCin(raw: String): List<ParsedEntry> {
        var inCharDef = false
        return raw.lineSequence().mapNotNull { original ->
            val line = original.trim()
            when {
                line.equals("%chardef begin", ignoreCase = true) -> {
                    inCharDef = true
                    return@mapNotNull null
                }
                line.equals("%chardef end", ignoreCase = true) -> {
                    inCharDef = false
                    return@mapNotNull null
                }
            }
            if (!inCharDef || line.isBlank() || line.startsWith("#") || line.startsWith("%")) {
                return@mapNotNull null
            }
            val columns = line.split(Regex("\\s+"), limit = 2)
            if (columns.size != 2) return@mapNotNull null
            val code = columns[0].trim()
            val phrase = columns[1].trim()
            if (code.isBlank() || phrase.isBlank()) return@mapNotNull null
            ParsedEntry(
                phrase = phrase,
                code = code,
                source = HybridLexiconSource.BOSHIAMY,
            )
        }.toList()
    }

    fun parseBaiduText(raw: String): List<ParsedEntry> =
        raw.lineSequence().mapNotNull { original ->
            val line = original.trim().removePrefix("\uFEFF")
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null

            val columns =
                line.split(Regex("[\\t,|;]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

            val phrase = columns.firstOrNull(::containsCjk) ?: return@mapNotNull null
            val pinyin = columns.firstOrNull(::looksLikePinyin)
            ParsedEntry(
                phrase = phrase,
                code = pinyin.orEmpty(),
                source = HybridLexiconSource.BAIDU,
            )
        }.toList()

    fun toEntity(entry: ParsedEntry): HybridLexiconEntity {
        val normalized = normalizeCode(entry.code)
        return HybridLexiconEntity(
            phrase = entry.phrase,
            code = entry.code.trim(),
            normalizedCode = normalized,
            initials = pinyinInitials(entry.code),
            source = entry.source.name,
            baseWeight = entry.baseWeight,
            frequencyWeight = entry.frequencyWeight,
            personalKind = entry.personalKind,
        )
    }

    fun normalizeCode(code: String): String =
        PinyinInputSegmentor.normalizeInput(code).filter { it in 'a'..'z' }

    fun pinyinInitials(code: String): String =
        PinyinInputSegmentor.dictionarySyllables(code)
            .joinToString("") { it.first().toString() }

    private fun containsCjk(value: String): Boolean =
        value.any { char ->
            char.code in 0x3400..0x4DBF || char.code in 0x4E00..0x9FFF
        }

    private fun looksLikePinyin(value: String): Boolean {
        val compact = value.lowercase().replace("ü", "v")
        return compact.isNotBlank() &&
            compact.any { it in 'a'..'z' } &&
            compact.all { it in 'a'..'z' || it == ' ' || it == '\'' }
    }
}
