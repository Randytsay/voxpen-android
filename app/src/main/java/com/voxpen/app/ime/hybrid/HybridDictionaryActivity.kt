package com.voxpen.app.ime.hybrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun HybridDictionaryScreen() {
        var status by remember { mutableStateOf("準備完成") }
        var counts by remember { mutableStateOf<Map<HybridLexiconSource, Int>>(emptyMap()) }
        var busy by remember { mutableStateOf(false) }
        var personalPhrase by remember { mutableStateOf("") }
        var personalPinyin by remember { mutableStateOf("") }
        var personalPinyinEdited by remember { mutableStateOf(false) }
        var personalSearch by remember { mutableStateOf("") }
        var personalFilter by remember { mutableStateOf(PersonalWordFilter.ALL) }
        var personalSort by remember { mutableStateOf(PersonalWordSort.PHRASE) }
        var personalPage by remember { mutableStateOf(0) }
        var personalEntries by remember { mutableStateOf<List<HybridLexiconEntity>>(emptyList()) }
        var editingPersonalId by remember { mutableStateOf<Long?>(null) }
        val personalPhraseRequester = remember { BringIntoViewRequester() }
        val screenScope = rememberCoroutineScope()

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

        val personalLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                lifecycleScope.launch {
                    busy = true
                    status = "正在匯入個人詞庫…"
                    runCatching {
                        val raw = readText(uri)
                        repository.importPersonalText(raw)
                    }.onSuccess { result ->
                        status = "個人詞庫匯入完成：新增 ${result.imported} 筆；略過 ${result.skipped} 筆（格式不符或重複）"
                        refreshCounts()
                        refreshPersonalEntries()
                    }.onFailure { error ->
                        status = "個人詞庫匯入失敗：${error.message}"
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
                    "未完全匹配的嘸蝦米碼列在後面。選過至少 2 次的詞會依使用次數與最近使用時間往前移。",
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
                    personalLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream"))
                },
            ) {
                Text("匯入個人詞庫（CSV / TSV）")
            }
            Text(
                "欄位可用「詞語,拼音」；標題列可有可無。匯入後會列在個人詞典，拼音需能對應每個中文字。",
                style = MaterialTheme.typography.bodySmall,
            )

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
                onValueChange = { phrase ->
                    personalPhrase = phrase
                    personalPinyinEdited = false
                    personalPinyin = ""
                    if (phrase.isNotBlank()) {
                        lifecycleScope.launch {
                            val suggestion = repository.suggestPinyin(phrase)
                            if (personalPhrase == phrase && !personalPinyinEdited) {
                                personalPinyin = suggestion.orEmpty()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(personalPhraseRequester),
                label = { Text("詞語，例如：公司名稱") },
                singleLine = true,
            )
            OutlinedTextField(
                value = personalPinyin,
                onValueChange = {
                    personalPinyin = it
                    personalPinyinEdited = true
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("拼音（自動帶入，可修改多音字）") },
                singleLine = true,
            )
            Text(
                "依已安裝詞庫推導拼音；找不到讀音時請手動輸入，儲存前請確認多音字。",
                style = MaterialTheme.typography.bodySmall,
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
                            personalPinyinEdited = false
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
                    personalPinyinEdited = false
                }) { Text("取消編輯") }
            }

            Spacer(Modifier.height(8.dp))
            Text("自訂與學習詞典", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = personalSearch,
                onValueChange = {
                    personalSearch = it
                    personalPage = 0
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜尋詞語、拼音或首字母") },
                trailingIcon = {
                    if (personalSearch.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                personalSearch = ""
                                personalPage = 0
                            },
                        ) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清除搜尋")
                        }
                    }
                },
                singleLine = true,
            )
            val wordPage =
                remember(personalEntries, personalSearch, personalFilter, personalSort, personalPage) {
                    personalWordPage(
                        entries = personalEntries,
                        search = personalSearch,
                        filter = personalFilter,
                        sort = personalSort,
                        requestedPage = personalPage,
                    )
                }
            PersonalWordTable(
                allEntries = personalEntries,
                page = wordPage,
                selectedFilter = personalFilter,
                selectedSort = personalSort,
                onFilterSelected = {
                    personalFilter = it
                    personalPage = 0
                },
                onSortSelected = {
                    personalSort = it
                    personalPage = 0
                },
                onPreviousPage = { personalPage = (wordPage.page - 1).coerceAtLeast(0) },
                onNextPage = { personalPage = (wordPage.page + 1).coerceAtMost(wordPage.pageCount - 1) },
                onEdit = { entry ->
                    editingPersonalId = entry.id
                    personalPhrase = entry.phrase
                    personalPinyin = entry.code
                    personalPinyinEdited = false
                    status = "正在編輯「${entry.phrase}」"
                    screenScope.launch { personalPhraseRequester.bringIntoView() }
                },
                onDelete = { entry ->
                    lifecycleScope.launch {
                        val deleted = repository.deletePersonalPhrase(entry.id)
                        status = if (deleted) "已刪除「${entry.phrase}」" else "刪除失敗"
                        refreshCounts()
                        refreshPersonalEntries()
                    }
                },
            )

            Button(
                enabled = personalEntries.any { it.personalKind == "AUTO_PROMOTED" },
                onClick = {
                    lifecycleScope.launch {
                        repository.clearAutomaticLearning()
                        status = "已清除自動學習詞、學習次數與聯想紀錄；手動自訂詞保留"
                        refreshCounts()
                        refreshPersonalEntries()
                    }
                },
            ) { Text("清除自動學習與聯想（保留手動自訂詞）") }

            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @Composable
    private fun PersonalWordTable(
        allEntries: List<HybridLexiconEntity>,
        page: PersonalWordPage,
        selectedFilter: PersonalWordFilter,
        selectedSort: PersonalWordSort,
        onFilterSelected: (PersonalWordFilter) -> Unit,
        onSortSelected: (PersonalWordSort) -> Unit,
        onPreviousPage: () -> Unit,
        onNextPage: () -> Unit,
        onEdit: (HybridLexiconEntity) -> Unit,
        onDelete: (HybridLexiconEntity) -> Unit,
    ) {
        val manualCount = allEntries.count { it.personalKind != AUTO_PROMOTED_KIND }
        val learnedCount = allEntries.size - manualCount
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonalWordOptionRow(
                label = "顯示",
                options =
                    listOf(
                        PersonalWordFilter.ALL to "全部 ${allEntries.size}",
                        PersonalWordFilter.MANUAL to "手動 $manualCount",
                        PersonalWordFilter.LEARNED to "學習 $learnedCount",
                    ),
                selected = selectedFilter,
                onSelected = onFilterSelected,
            )
            PersonalWordOptionRow(
                label = "排序",
                options =
                    listOf(
                        PersonalWordSort.PHRASE to "詞語",
                        PersonalWordSort.PINYIN to "拼音",
                        PersonalWordSort.MOST_USED to "常用",
                        PersonalWordSort.RECENT to "最近",
                    ),
                selected = selectedSort,
                onSelected = onSortSelected,
            )

            if (page.entries.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        if (allEntries.isEmpty()) {
                            "目前沒有個人詞；選用常用詞兩次後會自動加入學習詞典。"
                        } else {
                            "找不到符合條件的詞，請清除搜尋或切換顯示範圍。"
                        },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@Column
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column {
                    PersonalWordTableHeader()
                    page.entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PersonalWordTableRow(entry, onEdit, onDelete)
                    }
                }
            }

            PersonalWordPagination(page, onPreviousPage, onNextPage)
        }
    }

    @Composable
    private fun <T> PersonalWordOptionRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                modifier = Modifier.width(38.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            options.forEach { (value, optionLabel) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(optionLabel) },
                )
            }
        }
    }

    @Composable
    private fun PersonalWordTableHeader() {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 38.dp)
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableHeaderText("詞語", Modifier.weight(0.95f))
            TableHeaderText("拼音／縮寫", Modifier.weight(1.25f))
            TableHeaderText("來源", Modifier.width(42.dp))
            TableHeaderText("操作", Modifier.width(68.dp))
        }
    }

    @Composable
    private fun TableHeaderText(
        text: String,
        modifier: Modifier,
    ) {
        Text(
            text,
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun PersonalWordTableRow(
        entry: HybridLexiconEntity,
        onEdit: (HybridLexiconEntity) -> Unit,
        onDelete: (HybridLexiconEntity) -> Unit,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 54.dp)
                    .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.phrase,
                modifier = Modifier.weight(0.95f).padding(end = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Column(modifier = Modifier.weight(1.25f).padding(end = 5.dp)) {
                Text(
                    entry.code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.initials.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            Text(
                if (entry.personalKind == AUTO_PROMOTED_KIND) "學習" else "手動",
                modifier = Modifier.width(42.dp),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (entry.personalKind == AUTO_PROMOTED_KIND) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Row(modifier = Modifier.width(68.dp)) {
                IconButton(
                    onClick = { onEdit(entry) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "編輯${entry.phrase}",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { onDelete(entry) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "刪除${entry.phrase}",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun PersonalWordPagination(
        page: PersonalWordPage,
        onPreviousPage: () -> Unit,
        onNextPage: () -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${page.firstVisibleNumber}–${page.lastVisibleNumber} / ${page.totalEntries}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = page.page > 0,
                    onClick = onPreviousPage,
                ) { Text("上一頁") }
                Text(
                    "${page.page + 1} / ${page.pageCount}",
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(
                    enabled = page.page + 1 < page.pageCount,
                    onClick = onNextPage,
                ) { Text("下一頁") }
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
