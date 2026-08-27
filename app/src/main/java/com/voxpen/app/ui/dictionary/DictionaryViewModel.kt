package com.voxpen.app.ui.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxpen.app.billing.ProStatusResolver
import com.voxpen.app.data.local.DictionaryEntry
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.repository.DictionaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DictionaryViewModel
@Inject
constructor(
    private val repository: DictionaryRepository,
    private val preferencesManager: PreferencesManager,
    private val proStatusResolver: ProStatusResolver,
) : ViewModel() {

    val entries: StateFlow<List<DictionaryEntry>> =
        repository
            .getAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    val count: StateFlow<Int> =
        repository
            .count()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = 0,
            )

    val isPro: StateFlow<Boolean> =
        proStatusResolver
            .proStatus
            .map { status ->
                status.isPro
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /*
     * App 中目前所有被標記 ⭐ 的重要詞。
     *
     * 資料來源不是 Room，
     * 而是 PreferencesManager 的 DataStore。
     */
    val importantWords: StateFlow<Set<String>> =
        preferencesManager
            .importantWordsFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptySet(),
            )

    /*
     * Free 使用者仍保留原本詞庫數量限制。
     *
     * Personal Build 因為 isPro = true，
     * 所以不會受到這個限制。
     */
    val isLimitReached: StateFlow<Boolean> =
        combine(
            count,
            isPro,
        ) { currentCount, pro ->
            !pro &&
                currentCount >=
                FREE_DICTIONARY_LIMIT
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    private val _showDuplicateToast =
        MutableStateFlow(false)

    val showDuplicateToast: StateFlow<Boolean> =
        _showDuplicateToast.asStateFlow()

    fun addWord(
        word: String,
    ) {
        val normalizedWord =
            word.trim()

        if (normalizedWord.isBlank()) {
            return
        }

        viewModelScope.launch {
            if (isLimitReached.value) {
                return@launch
            }

            val result =
                repository.add(
                    normalizedWord,
                )

            if (result == -1L) {
                _showDuplicateToast.value =
                    true
            }
        }
    }

    /*
     * 設定 / 取消 ⭐ 重要詞。
     *
     * 這裡不再直接使用：
     *
     * importantWords.value
     *
     * 而是每次從 DataStore Flow
     * 取得目前最新狀態，
     * 避免 StateFlow 尚未同步完成時
     * 判斷錯誤。
     */
    fun toggleImportantWord(
        word: String,
    ) {
        val normalizedWord =
            word.trim()

        if (normalizedWord.isBlank()) {
            return
        }

        viewModelScope.launch {
            val currentImportantWords =
                preferencesManager
                    .importantWordsFlow
                    .first()

            val currentlyImportant =
                normalizedWord in
                    currentImportantWords

            preferencesManager
                .setImportantWord(
                    word =
                        normalizedWord,
                    important =
                        !currentlyImportant,
                )
        }
    }

    /*
     * 刪除詞庫項目。
     *
     * 同時清掉 DataStore 裡可能存在的
     * ⭐ 重要詞標記，
     * 避免留下不存在於詞庫的幽靈項目。
     */
    fun removeWord(
        entry: DictionaryEntry,
    ) {
        viewModelScope.launch {
            repository.remove(
                entry,
            )

            preferencesManager
                .setImportantWord(
                    word = entry.word,
                    important = false,
                )
        }
    }

    fun dismissDuplicateToast() {
        _showDuplicateToast.value =
            false
    }

    companion object {
        /*
         * 恢復官方 Free 詞庫限制。
         *
         * Personal Build 本身是 Pro，
         * 因此你的 Debug APK 不受此限制。
         */
        const val FREE_DICTIONARY_LIMIT =
            10
    }
}
