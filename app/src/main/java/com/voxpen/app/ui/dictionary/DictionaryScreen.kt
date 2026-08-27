package com.voxpen.app.ui.dictionary

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.voxpen.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreenContent(
    onNavigateBack: () -> Unit,
    viewModel: DictionaryViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val count by viewModel.count.collectAsState()
    val isPro by viewModel.isPro.collectAsState()
    val isLimitReached by viewModel.isLimitReached.collectAsState()
    val importantWords by viewModel.importantWords.collectAsState()
    val showDuplicate by viewModel.showDuplicateToast.collectAsState()

    var inputText by remember {
        mutableStateOf("")
    }

    var showHelp by remember {
        mutableStateOf(false)
    }

    val context = LocalContext.current
    val limit = DictionaryViewModel.FREE_DICTIONARY_LIMIT

    LaunchedEffect(showDuplicate) {
        if (showDuplicate) {
            Toast.makeText(
                context,
                context.getString(
                    R.string.dictionary_duplicate,
                ),
                Toast.LENGTH_SHORT,
            ).show()

            viewModel.dismissDuplicateToast()
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = {
                showHelp = false
            },
            title = {
                Text(
                    stringResource(
                        R.string.dictionary_title,
                    ),
                )
            },
            text = {
                Text(
                    "加入常用人名、品牌、專有名詞，可提高語音辨識與 AI 潤稿的準確度。\n\n" +
                        "點擊 ☆ 可設為 ⭐ 重要詞。\n\n" +
                        "重要詞會排在一般詞前面，優先提供給語音辨識模型。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHelp = false
                    },
                ) {
                    Text("OK")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.dictionary_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            showHelp = true
                        },
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "說明",
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
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = {
                        inputText = it
                    },
                    placeholder = {
                        Text(
                            stringResource(
                                R.string.dictionary_add_hint,
                            ),
                        )
                    },
                    modifier =
                        Modifier.weight(1f),
                    enabled =
                        !isLimitReached,
                    singleLine = true,
                )

                Button(
                    onClick = {
                        viewModel.addWord(
                            inputText,
                        )

                        inputText = ""
                    },
                    modifier =
                        Modifier.padding(
                            start = 8.dp,
                        ),
                    enabled =
                        !isLimitReached &&
                            inputText.isNotBlank(),
                ) {
                    Text(
                        stringResource(
                            R.string.dictionary_add_button,
                        ),
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(8.dp),
            )

            Text(
                text =
                    if (isPro) {
                        stringResource(
                            R.string.dictionary_count_unlimited,
                            count,
                        )
                    } else {
                        stringResource(
                            R.string.dictionary_count,
                            count,
                            limit,
                        )
                    },
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text =
                    "⭐ 重要詞：${importantWords.size} 個",
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        top = 4.dp,
                    ),
            )

            Text(
                text =
                    "重要詞會優先用於語音辨識與 AI 潤稿",
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        top = 2.dp,
                    ),
            )

            if (isLimitReached) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                vertical = 8.dp,
                            ),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.primaryContainer,
                        ),
                ) {
                    Text(
                        stringResource(
                            R.string.dictionary_upgrade,
                        ),
                        modifier =
                            Modifier.padding(
                                16.dp,
                            ),
                        style =
                            MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(8.dp),
            )

            if (entries.isEmpty()) {
                Text(
                    stringResource(
                        R.string.dictionary_empty,
                    ),
                    style =
                        MaterialTheme.typography.bodyMedium,
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            vertical = 32.dp,
                        ),
                )
            } else {
                LazyColumn {
                    items(
                        items = entries,
                        key = {
                            it.id
                        },
                    ) { entry ->
                        val isImportant =
                            entry.word in importantWords

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        vertical = 4.dp,
                                    ),
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            Text(
                                text =
                                    entry.word,
                                modifier =
                                    Modifier.weight(1f),
                                style =
                                    MaterialTheme.typography.bodyLarge,
                            )

                            IconButton(
                                onClick = {
                                    viewModel
                                        .toggleImportantWord(
                                            entry.word,
                                        )
                                },
                            ) {
                                Icon(
                                    imageVector =
                                        if (isImportant) {
                                            Icons.Filled.Star
                                        } else {
                                            Icons.Outlined.Star
                                        },
                                    contentDescription =
                                        if (isImportant) {
                                            "取消重要詞"
                                        } else {
                                            "設為重要詞"
                                        },
                                    tint =
                                        if (isImportant) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }

                            IconButton(
                                onClick = {
                                    viewModel.removeWord(
                                        entry,
                                    )
                                },
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription =
                                        "刪除",
                                    tint =
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
