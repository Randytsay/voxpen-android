package com.voxpen.app.data.repository

import com.voxpen.app.data.local.CorrectionHint
import com.voxpen.app.data.local.CorrectionManualLevel
import com.voxpen.app.data.local.CorrectionMemoryDao
import com.voxpen.app.data.local.CorrectionMemoryEntity
import com.voxpen.app.data.local.CorrectionScope
import com.voxpen.app.ime.CorrectionLearningCandidate
import kotlinx.coroutines.flow.Flow
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CorrectionMemoryRepository
    @Inject
    constructor(
        private val dao: CorrectionMemoryDao,
    ) {
        fun observeAll(): Flow<List<CorrectionMemoryEntity>> =
            dao.observeAll()

        suspend fun getAllOnce(): List<CorrectionMemoryEntity> =
            dao.getAllOnce()

        suspend fun learn(
            candidate: CorrectionLearningCandidate,
            packageName: String,
        ) {
            val wrong = normalize(candidate.wrongText)
            val correct = normalize(candidate.correctText)
            if (!isUsablePair(wrong, correct)) return

            val scope =
                if (packageName.isBlank()) {
                    CorrectionScope.GLOBAL
                } else {
                    CorrectionScope.APP
                }
            val scopedPackage =
                if (scope == CorrectionScope.APP) packageName else ""

            val existing =
                dao.findExact(
                    wrongText = wrong,
                    correctText = correct,
                    scope = scope.name,
                    packageName = scopedPackage,
                )

            val now = System.currentTimeMillis()
            if (existing == null) {
                dao.insert(
                    CorrectionMemoryEntity(
                        wrongText = wrong,
                        correctText = correct,
                        hitCount = 1,
                        autoConfidence = confidenceForHits(1),
                        scope = scope.name,
                        packageName = scopedPackage,
                        createdAt = now,
                        lastCorrectedAt = now,
                    ),
                )
            } else {
                val newHits = existing.hitCount + 1
                dao.update(
                    existing.copy(
                        hitCount = newHits,
                        autoConfidence = confidenceForHits(newHits),
                        lastCorrectedAt = now,
                    ),
                )
            }
        }

        suspend fun addOrUpdateManual(
            wrongText: String,
            correctText: String,
            manualLevel: CorrectionManualLevel,
            scope: CorrectionScope,
            packageName: String,
        ): Boolean {
            val wrong = normalize(wrongText)
            val correct = normalize(correctText)
            if (!isUsablePair(wrong, correct)) return false

            val scopedPackage =
                if (scope == CorrectionScope.APP) packageName.trim() else ""
            if (scope == CorrectionScope.APP && scopedPackage.isBlank()) return false

            val now = System.currentTimeMillis()
            val existing =
                dao.findExact(
                    wrongText = wrong,
                    correctText = correct,
                    scope = scope.name,
                    packageName = scopedPackage,
                )

            if (existing == null) {
                dao.insert(
                    CorrectionMemoryEntity(
                        wrongText = wrong,
                        correctText = correct,
                        hitCount = 1,
                        autoConfidence = confidenceForHits(1),
                        manualLevel = manualLevel.name,
                        scope = scope.name,
                        packageName = scopedPackage,
                        createdAt = now,
                        lastCorrectedAt = now,
                        enabled = manualLevel != CorrectionManualLevel.DISABLED,
                    ),
                )
            } else {
                dao.update(
                    existing.copy(
                        manualLevel = manualLevel.name,
                        enabled = manualLevel != CorrectionManualLevel.DISABLED,
                        lastCorrectedAt = now,
                    ),
                )
            }
            return true
        }

        suspend fun updateManualLevel(
            id: Long,
            level: CorrectionManualLevel,
        ) {
            val entity = dao.getById(id) ?: return
            dao.update(
                entity.copy(
                    manualLevel = level.name,
                    enabled = level != CorrectionManualLevel.DISABLED,
                ),
            )
        }

        suspend fun updateScope(
            id: Long,
            scope: CorrectionScope,
            packageName: String,
        ): Boolean {
            val entity = dao.getById(id) ?: return false
            val scopedPackage = if (scope == CorrectionScope.APP) packageName.trim() else ""
            if (scope == CorrectionScope.APP && scopedPackage.isBlank()) return false

            val duplicate =
                dao.findExact(
                    entity.wrongText,
                    entity.correctText,
                    scope.name,
                    scopedPackage,
                )
            if (duplicate != null && duplicate.id != entity.id) return false

            dao.update(
                entity.copy(
                    scope = scope.name,
                    packageName = scopedPackage,
                ),
            )
            return true
        }

        suspend fun delete(id: Long) {
            dao.deleteById(id)
        }

        suspend fun deleteAll() {
            dao.deleteAll()
        }

        suspend fun prepareForText(
            text: String,
            packageName: String,
            allowMemory: Boolean = true,
            hintLimit: Int = 50,
        ): CorrectionPreparation {
            if (!allowMemory || text.isBlank()) {
                return CorrectionPreparation(text, emptyList())
            }

            val candidates =
                dao.getEnabledForPackage(packageName)
                    .mapNotNull { entity ->
                        val level = entity.manualLevelOrDefault()
                        if (level == CorrectionManualLevel.DISABLED) return@mapNotNull null
                        if (!containsTerm(text, entity.wrongText)) return@mapNotNull null
                        PreparedRule(
                            entity = entity,
                            level = level,
                            effectiveConfidence = effectiveConfidence(entity, level),
                        )
                    }
                    .sortedWith(
                        compareByDescending<PreparedRule> { it.entity.scope == CorrectionScope.APP.name }
                            .thenByDescending { it.effectiveConfidence }
                            .thenByDescending { it.entity.wrongText.length }
                            .thenByDescending { it.entity.lastCorrectedAt },
                    )

            var correctedText = text
            val appliedIds = mutableListOf<Long>()
            candidates.forEach { prepared ->
                if (!shouldDirectlyApply(prepared)) return@forEach
                val replaced = replaceTerm(correctedText, prepared.entity.wrongText, prepared.entity.correctText)
                if (replaced != correctedText) {
                    correctedText = replaced
                    appliedIds += prepared.entity.id
                }
            }

            val now = System.currentTimeMillis()
            appliedIds.forEach { id ->
                dao.getById(id)?.let { entity ->
                    dao.update(entity.copy(lastAppliedAt = now))
                }
            }

            val hints =
                candidates
                    .take(hintLimit.coerceAtLeast(0))
                    .map {
                        CorrectionHint(
                            wrongText = it.entity.wrongText,
                            correctText = it.entity.correctText,
                            confidence = it.effectiveConfidence,
                            manualLevel = it.level,
                        )
                    }

            return CorrectionPreparation(
                correctedText = correctedText,
                hints = hints,
            )
        }

        suspend fun importEntity(entity: CorrectionMemoryEntity) {
            val wrong = normalize(entity.wrongText)
            val correct = normalize(entity.correctText)
            if (!isUsablePair(wrong, correct)) return

            val scope = runCatching { CorrectionScope.valueOf(entity.scope) }.getOrDefault(CorrectionScope.GLOBAL)
            val packageName = if (scope == CorrectionScope.APP) entity.packageName.trim() else ""
            if (scope == CorrectionScope.APP && packageName.isBlank()) return

            val level = entity.manualLevelOrDefault()
            val existing = dao.findExact(wrong, correct, scope.name, packageName)
            val normalized =
                entity.copy(
                    id = existing?.id ?: 0,
                    wrongText = wrong,
                    correctText = correct,
                    hitCount = entity.hitCount.coerceAtLeast(1),
                    autoConfidence = entity.autoConfidence.coerceIn(0.0, 1.0),
                    manualLevel = level.name,
                    scope = scope.name,
                    packageName = packageName,
                    enabled = entity.enabled && level != CorrectionManualLevel.DISABLED,
                )

            if (existing == null) {
                dao.insert(normalized)
            } else {
                dao.update(
                    normalized.copy(
                        id = existing.id,
                        hitCount = maxOf(existing.hitCount, normalized.hitCount),
                        autoConfidence = maxOf(existing.autoConfidence, normalized.autoConfidence),
                        createdAt = minOf(existing.createdAt, normalized.createdAt),
                        lastCorrectedAt = maxOf(existing.lastCorrectedAt, normalized.lastCorrectedAt),
                        lastAppliedAt = listOfNotNull(existing.lastAppliedAt, normalized.lastAppliedAt).maxOrNull(),
                    ),
                )
            }
        }

        companion object {
            const val AUTO_DIRECT_THRESHOLD = 0.85

            fun confidenceForHits(hitCount: Int): Double =
                when {
                    hitCount <= 1 -> 0.45
                    hitCount == 2 -> 0.65
                    hitCount == 3 -> 0.82
                    hitCount == 4 -> 0.90
                    else -> 0.95
                }

            fun effectiveConfidence(
                entity: CorrectionMemoryEntity,
                level: CorrectionManualLevel = entity.manualLevelOrDefault(),
            ): Double =
                when (level) {
                    CorrectionManualLevel.AUTO -> entity.autoConfidence.coerceIn(0.0, 1.0)
                    CorrectionManualLevel.LOW -> 0.35
                    CorrectionManualLevel.MEDIUM -> 0.65
                    CorrectionManualLevel.HIGH -> 0.90
                    CorrectionManualLevel.FIXED -> 1.0
                    CorrectionManualLevel.DISABLED -> 0.0
                }

            fun shouldDirectlyApply(
                level: CorrectionManualLevel,
                confidence: Double,
            ): Boolean =
                when (level) {
                    CorrectionManualLevel.FIXED,
                    CorrectionManualLevel.HIGH,
                    -> true
                    CorrectionManualLevel.AUTO -> confidence >= AUTO_DIRECT_THRESHOLD
                    CorrectionManualLevel.LOW,
                    CorrectionManualLevel.MEDIUM,
                    CorrectionManualLevel.DISABLED,
                    -> false
                }

            private fun shouldDirectlyApply(rule: PreparedRule): Boolean =
                shouldDirectlyApply(rule.level, rule.effectiveConfidence)

            private fun normalize(text: String): String =
                text.trim().replace(Regex("\\s+"), " ")

            private fun isUsablePair(wrong: String, correct: String): Boolean =
                wrong.isNotBlank() &&
                    correct.isNotBlank() &&
                    wrong != correct &&
                    wrong.length <= 80 &&
                    correct.length <= 80

            private fun containsTerm(text: String, term: String): Boolean =
                if (term.any(::isCjkLike)) {
                    text.contains(term)
                } else {
                    Regex("(?i)(?<![\\p{L}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{N}_])")
                        .containsMatchIn(text)
                }

            private fun replaceTerm(
                text: String,
                wrong: String,
                correct: String,
            ): String =
                if (wrong.any(::isCjkLike)) {
                    text.replace(wrong, correct)
                } else {
                    Regex("(?i)(?<![\\p{L}\\p{N}_])${Regex.escape(wrong)}(?![\\p{L}\\p{N}_])")
                        .replace(text) { correct }
                }

            private fun isCjkLike(ch: Char): Boolean =
                ch.code in 0x3400..0x4DBF ||
                    ch.code in 0x4E00..0x9FFF ||
                    ch.code in 0x3040..0x30FF ||
                    ch.code in 0xAC00..0xD7AF
        }

        private data class PreparedRule(
            val entity: CorrectionMemoryEntity,
            val level: CorrectionManualLevel,
            val effectiveConfidence: Double,
        )
    }

data class CorrectionPreparation(
    val correctedText: String,
    val hints: List<CorrectionHint>,
)

fun CorrectionMemoryEntity.manualLevelOrDefault(): CorrectionManualLevel =
    runCatching {
        CorrectionManualLevel.valueOf(manualLevel)
    }.getOrDefault(CorrectionManualLevel.AUTO)

fun CorrectionMemoryEntity.scopeOrDefault(): CorrectionScope =
    runCatching {
        CorrectionScope.valueOf(scope)
    }.getOrDefault(CorrectionScope.GLOBAL)
