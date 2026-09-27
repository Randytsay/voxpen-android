package com.voxpen.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.voxpen.app.data.local.AppDatabase
import com.voxpen.app.data.local.HybridCandidate
import com.voxpen.app.data.local.HybridLearningKind
import com.voxpen.app.data.local.HybridLexiconEntity
import com.voxpen.app.data.local.HybridLexiconSource
import com.voxpen.app.data.local.PersonalLexiconKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

@Singleton
class HybridInputRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        @ApplicationContext private val context: Context,
    ) {
        private val dao = database.hybridLexiconDao()
        private val contextDao by lazy { database.hybridContextLearningDao() }
        private val httpClient =
            OkHttpClient.Builder()
                .callTimeout(90, TimeUnit.SECONDS)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        private val bootstrapMutex = Mutex()
        private val queryCache = mutableMapOf<String, List<HybridCandidate>>()
        private val queryCacheMutex = Mutex()

        @Volatile
        private var bootstrapSeeded = false

        @Volatile
        private var bootstrapInProgress = false

        fun isBootstrapInProgress(): Boolean = bootstrapInProgress

        suspend fun ensureBootstrapLexicon() {
            if (bootstrapSeeded) return
            bootstrapMutex.withLock {
                if (bootstrapSeeded) return@withLock
                withContext(Dispatchers.IO + NonCancellable) {
                    bootstrapInProgress = true
                    val preferences = lexiconPreferences()
                    try {
                        val installedVersion = preferences.getInt(MIXTYPE_LEXICON_VERSION_KEY, 0)
                        val installedRows = dao.countSource(HybridLexiconSource.MIXTYPE.name)
                        if (installedVersion == MixTypeLexiconImporter.VERSION &&
                            installedRows >= MIN_EXPECTED_MIXTYPE_ROWS
                        ) {
                            bootstrapSeeded = true
                            return@withContext
                        }

                        var resumeAfter = preferences.getInt(MIXTYPE_IMPORT_PROGRESS_KEY, 0)
                        val preparedRevision = preferences.getInt(MIXTYPE_PREPARED_REVISION_KEY, 0)
                        if (preparedRevision != MixTypeLexiconImporter.VERSION) {
                            database.withTransaction {
                                // Replace only the old generic Pinyin baseline; retain user data and Boshiamy.
                                dao.deleteBySource(HybridLexiconSource.MIXTYPE.name)
                                dao.deleteBySource(HybridLexiconSource.PINYIN.name)
                            }
                            preferences.edit()
                                .putInt(MIXTYPE_PREPARED_REVISION_KEY, MixTypeLexiconImporter.VERSION)
                                .putInt(MIXTYPE_IMPORT_PROGRESS_KEY, 0)
                                .commit()
                            resumeAfter = 0
                            invalidateQueryCache()
                        }

                        val parsedRows = importBundledMixTypeLexicon(resumeAfter, preferences)
                        val installedRowsAfterImport = dao.countSource(HybridLexiconSource.MIXTYPE.name)
                        check(
                            parsedRows >= MIN_EXPECTED_MIXTYPE_ROWS &&
                                installedRowsAfterImport >= MIN_EXPECTED_MIXTYPE_ROWS,
                        ) {
                            "MixType dictionary import was incomplete: " +
                                "parsed=$parsedRows installed=$installedRowsAfterImport"
                        }
                        check(
                            preferences.edit()
                                .putInt(MIXTYPE_LEXICON_VERSION_KEY, MixTypeLexiconImporter.VERSION)
                                .remove(MIXTYPE_PREPARED_REVISION_KEY)
                                .remove(MIXTYPE_IMPORT_PROGRESS_KEY)
                                .commit(),
                        ) { "Unable to save MixType dictionary revision" }
                        invalidateQueryCache()
                        bootstrapSeeded = true
                    } finally {
                        bootstrapInProgress = false
                    }
                }
            }
        }

        private fun lexiconPreferences(): SharedPreferences =
            context.getSharedPreferences(MIXTYPE_LEXICON_PREFERENCES, Context.MODE_PRIVATE)

        private suspend fun importBundledMixTypeLexicon(
            resumeAfter: Int,
            preferences: SharedPreferences,
        ): Int {
            var parsedCount = 0
            var lastCheckpoint = resumeAfter
            val batch = ArrayList<HybridLexiconEntity>(LEXICON_INSERT_BATCH_SIZE)

            suspend fun flushBatch() {
                if (batch.isEmpty()) return
                database.withTransaction { dao.insertAll(batch.toList()) }
                batch.clear()
                if (parsedCount - lastCheckpoint >= IMPORT_PROGRESS_CHECKPOINT_INTERVAL) {
                    check(
                        preferences.edit()
                            .putInt(MIXTYPE_IMPORT_PROGRESS_KEY, parsedCount)
                            .commit(),
                    ) { "Unable to checkpoint MixType dictionary import" }
                    lastCheckpoint = parsedCount
                }
                invalidateQueryCache()
            }

            ZipInputStream(context.assets.open(MixTypeLexiconImporter.ASSET_NAME)).use { archive ->
                while (true) {
                    val archiveEntry = archive.nextEntry ?: break
                    if (!archiveEntry.isDirectory) {
                        val filename = archiveEntry.name.substringAfterLast('/')
                        if (MixTypeLexiconImporter.isSupportedFile(filename)) {
                            val reader = BufferedReader(InputStreamReader(archive, Charsets.UTF_8))
                            while (true) {
                                val line = reader.readLine() ?: break
                                val entry = MixTypeLexiconImporter.parseLine(filename, line) ?: continue
                                parsedCount++
                                if (parsedCount > resumeAfter) {
                                    batch += HybridLexiconImporter.toEntity(entry)
                                    if (batch.size >= LEXICON_INSERT_BATCH_SIZE) {
                                        flushBatch()
                                    }
                                }
                            }
                        }
                    }
                    archive.closeEntry()
                }
            }
            flushBatch()
            check(
                preferences.edit()
                    .putInt(MIXTYPE_IMPORT_PROGRESS_KEY, parsedCount)
                    .commit(),
            ) { "Unable to save MixType dictionary import progress" }
            return parsedCount
        }

        suspend fun query(
            rawCode: String,
            limit: Int = 12,
            toneFilter: Int? = null,
        ): List<HybridCandidate> {
            if (toneFilter != null) return querySingleSyllableCharacters(rawCode, limit, toneFilter)

            val query = HybridLexiconImporter.normalizeCode(rawCode)
            if (query.isBlank()) return emptyList()

            val cacheKey = "${PinyinInputSegmentor.normalizeInput(rawCode)}#$limit#$toneFilter"
            queryCacheMutex.withLock {
                queryCache[cacheKey]?.let { return it }
            }

            val searchLimit = maxOf(SEARCH_POOL, limit).coerceAtMost(MAX_QUERY_POOL)
            val (boshiamy, phoneticResults) =
                coroutineScope {
                    val boshiamyTask =
                        async {
                            dao.searchSourcePrefix(
                                source = HybridLexiconSource.BOSHIAMY.name,
                                prefix = query,
                                limit = searchLimit,
                            )
                        }
                    val initialsTask =
                        async {
                            dao.searchInitialsPrefix(
                                prefix = query,
                                limit = searchLimit,
                            )
                        }
                    val pinyinTask =
                        async {
                            dao.searchPinyinPrefix(
                                prefix = query,
                                limit = searchLimit,
                            )
                        }
                    val personalTask =
                        async {
                            dao.searchPersonalPrefix(
                                prefix = query,
                                limit = PERSONAL_SEARCH_POOL,
                            )
                        }
                    boshiamyTask.await() to
                        Triple(
                            initialsTask.await(),
                            pinyinTask.await(),
                            personalTask.await(),
                        )
                }
            val (initials, pinyin, personal) = phoneticResults

            val matchingPhoneticRows =
                (initials + pinyin + personal)
                    .distinctBy { it.id }
                    .filter { PinyinInputSegmentor.isValidReading(it.code) }
            val phoneticRows = matchingPhoneticRows

            val exactFullPinyin = phoneticRows.filter { it.normalizedCode == query }
            val exactInitials = phoneticRows.filter { it.initials == query && it !in exactFullPinyin }
            // A four-letter initials hit must not suppress a more specific mixed parse such as
            // Y+WEN. Exact full-Pinyin hits remain authoritative.
            val composedPinyin =
                composePinyinCandidates(
                    rawCode = rawCode,
                    hasExactFullPinyinMatch = exactFullPinyin.isNotEmpty(),
                    hasExactInitialsMatch = exactInitials.isNotEmpty(),
                )

            val exactBoshiamy = boshiamy.filter { it.normalizedCode == query }
            val partialBoshiamy = boshiamy.filter { it.normalizedCode != query }
            val fullPinyinPrefix =
                phoneticRows.filter {
                    it.normalizedCode.startsWith(query) && it.normalizedCode != query
                }
            val initialsPrefix =
                phoneticRows.filter {
                    it.initials.startsWith(query) && it.initials != query && it !in fullPinyinPrefix
                }
            val rankedCandidates =
                buildList {
                    addAll(exactBoshiamy.sortedByDescending { rank(it, query, isBoshiamy = true) })
                    addAll(
                        exactFullPinyin
                            .filter { it.source == HybridLexiconSource.PERSONAL.name }
                            .sortedWith(phoneticOrder),
                    )
                    addAll(
                        exactFullPinyin
                            .filter { it.source != HybridLexiconSource.PERSONAL.name }
                            .sortedWith(phoneticOrder),
                    )
                    addAll(composedPinyin.map { it.toEntityForRanking() })
                    addAll(
                        exactInitials
                            .filter { it.source == HybridLexiconSource.PERSONAL.name }
                            .sortedWith(phoneticOrder),
                    )
                    addAll(
                        exactInitials
                            .filter { it.source != HybridLexiconSource.PERSONAL.name }
                            .sortedWith(phoneticOrder),
                    )
                    addAll(
                        fullPinyinPrefix
                            .filter { it.source == HybridLexiconSource.PERSONAL.name }
                            .sortedWith(phoneticOrder),
                    )
                    addAll(
                        fullPinyinPrefix
                            .filter { it.source != HybridLexiconSource.PERSONAL.name }
                            .sortedWith(phoneticOrder),
                    )
                    addAll(
                        initialsPrefix
                            .filter { it.source == HybridLexiconSource.PERSONAL.name }
                            .sortedWith(phoneticOrder),
                    )
                    addAll(
                        initialsPrefix
                            .filter { it.source != HybridLexiconSource.PERSONAL.name }
                            .sortedWith(phoneticOrder),
                    )
                    addAll(partialBoshiamy.sortedByDescending { rank(it, query, isBoshiamy = true) })
                }

            val result = mutableListOf<HybridCandidate>()
            val seenPhrases = mutableSetOf<String>()
            rankedCandidates.forEach { entity ->
                if (seenPhrases.add(entity.phrase)) {
                    result += entity.toCandidate()
                }
            }
            val candidates = applySingleCharacterPreferences(result).take(limit)
            queryCacheMutex.withLock {
                if (bootstrapInProgress) {
                    queryCache.clear()
                } else if (queryCache.size >= MAX_QUERY_CACHE_SIZE) {
                    val firstKey = queryCache.keys.firstOrNull()
                    if (firstKey != null) queryCache.remove(firstKey)
                }
                if (!bootstrapInProgress) queryCache[cacheKey] = candidates
            }
            return candidates
        }

        /** Returns only single characters whose complete reading matches the entered syllable. */
        suspend fun querySingleSyllableCharacters(
            rawCode: String,
            limit: Int = 24,
            toneFilter: Int? = null,
        ): List<HybridCandidate> {
            val query = HybridLexiconImporter.normalizeCode(rawCode)
            if (query.isBlank() || toneFilter != null && toneFilter !in 1..5) return emptyList()
            val hasExactSyllable =
                PinyinInputSegmentor.segment(rawCode, limit = 8).any { path ->
                    path.tokens.singleOrNull()?.let { token ->
                        token.kind == PinyinInputToken.Kind.SYLLABLE && token.value == query
                    } == true
                }
            if (!hasExactSyllable) return emptyList()

            val cacheKey = "homophones:$query#$limit#${toneFilter ?: 0}"
            queryCacheMutex.withLock {
                queryCache[cacheKey]?.let { return it }
            }

            val toneKey = toneFilter ?: 0
            val rows = dao.searchSingleSyllableCharacters(query, toneKey)
            val candidates =
                rows.asSequence()
                    .filter { it.normalizedCode == query }
                    .filter { it.phrase.codePointCount(0, it.phrase.length) == 1 }
                    .filter { toneFilter == null || it.toneCode.firstOrNull()?.digitToIntOrNull() == toneFilter }
                    .distinctBy { it.phrase }
                    .map { it.toCandidate() }
                    .toList()
            val result = applySingleCharacterPreferences(candidates).take(limit)
            queryCacheMutex.withLock {
                if (!bootstrapInProgress) queryCache[cacheKey] = result
            }
            return result
        }

        private suspend fun composePinyinCandidates(
            rawCode: String,
            hasExactFullPinyinMatch: Boolean,
            hasExactInitialsMatch: Boolean,
        ): List<HybridCandidate> {
            if (hasExactFullPinyinMatch) return emptyList()
            if (hasExactInitialsMatch && !hasMixedInputPath(rawCode)) return emptyList()
            return findWholeDictionaryMatches(rawCode)
        }

        private fun hasMixedInputPath(rawCode: String): Boolean =
            PinyinInputSegmentor.segment(rawCode, limit = PINYIN_PATH_LIMIT).any { path ->
                isSupportedMixedInputPath(path) &&
                    path.tokens.any { it.kind == PinyinInputToken.Kind.INITIAL } &&
                    path.tokens.any { it.kind == PinyinInputToken.Kind.SYLLABLE }
            }

        /**
         * Resolves mixed/full Pinyin only to a complete existing dictionary entry. It must not
         * invent a phrase by concatenating unrelated single-character or word entries.
         */
        private suspend fun findWholeDictionaryMatches(rawCode: String): List<HybridCandidate> {
            val paths =
                PinyinInputSegmentor.segment(rawCode, limit = PINYIN_PATH_LIMIT)
                    .filter(::isSupportedMixedInputPath)
            if (paths.isEmpty()) return emptyList()

            val codeKeys = linkedSetOf<String>()
            val initialKeys = linkedSetOf<String>()
            paths.forEach { path ->
                if (path.tokens.all { it.kind == PinyinInputToken.Kind.SYLLABLE }) {
                    codeKeys += path.tokens.joinToString("") { it.value }
                }
                initialKeys += path.initials
            }
            if (codeKeys.isEmpty() && initialKeys.isEmpty()) return emptyList()

            val rows = linkedMapOf<Long, HybridLexiconEntity>()
            codeKeys.toList().chunked(LOOKUP_KEY_CHUNK_SIZE).forEach { chunk ->
                dao.searchPinyinCodes(chunk, SEARCH_POOL).forEach { rows[it.id] = it }
            }
            initialKeys.toList().chunked(LOOKUP_KEY_CHUNK_SIZE).forEach { chunk ->
                dao.searchInitialsKeys(chunk, SEARCH_POOL).forEach { rows[it.id] = it }
            }
            val matches = linkedMapOf<Long, DictionaryInputMatch>()
            paths.forEach { inputPath ->
                rows.values.forEach { entity ->
                    val reading = PinyinInputSegmentor.dictionarySyllables(entity.code)
                    if (reading.size != inputPath.tokens.size) return@forEach
                    var exactSyllables = 0
                    val matchesPath =
                        inputPath.tokens.indices.all { index ->
                            val token = inputPath.tokens[index]
                            if (token.kind == PinyinInputToken.Kind.SYLLABLE) {
                                if (token.value == reading[index]) {
                                    exactSyllables++
                                    true
                                } else {
                                    isRetroflexHOptional(token.value, reading[index])
                                }
                            } else {
                                token.value == reading[index].firstOrNull()?.toString()
                            }
                        }
                    if (!matchesPath) return@forEach
                    val previous = matches[entity.id]
                    if (previous == null || exactSyllables > previous.exactSyllables) {
                        matches[entity.id] = DictionaryInputMatch(entity, exactSyllables)
                    }
                }
            }

            return matches.values
                .sortedWith(
                    Comparator { first, second ->
                        val exactMatchOrder = second.exactSyllables.compareTo(first.exactSyllables)
                        if (exactMatchOrder != 0) {
                            exactMatchOrder
                        } else {
                            phoneticOrder.compare(first.entity, second.entity)
                        }
                    },
                )
                .distinctBy { it.entity.phrase }
                .take(COMPOSED_CANDIDATE_LIMIT)
                .map { it.entity.toCandidate() }
        }

        /**
         * Permit full Pinyin, initials, or one transition between the two styles (e.g. Y+WEN
         * and DUO+S). Reject alternating fragments such as B+H+A+T, which can accidentally
         * reinterpret arbitrary letter strings as a sequence of unrelated syllable clues.
         */
        private fun isSupportedMixedInputPath(path: PinyinInputPath): Boolean =
            path.tokens.zipWithNext().count { (left, right) -> left.kind != right.kind } <= 1

        /** Accepts common shorthand that omits the h in zh/ch/sh, below exact Pinyin matches. */
        private fun isRetroflexHOptional(
            input: String,
            reading: String,
        ): Boolean =
            input.length + 1 == reading.length &&
                input.firstOrNull() in RETROFLEX_INITIALS &&
                reading.firstOrNull() == input.firstOrNull() &&
                reading.getOrNull(1) == 'h' &&
                reading.removeRange(1, 2) == input

        suspend fun recordSelection(candidate: HybridCandidate) {
            queryCacheMutex.withLock { queryCache.clear() }
            val now = System.currentTimeMillis()
            if (candidate.id > 0) dao.recordSelection(candidate.id, now)
            if (candidate.source == HybridLexiconSource.BOSHIAMY) return

            val readings = PinyinInputSegmentor.dictionarySyllables(candidate.code)
            if (readings.isEmpty() || candidate.phrase.isBlank()) return
            val normalizedReading = readings.joinToString("")
            dao.recordLearningSelection(
                phrase = candidate.phrase,
                normalizedCode = normalizedReading,
                reading = readings.joinToString(" "),
                kind = HybridLearningKind.SELECTION.name,
                now = now,
            )
            val preference =
                dao.findLearning(
                    phrase = candidate.phrase,
                    normalizedCode = normalizedReading,
                    kind = HybridLearningKind.SELECTION.name,
                ) ?: return
            if (candidate.phrase.codePointCount(0, candidate.phrase.length) > 1 &&
                candidate.source != HybridLexiconSource.PERSONAL &&
                preference.selectionCount >= AUTO_PROMOTION_THRESHOLD
            ) {
                promotePersonalPhrase(candidate.phrase, readings.joinToString(" "))
            }
        }

        suspend fun addPersonalPhrase(
            phrase: String,
            pinyin: String,
        ): Boolean {
            queryCacheMutex.withLock { queryCache.clear() }
            val entity = personalEntity(phrase, pinyin, PersonalLexiconKind.MANUAL.name) ?: return false
            val id = dao.insertAll(listOf(entity)).firstOrNull() ?: return false
            return id != -1L
        }

        suspend fun importPersonalText(raw: String): ImportResult {
            val parsed = HybridLexiconImporter.parseBaiduText(raw)
            val entities = mutableListOf<HybridLexiconEntity>()
            var skipped = 0
            for (entry in parsed) {
                val pinyin = entry.code.ifBlank { derivePinyin(entry.phrase).orEmpty() }
                val entity = personalEntity(entry.phrase, pinyin, PersonalLexiconKind.MANUAL.name)
                if (entity == null) {
                    skipped++
                } else {
                    entities += entity
                }
            }
            val insertedIds = if (entities.isEmpty()) emptyList() else dao.insertAll(entities)
            val imported = insertedIds.count { it != -1L }
            skipped += entities.size - imported
            invalidateQueryCache()
            return ImportResult(imported, skipped)
        }

        suspend fun suggestPinyin(phrase: String): String? {
            val cleanedPhrase = phrase.trim()
            if (cleanedPhrase.isEmpty()) return null
            val suggestion = derivePinyin(cleanedPhrase) ?: return null
            return suggestion.takeIf {
                personalEntity(cleanedPhrase, it, PersonalLexiconKind.MANUAL.name) != null
            }
        }

        suspend fun updatePersonalPhrase(id: Long, phrase: String, pinyin: String): Boolean {
            val entity = personalEntity(phrase, pinyin, PersonalLexiconKind.MANUAL.name) ?: return false
            val updated = runCatching {
                dao.updatePersonalPhrase(
                    id = id,
                    phrase = entity.phrase,
                    code = entity.code,
                    normalizedCode = entity.normalizedCode,
                    initials = entity.initials,
                    baseWeight = entity.baseWeight,
                    personalKind = entity.personalKind,
                )
            }.getOrDefault(0)
            invalidateQueryCache()
            return updated > 0
        }

        suspend fun deletePersonalPhrase(id: Long): Boolean {
            val existing = dao.findPersonalPhrase(id) ?: return false
            val deleted = dao.deletePersonalPhrase(id) > 0
            if (deleted) dao.deleteLearning(existing.phrase, existing.normalizedCode)
            invalidateQueryCache()
            return deleted
        }

        suspend fun personalPhrases(): List<HybridLexiconEntity> = dao.getPersonalPhrases()

        suspend fun clearAutomaticLearning() {
            database.withTransaction {
                dao.deleteAutomaticPersonalPhrases()
                dao.clearLearning()
                contextDao.clearTransitions()
            }
            invalidateQueryCache()
        }

        suspend fun recordContextSelection(
            previousContext: String,
            selected: String,
            now: Long = System.currentTimeMillis(),
        ) {
            val transitions = ContextPredictionText.transitions(previousContext, selected)
            if (transitions.isEmpty()) return
            database.withTransaction {
                transitions.forEach { (contextText, continuation) ->
                    contextDao.recordTransition(contextText, continuation, now)
                }
            }
        }

        suspend fun queryContextSuggestions(
            context: String,
            limit: Int = 6,
        ): List<String> {
            val suffixes = ContextPredictionText.suffixes(context)
            if (suffixes.isEmpty() || limit <= 0) return emptyList()

            data class Option(val text: String, val score: Int, val lastUsedAt: Long)

            val options = mutableListOf<Option>()
            contextDao.findTransitions(suffixes, CONTEXT_MIN_SELECTIONS, CONTEXT_QUERY_POOL)
                .forEach { entry ->
                    if (ContextPredictionText.isChinesePhrase(entry.continuation)) {
                        val singleCharacterBonus =
                            if (entry.continuation.codePointCount(0, entry.continuation.length) == 1) 20 else 0
                        val score =
                            entry.contextText.length * 100 + 40 +
                                entry.selectionCount.coerceAtMost(50) * 8 + singleCharacterBonus
                        options +=
                            Option(
                                text = entry.continuation,
                                score = score,
                                lastUsedAt = entry.lastSelectedAt,
                            )
                        if (entry.continuation.codePointCount(0, entry.continuation.length) > 1) {
                            val firstCharacter = entry.continuation.codePoints().toArray().first()
                            options +=
                                Option(
                                    text = String(intArrayOf(firstCharacter), 0, 1),
                                    score = score + 20,
                                    lastUsedAt = entry.lastSelectedAt,
                                )
                        }
                    }
                }

            suffixes.forEach { prefix ->
                contextDao.findPersonalPhraseContinuations(
                    prefix,
                    ContextPredictionText.MAX_CONTINUATION_LENGTH,
                    PERSONAL_CONTEXT_QUERY_POOL,
                ).forEach { phrase ->
                    val continuation = phrase.phrase.removePrefix(prefix)
                    if (!ContextPredictionText.isChinesePhrase(continuation)) return@forEach
                    val manualBonus = if (phrase.personalKind == PersonalLexiconKind.MANUAL.name) 30 else 0
                    val score = prefix.length * 100 + 24 + manualBonus + phrase.usageCount.coerceAtMost(50) * 8
                    options += Option(continuation, score, phrase.lastUsedAt)
                    val firstCharacter = continuation.codePoints().toArray().first()
                    options += Option(String(intArrayOf(firstCharacter), 0, 1), score + 20, phrase.lastUsedAt)
                }
            }

            return options
                .groupBy { it.text }
                .values
                .map { matching -> matching.maxWith(compareBy<Option> { it.score }.thenBy { it.lastUsedAt }) }
                .sortedWith(
                    compareByDescending<Option> { it.score }
                        .thenByDescending { it.lastUsedAt }
                        .thenBy { it.text },
                )
                .take(limit)
                .map { it.text }
        }

        suspend fun learnPersonalPhrase(
            phrase: String,
            pinyin: String,
        ): Boolean {
            queryCacheMutex.withLock { queryCache.clear() }
            val entity = personalEntity(phrase, pinyin, PersonalLexiconKind.MANUAL.name) ?: return false
            val insertedId = dao.insertAll(listOf(entity)).firstOrNull()?.takeIf { it > 0 }
            val id =
                insertedId
                    ?: dao.findExactPersonal(
                        phrase = entity.phrase,
                        normalizedCode = entity.normalizedCode,
                    )?.id
                    ?: return false
            dao.recordSelection(id, System.currentTimeMillis())
            return true
        }

        suspend fun recordComposedPhraseSelection(phrase: String, pinyin: String) {
            val entity = personalEntity(phrase, pinyin, PersonalLexiconKind.AUTO_PROMOTED.name) ?: return
            val readings = PinyinInputSegmentor.dictionarySyllables(entity.code)
            if (readings.isEmpty() || phrase.codePointCount(0, phrase.length) < 2) return
            val now = System.currentTimeMillis()
            dao.recordLearningSelection(
                phrase = entity.phrase,
                normalizedCode = entity.normalizedCode,
                reading = readings.joinToString(" "),
                kind = HybridLearningKind.COMPOSITION.name,
                now = now,
            )
            val preference =
                dao.findLearning(entity.phrase, entity.normalizedCode, HybridLearningKind.COMPOSITION.name)
                    ?: return
            if (preference.selectionCount >= AUTO_PROMOTION_THRESHOLD) promotePersonalPhrase(phrase, pinyin)
        }

        private suspend fun promotePersonalPhrase(phrase: String, pinyin: String) {
            val entity = personalEntity(phrase, pinyin, PersonalLexiconKind.AUTO_PROMOTED.name) ?: return
            database.withTransaction { dao.insertAll(listOf(entity)) }
            invalidateQueryCache()
        }

        private suspend fun invalidateQueryCache() {
            queryCacheMutex.withLock { queryCache.clear() }
        }

        private suspend fun applySingleCharacterPreferences(
            candidates: List<HybridCandidate>,
        ): List<HybridCandidate> {
            val singles = candidates.filter { it.phrase.codePointCount(0, it.phrase.length) == 1 }
            val readingKeys = singles.map { it.normalizedCode }.distinct()
            if (readingKeys.isEmpty()) return candidates
            val preferences =
                dao.getLearningForCodes(readingKeys, HybridLearningKind.SELECTION.name)
                    .filter { it.selectionCount >= AUTO_PROMOTION_THRESHOLD }
                    .associateBy { it.phrase to it.normalizedCode }
            if (preferences.isEmpty()) return candidates

            val result = candidates.toMutableList()
            val positionsByReading = candidates.indices
                .filter { candidates[it].phrase.codePointCount(0, candidates[it].phrase.length) == 1 }
                .groupBy { candidates[it].source to candidates[it].normalizedCode }
            positionsByReading.values.forEach { positions ->
                val sorted = positions.sortedWith(
                    compareByDescending<Int> { index ->
                        preferences[candidates[index].phrase to candidates[index].normalizedCode]?.selectionCount ?: 0
                    }.thenByDescending { index ->
                        preferences[candidates[index].phrase to candidates[index].normalizedCode]?.lastSelectedAt ?: 0L
                    }.thenBy { it },
                )
                positions.zip(sorted).forEach { (target, source) -> result[target] = candidates[source] }
            }
            return result
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
                val dictionary = downloadText(RIME_DICTIONARY_URL, "Rime dictionary")
                val presetVocabulary = downloadText(RIME_ESSAY_URL, "Rime preset vocabulary")
                val parsed = HybridLexiconImporter.parseRimeDictionary(dictionary, presetVocabulary)
                check(parsed.isNotEmpty()) { "Rime dictionary contains no usable entries" }
                replacePinyinEntries(parsed)
                ImportResult(parsed.size, skipped = 0)
            }

        private fun downloadText(
            url: String,
            description: String,
        ): String {
            val request = Request.Builder().url(url).header("User-Agent", "VoxPen-Android").get().build()
            return httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "$description download failed: HTTP ${response.code}" }
                response.body?.string() ?: error("$description download returned an empty body")
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
            invalidateQueryCache()
        }

        /** Refresh only the upstream Pinyin rows; user learning and other imported dictionaries stay intact. */
        private suspend fun replacePinyinEntries(entries: List<HybridLexiconImporter.ParsedEntry>) {
            val entities =
                entries.map(HybridLexiconImporter::toEntity)
                    .distinctBy { it.phrase to it.normalizedCode }

            database.withTransaction {
                val previousRows = dao.getBySource(HybridLexiconSource.PINYIN.name)
                    .associateBy { it.phrase to it.normalizedCode }
                dao.deleteBySource(HybridLexiconSource.PINYIN.name)
                entities
                    .map { fresh ->
                        val previous = previousRows[fresh.phrase to fresh.normalizedCode]
                        if (previous == null) {
                            fresh
                        } else {
                            fresh.copy(
                                id = previous.id,
                                usageCount = previous.usageCount,
                                lastUsedAt = previous.lastUsedAt,
                                createdAt = previous.createdAt,
                            )
                        }
                    }
                    .chunked(1_000)
                    .forEach { dao.insertAll(it) }
            }
            invalidateQueryCache()
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

        private fun personalEntity(
            phrase: String,
            pinyin: String,
            personalKind: String,
        ): HybridLexiconEntity? {
            val cleanedPhrase = phrase.trim()
            val cleanedPinyin = pinyin.trim()
            if (cleanedPhrase.isBlank() || cleanedPinyin.isBlank()) return null
            val readings = PinyinInputSegmentor.dictionarySyllables(cleanedPinyin)
            var cjkCount = 0
            var offset = 0
            while (offset < cleanedPhrase.length) {
                val codePoint = cleanedPhrase.codePointAt(offset)
                if (isCjkCodePoint(codePoint)) cjkCount++
                offset += Character.charCount(codePoint)
            }
            if (readings.isEmpty() || cjkCount == 0 || readings.size != cjkCount) return null
            val canonicalPinyin = readings.joinToString(" ")
            return HybridLexiconImporter.toEntity(
                HybridLexiconImporter.ParsedEntry(
                    phrase = cleanedPhrase,
                    code = canonicalPinyin,
                    source = HybridLexiconSource.PERSONAL,
                    baseWeight = PERSONAL_BASE_WEIGHT,
                    personalKind = personalKind,
                    toneCode = PinyinToneCode.fromReading(cleanedPinyin),
                ),
            )
        }

        private fun isCjkCodePoint(codePoint: Int): Boolean =
            codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF || codePoint in 0x20000..0x2FA1F

        private fun rank(
            entry: HybridLexiconEntity,
            query: String,
            isBoshiamy: Boolean,
        ): Double {
            var score = 0.0
            if (entry.normalizedCode == query) {
                score += if (isBoshiamy) EXACT_BOSHIAMY_CODE_SCORE else EXACT_PINYIN_CODE_SCORE
            }
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
            if (entry.usageCount >= AUTO_PROMOTION_THRESHOLD) {
                score += ln(entry.usageCount + 1.0) * USAGE_MULTIPLIER
                score += recencyBonus(entry.lastUsedAt)
            }
            score += entry.baseWeight.coerceAtMost(MAX_WEIGHT) / WEIGHT_DIVISOR
            entry.frequencyWeight?.let { score += ln(it.coerceAtLeast(0.0) + 1.0) * FREQUENCY_MULTIPLIER }
            return score
        }

        private val phoneticOrder =
            compareBy<HybridLexiconEntity> { if (it.source == HybridLexiconSource.PERSONAL.name) 0 else 1 }
                .thenBy { if (it.usageCount >= AUTO_PROMOTION_THRESHOLD) 0 else 1 }
                .thenByDescending { if (it.usageCount >= AUTO_PROMOTION_THRESHOLD) it.usageCount else 0 }
                .thenByDescending { recencyBonus(it.lastUsedAt) }
                .thenByDescending { it.frequencyWeight ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { it.baseWeight }
                .thenBy { it.id }

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
                source =
                    runCatching { HybridLexiconSource.valueOf(source) }
                        .getOrDefault(HybridLexiconSource.PINYIN),
                usageCount = usageCount,
                lastUsedAt = lastUsedAt,
                baseWeight = baseWeight,
                personalKind = personalKind,
                frequencyWeight = frequencyWeight,
                toneCode = toneCode,
            )

        private fun HybridCandidate.toEntityForRanking(): HybridLexiconEntity =
            HybridLexiconEntity(
                id = id,
                phrase = phrase,
                code = code,
                normalizedCode = normalizedCode,
                initials = initials,
                source = source.name,
                usageCount = usageCount,
                lastUsedAt = lastUsedAt,
                baseWeight = baseWeight,
                personalKind = personalKind,
                frequencyWeight = frequencyWeight,
                toneCode = toneCode,
            )

        private data class DictionaryInputMatch(
            val entity: HybridLexiconEntity,
            val exactSyllables: Int,
        )

        data class ImportResult(
            val imported: Int,
            val skipped: Int,
        )

        companion object {
            private const val SEARCH_POOL = 120
            private const val MAX_QUERY_POOL = 480
            private const val PERSONAL_SEARCH_POOL = 256
            private const val PERSONAL_BASE_WEIGHT = 100_000
            private const val EXACT_BOSHIAMY_CODE_SCORE = 600.0
            private const val EXACT_PINYIN_CODE_SCORE = 720.0
            private const val EXACT_INITIALS_SCORE = 650.0
            private const val INITIALS_PREFIX_SCORE = 240.0
            private const val PINYIN_PREFIX_SCORE = 180.0
            private const val PERSONAL_SOURCE_SCORE = 250.0
            private const val USAGE_MULTIPLIER = 95.0
            private const val MAX_WEIGHT = 1_000_000
            private const val WEIGHT_DIVISOR = 10_000.0
            private const val FREQUENCY_MULTIPLIER = 30.0
            private const val MIXTYPE_LEXICON_PREFERENCES = "mixtype_lexicon"
            private const val MIXTYPE_LEXICON_VERSION_KEY = "installed_revision"
            private const val MIXTYPE_PREPARED_REVISION_KEY = "prepared_revision"
            private const val MIXTYPE_IMPORT_PROGRESS_KEY = "import_progress_rows"
            private const val MIN_EXPECTED_MIXTYPE_ROWS = 200_000
            private const val LEXICON_INSERT_BATCH_SIZE = 2_000
            private const val IMPORT_PROGRESS_CHECKPOINT_INTERVAL = 10_000
            private const val DAY_MS = 86_400_000L
            private const val MAX_QUERY_CACHE_SIZE = 128
            private const val COMPOSED_CANDIDATE_LIMIT = 12
            private const val PINYIN_PATH_LIMIT = 24
            private const val LOOKUP_KEY_CHUNK_SIZE = 350
            private const val AUTO_PROMOTION_THRESHOLD = 2
            private const val CONTEXT_MIN_SELECTIONS = 2
            private const val CONTEXT_QUERY_POOL = 30
            private const val PERSONAL_CONTEXT_QUERY_POOL = 8
            private val RETROFLEX_INITIALS = setOf('z', 'c', 's')

            private const val RIME_DICTIONARY_URL =
                "https://raw.githubusercontent.com/rime/rime-luna-pinyin/" +
                    "56b934b099dfbeab842320f13aa8b461a6ab3e42/luna_pinyin.dict.yaml"
            private const val RIME_ESSAY_URL =
                "https://raw.githubusercontent.com/rime/rime-essay/master/essay.txt"

        }
    }
