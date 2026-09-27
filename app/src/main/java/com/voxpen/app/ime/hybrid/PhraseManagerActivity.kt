package com.voxpen.app.ime.hybrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxpen.app.data.local.ClipboardEntry
import com.voxpen.app.data.local.ClipboardEntryType
import com.voxpen.app.data.repository.ClipboardRepository
import com.voxpen.app.ui.theme.VoxPenTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class PhraseManagerActivity : ComponentActivity() {
    @Inject
    lateinit var repository: ClipboardRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoxPenTheme(darkTheme = true, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PhraseManagerScreen(
                        repository = repository,
                        initialEntryId = intent.getLongExtra(EXTRA_ENTRY_ID, INVALID_ENTRY_ID),
                        openEditor = intent.getBooleanExtra(EXTRA_OPEN_EDITOR, false),
                        onBack = ::finish,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_ENTRY_ID = "phrase_entry_id"
        const val EXTRA_OPEN_EDITOR = "phrase_open_editor"
        const val INVALID_ENTRY_ID = -1L
    }
}

@Composable
private fun PhraseManagerScreen(
    repository: ClipboardRepository,
    initialEntryId: Long,
    openEditor: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<ClipboardEntry>()) }
    var selectedFilter by remember { mutableStateOf("全部") }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ClipboardEntry?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ClipboardEntry?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var initialEditorOpened by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            entries = withContext(Dispatchers.IO) { repository.getEntries(ClipboardEntryType.COMMON) }
        }
    }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { repository.getEntries(ClipboardEntryType.COMMON) }
        loaded = true
    }
    LaunchedEffect(entries, initialEntryId, openEditor, loaded) {
        if (initialEditorOpened || !loaded) return@LaunchedEffect
        when {
            initialEntryId != PhraseManagerActivity.INVALID_ENTRY_ID -> {
                entries.firstOrNull { it.id == initialEntryId }?.let { editing = it }
                if (editing == null) notice = "找不到這個片語，可能已被刪除"
                initialEditorOpened = true
            }
            openEditor -> {
                isCreating = true
                initialEditorOpened = true
            }
            else -> initialEditorOpened = true
        }
    }

    val categories = remember(entries) {
        (DEFAULT_PHRASE_CATEGORIES + entries.map { it.groupName.trim() })
            .filter(String::isNotBlank)
            .distinct()
    }
    val filtered = remember(entries, selectedFilter, search) {
        val query = search.trim()
        val matching = entries.filter { entry ->
            query.isBlank() || entry.label.contains(query, true) || entry.text.contains(query, true) ||
                entry.shortcut.contains(query, true) || entry.groupName.contains(query, true)
        }
        when (selectedFilter) {
            "最近" -> matching.filter { it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }
            "常用" -> matching.sortedWith(compareByDescending<ClipboardEntry> { it.usageCount }.thenByDescending { it.lastUsedAt })
            "收藏" -> matching.filter { it.isFavorite }
                .sortedWith(compareByDescending<ClipboardEntry> { it.isPinned }.thenByDescending { it.lastUsedAt })
            "全部" -> matching.sortedWith(
                compareByDescending<ClipboardEntry> { it.isPinned }
                    .thenByDescending { it.isFavorite }
                    .thenByDescending { it.lastUsedAt }
                    .thenByDescending { it.updatedAt },
            )
            else -> matching.filter { it.groupName.equals(selectedFilter, true) }
                .sortedWith(compareByDescending<ClipboardEntry> { it.isPinned }.thenByDescending { it.lastUsedAt })
        }
    }

    val currentEntry = editing
    if (currentEntry != null || isCreating) {
        PhraseEditor(
            entry = currentEntry,
            categories = categories,
            notice = notice,
            onBack = {
                editing = null
                isCreating = false
                notice = null
            },
            onDelete = { entry -> deleteTarget = entry },
            onSave = { label, category, shortcut, text ->
                scope.launch {
                    val success = withContext(Dispatchers.IO) {
                        if (currentEntry == null) {
                            repository.addCommonPhrase(text, category, shortcut, label)
                        } else {
                            repository.update(currentEntry, text, shortcut, category, label)
                        }
                    }
                    if (success) {
                        entries = withContext(Dispatchers.IO) {
                            repository.getEntries(ClipboardEntryType.COMMON)
                        }
                        selectedFilter = "全部"
                        search = ""
                        editing = null
                        isCreating = false
                        notice = "片語已儲存"
                    } else {
                        notice = "儲存失敗：請確認名稱、分類與內容已填寫，快捷碼沒有重複。"
                    }
                }
            },
        )
    } else {
        PhraseLibrary(
            entries = filtered,
            categories = categories,
            selectedFilter = selectedFilter,
            search = search,
            notice = notice,
            onBack = onBack,
            onSearch = { search = it },
            onFilter = { selectedFilter = it },
            onAdd = {
                notice = null
                isCreating = true
            },
            onEdit = { entry ->
                notice = null
                editing = entry
            },
            onToggleFavorite = { entry ->
                scope.launch {
                    withContext(Dispatchers.IO) { repository.setFavorite(entry, !entry.isFavorite) }
                    refresh()
                }
            },
            onTogglePinned = { entry ->
                scope.launch {
                    withContext(Dispatchers.IO) { repository.setPinned(entry, !entry.isPinned) }
                    refresh()
                }
            },
            onDelete = { deleteTarget = it },
        )
    }

    val pendingDelete = deleteTarget
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("刪除片語？") },
            text = { Text("「${pendingDelete.label.ifBlank { pendingDelete.text.take(24) }}」將從自訂片語中移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { repository.delete(pendingDelete) }
                            deleteTarget = null
                            editing = null
                            isCreating = false
                            notice = "片語已刪除"
                            refresh()
                        }
                    },
                ) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PhraseLibrary(
    entries: List<ClipboardEntry>,
    categories: List<String>,
    selectedFilter: String,
    search: String,
    notice: String?,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ClipboardEntry) -> Unit,
    onToggleFavorite: (ClipboardEntry) -> Unit,
    onTogglePinned: (ClipboardEntry) -> Unit,
    onDelete: (ClipboardEntry) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹") }
            Text("自訂片語", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = onAdd) { Text("＋ 新增") }
        }
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜尋名稱、內容或快捷碼") },
            leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { onSearch("") }) { Text("×") }
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf("全部", "最近", "常用", "收藏") + categories.filterNot { it in setOf("全部", "最近", "常用", "收藏") })
                .distinct()
                .forEach { category ->
                    FilterChip(
                        selected = selectedFilter == category,
                        onClick = { onFilter(category) },
                        label = { Text(category) },
                    )
                }
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("分類", modifier = Modifier.width(58.dp), style = MaterialTheme.typography.labelMedium)
            Text("名稱 / 快捷碼", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("內容預覽", modifier = Modifier.weight(1.15f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(72.dp))
        }
        Spacer(Modifier.height(4.dp))
        if (notice != null) {
            Text(notice, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(if (search.isBlank()) "還沒有自訂片語" else "找不到符合的片語")
                Text("可新增 Email、常用連結、地址或常用文字。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAdd) { Text("新增第一筆片語") }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(entries, key = { it.id }) { entry ->
                    PhraseTableRow(
                        entry = entry,
                        onEdit = { onEdit(entry) },
                        onToggleFavorite = { onToggleFavorite(entry) },
                        onTogglePinned = { onTogglePinned(entry) },
                        onDelete = { onDelete(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhraseTableRow(
    entry: ClipboardEntry,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).background(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            RoundedCornerShape(8.dp),
        ).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(categoryIcon(entry.groupName), modifier = Modifier.width(34.dp), style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.weight(1.05f)) {
            Text(entry.label.ifBlank { entry.text.take(20) }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(entry.shortcut.ifBlank { entry.groupName }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            maskedPhrasePreview(entry),
            modifier = Modifier.weight(1.15f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        IconButton(onClick = onTogglePinned, modifier = Modifier.size(36.dp)) {
            Text(if (entry.isPinned) "📌" else "⌖", style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
            Text(if (entry.isFavorite) "★" else "☆", color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) { Text("⋮") }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("編輯／移動分類") }, onClick = { menuExpanded = false; onEdit() })
                DropdownMenuItem(text = { Text("刪除") }, onClick = { menuExpanded = false; onDelete() })
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
}

@Composable
private fun PhraseEditor(
    entry: ClipboardEntry?,
    categories: List<String>,
    notice: String?,
    onBack: () -> Unit,
    onDelete: (ClipboardEntry) -> Unit,
    onSave: (label: String, category: String, shortcut: String, text: String) -> Unit,
) {
    var label by remember(entry?.id) { mutableStateOf(entry?.label.orEmpty()) }
    var category by remember(entry?.id) { mutableStateOf(entry?.groupName.orEmpty()) }
    var shortcut by remember(entry?.id) { mutableStateOf(entry?.shortcut.orEmpty()) }
    var text by remember(entry?.id) { mutableStateOf(entry?.text.orEmpty()) }
    var categoryExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("‹") }
            Text(if (entry == null) "新增片語" else "編輯片語", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (entry != null) TextButton(onClick = { onDelete(entry) }) { Text("刪除") }
        }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(60) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("片語名稱 *") },
            placeholder = { Text("例如：工作 Email") },
        )
        androidx.compose.foundation.layout.Box {
            OutlinedTextField(
                value = category,
                onValueChange = { category = it.take(40) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("分類 *") },
                placeholder = { Text("Email、連結、匯款、地址、工作…") },
                trailingIcon = { IconButton(onClick = { categoryExpanded = true }) { Text("▾") } },
            )
            DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                categories.filterNot { it in setOf("全部", "最近", "常用", "收藏") }.forEach { item ->
                    DropdownMenuItem(text = { Text(item) }, onClick = { category = item; categoryExpanded = false })
                }
            }
        }
        OutlinedTextField(
            value = shortcut,
            onValueChange = { shortcut = it.filterNot(Char::isWhitespace).take(32) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("快捷碼（選填）") },
            placeholder = { Text("例如 mm，輸入後從候選列點選插入") },
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(2_000) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 8,
            label = { Text("片語內容 *") },
            placeholder = { Text("輸入要快速貼上的文字") },
        )
        Text("密碼建議使用 Android 密碼管理器／系統自動填入；片語清單會隱藏敏感內容預覽。", style = MaterialTheme.typography.bodySmall)
        if (notice != null) Text(notice, color = if (notice.startsWith("片語已")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = { onSave(label.trim(), category.trim(), shortcut, text) },
                enabled = label.isNotBlank() && category.isNotBlank() && text.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("儲存") }
        }
        Spacer(Modifier.height(14.dp))
    }
}

private fun categoryIcon(category: String): String =
    when {
        category.contains("Email", true) || category.contains("郵件") -> "✉"
        category.contains("連結") || category.contains("網址") -> "↗"
        category.contains("匯款") || category.contains("銀行") || category.contains("帳號") -> "▤"
        category.contains("地址") -> "⌖"
        category.contains("密碼") -> "🔒"
        else -> "▣"
    }

private fun maskedPhrasePreview(entry: ClipboardEntry): String {
    if (entry.groupName.contains("密碼", true) || entry.label.contains("password", true)) return "••••••••"
    val preview = entry.text.replace('\n', ' ').trim()
    if (preview.contains('@')) {
        val at = preview.indexOf('@')
        return "${preview.take(2)}***${preview.substring(at)}".take(36)
    }
    if (listOf("匯款", "帳號", "銀行", "金融").any { entry.groupName.contains(it) }) {
        val digits = preview.filter(Char::isDigit)
        if (digits.length >= 7) return "•••• ${digits.takeLast(4)}"
    }
    return preview.take(36)
}

private val DEFAULT_PHRASE_CATEGORIES = listOf("Email", "連結", "匯款", "地址", "工作", "密碼")
