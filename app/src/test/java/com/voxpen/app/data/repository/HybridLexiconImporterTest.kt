package com.voxpen.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.HybridLexiconSource
import org.junit.jupiter.api.Test

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
