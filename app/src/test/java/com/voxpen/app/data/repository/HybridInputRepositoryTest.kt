package com.voxpen.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.AppDatabase
import com.voxpen.app.data.local.HybridLexiconDao
import com.voxpen.app.data.local.HybridLexiconEntity
import com.voxpen.app.data.local.HybridLexiconSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class HybridInputRepositoryTest {
    private val database = mockk<AppDatabase>()
    private val dao = mockk<HybridLexiconDao>()
    private val repository: HybridInputRepository

    init {
        every { database.hybridLexiconDao() } returns dao
        repository = HybridInputRepository(database)
    }

    @Test
    fun `boshiamy candidates stay ahead of pinyin candidates`() = runTest {
        stubBootstrapInstalled()
        coEvery {
            dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "bg", any())
        } returns listOf(entity(1, "嘸蝦米候選", "bg", "", HybridLexiconSource.BOSHIAMY))
        coEvery { dao.searchInitialsPrefix("bg", any()) } returns
            listOf(entity(2, "保固", "bao gu", "bg", HybridLexiconSource.PINYIN, usage = 100))
        coEvery { dao.searchPinyinPrefix("bg", any()) } returns emptyList()

        val result = repository.query("bg")

        assertThat(result.map { it.phrase }).containsExactly("嘸蝦米候選", "保固").inOrder()
    }

    @Test
    fun `frequently selected initial candidate moves forward`() = runTest {
        stubBootstrapInstalled()
        coEvery {
            dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "bg", any())
        } returns emptyList()
        coEvery { dao.searchInitialsPrefix("bg", any()) } returns
            listOf(
                entity(1, "不夠", "bu gou", "bg", HybridLexiconSource.PINYIN, usage = 1),
                entity(2, "保固", "bao gu", "bg", HybridLexiconSource.PINYIN, usage = 30),
                entity(3, "表格", "biao ge", "bg", HybridLexiconSource.PINYIN, usage = 3),
            )
        coEvery { dao.searchPinyinPrefix("bg", any()) } returns emptyList()

        val result = repository.query("bg")

        assertThat(result.first().phrase).isEqualTo("保固")
    }

    @Test
    fun `pinyin initials find a phrase learned from full pinyin`() = runTest {
        stubBootstrapInstalled()
        val learned =
            entity(
                id = 9,
                phrase = "常用文",
                code = "chang yong wen",
                initials = "cyw",
                source = HybridLexiconSource.PERSONAL,
                usage = 12,
            )
        coEvery {
            dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "cyw", any())
        } returns emptyList()
        coEvery { dao.searchInitialsPrefix("cyw", any()) } returns listOf(learned)
        coEvery { dao.searchPinyinPrefix("cyw", any()) } returns emptyList()

        val result = repository.query("cyw")

        assertThat(result.first().phrase).isEqualTo("常用文")
        assertThat(result.first().initials).isEqualTo("cyw")
    }

    @Test
    fun `selection increments persistent usage`() = runTest {
        coEvery { dao.recordSelection(7, any()) } returns Unit
        val candidate =
            entity(
                id = 7,
                phrase = "保固",
                code = "bao gu",
                initials = "bg",
                source = HybridLexiconSource.PINYIN,
            ).toCandidate()

        repository.recordSelection(candidate)

        coVerify(exactly = 1) { dao.recordSelection(7, any()) }
    }

    private fun stubBootstrapInstalled() {
        coEvery { dao.countSource(HybridLexiconSource.PINYIN.name) } returns 1
    }

    private fun entity(
        id: Long,
        phrase: String,
        code: String,
        initials: String,
        source: HybridLexiconSource,
        usage: Int = 0,
    ): HybridLexiconEntity =
        HybridLexiconEntity(
            id = id,
            phrase = phrase,
            code = code,
            normalizedCode = HybridLexiconImporter.normalizeCode(code),
            initials = initials,
            source = source.name,
            usageCount = usage,
        )

    private fun HybridLexiconEntity.toCandidate() =
        com.voxpen.app.data.local.HybridCandidate(
            id = id,
            phrase = phrase,
            code = code,
            normalizedCode = normalizedCode,
            initials = initials,
            source = HybridLexiconSource.valueOf(source),
            usageCount = usageCount,
            lastUsedAt = lastUsedAt,
            baseWeight = baseWeight,
        )
}
