package com.voxpen.app.ime.hybrid

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.HybridLexiconEntity
import com.voxpen.app.data.local.HybridLexiconSource
import org.junit.jupiter.api.Test

class PersonalLexiconTableStateTest {
    @Test
    fun `search covers phrase pinyin and initials`() {
        val entries =
            listOf(
                entry(1, "耀文", "yao wen", "yw"),
                entry(2, "姍靈", "shan ling", "sl"),
            )

        assertThat(page(entries, "耀").entries.map { it.phrase }).containsExactly("耀文")
        assertThat(page(entries, "shan").entries.map { it.phrase }).containsExactly("姍靈")
        assertThat(page(entries, "SL").entries.map { it.phrase }).containsExactly("姍靈")
    }

    @Test
    fun `manual and learned filters remain separate`() {
        val entries =
            listOf(
                entry(1, "耀文", "yao wen", "yw"),
                entry(2, "台達能源", "tai da neng yuan", "tdny", AUTO_PROMOTED_KIND),
            )

        assertThat(page(entries, filter = PersonalWordFilter.MANUAL).entries.map { it.phrase })
            .containsExactly("耀文")
        assertThat(page(entries, filter = PersonalWordFilter.LEARNED).entries.map { it.phrase })
            .containsExactly("台達能源")
    }

    @Test
    fun `requested page is clamped after filtering and reports visible range`() {
        val entries = (1L..31L).map { entry(it, "詞$it", "ci $it", "c") }

        val result = page(entries, requestedPage = 99)

        assertThat(result.page).isEqualTo(2)
        assertThat(result.pageCount).isEqualTo(3)
        assertThat(result.firstVisibleNumber).isEqualTo(31)
        assertThat(result.lastVisibleNumber).isEqualTo(31)
    }

    @Test
    fun `usage sorting places frequently selected words first`() {
        val entries =
            listOf(
                entry(1, "少用", "shao yong", "sy", usageCount = 1),
                entry(2, "常用", "chang yong", "cy", usageCount = 8),
            )

        val result = page(entries, sort = PersonalWordSort.MOST_USED)

        assertThat(result.entries.map { it.phrase }).containsExactly("常用", "少用").inOrder()
    }

    private fun page(
        entries: List<HybridLexiconEntity>,
        search: String = "",
        filter: PersonalWordFilter = PersonalWordFilter.ALL,
        sort: PersonalWordSort = PersonalWordSort.PHRASE,
        requestedPage: Int = 0,
    ): PersonalWordPage =
        personalWordPage(
            entries = entries,
            search = search,
            filter = filter,
            sort = sort,
            requestedPage = requestedPage,
        )

    private fun entry(
        id: Long,
        phrase: String,
        code: String,
        initials: String,
        personalKind: String = "MANUAL",
        usageCount: Int = 0,
    ): HybridLexiconEntity =
        HybridLexiconEntity(
            id = id,
            phrase = phrase,
            code = code,
            normalizedCode = code.replace(" ", ""),
            initials = initials,
            source = HybridLexiconSource.PERSONAL.name,
            personalKind = personalKind,
            usageCount = usageCount,
        )
}
