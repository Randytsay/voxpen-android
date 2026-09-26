package com.voxpen.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.HybridLexiconSource
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class HybridLexiconImporterTest {
    @Test
    fun `rime phrase creates full pinyin and initials`() {
        val raw = "---\nname: demo\n...\n常用文\tchang yong wen\t1200\n"
        val parsed = HybridLexiconImporter.parseRimeDictionary(raw)

        val entity = HybridLexiconImporter.toEntity(parsed.single())

        assertThat(entity.normalizedCode).isEqualTo("changyongwen")
        assertThat(entity.initials).isEqualTo("cyw")
        assertThat(entity.baseWeight).isEqualTo(1200)
    }

    @Test
    fun `rime preset vocabulary supplies weights when dictionary rows omit them`() {
        val dictionary = "---\nuse_preset_vocabulary: true\n...\n你好\tni hao\n少見詞\tshao jian ci\n"
        val preset = "你好\t937\n"

        val parsed = HybridLexiconImporter.parseRimeDictionary(dictionary, preset)

        assertThat(parsed.map { it.frequencyWeight }).containsExactly(937.0, 0.0).inOrder()
    }

    @Test
    fun `rime percentage weights override essay frequency`() {
        val dictionary = "---\nuse_preset_vocabulary: true\n...\n凸\ttu\t99%\n"

        val entry = HybridLexiconImporter.parseRimeDictionary(dictionary, "凸\t1284\n").single()

        assertThat(entry.frequencyWeight).isEqualTo(99.0)
        assertThat(entry.baseWeight).isEqualTo(99)
    }

    @Test
    fun `continuous pinyin gets syllable initials and tones normalize consistently`() {
        val entity =
            HybridLexiconImporter.toEntity(
                HybridLexiconImporter.ParsedEntry(
                    phrase = "耀文",
                    code = "yaowen",
                    source = HybridLexiconSource.PINYIN,
                ),
            )

        assertThat(entity.initials).isEqualTo("yw")
        assertThat(HybridLexiconImporter.normalizeCode("nǐ hǎo")).isEqualTo("nihao")
    }

    @Test
    fun `today has the expected pinyin initials`() {
        val entity =
            HybridLexiconImporter.toEntity(
                HybridLexiconImporter.ParsedEntry(
                    phrase = "今天",
                    code = "jin tian",
                    source = HybridLexiconSource.PINYIN,
                ),
            )

        assertThat(entity.initials).isEqualTo("jt")
        assertThat(entity.normalizedCode).isEqualTo("jintian")
    }

    @Test
    fun `mix type traditional phrase preserves its reading and source frequency`() {
        val parsed =
            MixTypeLexiconImporter.parseLine(
                "phrases-tabe-cht.txt",
                "要不要 22258 ㄧㄠˋ ㄅㄨˊ ㄧㄠˋ",
            )

        assertThat(parsed).isNotNull()
        val entity = HybridLexiconImporter.toEntity(checkNotNull(parsed))
        assertThat(entity.phrase).isEqualTo("要不要")
        assertThat(entity.code).isEqualTo("yao bu yao")
        assertThat(entity.normalizedCode).isEqualTo("yaobuyao")
        assertThat(entity.initials).isEqualTo("yby")
        assertThat(entity.frequencyWeight).isEqualTo(22258.0)
        assertThat(entity.source).isEqualTo(HybridLexiconSource.MIXTYPE.name)
    }

    @Test
    fun `mix type converter handles common and contracted pinyin syllables`() {
        assertThat(MixTypeLexiconImporter.zhuyinToPinyin("ㄋㄧˇ ㄏㄠˇ")).isEqualTo("ni hao")
        assertThat(MixTypeLexiconImporter.zhuyinToPinyin("ㄓㄨㄥ ㄍㄨㄛˊ")).isEqualTo("zhong guo")
        assertThat(MixTypeLexiconImporter.zhuyinToPinyin("ㄌㄩˋ ㄧㄡˊ")).isEqualTo("lv you")
        assertThat(MixTypeLexiconImporter.zhuyinToPinyin("ㄐㄩㄥˇ")).isEqualTo("jiong")
    }

    @Test
    fun `mix type core character rows use traditional frequency`() {
        val parsed = MixTypeLexiconImporter.parseLine("char-kanji-core.txt", "要\t20\t500\tㄧㄠˋ")

        assertThat(parsed?.phrase).isEqualTo("要")
        assertThat(parsed?.code).isEqualTo("yao")
        assertThat(parsed?.frequencyWeight).isEqualTo(500.0)
    }

    @Test
    fun `bundled MixType corpus imports at scale and ranks common YBY phrase first`() {
        val archiveFile =
            listOf(
                File("src/main/assets/${MixTypeLexiconImporter.ASSET_NAME}"),
                File("app/src/main/assets/${MixTypeLexiconImporter.ASSET_NAME}"),
            ).firstOrNull(File::isFile)
        assertThat(archiveFile).isNotNull()

        var parsedCount = 0
        val uniqueRows = mutableSetOf<Pair<String, String>>()
        val ybyCandidates = mutableMapOf<String, Double>()
        ZipInputStream(checkNotNull(archiveFile).inputStream()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && MixTypeLexiconImporter.isSupportedFile(entry.name.substringAfterLast('/'))) {
                    val reader = BufferedReader(InputStreamReader(archive, Charsets.UTF_8))
                    while (true) {
                        val line = reader.readLine() ?: break
                        val filename = entry.name.substringAfterLast('/')
                        val parsed = MixTypeLexiconImporter.parseLine(filename, line) ?: continue
                        parsedCount++
                        val entity = HybridLexiconImporter.toEntity(parsed)
                        uniqueRows += entity.phrase to entity.normalizedCode
                        if (entity.initials == "yby" && entity.normalizedCode == "yaobuyao") {
                            ybyCandidates.putIfAbsent(entity.phrase, entity.frequencyWeight ?: 0.0)
                        }
                    }
                }
                archive.closeEntry()
            }
        }

        assertThat(parsedCount).isAtLeast(231_000)
        assertThat(uniqueRows.size).isAtLeast(200_000)
        assertThat(ybyCandidates).containsKey("要不要")
        assertThat(ybyCandidates.entries.maxByOrNull { it.value }?.key).isEqualTo("要不要")
    }

    @Test
    fun `boshiamy cin parser reads chardef section only`() {
        val parsed =
            HybridLexiconImporter.parseBoshiamyCin(
                """
                %gen_inp
                ignored value
                %chardef begin
                bg 保固
                xyz 測試
                %chardef end
                tail ignored
                """.trimIndent(),
            )

        assertThat(parsed.map { it.phrase }).containsExactly("保固", "測試").inOrder()
        assertThat(parsed.first().source).isEqualTo(HybridLexiconSource.BOSHIAMY)
    }

    @Test
    fun `baidu text parser accepts phrase and pinyin columns`() {
        val raw = "保固\tbao gu\t10\n表格,biao ge,5\n"
        val parsed = HybridLexiconImporter.parseBaiduText(raw)

        assertThat(parsed).hasSize(2)
        assertThat(parsed[0].phrase).isEqualTo("保固")
        assertThat(parsed[0].code).isEqualTo("bao gu")
        assertThat(parsed[1].phrase).isEqualTo("表格")
        assertThat(parsed[1].code).isEqualTo("biao ge")
    }
}
