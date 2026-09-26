package com.voxpen.app.data.repository

import java.text.Normalizer

/** A token decoded from continuous Pinyin input. Initial tokens represent abbreviation typing. */
data class PinyinInputToken(
    val value: String,
    val kind: Kind,
) {
    enum class Kind { SYLLABLE, INITIAL }
}

data class PinyinInputPath(val tokens: List<PinyinInputToken>) {
    val initials: String get() = tokens.joinToString("") { it.value.first().toString() }
    val fullPinyin: String?
        get() =
            tokens.takeIf { path -> path.all { it.kind == PinyinInputToken.Kind.SYLLABLE } }
                ?.joinToString(" ") { it.value }
}

/**
 * Bounded syllable and abbreviation parser inspired by MixType's PinyinTrie/FuriousTyping
 * approach. It is deliberately independent from Android, Room, and the IME UI.
 */
object PinyinInputSegmentor {
    private class Node {
        val children = mutableMapOf<Char, Node>()
        var terminal = false
    }

    private val syllableRoot = Node()

    init {
        SYLLABLES.trimIndent().split(Regex("\\s+")).forEach(::insert)
    }

    private val initials =
        setOf(
            'b', 'p', 'm', 'f', 'd', 't', 'n', 'l', 'g', 'k', 'h', 'j', 'q', 'x', 'r', 'z', 'c', 's', 'y', 'w',
        )

    /** Returns the bounded best legal readings of a continuous or explicitly separated input. */
    fun segment(
        raw: String,
        limit: Int = DEFAULT_PATH_LIMIT,
    ): List<PinyinInputPath> {
        val normalized = normalizeInput(raw)
        if (normalized.isEmpty() || normalized.length > MAX_INPUT_LENGTH) return emptyList()
        val boundaries = normalized.indices.filter { normalized[it] == '\u0000' }
        if (boundaries.isNotEmpty()) return segmentDelimited(normalized, limit)

        val paths = Array(normalized.length + 1) { mutableListOf<List<PinyinInputToken>>() }
        paths[0].add(emptyList())
        for (start in normalized.indices) {
            if (paths[start].isEmpty()) continue
            val endings = syllableEndings(normalized, start)
            val initialEnd = start + 1
            if (normalized[start] in initials) endings.add(initialEnd to PinyinInputToken.Kind.INITIAL)
            for ((end, kind) in endings) {
                val token = PinyinInputToken(normalized.substring(start, end), kind)
                paths[start].forEach { previous ->
                    val next = previous + token
                    val target = paths[end]
                    target += next
                    trim(target, limit)
                }
            }
        }
        return paths[normalized.length]
            .distinctBy { it.joinToString("|") { token -> "${token.kind}:${token.value}" } }
            .sortedWith(pathComparator)
            .take(limit)
            .map(::PinyinInputPath)
    }

    /** Tokenizes a dictionary reading whose syllable boundaries are normally explicit. */
    fun dictionarySyllables(reading: String): List<String> {
        val parts =
            reading.trim().split(Regex("[\\s'’]+|(?<=[1-5])(?=[a-zA-ZüÜ])"))
                .filter(String::isNotBlank)
                .map(::normalizeSyllable)
        if (parts.isNotEmpty() && parts.all(::isSyllable)) return parts
        val decoded = segment(reading, limit = 1).firstOrNull() ?: return emptyList()
        return decoded.tokens.takeIf { tokens -> tokens.all { it.kind == PinyinInputToken.Kind.SYLLABLE } }
            ?.map { it.value }.orEmpty()
    }

    fun isValidReading(reading: String): Boolean = dictionarySyllables(reading).isNotEmpty()

    fun normalizeInput(raw: String): String {
        val value =
            normalizePinyinLetters(raw)
                .replace(Regex("[1-5]"), "")
        if (value.isEmpty()) return ""
        val parts = value.split(Regex("[\\s'’]+")).filter(String::isNotBlank)
        if (parts.size <= 1) return value.filter { it in 'a'..'z' }
        return parts.joinToString("\u0000") { part -> part.filter { it in 'a'..'z' } }
    }

    private fun segmentDelimited(
        normalized: String,
        limit: Int,
    ): List<PinyinInputPath> {
        val result = mutableListOf<List<PinyinInputToken>>()
        var combinations = listOf(emptyList<PinyinInputToken>())
        normalized.split('\u0000').forEach { part ->
            val decoded = segment(part, limit).map { it.tokens }
            val explicitSyllable = decoded.filter { tokens ->
                tokens.size == 1 && tokens.single().kind == PinyinInputToken.Kind.SYLLABLE &&
                    tokens.single().value == part
            }
            val paths = explicitSyllable.ifEmpty { decoded }
            if (paths.isEmpty()) return emptyList()
            combinations =
                combinations.flatMap { prefix -> paths.map { prefix + it } }
                    .sortedWith(tokenPathComparator)
                    .take(limit)
        }
        result += combinations
        return result.map(::PinyinInputPath)
    }

    private fun syllableEndings(
        input: String,
        start: Int,
    ): MutableList<Pair<Int, PinyinInputToken.Kind>> {
        val result = mutableListOf<Pair<Int, PinyinInputToken.Kind>>()
        var node = syllableRoot
        var index = start
        while (index < input.length && input[index] != '\u0000') {
            node = node.children[input[index]] ?: break
            index++
            if (node.terminal) result += index to PinyinInputToken.Kind.SYLLABLE
        }
        return result
    }

    private fun isSyllable(value: String): Boolean {
        var node = syllableRoot
        value.forEach { char -> node = node.children[char] ?: return false }
        return node.terminal
    }

    private fun normalizeSyllable(value: String): String =
        normalizePinyinLetters(value).replace(Regex("[1-5]$"), "")

    private fun normalizePinyinLetters(value: String): String =
        Normalizer.normalize(
            value.trim().lowercase()
                .replace("ǖ", "v")
                .replace("ǘ", "v")
                .replace("ǚ", "v")
                .replace("ǜ", "v")
                .replace("ü", "v")
                .replace("u:", "v"),
            Normalizer.Form.NFD,
        ).replace(Regex("\\p{M}+"), "")

    private fun insert(value: String) {
        var node = syllableRoot
        value.forEach { char -> node = node.children.getOrPut(char) { Node() } }
        node.terminal = true
    }

    private fun trim(
        paths: MutableList<List<PinyinInputToken>>,
        limit: Int,
    ) {
        if (paths.size <= limit * 3) return
        val retained =
            paths.distinctBy { it.joinToString("|") { token -> "${token.kind}:${token.value}" } }
                .sortedWith(tokenPathComparator)
                .take(limit)
        paths.clear()
        paths.addAll(retained)
    }

    private val tokenPathComparator =
        compareBy<List<PinyinInputToken>> { path ->
            path.sumOf { if (it.kind == PinyinInputToken.Kind.INITIAL) INITIAL_PENALTY else 0 }
        }.thenBy { it.size }
            .thenBy { path -> path.joinToString("") { it.value } }

    private val pathComparator =
        compareBy<List<PinyinInputToken>> { path ->
            path.sumOf { if (it.kind == PinyinInputToken.Kind.INITIAL) INITIAL_PENALTY else 0 }
        }.thenBy { it.size }

    private const val DEFAULT_PATH_LIMIT = 32
    private const val MAX_INPUT_LENGTH = 48
    private const val INITIAL_PENALTY = 4

    // Standard Mandarin Pinyin syllables, including commonly accepted ü spellings as v.
    private const val SYLLABLES = """
        a ai an ang ao e eh ei en eng er o ou
        hm hng m n ng
        ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
        pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
        ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
        fa fan fang fei fen feng fo fou fu
        da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
        ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
        na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nv nue nuo
        la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu long lou lu luan lun luo lv lve
        ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
        ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo
        ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
        ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun jun
        qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
        xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
        zhi zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo
        chi cha chai chan chang chao che chen cheng chong chou chu chuai chuan chuang chui chun chuo
        shi sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo
        ri ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
        zi za zai zan zang zao ze zei zen zeng zi zong zou zu zuan zui zun zuo
        ci cai can cang cao ce cen ceng ci cong cou cu cuan cui cun cuo
        si sai san sang sao se sen seng si song sou su suan sui sun suo
        yi ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
        wa wai wan wang wei wen weng wo wu
        zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo
        """
}
