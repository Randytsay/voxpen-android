package com.voxpen.app.ime.hybrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.voxpen.app.data.local.HybridLexiconEntity
import com.voxpen.app.data.local.HybridLexiconSource
import com.voxpen.app.data.repository.HybridInputRepository
import com.voxpen.app.data.repository.PinyinInputSegmentor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HybridDictionaryActivity : ComponentActivity() {
    @Inject
    lateinit var repository: HybridInputRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    HybridDictionaryScreen()
                }
            }
        }
    }

    @Composable
    private fun HybridDictionaryScreen() {
        var status by remember { mutableStateOf("準備完成") }
        var counts by remember { mutableStateOf<Map<HybridLexiconSource, Int>>(emptyMap()) }
        var busy by remember { mutableStateOf(false) }
        var personalPhrase by remember { mutableStateOf("") }
        var personalPinyin by remember { mutableStateOf("") }
        var personalSearch by remember { mutableStateOf("") }
        var personalEntries by remember { mutableStateOf<List<HybridLexiconEntity>>(emptyList()) }
        var editingPersonalId by remember { mutableStateOf<Long?>(null) }

        fun refreshCounts() {
            lifecycleScope.launch {
                counts = repository.sourceCounts()
            }
        }

        fun refreshPersonalEntries() {
            lifecycleScope.launch { personalEntries = repository.personalPhrases() }
        }

        val boshiamyLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                lifecycleScope.launch {
                    busy = true
                    status = "正在匯入嘸蝦米碼表…"
                    runCatching {
                        val raw = readText(uri)
                        repository.importBoshiamyCin(raw)
                    }.onSuccess { result ->
                        status = "嘸蝦米匯入完成：${result.imported} 筆"
                        refreshCounts()
                    }.onFailure { error ->
                        status = "嘸蝦米匯入失敗：${error.message}"
                    }
                    busy = false
                }
            }

        val baiduLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                lifecycleScope.launch {
                    busy = true
                    status = "正在匯入百度自訂詞庫…"
                    runCatching {
                        val raw = readText(uri)
                        repository.importBaiduText(raw)
                    }.onSuccess { result ->
                        status =
                            "百度詞庫匯入完成：${result.imported} 筆；無法推導拼音 ${result.skipped} 筆"
                        refreshCounts()
                    }.onFailure { error ->
                        status = "百度詞庫匯入失敗：${error.message}"
                    }
                    busy = false
                }
            }

        LaunchedEffect(Unit) {
            repository.ensureBootstrapLexicon()
            counts = repository.sourceCounts()
            personalEntries = repository.personalPhrases()
        }

        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "VoxPen 混合中文輸入",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "候選優先順序：完全匹配嘸蝦米碼 → 個人詞典 / 學習 → 全拼詞頻 → 拼音首字母與拆詞；" +
                    "未完全匹配的嘸蝦米碼列在後面。選過至少 3 次的詞會依使用次數與最近使用時間往前移。",
            )

            DictionaryCounts(counts)

            Button(
                enabled = !busy,
                onClick = {
                    lifecycleScope.launch {
                        busy = true
                        status = "正在下載 Rime 拼音詞庫與詞頻資料…"
                        runCatching {
                            repository.installFullPinyinDictionary()
                        }.onSuccess { result ->
                            status = "Rime 拼音與詞頻更新完成：${result.imported} 筆；個人詞典與學習紀錄已保留"
                            refreshCounts()
                        }.onFailure { error ->
                            status = "拼音詞庫安裝失敗：${error.message}"
                        }
                        busy = false
                    }
                },
            ) {
                Text("下載 / 更新完整拼音詞庫與詞頻（Rime）")
            }

            Button(
                enabled = !busy,
                onClick = {
                    boshiamyLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                },
            ) {
                Text("匯入自己的嘸蝦米 .cin 碼表")
            }

            Button(
                enabled = !busy,
                onClick = {
                    baiduLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream"))
                },
            ) {
                Text("匯入百度自訂詞庫（TXT / CSV / TSV）")
            }
            Text(
                "百度專有二進位 .dat / .bcd 格式目前不直接解碼；" +
                    "請先由百度輸入法匯出文字詞庫。沒有附拼音的詞，VoxPen 會嘗試用已安裝的拼音字典推導。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = if (editingPersonalId == null) "新增個人詞" else "編輯個人詞",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = personalPhrase,
                onValueChange = { personalPhrase = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("詞語，例如：公司名稱") },
                singleLine = true,
            )
            OutlinedTextField(
                value = personalPinyin,
                onValueChange = { personalPinyin = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("拼音，可輸入空格或連續拼音，例如：yao wen") },
                singleLine = true,
            )
            if (personalPinyin.isNotBlank()) {
                val previewInitials =
                    PinyinInputSegmentor.dictionarySyllables(personalPinyin)
                        .joinToString("") { it.first().toString() }
                Text(
                    "首字母：${previewInitials.ifBlank { "無法辨識" }}（例如輸入 yw 可查到「耀文」）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                enabled = !busy && personalPhrase.isNotBlank() && personalPinyin.isNotBlank(),
                onClick = {
                    lifecycleScope.launch {
                        val editingId = editingPersonalId
                        val saved =
                            if (editingId == null) {
                                repository.addPersonalPhrase(personalPhrase, personalPinyin)
                            } else {
                                repository.updatePersonalPhrase(editingId, personalPhrase, personalPinyin)
                            }
                        status =
                            if (saved) {
                                if (editingId == null) "個人詞已加入" else "個人詞已更新"
                            } else {
                                "詞語已存在、拼音格式不正確，或每個中文字需對應一個拼音音節"
                            }
                        if (saved) {
                            personalPhrase = ""
                            personalPinyin = ""
                            editingPersonalId = null
                            refreshCounts()
                            refreshPersonalEntries()
                        }
                    }
                },
            ) {
                Text(if (editingPersonalId == null) "加入個人詞並建立首字母縮寫" else "儲存修改")
            }
            if (editingPersonalId != null) {
                Button(onClick = {
                    editingPersonalId = null
                    personalPhrase = ""
                    personalPinyin = ""
                }) { Text("取消編輯") }
            }

            Spacer(Modifier.height(8.dp))
            Text("自訂與學習詞典", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = personalSearch,
                onValueChange = { personalSearch = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜尋詞語、拼音或首字母") },
                singleLine = true,
            )
            val visibleEntries =
                personalEntries.filter { entry ->
                    val needle = personalSearch.trim().lowercase()
                    needle.isBlank() || entry.phrase.lowercase().contains(needle) ||
                        entry.code.lowercase().contains(needle) || entry.initials.lowercase().contains(needle)
                }
            if (visibleEntries.isEmpty()) {
                Text("目前沒有符合的個人詞。選用常用詞累積三次後，也會自動加入學習詞典。")
            } else {
                visibleEntries.forEach { entry ->
                    PersonalWordRow(
                        entry = entry,
                        onEdit = {
                            editingPersonalId = entry.id
                            personalPhrase = entry.phrase
                            personalPinyin = entry.code
                        },
                        onDelete = {
                            lifecycleScope.launch {
                                val deleted = repository.deletePersonalPhrase(entry.id)
                                status = if (deleted) "已刪除「${entry.phrase}」" else "刪除失敗"
                                refreshCounts()
                                refreshPersonalEntries()
                            }
                        },
                    )
                }
            }

            Button(
                enabled = personalEntries.any { it.personalKind == "AUTO_PROMOTED" },
                onClick = {
                    lifecycleScope.launch {
                        repository.clearAutomaticLearning()
                        status = "已清除自動學習詞與學習次數；手動自訂詞保留"
                        refreshCounts()
                        refreshPersonalEntries()
                    }
                },
            ) { Text("清除自動學習（保留手動自訂詞）") }

            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @Composable
    private fun PersonalWordRow(
        entry: HybridLexiconEntity,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
           Text(entry.phrase, style = MaterialTheme.typography.titleSmall)
            val origin = if (entry.personalKind == "AUTO_PROMOTED") "學習詞" else "手動詞"
            Text(
                listOf(entry.code, entry.initials, origin).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
           Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
               Button(onClick = onEdit) { Text("編輯") }
               Button(onClick = onDelete) { Text("刪除") }
           }
       }
   }

    @Composable
    private fun DictionaryCounts(counts: Map<HybridLexiconSource, Int>) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("MixType ${counts[HybridLexiconSource.MIXTYPE] ?: 0}")
            Text("拼音 ${counts[HybridLexiconSource.PINYIN] ?: 0}")
            Text("嘸蝦米 ${counts[HybridLexiconSource.BOSHIAMY] ?: 0}")
            Text("百度 ${counts[HybridLexiconSource.BAIDU] ?: 0}")
            Text("個人 ${counts[HybridLexiconSource.PERSONAL] ?: 0}")
        }
    }

    private fun readText(uri: android.net.Uri): String =
        contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            reader.readText()
        } ?: error("無法讀取檔案")
}
