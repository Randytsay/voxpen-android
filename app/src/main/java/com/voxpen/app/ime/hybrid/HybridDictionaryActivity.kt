package com.voxpen.app.ime.hybrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.voxpen.app.data.local.HybridLexiconSource
import com.voxpen.app.data.repository.HybridInputRepository
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

        fun refreshCounts() {
            lifecycleScope.launch {
                counts = repository.sourceCounts()
            }
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
                "候選優先順序：嘸蝦米 → 個人學習 → 拼音首字母 → 全拼。" +
                    "選過的詞會依使用次數與最近使用時間往前移。",
            )

            DictionaryCounts(counts)

            Button(
                enabled = !busy,
                onClick = {
                    lifecycleScope.launch {
                        busy = true
                        status = "正在下載並建立完整拼音詞庫…"
                        runCatching {
                            repository.installFullPinyinDictionary()
                        }.onSuccess { result ->
                            status = "完整拼音詞庫安裝完成：${result.imported} 筆"
                            refreshCounts()
                        }.onFailure { error ->
                            status = "拼音詞庫安裝失敗：${error.message}"
                        }
                        busy = false
                    }
                },
            ) {
                Text("下載 / 更新完整拼音詞庫（Rime）")
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
                text = "新增個人詞",
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
                label = { Text("拼音，例如：gong si ming cheng") },
                singleLine = true,
            )
            Button(
                enabled = !busy && personalPhrase.isNotBlank() && personalPinyin.isNotBlank(),
                onClick = {
                    lifecycleScope.launch {
                        val added = repository.addPersonalPhrase(personalPhrase, personalPinyin)
                        status = if (added) "個人詞已加入" else "這個個人詞已存在或格式不完整"
                        if (added) {
                            personalPhrase = ""
                            personalPinyin = ""
                            refreshCounts()
                        }
                    }
                },
            ) {
                Text("加入個人詞並建立首字母縮寫")
            }

            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @Composable
    private fun DictionaryCounts(counts: Map<HybridLexiconSource, Int>) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
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
