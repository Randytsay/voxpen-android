package com.voxpen.app.ui.correction

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.voxpen.app.data.local.CorrectionManualLevel
import com.voxpen.app.data.local.CorrectionMemoryEntity
import com.voxpen.app.data.local.CorrectionScope
import com.voxpen.app.data.repository.CorrectionMemoryRepository
import com.voxpen.app.data.repository.manualLevelOrDefault
import com.voxpen.app.data.repository.scopeOrDefault
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectionMemoryScreenContent(
    onNavigateBack: () -> Unit,
    viewModel: CorrectionMemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var editingEntry by remember { mutableStateOf<CorrectionMemoryEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingExportText by remember { mutableStateOf<String?>(null) }

    val jsonExportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            val text = pendingExportText
            if (uri != null && text != null) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
            }
            pendingExportText = null
        }

    val csvExportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            val text = pendingExportText
            if (uri != null && text != null) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
            }
            pendingExportText = null
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val raw =
                    context.contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                if (raw != null) viewModel.importJson(raw)
            }
        }

    LaunchedEffect(state.statusMessage) {
        // Status is rendered inline. Keep it until the next action so the user can read it.
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🧠 個人學習記憶") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
        ) {
            LearningToggle(state.learningEnabled, viewModel::setLearningEnabled)
            Spacer(Modifier.height(8.dp))
            SummaryCard(state)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                label = { Text("搜尋錯詞、正詞或 App") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("＋ 手動新增")
                }
                OutlinedButton(
                    onClick = {
                        viewModel.exportJson { text ->
                            pendingExportText = text
                            jsonExportLauncher.launch("voxpen-personal-profile.json")
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("備份 JSON")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("匯入備份")
                }
                OutlinedButton(
                    onClick = {
                        viewModel.exportCsv { text ->
                            pendingExportText = text
                            csvExportLauncher.launch("voxpen-corrections.csv")
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("匯出 CSV")
                }
            }

            state.statusMessage?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    ) {
                        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::clearStatus) { Text("關閉") }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            if (state.visibleEntries.isEmpty()) {
                Text(
                    if (state.searchQuery.isBlank()) {
                        "尚未有學習記憶。VoxPen 會從你對辨識文字的局部修正中逐步學習，也可以手動新增。"
                    } else {
                        "找不到符合的修正記憶。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.visibleEntries, key = { it.id }) { entry ->
                        CorrectionRow(entry = entry, onClick = { editingEntry = entry })
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        RuleEditorDialog(
            title = "新增修正規則",
            initialWrong = "",
            initialCorrect = "",
            initialLevel = CorrectionManualLevel.FIXED,
            initialScope = CorrectionScope.GLOBAL,
            initialPackageName = "",
            showTextFields = true,
            onDismiss = { showAddDialog = false },
            onSave = { wrong, correct, level, scope, packageName ->
                viewModel.addManualRule(wrong, correct, level, scope, packageName)
                showAddDialog = false
            },
        )
    }

    editingEntry?.let { entry ->
        RuleEditorDialog(
            title = "編輯學習記憶",
            initialWrong = entry.wrongText,
            initialCorrect = entry.correctText,
            initialLevel = entry.manualLevelOrDefault(),
            initialScope = entry.scopeOrDefault(),
            initialPackageName = entry.packageName,
            showTextFields = false,
            onDismiss = { editingEntry = null },
            onSave = { _, _, level, scope, packageName ->
                viewModel.setManualLevel(entry, level)
                viewModel.setScope(entry, scope, packageName)
                editingEntry = null
            },
            onUpgrade = {
                viewModel.upgradeToImportant(entry)
                editingEntry = null
            },
            onDelete = {
                viewModel.delete(entry)
                editingEntry = null
            },
        )
    }
}

@Composable
private fun LearningToggle(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("個人化學習", style = MaterialTheme.typography.titleMedium)
            Text(
                if (enabled) {
                    "開啟後，VoxPen 會保守地學習你對剛插入文字所做的局部修正。"
                } else {
                    "已停止自動學習；既有修正規則仍保留並可管理。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun SummaryCard(state: CorrectionMemoryUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("已學習 ${state.entries.size} 筆", style = MaterialTheme.typography.titleSmall)
            Text(
                "待觀察 ${state.pendingCount}　⭐固定 ${state.fixedCount}　停用 ${state.disabledCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CorrectionRow(
    entry: CorrectionMemoryEntity,
    onClick: () -> Unit,
) {
    val level = entry.manualLevelOrDefault()
    val confidence = CorrectionMemoryRepository.effectiveConfidence(entry, level)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "${entry.wrongText}  →  ${entry.correctText}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${levelLabel(level)}　系統 ${entry.hitCount} 次　有效信心 ${(confidence * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (entry.scopeOrDefault() == CorrectionScope.GLOBAL) {
                    "範圍：所有 App"
                } else {
                    "範圍：${entry.packageName}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "最後學習：${formatTime(entry.lastCorrectedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuleEditorDialog(
    title: String,
    initialWrong: String,
    initialCorrect: String,
    initialLevel: CorrectionManualLevel,
    initialScope: CorrectionScope,
    initialPackageName: String,
    showTextFields: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, CorrectionManualLevel, CorrectionScope, String) -> Unit,
    onUpgrade: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var wrong by remember(initialWrong) { mutableStateOf(initialWrong) }
    var correct by remember(initialCorrect) { mutableStateOf(initialCorrect) }
    var level by remember(initialLevel) { mutableStateOf(initialLevel) }
    var scope by remember(initialScope) { mutableStateOf(initialScope) }
    var packageName by remember(initialPackageName) { mutableStateOf(initialPackageName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showTextFields) {
                    OutlinedTextField(
                        value = wrong,
                        onValueChange = { wrong = it },
                        label = { Text("容易辨識成") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = correct,
                        onValueChange = { correct = it },
                        label = { Text("正確寫法") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("${initialWrong} → ${initialCorrect}", style = MaterialTheme.typography.titleSmall)
                }

                Text("人工信心設定", style = MaterialTheme.typography.labelLarge)
                CorrectionManualLevel.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { level = option },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = level == option, onClick = { level = option })
                        Text(levelLabel(option))
                    }
                }

                Text("套用範圍", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = scope == CorrectionScope.GLOBAL,
                        onClick = { scope = CorrectionScope.GLOBAL },
                        label = { Text("所有 App") },
                    )
                    FilterChip(
                        selected = scope == CorrectionScope.APP,
                        onClick = { scope = CorrectionScope.APP },
                        label = { Text("指定 App") },
                    )
                }
                if (scope == CorrectionScope.APP) {
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("App package name") },
                        placeholder = { Text("例如 com.google.android.gm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (onUpgrade != null) {
                    OutlinedButton(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
                        Text("升級為 ⭐重要詞並固定")
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("刪除此記憶", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(wrong, correct, level, scope, packageName) },
                enabled =
                    (!showTextFields || (wrong.isNotBlank() && correct.isNotBlank())) &&
                        (scope == CorrectionScope.GLOBAL || packageName.isNotBlank()),
            ) {
                Text("儲存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun levelLabel(level: CorrectionManualLevel): String =
    when (level) {
        CorrectionManualLevel.AUTO -> "自動"
        CorrectionManualLevel.LOW -> "低（只供 Gemini 參考）"
        CorrectionManualLevel.MEDIUM -> "中（依上下文）"
        CorrectionManualLevel.HIGH -> "高（直接優先修正）"
        CorrectionManualLevel.FIXED -> "⭐ 固定"
        CorrectionManualLevel.DISABLED -> "🚫 停用"
    }

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
