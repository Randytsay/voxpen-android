package com.voxpen.app.data.repository

import androidx.room.withTransaction
import com.voxpen.app.data.local.AppDatabase
import com.voxpen.app.data.local.HybridCandidate
import com.voxpen.app.data.local.HybridLexiconEntity
import com.voxpen.app.data.local.HybridLexiconSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

@Singleton
class HybridInputRepository
    @Inject
    constructor(
        private val database: AppDatabase,
    ) {
        private val dao = database.hybridLexiconDao()
        private val httpClient = OkHttpClient()

        suspend fun ensureBootstrapLexicon() {
            if (dao.countSource(HybridLexiconSource.PINYIN.name) > 0) return
            val entries =
                BOOTSTRAP_PINYIN.map { (phrase, code) ->
                    HybridLexiconImporter.toEntity(
                        HybridLexiconImporter.ParsedEntry(
                            phrase = phrase,
                            code = code,
                            source = HybridLexiconSource.PINYIN,
                        ),
                    )
                }
            dao.insertAll(entries)
        }

        suspend fun query(
            rawCode: String,
            limit: Int = 12,
        ): List<HybridCandidate> {
            val query = HybridLexiconImporter.normalizeCode(rawCode)
            if (query.isBlank()) return emptyList()

            ensureBootstrapLexicon()

            val boshiamy =
                dao.searchSourcePrefix(
                    source = HybridLexiconSource.BOSHIAMY.name,
                    prefix = query,
                    limit = SEARCH_POOL,
                )
            val initials =
                if (query.length >= 2) {
                    dao.searchInitialsPrefix(
                        prefix = query,
                        limit = SEARCH_POOL,
                    )
                } else {
                    emptyList()
                }
            val pinyin =
                dao.searchPinyinPrefix(
                    prefix = query,
                    limit = SEARCH_POOL,
                )

            val boshiamyRanked =
                boshiamy.sortedByDescending { rank(it, query, isBoshiamy = true) }
            val phoneticRanked =
                (initials + pinyin)
                    .distinctBy { it.id }
                    .sortedByDescending { rank(it, query, isBoshiamy = false) }

            val result = mutableListOf<HybridCandidate>()
            val seenPhrases = mutableSetOf<String>()
            (boshiamyRanked + phoneticRanked).forEach { entity ->
                if (seenPhrases.add(entity.phrase)) {
                    result += entity.toCandidate()
                }
            }
            return result.take(limit)
        }

        suspend fun recordSelection(candidate: HybridCandidate) {
            if (candidate.id <= 0) return
            dao.recordSelection(candidate.id, System.currentTimeMillis())
        }

        suspend fun addPersonalPhrase(
            phrase: String,
            pinyin: String,
        ): Boolean {
            val cleanedPhrase = phrase.trim()
            val cleanedPinyin = pinyin.trim()
            if (cleanedPhrase.isBlank() || cleanedPinyin.isBlank()) return false
            val entity =
                HybridLexiconImporter.toEntity(
                    HybridLexiconImporter.ParsedEntry(
                        phrase = cleanedPhrase,
                        code = cleanedPinyin,
                        source = HybridLexiconSource.PERSONAL,
                        baseWeight = PERSONAL_BASE_WEIGHT,
                    ),
                )
            return dao.insertAll(listOf(entity)).firstOrNull() != -1L
        }

        suspend fun importBoshiamyCin(raw: String): ImportResult {
            val parsed = HybridLexiconImporter.parseBoshiamyCin(raw)
            mergeEntries(parsed)
            return ImportResult(parsed.size, skipped = 0)
        }

        suspend fun importBaiduText(raw: String): ImportResult {
            val parsed = HybridLexiconImporter.parseBaiduText(raw)
            var imported = 0
            var skipped = 0
            val resolved = mutableListOf<HybridLexiconImporter.ParsedEntry>()
            for (entry in parsed) {
                if (entry.code.isNotBlank()) {
                    resolved += entry
                    imported++
                    continue
                }
                val derived = derivePinyin(entry.phrase)
                if (derived != null) {
                    resolved += entry.copy(code = derived)
                    imported++
                } else {
                    skipped++
                }
            }
            mergeEntries(resolved)
            return ImportResult(imported, skipped)
        }

        suspend fun installFullPinyinDictionary(): ImportResult =
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(RIME_DICTIONARY_URL).get().build()
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) {
                        "Rime dictionary download failed: HTTP ${response.code}"
                    }
                    val raw = response.body?.string()
                        ?: error("Rime dictionary download returned an empty body")
                    val parsed = HybridLexiconImporter.parseRimeDictionary(raw)
                    check(parsed.isNotEmpty()) { "Rime dictionary contains no usable entries" }
                    mergeEntries(parsed)
                    ImportResult(parsed.size, skipped = 0)
                }
            }

        suspend fun sourceCounts(): Map<HybridLexiconSource, Int> =
            HybridLexiconSource.entries.associateWith { source ->
                dao.countSource(source.name)
            }

        private suspend fun mergeEntries(entries: List<HybridLexiconImporter.ParsedEntry>) {
            val entities = entries.map(HybridLexiconImporter::toEntity)
            database.withTransaction {
                entities.chunked(1_000).forEach { chunk ->
                    dao.insertAll(chunk)
                }
            }
        }

        private suspend fun derivePinyin(phrase: String): String? {
            val parts = mutableListOf<String>()
            phrase.forEach { char ->
                if (char.isWhitespace()) return@forEach
                val entry = dao.findPinyinForPhrase(char.toString()) ?: return null
                parts += entry.code.trim().substringBefore(' ')
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        private fun rank(
            entry: HybridLexiconEntity,
            query: String,
            isBoshiamy: Boolean,
        ): Double {
            var score = if (isBoshiamy) BOSHIAMY_GROUP_SCORE else 0.0
            if (entry.normalizedCode == query) score += EXACT_CODE_SCORE
            if (entry.initials == query) score += EXACT_INITIALS_SCORE
            if (entry.initials.startsWith(query) && entry.initials != query) {
                score += INITIALS_PREFIX_SCORE
            }
            if (entry.normalizedCode.startsWith(query) && entry.normalizedCode != query) {
                score += PINYIN_PREFIX_SCORE
            }
            if (entry.source == HybridLexiconSource.PERSONAL.name) {
                score += PERSONAL_SOURCE_SCORE
            }
            score += ln(entry.usageCount + 1.0) * USAGE_MULTIPLIER
            score += recencyBonus(entry.lastUsedAt)
            score += entry.baseWeight.coerceAtMost(MAX_WEIGHT) / WEIGHT_DIVISOR
            return score
        }

        private fun recencyBonus(lastUsedAt: Long): Double {
            if (lastUsedAt <= 0) return 0.0
            val age = System.currentTimeMillis() - lastUsedAt
            return when {
                age <= 7L * DAY_MS -> 120.0
                age <= 30L * DAY_MS -> 70.0
                age <= 180L * DAY_MS -> 25.0
                else -> 0.0
            }
        }

        private fun HybridLexiconEntity.toCandidate(): HybridCandidate =
            HybridCandidate(
                id = id,
                phrase = phrase,
                code = code,
                normalizedCode = normalizedCode,
                initials = initials,
                source = runCatching { HybridLexiconSource.valueOf(source) }
                    .getOrDefault(HybridLexiconSource.PINYIN),
                usageCount = usageCount,
                lastUsedAt = lastUsedAt,
                baseWeight = baseWeight,
            )

        data class ImportResult(
            val imported: Int,
            val skipped: Int,
        )

        companion object {
            private const val SEARCH_POOL = 120
            private const val PERSONAL_BASE_WEIGHT = 100_000
            private const val BOSHIAMY_GROUP_SCORE = 10_000.0
            private const val EXACT_CODE_SCORE = 420.0
            private const val EXACT_INITIALS_SCORE = 360.0
            private const val INITIALS_PREFIX_SCORE = 240.0
            private const val PINYIN_PREFIX_SCORE = 180.0
            private const val PERSONAL_SOURCE_SCORE = 250.0
            private const val USAGE_MULTIPLIER = 95.0
            private const val MAX_WEIGHT = 1_000_000
            private const val WEIGHT_DIVISOR = 10_000.0
            private const val DAY_MS = 86_400_000L

            private const val RIME_DICTIONARY_URL =
                "https://raw.githubusercontent.com/rime/rime-luna-pinyin/" +
                    "56b934b099dfbeab842320f13aa8b461a6ab3e42/luna_pinyin.dict.yaml"

            private val BOOTSTRAP_PINYIN =
                listOf(
                    "不夠" to "bu gou",
                    "不過" to "bu guo",
                    "保固" to "bao gu",
                    "表格" to "biao ge",
                    "報告" to "bao gao",
                    "辦公" to "ban gong",
                    "可以" to "ke yi",
                    "今天" to "jin tian",
                    "明天" to "ming tian",
                    "謝謝" to "xie xie",
                    "您好" to "nin hao",
                    "收到" to "shou dao",
                    "沒問題" to "mei wen ti",
                    "不好意思" to "bu hao yi si",
                    "請問" to "qing wen",
                    "時間" to "shi jian",
                    "安排" to "an pai",
                    "確認" to "que ren",
                    "資料" to "zi liao",
                    "系統" to "xi tong",
                )
        }
    }
