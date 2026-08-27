package com.voxpen.app.ui.correction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxpen.app.data.backup.PersonalProfileBackupService
import com.voxpen.app.data.local.CorrectionManualLevel
import com.voxpen.app.data.local.CorrectionMemoryEntity
import com.voxpen.app.data.local.CorrectionScope
import com.voxpen.app.data.local.PersonalLearningPreferences
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.repository.CorrectionMemoryRepository
import com.voxpen.app.data.repository.DictionaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CorrectionMemoryViewModel
    @Inject
    constructor(
        private val repository: CorrectionMemoryRepository,
        private val learningPreferences: PersonalLearningPreferences,
        private val backupService: PersonalProfileBackupService,
        private val dictionaryRepository: DictionaryRepository,
        private val preferencesManager: PreferencesManager,
    ) : ViewModel() {
        private val searchQuery = MutableStateFlow("")
        private val statusMessage = MutableStateFlow<String?>(null)

        val uiState: StateFlow<CorrectionMemoryUiState> =
            combine(
                repository.observeAll(),
                searchQuery,
                learningPreferences.enabled,
                statusMessage,
            ) { entries, query, enabled, status ->
                CorrectionMemoryUiState(
                    entries = entries,
                    searchQuery = query,
                    learningEnabled = enabled,
                    statusMessage = status,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = CorrectionMemoryUiState(
                    learningEnabled = learningPreferences.enabled.value,
                ),
            )

        fun setSearchQuery(query: String) {
            searchQuery.value = query
        }

        fun setLearningEnabled(enabled: Boolean) {
            learningPreferences.setEnabled(enabled)
        }

        fun addManualRule(
            wrongText: String,
            correctText: String,
            level: CorrectionManualLevel,
            scope: CorrectionScope,
            packageName: String,
        ) {
            viewModelScope.launch {
                val saved =
                    repository.addOrUpdateManual(
                        wrongText = wrongText,
                        correctText = correctText,
                        manualLevel = level,
                        scope = scope,
                        packageName = packageName,
                    )
                statusMessage.value =
                    if (saved) "已儲存個人修正規則" else "無法儲存：請檢查錯詞、正詞與 App 範圍"
            }
        }

        fun setManualLevel(
            entity: CorrectionMemoryEntity,
            level: CorrectionManualLevel,
        ) {
            viewModelScope.launch {
                repository.updateManualLevel(entity.id, level)
            }
        }

        fun setScope(
            entity: CorrectionMemoryEntity,
            scope: CorrectionScope,
            packageName: String,
        ) {
            viewModelScope.launch {
                val saved = repository.updateScope(entity.id, scope, packageName)
                if (!saved) {
                    statusMessage.value = "無法變更範圍：App 規則需要 package name，或已有相同規則"
                }
            }
        }

        fun delete(entity: CorrectionMemoryEntity) {
            viewModelScope.launch {
                repository.delete(entity.id)
                statusMessage.value = "已刪除修正記憶"
            }
        }

        fun upgradeToImportant(entity: CorrectionMemoryEntity) {
            viewModelScope.launch {
                dictionaryRepository.add(entity.correctText)
                preferencesManager.setImportantWord(entity.correctText, true)
                repository.updateManualLevel(entity.id, CorrectionManualLevel.FIXED)
                statusMessage.value = "已將「${entity.correctText}」升級為 ⭐重要詞並固定此修正"
            }
        }

        fun exportJson(onReady: (String) -> Unit) {
            viewModelScope.launch {
                runCatching { backupService.exportJson() }
                    .onSuccess(onReady)
                    .onFailure { statusMessage.value = "匯出失敗：${it.message ?: "未知錯誤"}" }
            }
        }

        fun exportCsv(onReady: (String) -> Unit) {
            viewModelScope.launch {
                runCatching { backupService.exportCorrectionsCsv() }
                    .onSuccess(onReady)
                    .onFailure { statusMessage.value = "CSV 匯出失敗：${it.message ?: "未知錯誤"}" }
            }
        }

        fun importJson(raw: String) {
            viewModelScope.launch {
                runCatching { backupService.importJson(raw) }
                    .onSuccess { summary ->
                        statusMessage.value =
                            "匯入完成：詞庫 ${summary.dictionaryCount}、重要詞 ${summary.importantCount}、修正 ${summary.correctionCount}"
                    }
                    .onFailure { statusMessage.value = "匯入失敗：${it.message ?: "格式不正確"}" }
            }
        }

        fun clearStatus() {
            statusMessage.value = null
        }
    }
