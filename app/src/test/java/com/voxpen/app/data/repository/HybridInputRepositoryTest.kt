package com.voxpen.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.AppDatabase
import com.voxpen.app.data.local.HybridContextLearningDao
import com.voxpen.app.data.local.HybridContextLearningEntity
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
    private val contextDao = mockk<HybridContextLearningDao>()
    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val repository: HybridInputRepository

    init {
        every { database.hybridLexiconDao() } returns dao
        every { database.hybridContextLearningDao() } returns contextDao
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.getInt(any(), any()) } returns MixTypeLexiconImporter.VERSION
        coEvery { dao.countSource(HybridLexiconSource.MIXTYPE.name) } returns 200_000
        coEvery { dao.searchPinyinCodes(any(), any()) } returns emptyList()
        coEvery { dao.searchInitialsKeys(any(), any()) } returns emptyList()
        coEvery { dao.searchPersonalPrefix(any(), any()) } returns emptyList()
        coEvery { dao.searchSingleSyllableCharacters(any(), any()) } returns emptyList()
        coEvery { dao.getLearningForCodes(any(), any()) } returns emptyList()
        coEvery { dao.recordLearningSelection(any(), any(), any(), any(), any()) } returns Unit
        coEvery { dao.findLearning(any(), any(), any()) } returns null
        coEvery { dao.deleteAutomaticPersonalPhrases() } returns 0
        coEvery { contextDao.findTransitions(any(), any(), any()) } returns emptyList()
        coEvery { contextDao.findPersonalPhraseContinuations(any(), any(), any()) } returns emptyList()
        coEvery { contextDao.clearTransitions() } returns Unit
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
    fun `tone page returns only exact-reading single characters matching selected tone`() =
        runTest {
            stubBootstrapInstalled()
            coEvery { dao.searchSingleSyllableCharacters("yao", 4) } returns
                listOf(
                    entity(71, "要", "yao", "y", HybridLexiconSource.MIXTYPE, frequency = 22_000.0, toneCode = "4"),
                    entity(72, "耀", "yao", "y", HybridLexiconSource.MIXTYPE, frequency = 1_000.0, toneCode = "4"),
                    entity(73, "遙", "yao", "y", HybridLexiconSource.MIXTYPE, frequency = 5_000.0, toneCode = "2"),
                    entity(
                        74,
                        "要是",
                        "yao shi",
                        "ys",
                        HybridLexiconSource.MIXTYPE,
                        frequency = 30_000.0,
                        toneCode = "44",
                    ),
                )

            val result = repository.querySingleSyllableCharacters("yao", toneFilter = 4)

            assertThat(result.map { it.phrase }).containsExactly("要", "耀").inOrder()
            coVerify(exactly = 1) { dao.searchSingleSyllableCharacters("yao", 4) }
        }

    @Test
    fun `all tones page lists exact syllable characters but excludes phrase completions`() =
        runTest {
            stubBootstrapInstalled()
            coEvery { dao.searchSingleSyllableCharacters("yao", 0) } returns
                listOf(
                    entity(81, "要", "yao", "y", HybridLexiconSource.MIXTYPE, frequency = 22_000.0, toneCode = "4"),
                    entity(82, "妖", "yao", "y", HybridLexiconSource.MIXTYPE, frequency = 2_000.0, toneCode = "1"),
                    entity(
                        83,
                        "要點",
                        "yao dian",
                        "yd",
                        HybridLexiconSource.MIXTYPE,
                        frequency = 30_000.0,
                        toneCode = "43",
                    ),
                )

            val result = repository.querySingleSyllableCharacters("yao")

            assertThat(result.map { it.phrase }).containsExactly("要", "妖").inOrder()
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
    fun `pinyin decoding does not concatenate unrelated dictionary chunks into a sentence`() =
        runTest {
            stubBootstrapInstalled()
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "jtqqhh", any())
            } returns emptyList()
            coEvery { dao.searchInitialsPrefix("jtqqhh", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("jtqqhh", any()) } returns emptyList()
            coEvery { dao.searchInitialsKeys(any(), any()) } returns
                listOf(
                    entity(11, "今天", "jin tian", "jt", HybridLexiconSource.PINYIN),
                    entity(12, "天氣", "tian qi", "tq", HybridLexiconSource.PINYIN),
                    entity(13, "很好", "hen hao", "hh", HybridLexiconSource.PINYIN),
                )

            val result = repository.query("jtqqhh")

            assertThat(result).isEmpty()
        }

    @Test
    fun `BHAT does not combine individual character readings into an invented phrase`() =
        runTest {
            stubBootstrapInstalled()
            coEvery { dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "bhat", any()) } returns emptyList()
            coEvery { dao.searchInitialsPrefix("bhat", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("bhat", any()) } returns emptyList()
            coEvery { dao.searchInitialsKeys(any(), any()) } returns
                listOf(
                    entity(21, "不", "bu", "b", HybridLexiconSource.MIXTYPE, frequency = 12_000.0),
                    entity(22, "會", "hui", "h", HybridLexiconSource.MIXTYPE, frequency = 18_000.0),
                    entity(23, "啊", "a", "a", HybridLexiconSource.MIXTYPE, frequency = 9_000.0),
                    entity(24, "他", "ta", "t", HybridLexiconSource.MIXTYPE, frequency = 20_000.0),
                )

            val result = repository.query("BHAT")

            assertThat(result).isEmpty()
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
    fun `manual words accept continuous shanling and store the SL initials`() =
        runTest {
            val inserted = slot<List<HybridLexiconEntity>>()
            coEvery { dao.insertAll(capture(inserted)) } returns listOf(43L)

            val added = repository.addPersonalPhrase("姍靈", "shanling")

            assertThat(added).isTrue()
            assertThat(inserted.captured.single().code).isEqualTo("shan ling")
            assertThat(inserted.captured.single().normalizedCode).isEqualTo("shanling")
            assertThat(inserted.captured.single().initials).isEqualTo("sl")
        }

    @Test
    fun `personal text import skips header and stores rows as manageable manual words`() =
        runTest {
            val inserted = slot<List<HybridLexiconEntity>>()
            coEvery { dao.insertAll(capture(inserted)) } returns listOf(44L)

            val result = repository.importPersonalText("詞語,拼音\n姍靈,shan ling\n")

            assertThat(result.imported).isEqualTo(1)
            assertThat(result.skipped).isEqualTo(0)
            assertThat(inserted.captured.single().phrase).isEqualTo("姍靈")
            assertThat(inserted.captured.single().code).isEqualTo("shan ling")
            assertThat(inserted.captured.single().initials).isEqualTo("sl")
            assertThat(inserted.captured.single().source).isEqualTo(HybridLexiconSource.PERSONAL.name)
            assertThat(inserted.captured.single().personalKind).isEqualTo("MANUAL")
        }

    @Test
    fun `personal dictionary prefix lookup finds full pinyin and initials outside the common pool`() =
        runTest {
            stubBootstrapInstalled()
            val personalPhrase = entity(70, "姍靈", "shan ling", "sl", HybridLexiconSource.PERSONAL)
            val commonCandidates =
                (1L..120L).map { index ->
                    entity(
                        id = 1_000 + index,
                        phrase = "一般詞$index",
                        code = "shan ling",
                        initials = "sl",
                        source = HybridLexiconSource.MIXTYPE,
                    )
                }
            coEvery { dao.searchSourcePrefix(any(), any(), any()) } returns emptyList()
            coEvery { dao.searchInitialsPrefix(any(), any()) } answers {
                if (firstArg<String>() == "sl") commonCandidates else emptyList()
            }
            coEvery { dao.searchPinyinPrefix(any(), any()) } answers {
                if (firstArg<String>() == "shanling") commonCandidates else emptyList()
            }
            coEvery { dao.searchPersonalPrefix(any(), any()) } answers {
                val prefix = firstArg<String>()
                listOf(personalPhrase).filter {
                    it.normalizedCode.startsWith(prefix) || it.initials.startsWith(prefix)
                }
            }

            val fullPinyinResult = repository.query("shanling")
            val initialsResult = repository.query("SL")

            assertThat(fullPinyinResult.first().phrase).isEqualTo("姍靈")
            assertThat(fullPinyinResult).hasSize(12)
            assertThat(initialsResult.first().phrase).isEqualTo("姍靈")
            assertThat(initialsResult).hasSize(12)
            coVerify(exactly = 1) { dao.searchPersonalPrefix("shanling", any()) }
            coVerify(exactly = 1) { dao.searchPersonalPrefix("sl", any()) }
        }

    @Test
    fun `continuous full pinyin returns a phrase only when that complete phrase exists`() =
        runTest {
            stubBootstrapInstalled()
            val yao = entity(51, "耀", "yao", "y", HybridLexiconSource.PINYIN)
            val wen = entity(52, "文", "wen", "w", HybridLexiconSource.PINYIN)
            val learnedPhrase = entity(53, "耀文", "yao wen", "yw", HybridLexiconSource.PERSONAL)
            coEvery { dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "yaowen", any()) } returns emptyList()
            coEvery { dao.searchInitialsPrefix("yaowen", any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix("yaowen", any()) } returns emptyList()
            coEvery { dao.searchPinyinCodes(any(), any()) } answers {
                val keys = firstArg<List<String>>().toSet()
                listOf(yao, wen, learnedPhrase).filter { it.normalizedCode in keys }
            }

            val result = repository.query("yaowen")

            assertThat(result.map { it.phrase }).containsExactly("耀文")
            assertThat(result.first().id).isEqualTo(learnedPhrase.id)
        }

    @Test
    fun `mixed initial and full syllable returns a dictionary phrase but never assembles its characters`() =
        runTest {
            stubBootstrapInstalled()
            val yao = entity(61, "耀", "yao", "y", HybridLexiconSource.MIXTYPE)
            val wen = entity(62, "文", "wen", "w", HybridLexiconSource.MIXTYPE)
            val learnedPhrase = entity(63, "耀文", "yao wen", "yw", HybridLexiconSource.PERSONAL)
            val ambiguousInitialsPhrase =
                entity(64, "四字冷僻詞", "yi wang er nian", "ywen", HybridLexiconSource.MIXTYPE, frequency = 1.0)
            coEvery { dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, "ywen", any()) } returns emptyList()
            coEvery { dao.searchInitialsPrefix("ywen", any()) } returns listOf(ambiguousInitialsPhrase)
            coEvery { dao.searchPinyinPrefix("ywen", any()) } returns emptyList()
            coEvery { dao.searchInitialsKeys(any(), any()) } answers {
                val keys = firstArg<List<String>>().toSet()
                listOf(yao, wen, learnedPhrase).filter { it.initials in keys }
            }

            val result = repository.query("ywen")

            assertThat(result.first().phrase).isEqualTo("耀文")
            assertThat(result.map { it.phrase }).contains("四字冷僻詞")
            assertThat(result.first().id).isEqualTo(learnedPhrase.id)
        }

    @Test
    fun `duos and dsao both find 多少`() =
        runTest {
            stubBootstrapInstalled()
            val duoshao =
                entity(
                    54,
                    "多少",
                    "duo shao",
                    "ds",
                    HybridLexiconSource.MIXTYPE,
                    frequency = 22_000.0,
                )
            coEvery {
                dao.searchSourcePrefix(HybridLexiconSource.BOSHIAMY.name, any(), any())
            } returns emptyList()
            coEvery { dao.searchInitialsPrefix(any(), any()) } returns emptyList()
            coEvery { dao.searchPinyinPrefix(any(), any()) } returns emptyList()
            coEvery { dao.searchInitialsKeys(any(), any()) } answers {
                val keys = firstArg<List<String>>().toSet()
                listOf(duoshao).filter { it.initials in keys }
            }

            assertThat(repository.query("duos").map { it.phrase }).contains("多少")
            assertThat(repository.query("dsao").map { it.phrase }).contains("多少")
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
                        selectionCount = 2,
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

    @Test
    fun `personal phrase pinyin is suggested from installed character readings`() =
        runTest {
            coEvery { dao.findPinyinForPhrase("姍") } returns
                entity(1, "姍", "shan", "s", HybridLexiconSource.MIXTYPE)
            coEvery { dao.findPinyinForPhrase("靈") } returns
                entity(2, "靈", "ling", "l", HybridLexiconSource.MIXTYPE)

            assertThat(repository.suggestPinyin("姍靈")).isEqualTo("shan ling")
        }

    @Test
    fun `personal phrase pinyin remains empty when a character has no reading`() =
        runTest {
            coEvery { dao.findPinyinForPhrase("姍") } returns null

            assertThat(repository.suggestPinyin("姍靈")).isNull()
        }

    @Test
    fun `context suggestions continue a four character personal phrase`() =
        runTest {
            coEvery { contextDao.findTransitions(listOf("台"), 2, any()) } returns
                listOf(HybridContextLearningEntity("台", "達", 4, 100L))
            coEvery { contextDao.findPersonalPhraseContinuations("台", any(), any()) } returns
                listOf(
                    entity(3, "台達能源", "tai da neng yuan", "tdny", HybridLexiconSource.PERSONAL)
                        .copy(personalKind = "AUTO_PROMOTED"),
                )
            coEvery { contextDao.findPersonalPhraseContinuations("台達", any(), any()) } returns
                listOf(
                    entity(3, "台達能源", "tai da neng yuan", "tdny", HybridLexiconSource.PERSONAL)
                        .copy(personalKind = "AUTO_PROMOTED"),
                )

            assertThat(repository.queryContextSuggestions("台")).containsAtLeast("達", "達能源")
            assertThat(repository.queryContextSuggestions("台").first()).isEqualTo("達")
            assertThat(repository.queryContextSuggestions("台達")).containsAtLeast("能", "能源")
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
        toneCode: String = "",
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
            toneCode = toneCode,
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
            toneCode = toneCode,
        )
}
