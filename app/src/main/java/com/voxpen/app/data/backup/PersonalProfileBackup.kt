package com.voxpen.app.data.backup

import com.voxpen.app.data.local.CorrectionMemoryEntity
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.repository.CorrectionMemoryRepository
import com.voxpen.app.data.repository.DictionaryRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class VoxPenPersonalProfile(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAt: Long,
    val dictionary: List<String>,
    val importantTerms: List<String>,
    val corrections: List<CorrectionBackupItem>,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

@Serializable
data class CorrectionBackupItem(
    val wrongText: String,
    val correctText: String,
    val hitCount: Int,
    val autoConfidence: Double,
    val manualLevel: String,
    val scope: String,
    val packageName: String,
    val createdAt: Long,
    val lastCorrectedAt: Long,
    val lastAppliedAt: Long? = null,
    val enabled: Boolean,
)

data class ImportSummary(
    val dictionaryCount: Int,
    val importantCount: Int,
    val correctionCount: Int,
)

@Singleton
class PersonalProfileBackupService
    @Inject
    constructor(
        private val dictionaryRepository: DictionaryRepository,
        private val correctionMemoryRepository: CorrectionMemoryRepository,
        private val preferencesManager: PreferencesManager,
    ) {
        private val json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        suspend fun exportJson(): String {
            val dictionary = dictionaryRepository.getWords(EXPORT_DICTIONARY_LIMIT)
            val importantTerms = preferencesManager.importantWordsFlow.first().sorted()
            val corrections =
                correctionMemoryRepository
                    .getAllOnce()
                    .map { it.toBackupItem() }

            return json.encodeToString(
                VoxPenPersonalProfile(
                    exportedAt = System.currentTimeMillis(),
                    dictionary = dictionary,
                    importantTerms = importantTerms,
                    corrections = corrections,
                ),
            )
        }

        suspend fun importJson(raw: String): ImportSummary {
            val profile = json.decodeFromString<VoxPenPersonalProfile>(raw)
            require(profile.formatVersion in 1..VoxPenPersonalProfile.CURRENT_FORMAT_VERSION) {
                "Unsupported VoxPen profile format: ${profile.formatVersion}"
            }

            var dictionaryCount = 0
            profile.dictionary
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { word ->
                    if (dictionaryRepository.add(word) != -1L) {
                        dictionaryCount++
                    }
                }

            var importantCount = 0
            profile.importantTerms
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { word ->
                    // Ensure an important term also exists in the visible dictionary.
                    dictionaryRepository.add(word)
                    preferencesManager.setImportantWord(word, true)
                    importantCount++
                }

            var correctionCount = 0
            profile.corrections.forEach { item ->
                correctionMemoryRepository.importEntity(item.toEntity())
                correctionCount++
            }

            return ImportSummary(
                dictionaryCount = dictionaryCount,
                importantCount = importantCount,
                correctionCount = correctionCount,
            )
        }

        suspend fun exportCorrectionsCsv(): String {
            val header =
                "wrong,correct,hitCount,autoConfidence,manualLevel,scope,packageName,enabled"
            val rows =
                correctionMemoryRepository
                    .getAllOnce()
                    .joinToString("\n") { entity ->
                        listOf(
                            csv(entity.wrongText),
                            csv(entity.correctText),
                            entity.hitCount.toString(),
                            entity.autoConfidence.toString(),
                            csv(entity.manualLevel),
                            csv(entity.scope),
                            csv(entity.packageName),
                            entity.enabled.toString(),
                        ).joinToString(",")
                    }
            return if (rows.isBlank()) header else "$header\n$rows"
        }

        companion object {
            private const val EXPORT_DICTIONARY_LIMIT = 100_000

            private fun csv(value: String): String =
                "\"${value.replace("\"", "\"\"")}\""
        }
    }

private fun CorrectionMemoryEntity.toBackupItem(): CorrectionBackupItem =
    CorrectionBackupItem(
        wrongText = wrongText,
        correctText = correctText,
        hitCount = hitCount,
        autoConfidence = autoConfidence,
        manualLevel = manualLevel,
        scope = scope,
        packageName = packageName,
        createdAt = createdAt,
        lastCorrectedAt = lastCorrectedAt,
        lastAppliedAt = lastAppliedAt,
        enabled = enabled,
    )

private fun CorrectionBackupItem.toEntity(): CorrectionMemoryEntity =
    CorrectionMemoryEntity(
        wrongText = wrongText,
        correctText = correctText,
        hitCount = hitCount,
        autoConfidence = autoConfidence,
        manualLevel = manualLevel,
        scope = scope,
        packageName = packageName,
        createdAt = createdAt,
        lastCorrectedAt = lastCorrectedAt,
        lastAppliedAt = lastAppliedAt,
        enabled = enabled,
    )
