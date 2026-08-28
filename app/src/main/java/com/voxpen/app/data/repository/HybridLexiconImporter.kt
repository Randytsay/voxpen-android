package com.voxpen.app.data.repository

import com.voxpen.app.data.local.HybridLexiconEntity
import com.voxpen.app.data.local.HybridLexiconSource

object HybridLexiconImporter {
    data class ParsedEntry(
        val phrase: String,
        val code: String,
        val source: HybridLexiconSource,
        val baseWeight: Int = 0,
    )

    fun parseRimeDictionary(raw: String): List<ParsedEntry> {
        var inDataSection = false
        return raw.lineSequence().mapNotNull { original ->
            val line = original.trim()
            if (line == "...") {
                inDataSection = true
                return@mapNotNull null
            }
            if (!inDataSection || line.isBlank() || line.startsWith("#")) {
                return@mapNotNull null
            }

            val columns = original.split('\t')
            if (columns.size < 2) return@mapNotNull null
            val phrase = columns[0].trim()
            val code = columns[1].trim()
            if (phrase.isBlank() || code.isBlank()) return@mapNotNull null
            val weight = columns.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            ParsedEntry(
                phrase = phrase,
                code = code,
                source = HybridLexiconSource.PINYIN,
                baseWeight = weight,
            )
        }.toList()
    }

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

            val columns = line.split(Regex("[\\t,|;]+"))
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
        )
    }

    fun normalizeCode(code: String): String =
        code.lowercase()
            .replace("ü", "v")
            .filter { it in 'a'..'z' }

    fun pinyinInitials(code: String): String {
        val syllables = code.lowercase()
            .replace("ü", "v")
            .split(Regex("[\\s']+"))
            .filter { it.isNotBlank() }
        if (syllables.size <= 1) return syllables.firstOrNull()?.take(1).orEmpty()
        return syllables.joinToString(separator = "") { it.take(1) }
    }

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
