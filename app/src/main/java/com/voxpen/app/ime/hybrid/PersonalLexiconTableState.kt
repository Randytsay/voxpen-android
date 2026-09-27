package com.voxpen.app.ime.hybrid

import com.voxpen.app.data.local.HybridLexiconEntity

internal enum class PersonalWordFilter {
    ALL,
    MANUAL,
    LEARNED,
}

internal enum class PersonalWordSort {
    PHRASE,
    PINYIN,
    MOST_USED,
    RECENT,
}

internal data class PersonalWordPage(
    val entries: List<HybridLexiconEntity>,
    val totalEntries: Int,
    val page: Int,
    val pageCount: Int,
    val firstVisibleNumber: Int,
    val lastVisibleNumber: Int,
)

internal fun personalWordPage(
    entries: List<HybridLexiconEntity>,
    search: String,
    filter: PersonalWordFilter,
    sort: PersonalWordSort,
    requestedPage: Int,
    pageSize: Int = 15,
): PersonalWordPage {
    require(pageSize > 0)
    val needle = search.trim().lowercase()
    val filtered =
        entries.asSequence()
            .filter { entry ->
                when (filter) {
                    PersonalWordFilter.ALL -> true
                    PersonalWordFilter.MANUAL -> entry.personalKind != AUTO_PROMOTED_KIND
                    PersonalWordFilter.LEARNED -> entry.personalKind == AUTO_PROMOTED_KIND
                }
            }
            .filter { entry ->
                needle.isBlank() ||
                    entry.phrase.lowercase().contains(needle) ||
                    entry.code.lowercase().contains(needle) ||
                    entry.initials.lowercase().contains(needle)
            }
            .let { sequence ->
                when (sort) {
                    PersonalWordSort.PHRASE -> sequence.sortedWith(compareBy({ it.phrase }, { it.code }, { it.id }))
                    PersonalWordSort.PINYIN -> sequence.sortedWith(compareBy({ it.code }, { it.phrase }, { it.id }))
                    PersonalWordSort.MOST_USED ->
                        sequence.sortedWith(
                            compareByDescending<HybridLexiconEntity> { it.usageCount }
                                .thenByDescending { it.lastUsedAt }
                                .thenBy { it.phrase },
                        )
                    PersonalWordSort.RECENT ->
                        sequence.sortedWith(
                            compareByDescending<HybridLexiconEntity> { it.lastUsedAt }
                                .thenByDescending { it.usageCount }
                                .thenBy { it.phrase },
                        )
                }
            }
            .toList()
    val pageCount = maxOf(1, (filtered.size + pageSize - 1) / pageSize)
    val page = requestedPage.coerceIn(0, pageCount - 1)
    val start = page * pageSize
    val visible = filtered.drop(start).take(pageSize)
    return PersonalWordPage(
        entries = visible,
        totalEntries = filtered.size,
        page = page,
        pageCount = pageCount,
        firstVisibleNumber = if (visible.isEmpty()) 0 else start + 1,
        lastVisibleNumber = start + visible.size,
    )
}

internal const val AUTO_PROMOTED_KIND = "AUTO_PROMOTED"
