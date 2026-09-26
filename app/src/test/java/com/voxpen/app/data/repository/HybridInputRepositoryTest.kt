package com.voxpen.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.AppDatabase
import com.voxpen.app.data.local.HybridLexiconDao
import com.voxpen.app.data.local.HybridLexiconEntity
import com.voxpen.app.data.local.HybridLexiconSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class HybridInputRepositoryTest {
    private val database = mockk<AppDatabase>()
    private val dao = mockk<HybridLexiconDao>()
    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val repository: HybridInputRepository

    init {
        every { database.hybridLexiconDao() } returns dao
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getInt(any(), any()) } returns MixTypeLexiconImporter.VERSION
        coEvery { dao.countSource(HybridLexiconSource.MIXTYPE.name) } returns 200_000
        coEvery { dao.searchPinyinCodes(any(), any()) } returns emptyList()
        coEvery { dao.searchInitialsKeys(any(), any()) } returns emptyList()
        coEvery { dao.getLearningForCodes(any(), any()) } returns emptyList()
        coEvery { dao.recordLearningSelection(any(), any(), any(), any(), any()) } returns Unit
        coEvery { dao.findLearning(any(), any(), any()) } returns null
        coEvery { dao.deleteAutomaticPersonalPhrases() } returns 0
        repository = HybridInputRepository(database, context)
    }

    @Test
    fun `mix type data is considered installed when revision and rows are present`() =
        runTest {
            repository.ensureBootstrapLexicon()

            coVerify(exactly = 1) { dao.countSource(HybridLexiconSource.MIXTYPE.name) }
            coVerify(exactly = 0) { dao.deleteBySource(any()) }
        }

    @Test
    fun `candidate lookup stays responsive without waiting for the full dictionary bootstrap`() =
        runTest {
            stubBootstrapInstalled()
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "bg", any())
            } returns listOf(entity(1, "保固", "bg", "", HybridLexiconSource.BOSHIAMY))
            coEvery { dao.searchInitialsPrefix("bg", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("bg", any()) } returns emptyList()

            val candidates = repository.query("bg")

            assertThat(candidates.first().phrase).isEqualTo("保固")
            coVerify(exactly = 0) { dao.countSource(HybridLexiconSource.MIXTYPE.name) }
        }

    @Test
    fun `exact pinyin initials stay available beside an ambiguous boshiamy code`() =
        runTest {
            stubBootstrapInstalled()
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "bg", any())
            } returns listOf(entity(1, "嘸蝦米候選", "bgx", "", HybridLexiconSource.BOSHIAMY))
            coEvery { dao.searchInitialsPrefix("bg", any()) } returns
                listOf(entity(2, "保固", "bao gu", "bg", HybridLexiconSource.PINYIN, usage = 100))
            coEvery { dao.searchPinyinPrefix("bg", any()) } returns emptyList()

            val result = repository.query("bg")

            assertThat(result.first().phrase).isEqualTo("保固")
            assertThat(result.map { it.phrase }).contains("嘸蝦米候選")
        }

    @Test
    fun `exact boshiamy code outranks conflicting exact Pinyin initials`() =
        runTest {
            stubBootstrapInstalled()
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "jt", any())
            } returns
                listOf(
                    entity(1, "無蝦米候選", "jt", "", HybridLexiconSource.BOSHIAMY),
                )
            coEvery { dao.searchInitialsPrefix("jt", any()) } returns
                listOf(entity(2, "今天", "jin tian", "jt", HybridLexiconSource.PINYIN))
            coEvery { dao.searchPinyinPrefix("jt", any()) } returns emptyList()

            val result = repository.query("JT")

            assertThat(result.map { it.phrase }).containsExactly("無蝦米候選", "今天").inOrder()
        }

    @Test
    fun `WBZD exact Boshiamy match comes before common Pinyin initials`() =
        runTest {
            stubBootstrapInstalled()
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "wbzd", any())
            } returns listOf(entity(21, "嘸蝦米同碼候選", "wbzd", "", HybridLexiconSource.BOSHIAMY))
            coEvery { dao.searchInitialsPrefix("wbzd", any()) } returns
                listOf(
                    entity(22, "無比重大", "wu bu zhong da", "wbzd", HybridLexiconSource.MIXTYPE, frequency = 1.0),
                    entity(23, "我不知道", "wo bu zhi dao", "wbzd", HybridLexiconSource.MIXTYPE, frequency = 22_000.0),
                )
            coEvery { dao.searchPinyinPrefix("wbzd", any()) } returns emptyList()

            val result = repository.query("WBZD")

            assertThat(result.map { it.phrase }).containsExactly("嘸蝦米同碼候選", "我不知道", "無比重大").inOrder()
            coVerify(exactly = 0) { dao.searchInitialsExact(any(), any()) }
        }

    @Test
    fun `MixType frequency ranks 要不要 first for YBY`() =
        runTest {
            stubBootstrapInstalled()
            coEvery { dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "yby", any()) } returns emptyList()
            coEvery { dao.searchInitialsPrefix("yby", any()) } returns
                listOf(
                    entity(31, "冷僻候選", "yi bu yi", "yby", HybridLexiconSource.MIXTYPE, frequency = 1.0),
                    entity(32, "要不要", "yao bu yao", "yby", HybridLexiconSource.MIXTYPE, frequency = 22_258.0),
                )
            coEvery { dao.searchPinyinPrefix("yby", any()) } returns emptyList()

            val result = repository.query("YBY")

            assertThat(result.first().phrase).isEqualTo("要不要")
        }

    @Test
    fun `exact boshiamy stays first and exact Pinyin sorts by frequency`() =
        runTest {
            stubBootstrapInstalled()
            coEvery { dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "tu", any()) } returns
                listOf(entity(1, "嘸蝦米碼字", "tu", "", HybridLexiconSource.BOSHIAMY))
            coEvery { dao.searchInitialsPrefix("tu", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("tu", any()) } returns
                listOf(
                    entity(2, "途", "tu", "t", HybridLexiconSource.PINYIN, frequency = 20.0),
                    entity(3, "圖", "tu", "t", HybridLexiconSource.PINYIN, frequency = 900.0),
                )

            val result = repository.query("tu")

            assertThat(result.map { it.phrase }).containsExactly("嘸蝦米碼字", "圖", "途").inOrder()
        }

    @Test
    fun `JT puts today first when boshiamy only has a prefix`() =
        runTest {
            stubBootstrapInstalled()
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "jt", any())
            } returns
                listOf(
                    entity(1, "較長無蝦米碼", "jta", "", HybridLexiconSource.BOSHIAMY),
                )
            coEvery { dao.searchInitialsPrefix("jt", any()) } returns
                listOf(entity(2, "今天", "jin tian", "jt", HybridLexiconSource.PINYIN))
            coEvery { dao.searchPinyinPrefix("jt", any()) } returns emptyList()

            val result = repository.query("JT")

            assertThat(result.first().phrase).isEqualTo("今天")
        }

    @Test
    fun `pinyin common words take priority when boshiamy only has partial decomposition codes`() =
        runTest {
            stubBootstrapInstalled()
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "wo", any())
            } returns
                listOf(
                    entity(1, "部分拆碼一", "wod", "", HybridLexiconSource.BOSHIAMY),
                    entity(2, "部分拆碼二", "wos", "", HybridLexiconSource.BOSHIAMY),
                )
            coEvery { dao.searchInitialsPrefix("wo", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("wo", any()) } returns
                listOf(
                    entity(3, "我", "wo", "w", HybridLexiconSource.PINYIN, usage = 100),
                    entity(4, "握", "wo", "w", HybridLexiconSource.PINYIN, usage = 50),
                )

            val result = repository.query("wo")

            assertThat(result[0].phrase).isEqualTo("我")
            assertThat(result[1].phrase).isEqualTo("握")
            val pinyinLastIndex = result.indexOfFirst { it.phrase == "握" }
            val partialFirstIndex = result.indexOfFirst { it.phrase == "部分拆碼一" }
            assertThat(partialFirstIndex).isGreaterThan(pinyinLastIndex)
        }

    @Test
    fun `one mistyped initial can compose a pinyin phrase`() =
        runTest {
            stubBootstrapInstalled()
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "jtqqhh", any())
            } returns emptyList()
            coEvery { dao.searchInitialsPrefix("jtqqhh", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("jtqqhh", any()) } returns emptyList()
            coEvery { dao.searchInitialsExact("jt", any()) } returns
                listOf(entity(11, "今天", "jin tian", "jt", HybridLexiconSource.PINYIN))
            coEvery { dao.searchInitialsExact("hh", any()) } returns
                listOf(entity(13, "很好", "hen hao", "hh", HybridLexiconSource.PINYIN))
            coEvery { dao.searchInitialsPattern("_q", 2, any()) } returns
                listOf(entity(12, "天氣", "tian qi", "tq", HybridLexiconSource.PINYIN))

            val result = repository.query("jtqqhh")

            assertThat(result.map { it.phrase }).contains("今天天氣很好")
        }

    @Test
    fun `frequently selected initial candidate moves forward`() =
        runTest {
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
    fun `pinyin initials find a phrase learned from full pinyin`() =
        runTest {
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
    fun `learning a multi syllable personal phrase creates initials and usage`() =
        runTest {
            val inserted = slot<List<HybridLexiconEntity>>()
            coEvery { dao.insertAll(capture(inserted)) } returns listOf(41L)
            coEvery { dao.recordSelection(41L, any()) } returns Unit

            val learned = repository.learnPersonalPhrase("常用文", "chang yong wen")

            assertThat(learned).isTrue()
            assertThat(inserted.captured.single().initials).isEqualTo("cyw")
            assertThat(inserted.captured.single().source).isEqualTo(HybridLexiconSource.PERSONAL.name)
            assertThat(inserted.captured.single().personalKind).isEqualTo("MANUAL")
            coVerify(exactly = 1) { dao.recordSelection(41L, any()) }
        }

    @Test
    fun `manual words accept continuous pinyin and store all initials`() =
        runTest {
            val inserted = slot<List<HybridLexiconEntity>>()
            coEvery { dao.insertAll(capture(inserted)) } returns listOf(42L)

            val added = repository.addPersonalPhrase("耀文", "yaowen")

            assertThat(added).isTrue()
            assertThat(inserted.captured.single().code).isEqualTo("yao wen")
            assertThat(inserted.captured.single().initials).isEqualTo("yw")
        }

    @Test
    fun `continuous full pinyin composes a phrase from dictionary words`() =
        runTest {
            stubBootstrapInstalled()
            val yao = entity(51, "耀", "yao", "y", HybridLexiconSource.PINYIN)
            val wen = entity(52, "文", "wen", "w", HybridLexiconSource.PINYIN)
            coEvery { dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "yaowen", any()) } returns emptyList()
            coEvery { dao.searchInitialsPrefix("yaowen", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("yaowen", any()) } returns emptyList()
            coEvery { dao.searchPinyinCodes(any(), any()) } answers {
                val keys = firstArg<List<String>>().toSet()
                listOf(yao, wen).filter { it.normalizedCode in keys }
            }

            val result = repository.query("yaowen")

            assertThat(result.map { it.phrase }).contains("耀文")
            assertThat(result.first { it.phrase == "耀文" }.initials).isEqualTo("yw")
        }

    @Test
    fun `manual phrase is immediately available from its initials`() =
        runTest {
            stubBootstrapInstalled()
            coEvery { dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "yw", any()) } returns emptyList()
            coEvery { dao.searchInitialsPrefix("yw", any()) } returns
                listOf(entity(53, "耀文", "yao wen", "yw", HybridLexiconSource.PERSONAL))
            coEvery { dao.searchPinyinPrefix("yw", any()) } returns emptyList()

            val result = repository.query("yw")

            assertThat(result.first().phrase).isEqualTo("耀文")
        }

    @Test
    fun `single character learning preferences only reorder candidates with the same reading`() =
        runTest {
            stubBootstrapInstalled()
            coEvery { dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "shi", any()) } returns emptyList()
            coEvery { dao.searchInitialsPrefix("shi", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("shi", any()) } returns
                listOf(
                    entity(1, "是", "shi", "s", HybridLexiconSource.PINYIN),
                    entity(2, "時", "shi", "s", HybridLexiconSource.PINYIN),
                    entity(3, "事", "shi", "s", HybridLexiconSource.PINYIN),
                    entity(4, "試", "shi", "s", HybridLexiconSource.PINYIN),
                )
            coEvery { dao.getLearningForCodes(listOf("shi"), "SELECTION") } returns
                listOf(
                    com.voxpen.app.data.local.HybridLearningEntity(
                        phrase = "試",
                        normalizedCode = "shi",
                        reading = "shi",
                        kind = "SELECTION",
                        selectionCount = 3,
                        lastSelectedAt = 30,
                    ),
                )

            val result = repository.query("shi")

            assertThat(result.first().phrase).isEqualTo("試")
        }

    @Test
    fun `selection increments persistent usage`() =
        runTest {
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
            coVerify(exactly = 1) {
                dao.recordLearningSelection("保固", "baogu", "bao gu", "SELECTION", any())
            }
        }

    @Test
    fun `query caches results in memory and clears cache on selection`() =
        runTest {
            stubBootstrapInstalled()
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "cachetest", any())
            } returns listOf(entity(1, "測試快取", "cachetest", "", HybridLexiconSource.BOSHIAMY))
            coEvery { dao.searchInitialsPrefix("cachetest", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("cachetest", any()) } returns emptyList()
            coEvery { dao.recordSelection(any(), any()) } returns Unit

            val result1 = repository.query("cachetest")
            val result2 = repository.query("cachetest")

            assertThat(result1.first().phrase).isEqualTo("測試快取")
            assertThat(result2.first().phrase).isEqualTo("測試快取")
            coVerify(exactly = 1) {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "cachetest", any())
            }

            repository.recordSelection(result1.first())
            repository.query("cachetest")
            coVerify(exactly = 2) {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "cachetest", any())
            }
        }

    private fun stubBootstrapInstalled() {
        coEvery { dao.insertAll(any()) } returns emptyList()
        coEvery { dao.searchInitialsExact(any(), any()) } returns emptyList()
        coEvery { dao.searchInitialsPattern(any(), any(), any()) } returns emptyList()
    }

    private fun entity(
        id: Long,
        phrase: String,
        code: String,
        initials: String,
        source: HybridLexiconSource,
        usage: Int = 0,
        frequency: Double? = null,
    ): HybridLexiconEntity =
        HybridLexiconEntity(
            id = id,
            phrase = phrase,
            code = code,
            normalizedCode = HybridLexiconImporter.normalizeCode(code),
            initials = initials,
            source = source.name,
            usageCount = usage,
            frequencyWeight = frequency,
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
