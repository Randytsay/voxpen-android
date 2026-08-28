package com.voxpen.app.util

/** Selects ordinary vocabulary in relevance order while retaining recent-order fill. */
object VocabularySelector {
    const val DEFAULT_LIMIT = 120

    fun prioritizeImportant(
        importantWords: Set<String>,
        recentVocabulary: List<String>,
    ): List<String> {
        return (
            recentVocabulary.filter { it in importantWords } +
                importantWords.filterNot { it in recentVocabulary }.sorted()
        ).distinct()
    }

    fun selectRelevant(
        transcription: String,
        ordinaryVocabulary: List<String>,
        limit: Int = DEFAULT_LIMIT,
    ): List<String> {
        if (limit <= 0) return emptyList()

        val candidates =
            ordinaryVocabulary
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
        if (candidates.isEmpty()) return emptyList()

        val exact = candidates.filter { transcription.contains(it) }
        val caseInsensitive =
            candidates.filter { candidate ->
                candidate !in exact && transcription.contains(candidate, ignoreCase = true)
            }
        val speechTokens = meaningfulTokens(transcription.lowercase())
        val overlap =
            candidates
                .filter { it !in exact && it !in caseInsensitive }
                .mapIndexedNotNull { index, candidate ->
                    val score = overlapScore(candidate, speechTokens)
                    if (score > 0) Triple(index, candidate, score) else null
                }.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
                .map { it.second }

        return (exact + caseInsensitive + overlap + candidates)
            .distinct()
            .take(limit)
    }

    private fun overlapScore(
        candidate: String,
        speechTokens: Set<String>,
    ): Int {
        val normalized = candidate.lowercase()
        val tokens = meaningfulTokens(normalized)
        if (tokens.isEmpty()) return 0

        val overlap = tokens.count { it in speechTokens }
        if (overlap == 0) return 0

        val cjkOnly =
            tokens.all { token ->
                token.length == 1 && token.first().code in 0x3400..0x9FFF
            }
        // A one-character overlap is too noisy for CJK text; direct contains above
        // still handles exact terms and multi-character meaningful matches.
        return when {
            cjkOnly -> if (tokens.size >= 2 && overlap >= 2) overlap else 0
            normalized.length >= 2 || tokens.size > 1 -> overlap
            else -> 0
        }
    }

    private fun meaningfulTokens(text: String): Set<String> = TOKEN_REGEX.findAll(text).map { it.value }.toSet()

    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+|[\\u3400-\\u9FFF]")
}
