package com.voxpen.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import com.voxpen.app.data.local.AppDatabase
import com.voxpen.app.data.local.HybridCandidate
import com.voxpen.app.data.local.HybridLearningKind
import com.voxpen.app.data.local.HybridLexiconEntity
import com.voxpen.app.data.local.HybridLexiconSource
import com.voxpen.app.data.local.PersonalLexiconKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import kotlin.math.abs
import kotlin.math.ln

@Singleton
class HybridInputRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        @ApplicationContext private val context: Context,
    ) {
        private val dao = database.hybridLexiconDao()
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
                        check(parsedRows >= MIN_EXPECTED_MIXTYPE_ROWS &&
                            installedRowsAfterImport >= MIN_EXPECTED_MIXTYPE_ROWS
                        ) {
                            "MixType dictionary import was incomplete: parsed=$parsedRows installed=$installedRowsAfterImport"
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
        ): List<HybridCandidate> {
            val query = HybridLexiconImporter.normalizeCode(rawCode)
            if (query.isBlank()) return emptyList()

            val cacheKey = "${PinyinInputSegmentor.normalizeInput(rawCode)}#$limit"
            queryCacheMutex.withLock {
                queryCache[cacheKey]?.let { return it }
            }

            val boshiamy =
                dao.searchSourcePrefix(
                    source = HybridLexiconSource.BOSHIAMY.name,
                    prefix = query,
                    limit = SEARCH_POOL,
                )
            val initials =
                if (query.length >= 1) {
                    dao.searchInitialsPrefix(
                        prefix = query,
                        limit = SEARCH_POOL,
                    )
                } else {
                    dao.searchInitialsExact(
                        initials = query,
                        limit = SEARCH_POOL,
                    )
                }
            val pinyin =
                dao.searchPinyinPrefix(
                    prefix = query,
                    limit = SEARCH_POOL,
                )

            val phoneticRows =
                (initials + pinyin)
                    .distinctBy { it.id }
                    .filter { PinyinInputSegmentor.isValidReading(it.code) }

            val exactFullPinyin = phoneticRows.filter { it.normalizedCode == query }
            val exactInitials = phoneticRows.filter { it.initials == query && it !in exactFullPinyin }
            // Exact dictionary readings and exact initials are both direct matches; only build
            // fuzzy/composed alternatives when neither direct route found a candidate.
            val hasExactPhoneticMatch = exactFullPinyin.isNotEmpty() || exactInitials.isNotEmpty()
            val composedPinyin =
                if (hasExactPhoneticMatch) {
                    emptyList()
                } else {
                    composePinyinWords(rawCode) + composePinyinInitials(query)
                }

            val exactBoshiamy = boshiamy.filter { it.normalizedCode == query }
            val partialBoshiamy = boshiamy.filter { it.normalizedCode != query }
            val fullPinyinPrefix = phoneticRows.filter {
                it.normalizedCode.startsWith(query) && it.normalizedCode != query
            }
            val initialsPrefix = phoneticRows.filter {
                it.initials.startsWith(query) && it.initials != query && it !in fullPinyinPrefix
            }
            val rankedCandidates = buildList {
                addAll(exactBoshiamy.sortedByDescending { rank(it, query, isBoshiamy = true) })
                addAll(exactFullPinyin.filter { it.source == HybridLexiconSource.PERSONAL.name }.sortedWith(phoneticOrder))
                addAll(exactFullPinyin.filter { it.source != HybridLexiconSource.PERSONAL.name }.sortedWith(phoneticOrder))
                addAll(exactInitials.filter { it.source == HybridLexiconSource.PERSONAL.name }.sortedWith(phoneticOrder))
                addAll(exactInitials.filter { it.source != HybridLexiconSource.PERSONAL.name }.sortedWith(phoneticOrder))
                addAll(fullPinyinPrefix.filter { it.source == HybridLexiconSource.PERSONAL.name }.sortedWith(phoneticOrder))
                addAll(fullPinyinPrefix.filter { it.source != HybridLexiconSource.PERSONAL.name }.sortedWith(phoneticOrder))
                addAll(initialsPrefix.filter { it.source == HybridLexiconSource.PERSONAL.name }.sortedWith(phoneticOrder))
                addAll(initialsPrefix.filter { it.source != HybridLexiconSource.PERSONAL.name }.sortedWith(phoneticOrder))
                addAll(composedPinyin.map { it.toEntityForRanking() })
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

        /**
         * Builds local pinyin-initial phrases, allowing one mistyped, omitted, or extra initial.
         * The decoder is intentionally bounded so a long input cannot create an expensive
         * combinatorial search on the IME thread.
         */
        private suspend fun composePinyinInitials(query: String): List<HybridCandidate> {
            if (query.length < MIN_COMPOSED_INITIALS || query.length > MAX_COMPOSED_INITIALS) return emptyList()

            val exactCache = mutableMapOf<String, List<HybridLexiconEntity>>()
            val fuzzyCache = mutableMapOf<String, List<FuzzyInitialMatch>>()
            val beams = Array(query.length + 1) { mutableListOf<PhrasePath>() }
            beams[0].add(PhrasePath(position = 0, edits = 0, phrase = "", code = "", score = 0.0))

            for (position in query.indices) {
                if (beams[position].isEmpty()) continue
                val states = beams[position].toList()
                for (state in states) {
                    val maxLength = minOf(MAX_TOKEN_INITIALS, query.length - position)
                    for (length in 1..maxLength) {
                        val segment = query.substring(position, position + length)
                        val exact =
                            exactCache.getOrPut(segment) {
                                dao.searchInitialsExact(segment, TOKEN_SEARCH_LIMIT)
                            }
                        exact.forEach { entity ->
                            addPath(
                                beams[position + length],
                                state,
                                entity,
                                consumedLength = length,
                                editCount = 0,
                            )
                        }

                        if (state.edits == 0 && length >= MIN_FUZZY_SEGMENT) {
                            val fuzzyKey = segment
                            val fuzzy =
                                fuzzyCache.getOrPut(fuzzyKey) {
                                    findOneEditInitials(segment)
                                }
                            fuzzy.forEach { match ->
                                addPath(
                                    beams[position + length],
                                    state,
                                    match.entity,
                                    consumedLength = length,
                                    editCount = 1,
                                )
                            }
                        }
                    }
                }
                trimBeam(beams[position + 1])
            }

            return beams[query.length]
                .filter { it.phrase.isNotBlank() && it.edits <= MAX_INITIAL_EDITS }
                .sortedByDescending { it.score }
                .distinctBy { it.phrase }
                .take(COMPOSED_CANDIDATE_LIMIT)
                .map { path ->
                    HybridCandidate(
                        id = 0,
                        phrase = path.phrase,
                        code = path.code,
                        normalizedCode = HybridLexiconImporter.normalizeCode(path.code),
                        initials = path.initials,
                        source = HybridLexiconSource.PINYIN,
                        usageCount = 0,
                        lastUsedAt = 0,
                        baseWeight = 0,
                    )
                }
        }

        /**
         * Decodes continuous/full Pinyin, including ambiguous legal syllable boundaries such as
         * xian = xian or xi'an, and composes dictionary phrases through a bounded beam.
         */
        private suspend fun composePinyinWords(rawCode: String): List<HybridCandidate> {
            val paths = PinyinInputSegmentor.segment(rawCode, limit = PINYIN_PATH_LIMIT)
            if (paths.isEmpty()) return emptyList()

            val codeKeys = linkedSetOf<String>()
            val initialKeys = linkedSetOf<String>()
            paths.forEach { path ->
                for (start in path.tokens.indices) {
                    for (end in start + 1..minOf(path.tokens.size, start + MAX_WORD_SYLLABLES)) {
                        val tokens = path.tokens.subList(start, end)
                        val syllables = tokens.map { token ->
                            if (token.kind == PinyinInputToken.Kind.SYLLABLE) token.value else ""
                        }
                        if (syllables.all(String::isNotEmpty)) codeKeys += syllables.joinToString("")
                        initialKeys += tokens.joinToString("") { it.value.first().toString() }
                    }
                }
            }
            if (codeKeys.isEmpty() && initialKeys.isEmpty()) return emptyList()

            val rows = linkedMapOf<Long, HybridLexiconEntity>()
            codeKeys.toList().chunked(LOOKUP_KEY_CHUNK_SIZE).forEach { chunk ->
                dao.searchPinyinCodes(chunk, SEARCH_POOL).forEach { rows[it.id] = it }
            }
            initialKeys.toList().chunked(LOOKUP_KEY_CHUNK_SIZE).forEach { chunk ->
                dao.searchInitialsKeys(chunk, SEARCH_POOL).forEach { rows[it.id] = it }
            }
            val byCode = rows.values.groupBy { it.normalizedCode }
            val byInitials = rows.values.groupBy { it.initials }

            val decoded = mutableListOf<PhrasePath>()
            paths.forEach { inputPath ->
                val beams = Array(inputPath.tokens.size + 1) { mutableListOf<PhrasePath>() }
                beams[0].add(PhrasePath(position = 0, edits = 0, phrase = "", code = "", score = 0.0))
                for (position in inputPath.tokens.indices) {
                    if (beams[position].isEmpty()) continue
                    val states = beams[position].toList()
                    val last = minOf(inputPath.tokens.size, position + MAX_WORD_SYLLABLES)
                    for (end in position + 1..last) {
                        val inputTokens = inputPath.tokens.subList(position, end)
                        val inputCode = inputTokens.joinToString("") { it.value }
                        val possible = linkedMapOf<Long, HybridLexiconEntity>()
                        if (inputTokens.all { it.kind == PinyinInputToken.Kind.SYLLABLE }) {
                            byCode[inputCode].orEmpty().forEach { possible[it.id] = it }
                        }
                        val initials = inputTokens.joinToString("") { it.value.first().toString() }
                        byInitials[initials].orEmpty().forEach { possible[it.id] = it }

                        possible.values.forEach { entity ->
                            val reading = PinyinInputSegmentor.dictionarySyllables(entity.code)
                            if (reading.size != inputTokens.size) return@forEach
                            val matches = inputTokens.indices.all { index ->
                                val token = inputTokens[index]
                                if (token.kind == PinyinInputToken.Kind.SYLLABLE) {
                                    token.value == reading[index]
                                } else {
                                    token.value == reading[index].firstOrNull()?.toString()
                                }
                            }
                            if (!matches) return@forEach
                            val segmentQuery = inputTokens.joinToString("") { it.value }
                            states.forEach { previous ->
                                val phrase = previous.phrase + entity.phrase
                                if (phrase.codePointCount(0, phrase.length) > MAX_COMPOSED_PHRASE_LENGTH) {
                                    return@forEach
                                }
                                val candidateCode = listOf(previous.code, entity.code)
                                    .filter(String::isNotBlank).joinToString(" ")
                                val candidateInitials = previous.initials + entity.initials
                                beams[end] += PhrasePath(
                                    position = end,
                                    edits = 0,
                                    phrase = phrase,
                                    code = candidateCode,
                                    initials = candidateInitials,
                                    score = previous.score + rank(entity, segmentQuery, isBoshiamy = false) +
                                        inputTokens.size * TOKEN_LENGTH_BONUS,
                                )
                            }
                        }
                    }
                    trimBeam(beams[position + 1])
                }
                decoded += beams.last().filter { it.phrase.isNotBlank() }
            }

            return decoded
                .sortedWith(compareByDescending<PhrasePath> { it.score }.thenBy { it.phrase.length })
                .distinctBy { it.phrase }
                .take(COMPOSED_CANDIDATE_LIMIT)
                .map { path ->
                    HybridCandidate(
                        id = 0,
                        phrase = path.phrase,
                        code = path.code,
                        normalizedCode = HybridLexiconImporter.normalizeCode(path.code),
                        initials = path.initials,
                        source = HybridLexiconSource.PINYIN,
                        usageCount = 0,
                        lastUsedAt = 0,
                        baseWeight = 0,
                    )
                }
        }

        private suspend fun findOneEditInitials(segment: String): List<FuzzyInitialMatch> {
            val results = mutableListOf<FuzzyInitialMatch>()
            for (index in segment.indices) {
                val pattern = segment.replaceRange(index, index + 1, "_")
                dao.searchInitialsPattern(pattern, segment.length, TOKEN_SEARCH_LIMIT).forEach { entity ->
                    if (editDistanceAtMostOne(entity.initials, segment)) {
                        results += FuzzyInitialMatch(entity)
                    }
                }
            }

            if (segment.length > 1) {
                for (index in segment.indices) {
                    val target = segment.removeRange(index, index + 1)
                    dao.searchInitialsExact(target, TOKEN_SEARCH_LIMIT).forEach { entity ->
                        if (editDistanceAtMostOne(entity.initials, segment)) {
                            results += FuzzyInitialMatch(entity)
                        }
                    }
                }
            }

            for (index in 0..segment.length) {
                val pattern = segment.substring(0, index) + "_" + segment.substring(index)
                dao.searchInitialsPattern(pattern, segment.length + 1, TOKEN_SEARCH_LIMIT).forEach { entity ->
                    if (editDistanceAtMostOne(entity.initials, segment)) {
                        results += FuzzyInitialMatch(entity)
                    }
                }
            }
            return results
                .distinctBy { it.entity.id }
                .filter { editDistanceAtMostOne(it.entity.initials, segment) }
        }

        private fun editDistanceAtMostOne(
            first: String,
            second: String,
        ): Boolean {
            if (first == second || abs(first.length - second.length) > 1) return false
            if (first.length == second.length) {
                return first.indices.count { first[it] != second[it] } == 1
            }

            val longer = if (first.length > second.length) first else second
            val shorter = if (first.length > second.length) second else first
            var longIndex = 0
            var shortIndex = 0
            var differences = 0
            while (longIndex < longer.length && shortIndex < shorter.length) {
                if (longer[longIndex] == shorter[shortIndex]) {
                    longIndex++
                    shortIndex++
                } else {
                    differences++
                    longIndex++
                    if (differences > 1) return false
                }
            }
            return true
        }

        private fun addPath(
            target: MutableList<PhrasePath>,
            previous: PhrasePath,
            entity: HybridLexiconEntity,
            consumedLength: Int,
            editCount: Int,
        ) {
            val phrase = previous.phrase + entity.phrase
            if (phrase.length > MAX_COMPOSED_PHRASE_LENGTH) return
            val tokenScore =
                rank(entity, entity.initials, isBoshiamy = false) +
                    entity.initials.length * TOKEN_LENGTH_BONUS -
                    editCount * FUZZY_EDIT_PENALTY
            target +=
                PhrasePath(
                    position = previous.position + consumedLength,
                    edits = previous.edits + editCount,
                    phrase = phrase,
                    code = listOf(previous.code, entity.code).filter { it.isNotBlank() }.joinToString(" "),
                    initials = previous.initials + entity.initials,
                    score = previous.score + tokenScore,
                )
            trimBeam(target)
        }

        private fun trimBeam(paths: MutableList<PhrasePath>) {
            if (paths.size <= BEAM_WIDTH) return
            val best =
                paths
                    .sortedWith(compareByDescending<PhrasePath> { it.score }.thenBy { it.edits })
                    .take(BEAM_WIDTH)
            paths.clear()
            paths.addAll(best)
        }

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
            dao.deleteAutomaticPersonalPhrases()
            dao.clearLearning()
            invalidateQueryCache()
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
            )

        private data class PhrasePath(
            val position: Int,
            val edits: Int,
            val phrase: String,
            val code: String,
            val initials: String = "",
            val score: Double,
        )

        private data class FuzzyInitialMatch(
            val entity: HybridLexiconEntity,
        )

        data class ImportResult(
            val imported: Int,
            val skipped: Int,
        )

        companion object {
            private const val SEARCH_POOL = 120
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
            private const val MIN_COMPOSED_INITIALS = 3
            private const val MAX_COMPOSED_INITIALS = 10
            private const val MIN_FUZZY_SEGMENT = 2
            private const val MAX_TOKEN_INITIALS = 4
            private const val MAX_INITIAL_EDITS = 1
            private const val TOKEN_SEARCH_LIMIT = 32
            private const val COMPOSED_CANDIDATE_LIMIT = 12
            private const val PINYIN_PATH_LIMIT = 24
            private const val MAX_WORD_SYLLABLES = 6
            private const val LOOKUP_KEY_CHUNK_SIZE = 350
            private const val BEAM_WIDTH = 32
            private const val MAX_COMPOSED_PHRASE_LENGTH = 24
            private const val TOKEN_LENGTH_BONUS = 55.0
            private const val FUZZY_EDIT_PENALTY = 300.0
            private const val AUTO_PROMOTION_THRESHOLD = 3

            private const val RIME_DICTIONARY_URL =
                "https://raw.githubusercontent.com/rime/rime-luna-pinyin/" +
                    "56b934b099dfbeab842320f13aa8b461a6ab3e42/luna_pinyin.dict.yaml"
            private const val RIME_ESSAY_URL =
                "https://raw.githubusercontent.com/rime/rime-essay/master/essay.txt"

        }
    }
