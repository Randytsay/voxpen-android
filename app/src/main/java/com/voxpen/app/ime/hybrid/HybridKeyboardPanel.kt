package com.voxpen.app.ime.hybrid

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.AlertDialog
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextBoundsInfo
import android.view.inputmethod.TextBoundsInfoResult
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.voxpen.app.R
import com.voxpen.app.data.local.ClipboardEntry
import com.voxpen.app.data.local.ClipboardEntryType
import com.voxpen.app.data.local.HybridCandidate
import com.voxpen.app.data.local.HybridLexiconSource
import com.voxpen.app.data.repository.ClipboardRepository
import com.voxpen.app.data.repository.ContextPredictionText
import com.voxpen.app.data.repository.HybridInputRepository
import com.voxpen.app.data.repository.HybridLexiconImporter
import com.voxpen.app.data.repository.PinyinInputSegmentor
import com.voxpen.app.data.repository.PinyinInputToken
import com.voxpen.app.ime.EditorTextClearer
import com.voxpen.app.ime.ImePrivacyPolicy
import com.voxpen.app.ime.VoxPenIMEEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.coroutines.resume

@Suppress("TooManyFunctions")
class HybridKeyboardPanel
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        private enum class Screen {
            MAIN,
            EDIT,
            NUMERIC,
            SYMBOLS,
            EMOJI,
            CLIPBOARD,
            PHRASES,
            CANDIDATES,
        }

        private enum class BackspaceSwipeAction {
            CLEAR_BEFORE_CURSOR,
            CLEAR_AFTER_CURSOR,
        }

        private data class EmojiCategory(
            val label: String,
            val icon: String,
            val emojis: List<String>,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                VoxPenIMEEntryPoint::class.java,
            )
        private val repository: HybridInputRepository = entryPoint.hybridInputRepository()
        private val clipboardRepository: ClipboardRepository = entryPoint.clipboardRepository()
        private var ime: InputMethodService? = findImeService(context)
        private val clipboardManager = context.getSystemService(ClipboardManager::class.java)
        private val clipboardListener =
            ClipboardManager.OnPrimaryClipChangedListener {
                recordPrimaryClipboard()
            }

        private val learningTokens = ArrayDeque<LearningToken>()
        private val content = LinearLayout(context)
        private val clipboardList = LinearLayout(context)
        private val clipboardEditorContainer = LinearLayout(context)
        private val clipboardEditor = EditText(context)
        private val clipboardShortcutEditor = EditText(context)
        private val clipboardLabelEditor = EditText(context)
        private val clipboardGroupEditor = EditText(context)
        private var clipboardEntries: List<ClipboardEntry> = emptyList()
        private var phraseEntries: List<ClipboardEntry> = emptyList()
        private var phraseCategory = "全部"
        private var phraseSearchMode = false
        private var phraseQuery = ""
        private var screenBeforeNumeric = Screen.MAIN
        private var phraseSearchResults: List<ClipboardEntry> = emptyList()
        private var phraseCategoryRow: LinearLayout? = null
        private var editingClipboardEntry: ClipboardEntry? = null
        private var screen = Screen.MAIN
        private var clipboardType = ClipboardEntryType.HISTORY
        private var selectedEmojiCategory = 1
        private var selectionMode = false
        private var selectionAnchorOffset: Int? = null
        private var selectionActiveOffset: Int? = null
        private var selectionVerticalDesiredX: Float? = null
        private var verticalSelectionJob: Job? = null
        private val verticalSelectionQueue = ArrayDeque<Int>()
        private var selectionSessionGeneration = 0L
        private var boundsInfoUnsupportedConnection: InputConnection? = null
        private var composition = ""
        private var chineseMode = true
        private var capsLockEnabled = false
        private var candidates: List<HybridCandidate> = emptyList()
        private var shortcutCandidates: List<ClipboardEntry> = emptyList()
        private var expandedCandidates: List<HybridCandidate> = emptyList()
        private var expandedToneFilter: Int? = null
        private var expandedCandidatePage = 0
        private var expandedHasNextPage = false
        private var expandedCandidatesLoading = false
        private var candidatesLoading = false
        private var queryJob: Job? = null
        private var queryGeneration = 0L
        private var pendingCommitTarget: String? = null
        private var clipboardRefreshJob: Job? = null
        private var phraseRefreshJob: Job? = null
        private var lastLearningSelectionAt = 0L
        private var contextText = ""
        private var lastContextSelectionAt = 0L
        private var contextSuggestions: List<String> = emptyList()
        private var contextSuggestionsDismissed = false
        private var contextGeneration = 0L
        private var contextQueryJob: Job? = null
        private var candidateToolbar: LinearLayout? = null
        private var compositionCodeView: TextView? = null
        private var clipboardDeleteButton: TextView? = null

        init {
            orientation = VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setBackgroundColor(resourceColor(R.color.keyboard_background))
            content.orientation = VERTICAL
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
            renderScreen()
            scope.launch(Dispatchers.IO) {
                repository.ensureBootstrapLexicon()
            }
        }

        override fun onDetachedFromWindow() {
            queryJob?.cancel()
            contextQueryJob?.cancel()
            clipboardRefreshJob?.cancel()
            phraseRefreshJob?.cancel()
            cancelSelectionMovement()
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
            scope.cancel()
            super.onDetachedFromWindow()
        }

        fun resetToMain() {
            resetContextPrediction()
            phraseSearchMode = false
            phraseQuery = ""
            screen = Screen.MAIN
            selectionMode = false
            cancelSelectionMovement()
            renderScreen()
        }

        fun hasPendingComposition(): Boolean = composition.isNotBlank()

        fun showMainScreen() {
            phraseSearchMode = false
            phraseQuery = ""
            openScreen(Screen.MAIN)
        }

        fun showEditScreen() {
            openScreen(Screen.EDIT)
        }

        fun toggleEditScreen() {
            openScreen(if (screen == Screen.EDIT) Screen.MAIN else Screen.EDIT)
        }

        fun showNumericScreen() {
            screenBeforeNumeric = screen
            openScreen(Screen.NUMERIC)
        }

        fun showSymbolsScreen() {
            openScreen(Screen.SYMBOLS)
        }

        fun showDictionaryScreen() {
            openDictionaryManager()
        }

        fun toggleEmojiScreen() {
            openScreen(if (screen == Screen.EMOJI) Screen.MAIN else Screen.EMOJI)
        }

        fun togglePhraseScreen() {
            phraseSearchMode = false
            if (screen == Screen.PHRASES) {
                openScreen(Screen.MAIN)
            } else {
                composition = ""
                openScreen(Screen.PHRASES)
                refreshPhraseEntries()
            }
        }

        fun attachCandidateToolbar(
            toolbar: LinearLayout?,
            compositionCode: TextView?,
        ) {
            candidateToolbar = toolbar
            compositionCodeView = compositionCode
            renderCandidates()
        }

        fun attachInputMethodService(service: InputMethodService) {
            ime = service
        }

        private fun renderScreen() {
            content.removeAllViews()
            phraseGrid = null
            phraseCardScroll = null
            content.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                if (screen == Screen.PHRASES) (resources.displayMetrics.heightPixels * 0.48f).toInt()
                else LayoutParams.WRAP_CONTENT,
            )
            when (screen) {
                Screen.MAIN -> buildMainScreen()
                Screen.EDIT -> buildEditScreen()
                Screen.NUMERIC -> buildNumericScreen()
                Screen.SYMBOLS -> buildSymbolsScreen()
                Screen.EMOJI -> buildEmojiScreen()
                Screen.CLIPBOARD -> buildClipboardScreen()
                Screen.PHRASES -> buildPhraseScreen()
                Screen.CANDIDATES -> buildExpandedCandidatesScreen()
            }
            renderCandidates()
        }

        private fun buildMainScreen() {
            addNumberRow()
            addLetterRow("qwertyuiop")
            addLetterRow("asdfghjkl", horizontalPadding = dp(14))
            addBottomLetterRow()
            addMainBottomRow()
            renderCandidates()
        }

        private fun addNumberRow() {
            val row = newRow(MAIN_NUMBER_ROW_HEIGHT_DP)
            "1234567890".forEach { value ->
                row.addView(actionKey(value.toString(), 1f) { commitNumber(value) })
            }
            content.addView(row)
        }

        private fun addBottomLetterRow() {
            val row =
                newRow(MAIN_LETTER_ROW_HEIGHT_DP).apply {
                    setPadding(dp(2), 0, dp(2), 0)
                }
            row.addView(shiftKey(1.0f))
            "zxcvbnm".forEach { letter ->
                val label =
                    if (chineseMode || capsLockEnabled) {
                        letter.uppercaseChar().toString()
                    } else {
                        letter.toString()
                    }
                row.addView(
                    secondaryLetterKey(label, SECONDARY_SYMBOLS.getValue(letter)) { handleLetter(letter) },
                    weightedParams(1f),
                )
            }
            row.addView(backspaceKeyView(1.1f))
            content.addView(row)
        }

        private fun addMainBottomRow() {
            val bottom = newRow(MAIN_BOTTOM_ROW_HEIGHT_DP)
            bottom.addView(numberAndSymbolsKey(1.25f))
            bottom.addView(actionKey("，", 0.8f) { commitPunctuation(",") })
            bottom.addView(actionKey("空白", 3.8f) { handleSpace() })
            bottom.addView(actionKey("。", 0.8f) { commitPunctuation(".") })
            bottom.addView(actionKey(if (chineseMode) "中" else "EN", 0.9f) { toggleMode() })
            bottom.addView(enterKey(1.1f))
            content.addView(bottom)
        }

        private fun numberAndSymbolsKey(weight: Float): LinearLayout =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundColor(resourceColor(R.color.key_background))
                layoutParams = weightedParams(weight)

                addView(
                    TextView(context).apply {
                        text = "123"
                        gravity = Gravity.CENTER
                        textSize = 14f
                        setTextColor(resourceColor(R.color.key_text))
                        contentDescription = "數字鍵盤"
                        isFocusable = true
                        setOnClickListener { showNumericScreen() }
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
                )
                addView(View(context).apply { setBackgroundColor(0x558C8B91) }, LayoutParams(dp(1), dp(24)))
                addView(
                    TextView(context).apply {
                        text = "符號"
                        gravity = Gravity.CENTER
                        textSize = 9f
                        setTextColor(resourceColor(R.color.key_text))
                        contentDescription = "開啟符號鍵盤"
                        isFocusable = true
                        setOnClickListener { showSymbolsScreen() }
                    },
                    LayoutParams(dp(24), LayoutParams.MATCH_PARENT),
                )
            }

        private fun commitNumber(value: Char) {
            if (phraseSearchMode) {
                if (composition.isNotEmpty()) {
                    composition += value
                    refreshCandidates()
                } else {
                    phraseQuery += value
                }
                renderPhraseSearchCandidates()
                renderCandidates()
                return
            }
            if (composition.isNotEmpty()) commitRawComposition()
            resetContextPrediction()
            commitText(value.toString())
        }

        private fun shiftKey(weight: Float): ImageView =
            ImageView(context).apply {
                setImageResource(if (capsLockEnabled) R.drawable.shift_key_filled else R.drawable.shift_key_outline)
                setBackgroundColor(resourceColor(R.color.key_background))
                contentDescription = if (capsLockEnabled) "關閉大寫鎖定" else "開啟大寫鎖定"
                isFocusable = true
                layoutParams = weightedParams(weight)
                setOnClickListener { toggleCapsLock() }
            }

        private fun toggleCapsLock() {
            val toggle = {
                capsLockEnabled = !capsLockEnabled
                resetContextPrediction()
                renderScreen()
            }
            if (chineseMode && !capsLockEnabled && composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate(toggle)
            } else {
                toggle()
            }
        }

        private fun addLetterRow(
            letters: String,
            horizontalPadding: Int = 0,
        ) {
            val row =
                newRow(MAIN_LETTER_ROW_HEIGHT_DP).apply {
                    setPadding(horizontalPadding, 0, horizontalPadding, 0)
                }
            letters.forEach { letter ->
                val label =
                    if (chineseMode || capsLockEnabled) {
                        letter.uppercaseChar().toString()
                    } else {
                        letter.toString()
                    }
                val key = secondaryLetterKey(label, SECONDARY_SYMBOLS.getValue(letter)) { handleLetter(letter) }
                row.addView(key, weightedParams(1f))
            }
            content.addView(row)
        }

        private fun buildEditScreen() {
            buildMainScreen()

            val keyboardBackground =
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    alpha = 0.22f
                    while (content.childCount > 0) {
                        val child = content.getChildAt(0)
                        content.removeViewAt(0)
                        addView(child)
                    }
                }
            val availableKeyboardWidth =
                (width - paddingLeft - paddingRight)
                    .takeIf { it > 0 }
                    ?: (resources.displayMetrics.widthPixels - paddingLeft - paddingRight).coerceAtLeast(1)
            keyboardBackground.measure(
                View.MeasureSpec.makeMeasureSpec(availableKeyboardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val keyboardContentHeight = keyboardBackground.measuredHeight.coerceAtLeast(dp(MAIN_KEY_ROW_HEIGHT_DP))
            val frame = FrameLayout(context)
            frame.addView(
                keyboardBackground,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, keyboardContentHeight),
            )
            frame.addView(
                View(context).apply {
                    setBackgroundColor(0xE51B1B1F.toInt())
                    isClickable = true
                    isFocusable = true
                },
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )

            val controls =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
            val cursorColumn = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
            }
            cursorColumn.addView(
                buildCursorPad(),
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { gravity = Gravity.CENTER_HORIZONTAL },
            )
            val navigation = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            navigation.addView(View(context), LayoutParams(0, 1, 1f))
            navigation.addView(editCursorKey("⇤", "選取到文字開頭", ::selectToStart))
            navigation.addView(View(context), LayoutParams(0, 1, 2f))
            navigation.addView(editCursorKey("⇥", "選取到文字結尾", ::selectToEnd))
            navigation.addView(View(context), LayoutParams(0, 1, 1f))
            cursorColumn.addView(navigation, LayoutParams(LayoutParams.MATCH_PARENT, dp(60)))
            controls.addView(cursorColumn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

            val actions = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
            }
            addEditActionRow(actions, "全選", "全選文字", ::selectAll, "⌫", "刪除文字", ::deleteSelectionOrPrevious)
            addEditActionRow(actions, "複製", "複製選取文字", ::copySelection, "↵", "插入換行", ::insertNewline)
            addEditActionRow(actions, "貼上", "貼上剪貼簿內容", ::pasteSelection, "剪貼板", "開啟剪貼板", { openScreen(Screen.CLIPBOARD) })
            controls.addView(actions, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            frame.addView(
                controls,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            // Keep the editor overlay inside the keyboard's measured bounds. MATCH_PARENT here
            // must not inherit the IME window's full available height on devices that measure
            // the input view with an AT_MOST/full-window constraint.
            content.addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, keyboardContentHeight))
            selectionMode = false
            selectionAnchorOffset = null
            selectionActiveOffset = null
            cancelSelectionMovement()
        }

        private fun buildCursorPad(): FrameLayout {
            val pad =
                object : FrameLayout(context) {
                    override fun onMeasure(
                        widthMeasureSpec: Int,
                        heightMeasureSpec: Int,
                    ) {
                        val diameter =
                            minOf(
                                View.MeasureSpec.getSize(widthMeasureSpec),
                                View.MeasureSpec.getSize(heightMeasureSpec),
                            )
                        val squareSpec = View.MeasureSpec.makeMeasureSpec(diameter, View.MeasureSpec.EXACTLY)
                        super.onMeasure(squareSpec, squareSpec)
                    }
                }.apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(resourceColor(R.color.key_background))
                            setStroke(dp(1), 0x88FFFFFF.toInt())
                        }
                }
            addPadButton(
                pad,
                "↑",
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ) { moveCursor(KeyEvent.KEYCODE_DPAD_UP) }
            addPadButton(
                pad,
                "↓",
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ) { moveCursor(KeyEvent.KEYCODE_DPAD_DOWN) }
            addPadButton(
                pad,
                "←",
                Gravity.CENTER_VERTICAL or Gravity.START,
            ) { moveCursor(KeyEvent.KEYCODE_DPAD_LEFT) }
            addPadButton(
                pad,
                "→",
                Gravity.CENTER_VERTICAL or Gravity.END,
            ) { moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT) }
            val center =
                keyView("選擇", 14f) { view ->
                    selectionMode = !selectionMode
                    cancelSelectionMovement()
                    selectionAnchorOffset = null
                    selectionActiveOffset = null
                    (view as? TextView)?.apply {
                        text = if (selectionMode) "選取中" else "選擇"
                        contentDescription = if (selectionMode) "選取模式已開啟" else "開啟選取模式"
                    }
                }.apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0xFF424047.toInt())
                            setStroke(dp(1), 0x88FFFFFF.toInt())
                        }
                }
            pad.addView(center, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            return pad
        }

        private fun editCursorKey(
            label: String,
            description: String,
            action: () -> Unit,
        ): TextView =
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(resourceColor(R.color.key_text))
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x14FFFFFF)
                        setStroke(dp(1), 0x777E7E82.toInt())
                    }
                contentDescription = description
                isFocusable = true
                setOnClickListener { action() }
                layoutParams = LayoutParams(dp(48), dp(48))
            }

        private fun addPadButton(
            pad: FrameLayout,
            label: String,
            gravity: Int,
            action: () -> Unit,
        ) {
            val button = keyView(label, 26f) { action() }
            button.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            pad.addView(button, FrameLayout.LayoutParams(dp(58), dp(48), gravity))
        }

        private fun addEditActionRow(
            parent: LinearLayout,
            firstLabel: String,
            firstDescription: String,
            firstAction: () -> Unit,
            secondLabel: String,
            secondDescription: String,
            secondAction: () -> Unit,
        ) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }
            row.addView(editActionKey(firstLabel, firstDescription, firstAction), weightedParams(1f))
            row.addView(editActionKey(secondLabel, secondDescription, secondAction), weightedParams(1f))
            parent.addView(row)
        }

        private fun editActionKey(
            label: String,
            description: String,
            action: () -> Unit,
        ): TextView =
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = if (label.length > 3) 17f else 23f
                setTextColor(resourceColor(R.color.key_text))
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(0x14FFFFFF)
                        setStroke(dp(1), 0x777E7E82.toInt())
                    }
                contentDescription = description
                isFocusable = true
                setOnClickListener { action() }
            }

        private fun deleteSelectionOrPrevious() {
            val connection = ime?.currentInputConnection ?: return
            val selectedText = connection.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                connection.commitText("", 1)
            } else {
                connection.deleteSurroundingTextInCodePoints(1, 0)
            }
        }

        private fun buildNumericScreen() {
            val body = newRow(NUMERIC_BODY_HEIGHT_DP)
            val operators = LinearLayout(context).apply { orientation = VERTICAL }
            listOf("+", "-", "*", "/", "=", "%").forEach { symbol ->
                operators.addView(
                actionKey(symbol, 1f) { commitPunctuation(symbol) }.apply {
                        layoutParams = verticalWeightedParams(1f)
                    },
                )
            }
            body.addView(operators, LayoutParams(dp(58), LayoutParams.MATCH_PARENT))

            val numbers = LinearLayout(context).apply { orientation = VERTICAL }
            val rowValues =
                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                )
            rowValues.forEachIndexed { index, values ->
                val row = newWeightedNumericRow()
                values.forEach { value -> row.addView(actionKey(value, 1f) { commitNumber(value.first()) }) }
                when (index) {
                    0 -> row.addView(backspaceKeyView(1f, 20f))
                    1 -> row.addView(actionKey(".", 1f) { commitPunctuation(".") })
                    else -> row.addView(actionKey(",", 1f) { commitPunctuation(",") })
                }
                numbers.addView(row)
            }

            val last = newWeightedNumericRow()
            last.addView(
                actionKey("返回", 1f) { openScreen(screenBeforeNumeric) }.apply {
                    contentDescription = "返回主鍵盤"
                },
            )
            last.addView(actionKey("0", 1f) { commitNumber('0') })
            last.addView(actionKey("空白", 1f) { handleSpace() })
            last.addView(enterKey(1f))
            numbers.addView(last)
            body.addView(numbers, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            content.addView(body)
        }

        private fun newWeightedNumericRow(): LinearLayout =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }

        private fun buildSymbolsScreen() {
            content.addView(screenHeader("符號與數學") { openScreen(Screen.MAIN) })
            val categoryRow = newRow(40)
            SYMBOL_CATEGORIES.forEach { (label, values) ->
                categoryRow.addView(actionKey(label, 1f) { buildSymbolGrid(values) })
            }
            content.addView(categoryRow)
            val grid = LinearLayout(context).apply { orientation = VERTICAL }
            content.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, dp(210)))
            buildSymbolGrid(SYMBOL_CATEGORIES.first().second, grid)
        }

        private fun buildSymbolGrid(
            symbols: String,
            target: LinearLayout? = null,
        ) {
            val grid = target ?: content.getChildAt(content.childCount - 1) as? LinearLayout ?: return
            grid.removeAllViews()
            symbols.chunked(SYMBOLS_PER_ROW).forEach { rowSymbols ->
                val row = newRow(42)
                rowSymbols.forEach { symbol ->
                    row.addView(actionKey(symbol.toString(), 1f) { commitPunctuation(symbol.toString()) })
                }
                grid.addView(row)
            }
        }

        private fun buildEmojiScreen() {
            val category = EMOJI_CATEGORIES[selectedEmojiCategory.coerceIn(0, EMOJI_CATEGORIES.lastIndex)]

            val emojiGrid = LinearLayout(context).apply { orientation = VERTICAL }
            val emojis = if (selectedEmojiCategory == 0) loadRecentEmojis() else category.emojis
            if (emojis.isEmpty()) {
                emojiGrid.addView(
                    TextView(context).apply {
                        text = "選取過的表情會顯示在這裡"
                        gravity = Gravity.CENTER
                        textSize = 14f
                        setTextColor(0x99FFFFFF.toInt())
                    },
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(EMOJI_GRID_HEIGHT_DP)),
                )
            } else {
                emojis.chunked(EMOJI_COLUMNS).forEach { emojiRow ->
                    val row = newRow(EMOJI_ROW_HEIGHT_DP)
                    emojiRow.forEach { emoji ->
                        val key =
                            TextView(context).apply {
                                text = emoji
                                textSize = 25f
                                gravity = Gravity.CENTER
                                setBackgroundColor(resourceColor(R.color.key_background))
                                contentDescription = emoji
                                isFocusable = true
                                setOnClickListener {
                                    if (commitText(emoji)) recordRecentEmoji(emoji)
                                }
                            }
                        row.addView(key, weightedParams(1f))
                    }
                    repeat(EMOJI_COLUMNS - emojiRow.size) {
                        val spacer = View(context).apply { alpha = 0f }
                        row.addView(spacer, weightedParams(1f))
                    }
                    emojiGrid.addView(row)
                }
            }

            content.addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    addView(emojiGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(EMOJI_GRID_HEIGHT_DP)),
            )

            val categoryRow =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            val categoryScroller =
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(categoryRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
                }
            EMOJI_CATEGORIES.forEachIndexed { index, item ->
                val tab =
                    TextView(context).apply {
                        text = "${item.icon} ${item.label}"
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(resourceColor(R.color.key_text))
                        setPadding(dp(9), 0, dp(9), 0)
                        val backgroundColor =
                            if (selectedEmojiCategory == index) {
                                0xFF393846.toInt()
                            } else {
                                resourceColor(R.color.key_background)
                            }
                        setBackgroundColor(backgroundColor)
                        isFocusable = true
                        setOnClickListener {
                            selectedEmojiCategory = index
                            renderScreen()
                        }
                    }
                categoryRow.addView(
                    tab,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }
            content.addView(categoryScroller, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
        }

        private fun loadRecentEmojis(): List<String> =
            context.getSharedPreferences(EMOJI_PREFERENCES, Context.MODE_PRIVATE)
                .getString(EMOJI_RECENTS_KEY, null)
                ?.split(EMOJI_RECENTS_SEPARATOR)
                ?.filter(String::isNotBlank)
                .orEmpty()

        private fun recordRecentEmoji(emoji: String) {
            val updated = (listOf(emoji) + loadRecentEmojis().filterNot { it == emoji }).take(MAX_RECENT_EMOJIS)
            context.getSharedPreferences(EMOJI_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(EMOJI_RECENTS_KEY, updated.joinToString(EMOJI_RECENTS_SEPARATOR))
                .apply()
        }

        private fun buildClipboardScreen() {
            (clipboardList.parent as? android.view.ViewGroup)?.removeView(clipboardList)
            clipboardEditorContainer.removeAllViews()
            content.addView(screenHeader("剪貼板") { openScreen(Screen.EDIT) })
            val tabs = newRow(40)
            listOf(
                ClipboardEntryType.HISTORY to "歷史",
                ClipboardEntryType.COMMON to "常用語",
                ClipboardEntryType.SYMBOL to "自訂符號",
            ).forEach { (type, label) ->
                tabs.addView(
                    actionKey(if (type == clipboardType) "▌$label" else label, 1f) {
                        if (type == ClipboardEntryType.COMMON) {
                            launchPhraseManager()
                        } else {
                            clipboardType = type
                            renderScreen()
                        }
                    },
                )
            }
            content.addView(tabs)

            clipboardEditorContainer.apply {
                orientation = VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(2))
                visibility = View.GONE
            }
            clipboardEditor.apply {
                setTextColor(resourceColor(R.color.key_text))
                setHintTextColor(0x99FFFFFF.toInt())
                hint = "輸入要保存的文字或符號"
                setSingleLine(false)
                minLines = 2
                maxLines = 3
            }
            clipboardEditorContainer.addView(clipboardEditor, LayoutParams(LayoutParams.MATCH_PARENT, dp(58)))
            clipboardLabelEditor.apply {
                setTextColor(resourceColor(R.color.key_text))
                setHintTextColor(0x99FFFFFF.toInt())
                hint = "片語名稱（例如：工作 Email）"
                setSingleLine(true)
                visibility = if (clipboardType == ClipboardEntryType.COMMON) View.VISIBLE else View.GONE
            }
            clipboardEditorContainer.addView(clipboardLabelEditor, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
            clipboardGroupEditor.apply {
                setTextColor(resourceColor(R.color.key_text))
                setHintTextColor(0x99FFFFFF.toInt())
                hint = "分類（Email、連結、匯款、地址、工作…）"
                setSingleLine(true)
                visibility = if (clipboardType == ClipboardEntryType.COMMON) View.VISIBLE else View.GONE
            }
            clipboardEditorContainer.addView(clipboardGroupEditor, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
            clipboardShortcutEditor.apply {
                setTextColor(resourceColor(R.color.key_text))
                setHintTextColor(0x99FFFFFF.toInt())
                hint = "快捷碼（選填，例如 mm）"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true)
                visibility = if (clipboardType == ClipboardEntryType.COMMON) View.VISIBLE else View.GONE
            }
            clipboardEditorContainer.addView(
                clipboardShortcutEditor,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(42)),
            )
            val editorActions = newRow(42)
            editorActions.addView(actionKey("儲存", 1f) { saveClipboardEditor() })
            clipboardDeleteButton = actionKey("刪除", 1f) { deleteClipboardEditor() }
            clipboardDeleteButton?.visibility = View.GONE
            clipboardDeleteButton?.let { editorActions.addView(it) }
            editorActions.addView(actionKey("取消", 1f) { hideClipboardEditor() })
            clipboardEditorContainer.addView(editorActions)
            content.addView(clipboardEditorContainer)

            if (clipboardType != ClipboardEntryType.HISTORY) {
                content.addView(
                    actionKey("＋新增${if (clipboardType == ClipboardEntryType.COMMON) "常用語" else "符號"}", 1f) {
                        showClipboardEditor(null)
                    },
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(40)),
                )
            }

            val scroll =
                ScrollView(context).apply {
                    isFillViewport = true
                    addView(clipboardList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                }
            content.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(190)))
            refreshClipboardEntries()
        }

        private fun buildPhraseScreen() {
            content.addView(
                newRow(PHRASE_SEARCH_ROW_HEIGHT_DP).apply {
                    addView(
                        TextView(context).apply {
                            text = if (phraseQuery.isBlank()) "⌕  搜尋名稱、內容或快捷碼" else "⌕  $phraseQuery"
                            textSize = 13f
                            gravity = Gravity.CENTER_VERTICAL
                            setSingleLine(true)
                            ellipsize = TextUtils.TruncateAt.END
                            setTextColor(0xD9FFFFFF.toInt())
                            setPadding(dp(10), 0, dp(6), 0)
                            background = roundedKeyBackground(0xFF292A30.toInt())
                            contentDescription = "搜尋片語"
                            setOnClickListener { beginPhraseSearch() }
                        },
                        LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                            setMargins(dp(2), dp(1), dp(2), dp(1))
                        },
                    )
                    addView(actionKey("☷", 0.75f) { launchPhraseManager() }.apply {
                        contentDescription = "管理片語"
                        layoutParams = LayoutParams(dp(44), LayoutParams.MATCH_PARENT)
                    })
                    addView(actionKey("⌨", 0.6f) { showMainScreen() }.apply {
                        contentDescription = "返回主鍵盤"
                        layoutParams = LayoutParams(dp(44), LayoutParams.MATCH_PARENT)
                    })
                },
            )
            val categoryScroller =
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                }
            val categoryRow = LinearLayout(context).apply { orientation = HORIZONTAL }
            phraseCategoryRow = categoryRow
            categoryScroller.addView(categoryRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            renderPhraseCategoryItems()
            content.addView(categoryScroller, LayoutParams(LayoutParams.MATCH_PARENT, dp(PHRASE_CATEGORY_ROW_HEIGHT_DP)))
            val grid = LinearLayout(context).apply { orientation = VERTICAL }
            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                isFillViewport = true
                addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            phraseGrid = grid
            phraseCardScroll = scroll
            content.addView(
                scroll,
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
            )
            renderPhraseCards()
            refreshPhraseEntries()
        }

        private fun renderPhraseCategoryItems() {
            val categoryRow = phraseCategoryRow ?: return
            categoryRow.removeAllViews()
            val categories =
                (listOf("全部", "最近", "常用", "收藏") + DEFAULT_PHRASE_CATEGORIES +
                    phraseEntries.map { it.groupName.trim() })
                    .filter(String::isNotBlank)
                    .distinct()
            categories.forEach { category ->
                val chip = TextView(context).apply {
                    text = category
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(dp(9), 0, dp(9), 0)
                    setTextColor(resourceColor(R.color.key_text))
                    background = roundedKeyBackground(
                        if (phraseCategory == category) 0xFF4E67E8.toInt() else 0xFF292A30.toInt(),
                    )
                    isFocusable = true
                    setOnClickListener {
                        phraseCategory = category
                        renderPhraseCategoryItems()
                        renderPhraseCards()
                    }
                }
                categoryRow.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                    setMargins(dp(2), dp(1), dp(2), dp(1))
                })
            }
        }

        private fun beginPhraseSearch() {
            phraseSearchMode = true
            composition = ""
            candidates = emptyList()
            shortcutCandidates = emptyList()
            candidatesLoading = false
            screen = Screen.MAIN
            resetContextPrediction()
            renderScreen()
            renderPhraseSearchCandidates()
            renderCandidates()
        }

        private fun finishPhraseSearch() {
            if (composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate()
                return
            }
            phraseSearchMode = false
            screen = Screen.PHRASES
            renderScreen()
            renderCandidates()
        }

        private fun launchPhraseManager(
            entry: ClipboardEntry? = null,
            openEditor: Boolean = false,
        ) {
            phraseSearchMode = false
            screen = Screen.MAIN
            resetContextPrediction()
            clearComposition()
            renderScreen()
            val intent =
                Intent(context, PhraseManagerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (entry != null) intent.putExtra(PhraseManagerActivity.EXTRA_ENTRY_ID, entry.id)
            if (openEditor) intent.putExtra(PhraseManagerActivity.EXTRA_OPEN_EDITOR, true)
            context.startActivity(intent)
        }

        private var phraseGrid: LinearLayout? = null
        private var phraseCardScroll: ScrollView? = null

        private fun refreshPhraseEntries() {
            phraseRefreshJob?.cancel()
            phraseRefreshJob = scope.launch {
                val entries = withContext(Dispatchers.IO) {
                    clipboardRepository.getEntries(ClipboardEntryType.COMMON)
                }
                phraseEntries = entries
                if (screen == Screen.PHRASES) {
                    renderPhraseCategoryItems()
                    renderPhraseCards()
                }
                if (phraseSearchMode) {
                    renderPhraseSearchCandidates()
                    renderCandidates()
                }
            }
        }

        private fun renderPhraseCards() {
            val grid = phraseGrid ?: return
            grid.removeAllViews()
            val filtered = filteredPhraseEntries()
            if (filtered.isEmpty()) {
                grid.addView(TextView(context).apply {
                    text = if (phraseEntries.isEmpty()) "尚無片語，按右上角管理圖示新增" else "沒有符合的片語"
                    gravity = Gravity.CENTER
                    setTextColor(0x99FFFFFF.toInt())
                    textSize = 13f
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                })
                return
            }
            filtered.chunked(2).forEach { rowEntries ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                rowEntries.forEach { entry ->
                    val card = LinearLayout(context).apply {
                        orientation = VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(3), dp(5), dp(3))
                        setOnClickListener { pasteClipboardEntry(entry) }
                        setOnLongClickListener { showPhraseActions(card = this, entry = entry); true }
                    }
                    val titleRow = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    titleRow.addView(TextView(context).apply {
                        text = entry.label.ifBlank { entry.text.take(12) }
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(resourceColor(R.color.key_text))
                    }, LayoutParams(0, dp(24), 1f))
                    titleRow.addView(TextView(context).apply {
                        text = if (entry.isFavorite) "★" else "☆"
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setTextColor(if (entry.isFavorite) 0xFFFFD54F.toInt() else 0xFFBFC1C9.toInt())
                        contentDescription = if (entry.isFavorite) "取消收藏" else "加入收藏"
                        setOnClickListener { togglePhraseFavorite(entry) }
                    }, LayoutParams(dp(22), dp(24)))
                    titleRow.addView(TextView(context).apply {
                        text = "⋮"
                        textSize = 20f
                        gravity = Gravity.CENTER
                        contentDescription = "${entry.label}的片語操作"
                        setOnClickListener { showPhraseActions(this, entry) }
                    }, LayoutParams(dp(22), dp(24)))
                    card.addView(titleRow)
                    card.addView(TextView(context).apply {
                        val shortcutText = entry.shortcut.takeIf(String::isNotBlank)?.let { "　· $it" }.orEmpty()
                        text = phrasePreview(entry) + shortcutText
                        textSize = 11f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(0xBFFFFFFF.toInt())
                    })
                    row.addView(card, LayoutParams(0, dp(PHRASE_CARD_ROW_HEIGHT_DP - 4), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                }
                if (rowEntries.size == 1) {
                    row.addView(View(context), LayoutParams(0, dp(PHRASE_CARD_ROW_HEIGHT_DP - 4), 1f))
                }
                grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(PHRASE_CARD_ROW_HEIGHT_DP)))
                grid.addView(View(context).apply {
                    setBackgroundColor(0x26FFFFFF)
                }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
            }
        }

        private fun filteredPhraseEntries(): List<ClipboardEntry> {
            val query = phraseQuery.trim()
            val matching = phraseEntries.filter { entry ->
                query.isBlank() || entry.label.contains(query, true) || entry.text.contains(query, true) ||
                    entry.shortcut.contains(query, true)
            }
            return when (phraseCategory) {
                "最近" -> matching.filter { it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }
                "常用" -> matching.sortedWith(
                    compareByDescending<ClipboardEntry> { it.usageCount }.thenByDescending { it.lastUsedAt },
                )
                "收藏" -> matching.filter { it.isFavorite }.sortedWith(
                    compareByDescending<ClipboardEntry> { it.isPinned }.thenByDescending { it.lastUsedAt },
                )
                "全部" -> matching.sortedWith(
                    compareByDescending<ClipboardEntry> { it.isPinned }
                        .thenByDescending { it.isFavorite }
                        .thenByDescending { it.usageCount }
                        .thenByDescending { it.lastUsedAt }
                        .thenByDescending { it.updatedAt },
                )
                else -> matching.filter { it.groupName.equals(phraseCategory, true) }
                    .sortedWith(compareByDescending<ClipboardEntry> { it.isPinned }
                        .thenByDescending { it.isFavorite }
                        .thenByDescending { it.usageCount }
                        .thenByDescending { it.lastUsedAt })
            }
        }

        private fun phrasePreview(entry: ClipboardEntry): String {
            val preview = entry.text.replace('\n', ' ').trim()
            if (entry.groupName.contains("密碼", true) || entry.label.contains("password", true)) return "••••••••"
            if (preview.contains('@')) {
                val at = preview.indexOf('@')
                val local = preview.substring(0, at)
                val domain = preview.substring(at + 1)
                return "${local.take(2)}***@$domain".take(25)
            }
            if (listOf("匯款", "帳號", "銀行", "金融").any { entry.groupName.contains(it) }) {
                val digits = preview.filter(Char::isDigit)
                if (digits.length >= 7) return "•••• ${digits.takeLast(4)}"
            }
            return preview.take(25)
        }

        private fun openPhraseEditor(entry: ClipboardEntry? = null) {
            launchPhraseManager(entry, openEditor = entry == null)
        }

        private fun togglePhraseFavorite(entry: ClipboardEntry) {
            scope.launch(Dispatchers.IO) {
                clipboardRepository.setFavorite(entry, !entry.isFavorite)
                withContext(Dispatchers.Main) { refreshPhraseEntries() }
            }
        }

        private fun togglePhrasePinned(entry: ClipboardEntry) {
            scope.launch(Dispatchers.IO) {
                clipboardRepository.setPinned(entry, !entry.isPinned)
                withContext(Dispatchers.Main) { refreshPhraseEntries() }
            }
        }

        private fun showPhraseActions(
            card: View,
            entry: ClipboardEntry,
        ) {
            PopupMenu(context, card).apply {
                menu.add("編輯片語").setOnMenuItemClickListener {
                    launchPhraseManager(entry)
                    true
                }
                menu.add("移動分類").setOnMenuItemClickListener {
                    showPhraseCategoryPicker(entry)
                    true
                }
                menu.add(if (entry.isPinned) "取消置頂" else "置頂").setOnMenuItemClickListener {
                    togglePhrasePinned(entry)
                    true
                }
                menu.add(if (entry.isFavorite) "取消收藏" else "加入收藏").setOnMenuItemClickListener {
                    togglePhraseFavorite(entry)
                    true
                }
                menu.add("刪除").setOnMenuItemClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("刪除片語？")
                        .setMessage("「${entry.label.ifBlank { entry.text.take(24) }}」將從自訂片語中移除。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("刪除") { _, _ ->
                            scope.launch(Dispatchers.IO) {
                                clipboardRepository.delete(entry)
                                withContext(Dispatchers.Main) { refreshPhraseEntries() }
                            }
                        }
                        .show()
                    true
                }
                show()
            }
        }

        private fun showPhraseCategoryPicker(entry: ClipboardEntry) {
            val categories =
                (DEFAULT_PHRASE_CATEGORIES + phraseEntries.map { it.groupName.trim() })
                    .filter(String::isNotBlank)
                    .distinct()
            val selectedIndex = categories.indexOf(entry.groupName).coerceAtLeast(0)
            AlertDialog.Builder(context)
                .setTitle("移動到分類")
                .setSingleChoiceItems(categories.toTypedArray(), selectedIndex) { dialog, which ->
                    val selected = categories[which]
                    dialog.dismiss()
                    scope.launch(Dispatchers.IO) {
                        clipboardRepository.update(entry, entry.text, entry.shortcut, selected, entry.label)
                        withContext(Dispatchers.Main) { refreshPhraseEntries() }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun refreshClipboardEntries() {
            clipboardRefreshJob?.cancel()
            clipboardRefreshJob =
                scope.launch {
                    val entries =
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            clipboardRepository.getEntries(clipboardType)
                        }
                    if (screen == Screen.CLIPBOARD) {
                        clipboardEntries = entries
                        renderClipboardEntries()
                    }
                }
        }

        private fun renderClipboardEntries() {
            clipboardList.removeAllViews()
            if (clipboardEntries.isEmpty()) {
                clipboardList.addView(
                    TextView(context).apply {
                        text =
                            when (clipboardType) {
                                ClipboardEntryType.HISTORY -> "尚無剪貼板歷史"
                                ClipboardEntryType.COMMON -> "尚無常用語，請按＋新增"
                                ClipboardEntryType.SYMBOL -> "尚無自訂符號，請按＋新增"
                            }
                        setTextColor(0x99FFFFFF.toInt())
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(18), dp(8), dp(18))
                    },
                )
                return
            }
            clipboardEntries.forEach { entry ->
                val row = newRow(52)
                val text =
                    TextView(context).apply {
                        text =
                            if (entry.shortcut.isBlank()) {
                                entry.text
                            } else {
                                "${entry.text}\n快捷碼：${entry.shortcut}"
                            }
                        textSize = 15f
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(resourceColor(R.color.key_text))
                        setPadding(dp(8), 0, dp(8), 0)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setOnClickListener { pasteClipboardEntry(entry) }
                        setOnLongClickListener {
                            showClipboardEditor(entry)
                            true
                        }
                    }
                row.addView(text, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                row.addView(actionKey("貼上", 0.9f) { pasteClipboardEntry(entry) })
                row.addView(
                    actionKey(if (entry.isPinned) "★" else "☆", 0.55f) {
                        scope.launch(Dispatchers.IO) {
                            clipboardRepository.setPinned(entry, !entry.isPinned)
                            refreshClipboardEntries()
                        }
                    },
                )
                row.addView(actionKey("⋮", 0.55f) { showClipboardEditor(entry) })
                clipboardList.addView(row)
            }
        }

        private fun showClipboardEditor(entry: ClipboardEntry?) {
            if (entry == null && clipboardType == ClipboardEntryType.HISTORY) return
            editingClipboardEntry = entry
            clipboardEditor.setText(entry?.text.orEmpty())
            clipboardEditor.setSelection(clipboardEditor.length())
            clipboardShortcutEditor.setText(entry?.shortcut.orEmpty())
            clipboardLabelEditor.setText(entry?.label.orEmpty())
            clipboardGroupEditor.setText(entry?.groupName.orEmpty())
            clipboardDeleteButton?.visibility = if (entry == null) View.GONE else View.VISIBLE
            clipboardEditorContainer.visibility = View.VISIBLE
            clipboardEditor.requestFocus()
        }

        private fun hideClipboardEditor() {
            editingClipboardEntry = null
            clipboardDeleteButton?.visibility = View.GONE
            clipboardEditorContainer.visibility = View.GONE
            clipboardEditor.text?.clear()
            clipboardShortcutEditor.text?.clear()
            clipboardLabelEditor.text?.clear()
            clipboardGroupEditor.text?.clear()
        }

        private fun saveClipboardEditor() {
            val text = clipboardEditor.text?.toString().orEmpty()
            val shortcut = clipboardShortcutEditor.text?.toString().orEmpty()
            val label = clipboardLabelEditor.text?.toString().orEmpty()
            val groupName = clipboardGroupEditor.text?.toString().orEmpty()
            val entry = editingClipboardEntry
            scope.launch(Dispatchers.IO) {
                val saved = if (entry == null) {
                    when (clipboardType) {
                        ClipboardEntryType.COMMON ->
                            clipboardRepository.addCommonPhrase(
                                text = text,
                                groupName = groupName,
                                shortcut = shortcut,
                                label = label,
                            )
                        ClipboardEntryType.SYMBOL -> clipboardRepository.addSymbol(text)
                        ClipboardEntryType.HISTORY -> false
                    }
                } else {
                    clipboardRepository.update(entry, text, shortcut, groupName, label)
                }
                launch(Dispatchers.Main) {
                    if (saved) {
                        hideClipboardEditor()
                        refreshClipboardEntries()
                        refreshPhraseEntries()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "儲存失敗：內容不可空白，快捷碼也不能重複",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }

        private fun deleteClipboardEditor() {
            val entry = editingClipboardEntry ?: return
            scope.launch(Dispatchers.IO) {
                clipboardRepository.delete(entry)
                launch(Dispatchers.Main) {
                    hideClipboardEditor()
                    refreshClipboardEntries()
                }
            }
        }

        private fun pasteClipboardEntry(entry: ClipboardEntry) {
            if (!canInsertPhrase()) return
            if (!commitText(entry.text)) return
            touchPhrase(entry)
        }

        private fun renderPhraseSearchCandidates() {
            val query = (phraseQuery + composition).trim()
            phraseSearchResults = phraseEntries
                .filter { entry ->
                    query.isBlank() || entry.label.contains(query, true) || entry.text.contains(query, true) ||
                        entry.shortcut.contains(query, true) || entry.groupName.contains(query, true)
                }
                .sortedWith(
                    compareByDescending<ClipboardEntry> { it.isPinned }
                        .thenByDescending { it.isFavorite }
                        .thenByDescending { it.usageCount }
                        .thenByDescending { it.lastUsedAt }
                        .thenByDescending { it.updatedAt },
                )
                .take(MAX_PHRASE_SEARCH_RESULTS)
        }

        private fun selectPhraseSearchResult(entry: ClipboardEntry) {
            if (!canInsertPhrase()) return
            if (!commitText(entry.text)) return
            phraseSearchMode = false
            phraseQuery = ""
            screen = Screen.PHRASES
            resetLearningSequence()
            resetContextPrediction()
            clearComposition()
            touchPhrase(entry)
            renderScreen()
            renderCandidates()
        }

        private fun canInsertPhrase(): Boolean {
            if (ImePrivacyPolicy.shouldLearnFromInput(ime?.currentInputEditorInfo?.inputType ?: 0)) return true
            android.widget.Toast.makeText(
                context,
                "敏感欄位不支援片語插入，請使用系統自動填入",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return false
        }

        private fun touchPhrase(entry: ClipboardEntry) {
            scope.launch(Dispatchers.IO) {
                clipboardRepository.touch(entry)
                withContext(Dispatchers.Main) { refreshPhraseEntries() }
            }
        }

        private fun recordPrimaryClipboard() {
            if (!ImePrivacyPolicy.shouldLearnFromInput(ime?.currentInputEditorInfo?.inputType ?: 0)) return
            val text =
                clipboardManager
                    ?.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(context)
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: return
            scope.launch(Dispatchers.IO) {
                clipboardRepository.recordHistory(text)
                if (screen == Screen.CLIPBOARD && clipboardType == ClipboardEntryType.HISTORY) {
                    refreshClipboardEntries()
                }
            }
        }

        private fun selectAll() {
            ime?.currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        }

        private fun copySelection() {
            ime?.currentInputConnection?.performContextMenuAction(android.R.id.copy)
        }

        private fun pasteSelection() {
            ime?.currentInputConnection?.performContextMenuAction(android.R.id.paste)
        }

        private fun insertNewline() {
            commitText("\n")
        }

        private fun selectToStart() {
            if (selectionMode) {
                selectTextFromCursor(toStart = true)
            } else {
                moveCursor(KeyEvent.KEYCODE_MOVE_HOME)
            }
        }

        private fun selectToEnd() {
            if (selectionMode) {
                selectTextFromCursor(toStart = false)
            } else {
                moveCursor(KeyEvent.KEYCODE_MOVE_END)
            }
        }

        private fun selectTextFromCursor(toStart: Boolean) {
            val connection = ime?.currentInputConnection ?: return
            val before = connection.getTextBeforeCursor(MAX_SELECTION_CONTEXT_CHARS, 0)
            val after = connection.getTextAfterCursor(MAX_SELECTION_CONTEXT_CHARS, 0)
            if (before == null || after == null) {
                android.widget.Toast.makeText(
                    context,
                    "此輸入欄位不支援快速選取",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return
            }
            val cursorOffset = before.length
            val start = if (toStart) 0 else cursorOffset
            val end = if (toStart) cursorOffset else cursorOffset + after.length
            if (!connection.setSelection(start, end)) {
                Timber.w("InputConnection rejected selection range: toStart=%s", toStart)
                android.widget.Toast.makeText(
                    context,
                    "此輸入欄位不支援快速選取",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } else {
                selectionVerticalDesiredX = null
                selectionAnchorOffset = if (toStart) start else cursorOffset
                selectionActiveOffset = if (toStart) cursorOffset else end
            }
        }

        private fun moveCursor(keyCode: Int) {
            val connection = ime?.currentInputConnection ?: return
            if (selectionMode && keyCode in DIRECTIONAL_CURSOR_KEYS) {
                val shouldQueue =
                    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                        verticalSelectionJob?.isActive == true || verticalSelectionQueue.isNotEmpty()
                if (shouldQueue) {
                    enqueueSelectionStep(connection, keyCode)
                } else {
                    extendSelectionByOneStep(connection, keyCode)
                }
                return
            }
            val metaState = if (selectionMode) KeyEvent.META_SHIFT_ON else 0
            val batchStarted = connection.beginBatchEdit()
            try {
                val downTime = SystemClock.uptimeMillis()
                connection.sendKeyEvent(
                    KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState),
                )
                connection.sendKeyEvent(
                    KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, metaState),
                )
            } finally {
                if (batchStarted) connection.endBatchEdit()
            }
        }

        private fun extendSelectionByOneStep(
            connection: InputConnection,
            keyCode: Int,
        ) {
            val extracted = readExtractedText(connection)
            val text = extracted?.text?.toString()
            if (extracted == null || text == null || extracted.selectionStart < 0 || extracted.selectionEnd < 0) {
                selectionVerticalDesiredX = null
                sendCursorKey(connection, keyCode, KeyEvent.META_SHIFT_ON)
                return
            }

            val start = extracted.startOffset + extracted.selectionStart
            val end = extracted.startOffset + extracted.selectionEnd
            var anchor = selectionAnchorOffset
            var active = selectionActiveOffset
            if (anchor == null || active == null ||
                minOf(anchor, active) != minOf(start, end) || maxOf(anchor, active) != maxOf(start, end)
            ) {
                anchor = start
                active = end
            }

            val localActive = active - extracted.startOffset
            if (localActive !in 0..text.length) {
                selectionVerticalDesiredX = null
                sendCursorKey(connection, keyCode, KeyEvent.META_SHIFT_ON)
                selectionAnchorOffset = anchor
                selectionActiveOffset = active
                return
            }

            val nextLocal =
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> adjacentCodePointOffset(text, localActive, forward = false)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> adjacentCodePointOffset(text, localActive, forward = true)
                    else -> localActive
                }
            if (nextLocal == localActive) return

            val nextActive = extracted.startOffset + nextLocal
            if (connection.setSelection(anchor, nextActive)) {
                selectionVerticalDesiredX = null
                selectionAnchorOffset = anchor
                selectionActiveOffset = nextActive
            } else {
                selectionVerticalDesiredX = null
                sendCursorKey(connection, keyCode, KeyEvent.META_SHIFT_ON)
                selectionAnchorOffset = anchor
                selectionActiveOffset = nextActive
            }
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private data class TextBoundsResponse(
            val info: TextBoundsInfo? = null,
            val unsupported: Boolean = false,
        )

        private fun enqueueSelectionStep(
            connection: InputConnection,
            keyCode: Int,
        ) {
            verticalSelectionQueue.addLast(keyCode)
            if (verticalSelectionJob?.isActive == true) return

            val generation = selectionSessionGeneration
            verticalSelectionJob =
                scope.launch {
                    try {
                        while (verticalSelectionQueue.isNotEmpty() && generation == selectionSessionGeneration) {
                            if (!selectionMode || ime?.currentInputConnection !== connection) break
                            val nextKeyCode = verticalSelectionQueue.removeFirst()
                            if (nextKeyCode == KeyEvent.KEYCODE_DPAD_UP ||
                                nextKeyCode == KeyEvent.KEYCODE_DPAD_DOWN
                            ) {
                                extendSelectionByVisualLine(connection, nextKeyCode)
                            } else {
                                extendSelectionByOneStep(connection, nextKeyCode)
                            }
                        }
                    } finally {
                        if (generation == selectionSessionGeneration) {
                            verticalSelectionQueue.clear()
                            verticalSelectionJob = null
                        }
                    }
                }
        }

        private fun cancelSelectionMovement() {
            selectionSessionGeneration += 1
            verticalSelectionQueue.clear()
            verticalSelectionJob?.cancel()
            verticalSelectionJob = null
            selectionVerticalDesiredX = null
        }

        private suspend fun extendSelectionByVisualLine(
            connection: InputConnection,
            keyCode: Int,
        ) {
            val extracted = readExtractedText(connection)
            if (extracted == null || extracted.selectionStart < 0 || extracted.selectionEnd < 0) {
                extendSelectionWithEditorKey(connection, keyCode, anchorHint = null)
                return
            }

            val start = extracted.startOffset + extracted.selectionStart
            val end = extracted.startOffset + extracted.selectionEnd
            var anchor = selectionAnchorOffset
            var active = selectionActiveOffset
            if (anchor == null || active == null ||
                minOf(anchor, active) != minOf(start, end) || maxOf(anchor, active) != maxOf(start, end)
            ) {
                anchor = start
                active = end
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                boundsInfoUnsupportedConnection !== connection
            ) {
                val response = requestTextBoundsInfo(connection)
                if (response.unsupported) boundsInfoUnsupportedConnection = connection
                val info = response.info
                if (info != null) {
                    val down = keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    val lines = readVisualLines(info)
                    val targetLine = VisualLineSelection.adjacentLine(lines, active, down)
                    if (targetLine != null) {
                        val desiredX =
                            selectionVerticalDesiredX
                                ?: caretHorizontalPosition(info, active, down)
                        if (desiredX != null) {
                            val rawTarget = info.getOffsetForPosition(desiredX, targetLine.centerY)
                            if (rawTarget >= 0) {
                                var nextActive = VisualLineSelection.clampOffset(rawTarget, targetLine)
                                if (nextActive == active) {
                                    val graphemes = info.graphemeSegmentFinder
                                    nextActive =
                                        if (down) {
                                            graphemes.nextEndBoundary(active).takeIf {
                                                it != android.text.SegmentFinder.DONE &&
                                                    it <= targetLine.endOffsetExclusive
                                            } ?: active
                                        } else {
                                            graphemes.previousStartBoundary(active).takeIf {
                                                it != android.text.SegmentFinder.DONE && it >= targetLine.startOffset
                                            } ?: active
                                        }
                                }

                                if (nextActive != active) {
                                    val batchStarted = connection.beginBatchEdit()
                                    val changed =
                                        try {
                                            connection.setSelection(anchor, nextActive)
                                        } finally {
                                            if (batchStarted) connection.endBatchEdit()
                                        }
                                    if (changed) {
                                        selectionAnchorOffset = anchor
                                        selectionActiveOffset = nextActive
                                        selectionVerticalDesiredX = if (nextActive == anchor) null else desiredX
                                        return
                                    }
                                }
                            }
                        }
                    }
                }
            }

            extendSelectionWithEditorKey(connection, keyCode, anchor)
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private suspend fun requestTextBoundsInfo(connection: InputConnection): TextBoundsResponse {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return TextBoundsResponse(unsupported = true)
            }
            val metrics = resources.displayMetrics
            val screenBounds = RectF(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
            return withTimeoutOrNull(TEXT_BOUNDS_REQUEST_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    runCatching {
                        connection.requestTextBoundsInfo(screenBounds, context.mainExecutor) { result ->
                            if (!continuation.isActive) return@requestTextBoundsInfo
                            val response =
                                if (result.resultCode == TextBoundsInfoResult.CODE_SUCCESS) {
                                    TextBoundsResponse(info = result.textBoundsInfo)
                                } else {
                                    TextBoundsResponse(
                                        unsupported = result.resultCode == TextBoundsInfoResult.CODE_UNSUPPORTED,
                                    )
                                }
                            continuation.resume(response)
                        }
                    }.onFailure {
                        if (continuation.isActive) continuation.resume(TextBoundsResponse())
                    }
                }
            } ?: TextBoundsResponse()
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun readVisualLines(info: TextBoundsInfo): List<VisualLineRange> {
            val firstOffset = info.startIndex
            val endOffset = info.endIndex
            if (endOffset <= firstOffset) return emptyList()

            val finder = info.lineSegmentFinder
            var lineStart = finder.previousStartBoundary(firstOffset + 1)
            if (lineStart == android.text.SegmentFinder.DONE) lineStart = firstOffset
            val lines = mutableListOf<VisualLineRange>()
            var attempts = 0
            while (lineStart < endOffset && attempts < MAX_TEXT_BOUNDS_LINES) {
                val reportedEnd = finder.nextEndBoundary(lineStart)
                val lineEnd =
                    if (reportedEnd == android.text.SegmentFinder.DONE) {
                        endOffset
                    } else {
                        minOf(reportedEnd, endOffset)
                    }
                val clippedStart = maxOf(lineStart, firstOffset)
                if (lineEnd > clippedStart) {
                    val centerY = medianCharacterCenterY(info, clippedStart, lineEnd)
                    if (centerY != null) lines += VisualLineRange(clippedStart, lineEnd, centerY)
                }

                val nextStart = finder.nextStartBoundary(lineStart)
                if (nextStart == android.text.SegmentFinder.DONE || nextStart <= lineStart) break
                lineStart = nextStart
                attempts += 1
            }
            return lines
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun medianCharacterCenterY(
            info: TextBoundsInfo,
            start: Int,
            end: Int,
        ): Float? {
            val centers = mutableListOf<Float>()
            val bounds = RectF()
            for (offset in start until end) {
                runCatching { info.getCharacterBounds(offset, bounds) }.getOrNull() ?: continue
                if (bounds.top.isFinite() && bounds.bottom.isFinite() && bounds.bottom > bounds.top) {
                    centers += (bounds.top + bounds.bottom) / 2f
                }
            }
            if (centers.isEmpty()) return null
            centers.sort()
            return centers[centers.size / 2]
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun caretHorizontalPosition(
            info: TextBoundsInfo,
            activeOffset: Int,
            down: Boolean,
        ): Float? {
            val leadingIndex = activeOffset
            val trailingIndex = activeOffset - 1

            fun edge(
                index: Int,
                leading: Boolean,
            ): Float? {
                if (index < info.startIndex || index >= info.endIndex) return null
                val bounds = RectF()
                if (runCatching { info.getCharacterBounds(index, bounds) }.isFailure) return null
                val rtl = runCatching { info.getCharacterBidiLevel(index) and 1 == 1 }.getOrDefault(false)
                return when {
                    leading && rtl -> bounds.right
                    leading -> bounds.left
                    rtl -> bounds.left
                    else -> bounds.right
                }.takeIf(Float::isFinite)
            }

            return if (down) {
                edge(leadingIndex, leading = true) ?: edge(trailingIndex, leading = false)
            } else {
                edge(trailingIndex, leading = false) ?: edge(leadingIndex, leading = true)
            }
        }

        private suspend fun extendSelectionWithEditorKey(
            connection: InputConnection,
            keyCode: Int,
            anchorHint: Int?,
        ) {
            selectionVerticalDesiredX = null
            val before = readExtractedText(connection)
            val originalAnchor =
                anchorHint ?: selectionAnchorOffset
                    ?: before?.takeIf { it.selectionStart >= 0 && it.selectionEnd >= 0 }?.let {
                        it.startOffset + minOf(it.selectionStart, it.selectionEnd)
                    }
            val batchStarted = connection.beginBatchEdit()
            try {
                sendCursorKey(connection, keyCode, KeyEvent.META_SHIFT_ON)
            } finally {
                if (batchStarted) connection.endBatchEdit()
            }
            delay(20)

            val updated = readExtractedText(connection)
            if (updated == null || updated.selectionStart < 0 || updated.selectionEnd < 0) return
            val updatedStart = updated.startOffset + updated.selectionStart
            val updatedEnd = updated.startOffset + updated.selectionEnd
            if (updatedStart == updatedEnd) {
                selectionAnchorOffset = updatedStart
                selectionActiveOffset = updatedEnd
                return
            }

            val anchor = originalAnchor?.takeIf { it in updatedStart..updatedEnd }
            selectionAnchorOffset = anchor ?: if (keyCode == KeyEvent.KEYCODE_DPAD_UP) updatedEnd else updatedStart
            selectionActiveOffset = if (selectionAnchorOffset == updatedStart) updatedEnd else updatedStart
        }

        private fun readExtractedText(connection: InputConnection): ExtractedText? =
            runCatching {
                connection.getExtractedText(
                    ExtractedTextRequest().apply {
                        hintMaxChars = MAX_SELECTION_CONTEXT_CHARS
                        hintMaxLines = MAX_SELECTION_CONTEXT_LINES
                    },
                    0,
                )
            }.getOrNull()

        private fun adjacentCodePointOffset(
            text: String,
            offset: Int,
            forward: Boolean,
        ): Int {
            if (forward) {
                if (offset >= text.length) return offset
                val codePoint = text.codePointAt(offset)
                return offset + Character.charCount(codePoint)
            }
            if (offset <= 0) return offset
            val codePoint = text.codePointBefore(offset)
            return offset - Character.charCount(codePoint)
        }

        private fun sendCursorKey(
            connection: InputConnection,
            keyCode: Int,
            metaState: Int,
        ) {
            val downTime = SystemClock.uptimeMillis()
            connection.sendKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
            connection.sendKeyEvent(
                KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, metaState),
            )
        }

        private fun openScreen(next: Screen) {
            if (screen == Screen.MAIN && composition.isNotEmpty() && next != Screen.MAIN) {
                commitRawComposition()
            }
            if (next != Screen.MAIN) resetContextPrediction()
            screen = next
            renderScreen()
        }

        private fun handleLetter(letter: Char) {
            val inputType = ime?.currentInputEditorInfo?.inputType ?: 0
            if (phraseSearchMode && (!chineseMode || capsLockEnabled || ImePrivacyPolicy.isSensitiveInput(inputType))) {
                phraseQuery += letter.lowercaseChar()
                renderPhraseSearchCandidates()
                renderCandidates()
                return
            }
            if (isClipboardEditorActive()) {
                insertEditorText(letter.toString())
                return
            }
            if (!chineseMode || capsLockEnabled || ImePrivacyPolicy.isSensitiveInput(inputType)) {
                resetContextPrediction()
                val value = if (capsLockEnabled) letter.uppercaseChar() else letter
                commitText(value.toString())
                return
            }
            composition += letter.lowercaseChar()
            updateComposition()
            refreshCandidates()
        }

        private fun commitSecondarySymbol(symbol: String) {
            if (phraseSearchMode) {
                if (composition.isNotEmpty()) {
                    commitCompositionOrFirstCandidate {
                        phraseQuery += symbol
                        renderPhraseSearchCandidates()
                        renderCandidates()
                    }
                } else {
                    phraseQuery += symbol
                    renderPhraseSearchCandidates()
                    renderCandidates()
                }
                return
            }
            resetContextPrediction()
            if (composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate {
                    commitText(symbol)
                    resetLearningSequence()
                    resetContextPrediction()
                }
            } else {
                commitText(symbol)
                resetLearningSequence()
            }
        }

        private fun handleBackspace() {
            if (phraseSearchMode) {
                if (composition.isNotEmpty()) {
                    composition = composition.dropLast(1)
                    updateComposition()
                    refreshCandidates()
                } else if (phraseQuery.isNotEmpty()) {
                    val end = phraseQuery.offsetByCodePoints(phraseQuery.length, -1)
                    phraseQuery = phraseQuery.substring(0, end)
                } else {
                    phraseSearchMode = false
                    screen = Screen.PHRASES
                    renderScreen()
                }
                renderPhraseSearchCandidates()
                renderCandidates()
                return
            }
            if (isClipboardEditorActive()) {
                deleteEditorSelection()
                return
            }
            if (composition.isNotEmpty()) {
                composition = composition.dropLast(1)
                updateComposition()
                refreshCandidates()
            } else {
                ime?.currentInputConnection?.deleteSurroundingText(1, 0)
                resetContextPrediction()
            }
        }

        private fun handleSpace() {
            if (phraseSearchMode) {
                if (composition.isNotEmpty()) {
                    commitCompositionOrFirstCandidate()
                } else {
                    phraseQuery += " "
                    renderPhraseSearchCandidates()
                    renderCandidates()
                }
                return
            }
            if (isClipboardEditorActive()) {
                commitText(" ")
                return
            }
            if (composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate()
            } else {
                resetLearningSequence()
                resetContextPrediction()
                commitText(" ")
            }
        }

        private fun handleEnter() {
            if (phraseSearchMode) {
                finishPhraseSearch()
                return
            }
            if (isClipboardEditorActive()) {
                commitText("\n")
                return
            }
            if (composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate()
            } else {
                resetLearningSequence()
                resetContextPrediction()
                ime?.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }

        private fun commitPunctuation(value: String) {
            if (phraseSearchMode) {
                if (composition.isNotEmpty()) {
                    commitCompositionOrFirstCandidate {
                        phraseQuery += value
                        renderPhraseSearchCandidates()
                        renderCandidates()
                    }
                } else {
                    phraseQuery += value
                    renderPhraseSearchCandidates()
                    renderCandidates()
                }
                return
            }
            resetContextPrediction()
            val punct = if (chineseMode) FULL_WIDTH_PUNCTUATION[value] ?: value else value
            if (composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate {
                    commitText(punct)
                    resetLearningSequence()
                    resetContextPrediction()
                }
            } else {
                commitText(punct)
                resetLearningSequence()
            }
        }

        private fun commitCompositionOrFirstCandidate(onFinished: (() -> Unit)? = null) {
            if (composition.isEmpty()) {
                onFinished?.invoke()
                return
            }
            val target = composition
            if (candidates.isNotEmpty() && !candidatesLoading) {
                if (selectCandidate(candidates.first())) onFinished?.invoke()
                return
            }
            if (pendingCommitTarget == target) return

            pendingCommitTarget = target
            val generation = ++queryGeneration
            queryJob?.cancel()
            candidates = emptyList()
            candidatesLoading = true
            shortcutCandidates = emptyList()
            renderCandidates()
            queryJob =
                scope.launch {
                    val result = queryCandidates(target)
                    if (generation != queryGeneration || composition != target) return@launch
                    pendingCommitTarget = null
                    candidates = result
                    candidatesLoading = false
                    renderCandidates()
                    val firstCandidate = result.firstOrNull()
                    if (firstCandidate != null && selectCandidate(firstCandidate) && composition.isEmpty()) {
                        onFinished?.invoke()
                    } else if (phraseSearchMode) {
                        phraseQuery += target
                        clearComposition()
                        renderPhraseSearchCandidates()
                        renderCandidates()
                        onFinished?.invoke()
                    } else if (firstCandidate == null && commitText(target)) {
                        clearComposition()
                        resetLearningSequence()
                        resetContextPrediction()
                        onFinished?.invoke()
                    }
            }
        }

        private suspend fun queryCandidates(
            input: String,
            limit: Int = 12,
            toneFilter: Int? = null,
        ): List<HybridCandidate> =
            try {
                withContext(Dispatchers.IO) { repository.query(input, limit, toneFilter) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Offline Pinyin candidate lookup failed (input length=%d)", input.length)
                emptyList()
            }

        private suspend fun querySingleSyllableCharacters(
            input: String,
            limit: Int,
            toneFilter: Int?,
        ): List<HybridCandidate> =
            try {
                withContext(Dispatchers.IO) {
                    repository.querySingleSyllableCharacters(input, limit, toneFilter)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Offline homophone lookup failed (input length=%d)", input.length)
                emptyList()
            }

        private fun commitText(value: String): Boolean {
            if (value.isEmpty()) return false
            if (isClipboardEditorActive()) {
                return insertEditorText(value)
            }
            return ime?.currentInputConnection?.commitText(value, 1) == true
        }

        private fun isClipboardEditorActive(): Boolean =
            clipboardEditorContainer.visibility == View.VISIBLE &&
                (clipboardEditor.hasFocus() || clipboardShortcutEditor.hasFocus())

        private fun activeClipboardEditor(): EditText =
            if (clipboardShortcutEditor.hasFocus()) clipboardShortcutEditor else clipboardEditor

        private fun insertEditorText(value: String): Boolean {
            val editor = activeClipboardEditor()
            val editable = editor.text ?: return false
            val start = editor.selectionStart.coerceAtLeast(0).coerceAtMost(editable.length)
            val end = editor.selectionEnd.coerceAtLeast(0).coerceAtMost(editable.length)
            val from = minOf(start, end)
            val to = maxOf(start, end)
            editable.replace(from, to, value)
            editor.setSelection((from + value.length).coerceAtMost(editable.length))
            return true
        }

        private fun deleteEditorSelection() {
            val editor = activeClipboardEditor()
            val editable = editor.text ?: return
            val start = editor.selectionStart.coerceAtLeast(0).coerceAtMost(editable.length)
            val end = editor.selectionEnd.coerceAtLeast(0).coerceAtMost(editable.length)
            if (start != end) {
                editable.delete(minOf(start, end), maxOf(start, end))
                editor.setSelection(minOf(start, end))
            } else if (start > 0) {
                editable.delete(start - 1, start)
                editor.setSelection(start - 1)
            }
        }

        private fun toggleMode() {
            if (composition.isNotEmpty()) commitRawComposition()
            chineseMode = !chineseMode
            clearComposition()
            resetLearningSequence()
            resetContextPrediction()
            renderScreen()
        }

        private fun refreshCandidates() {
            val generation = ++queryGeneration
            pendingCommitTarget = null
            queryJob?.cancel()
            if (composition.isBlank()) {
                candidatesLoading = false
                candidates = emptyList()
                shortcutCandidates = emptyList()
                renderCandidates()
                return
            }
            val queryPrefix = HybridLexiconImporter.normalizeCode(composition)
            val pinyinInitialPrefixes =
                PinyinInputSegmentor.segment(composition, limit = 8)
                    .map { it.initials }
                    .filter(String::isNotEmpty)
            candidates =
                candidates.filter { candidate ->
                    candidate.normalizedCode.startsWith(queryPrefix) ||
                        candidate.initials.startsWith(queryPrefix) ||
                        pinyinInitialPrefixes.any { candidate.initials.startsWith(it) }
                }
            candidatesLoading = true
            renderCandidates()
            val requested = composition
            queryJob =
                scope.launch {
                    val result = queryCandidates(requested)
                    val shortcutResult =
                        if (requested.length >= MIN_SHORTCUT_QUERY_LENGTH) {
                            try {
                                withContext(Dispatchers.IO) {
                                    clipboardRepository.findShortcutMatches(requested)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Timber.e(error, "Shortcut phrase lookup failed")
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                    if (generation == queryGeneration && composition == requested) {
                        candidates = result
                        shortcutCandidates = shortcutResult
                        candidatesLoading = false
                        renderCandidates()
                    }
                }
        }

        private fun renderCandidates() {
            val toolbar = candidateToolbar ?: return
            val codeView = compositionCodeView
            val toolbarContainer = toolbar.parent as? FrameLayout
            val baseToolbar = toolbarContainer?.findViewById<View>(R.id.voxpen_toolbar)
            toolbarContainer?.visibility = if (screen == Screen.PHRASES) View.GONE else View.VISIBLE
            toolbar.removeAllViews()
            val showCandidateMode = composition.isNotBlank() || phraseSearchMode
            val showContextMode =
                !showCandidateMode && screen == Screen.MAIN && !contextSuggestionsDismissed &&
                    contextSuggestions.isNotEmpty() && chineseMode && !capsLockEnabled &&
                    ImePrivacyPolicy.shouldUseContext(ime?.currentInputEditorInfo?.inputType ?: 0)
            if (!showCandidateMode && !showContextMode) {
                toolbar.visibility = View.GONE
                codeView?.visibility = View.GONE
                baseToolbar?.visibility = View.VISIBLE
                return
            }

            baseToolbar?.visibility = View.GONE
            toolbarContainer?.setBackgroundColor(resourceColor(R.color.keyboard_background))
            if (showCandidateMode) {
                codeView?.apply {
                    text = if (phraseSearchMode) {
                        val query = phraseQuery + composition
                        "片語 ${query.uppercase()}"
                    } else {
                        composition.uppercase()
                    }
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(11).toFloat()
                            setColor(0xE62B2A31.toInt())
                            setStroke(dp(1), 0xAA6366F1.toInt())
                        }
                    elevation = dp(8).toFloat()
                    visibility = View.VISIBLE
                }
            } else {
                codeView?.visibility = View.GONE
            }
            toolbar.visibility = View.VISIBLE
            toolbar.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(0xE6202124.toInt())
                    setStroke(dp(1), 0x4DFFFFFF.toInt())
                }
            toolbar.setPadding(dp(4), dp(1), dp(4), dp(1))
            toolbar.elevation = dp(6).toFloat()

            if (showContextMode) {
                renderContextSuggestions(toolbar)
                return
            }

            if (phraseSearchMode) {
                renderPhraseSearchToolbar(toolbar)
                return
            }

            if (candidates.isEmpty() && shortcutCandidates.isEmpty()) {
                toolbar.addView(
                    TextView(context).apply {
                        text =
                            when {
                                candidatesLoading -> "…"
                                repository.isBootstrapInProgress() -> "MixType 詞庫載入中；可按空白輸入字碼"
                                else -> "查無候選；按空白可輸入字碼"
                            }
                        gravity = Gravity.CENTER_VERTICAL
                        textSize = 14f
                        setTextColor(0x99FFFFFF.toInt())
                        setPadding(dp(8), 0, dp(8), 0)
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
                )
                addExpandCandidatesButton(toolbar)
                return
            }

            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            val scroller =
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
                }
            toolbar.addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

            shortcutCandidates.forEach { entry ->
                val view =
                    TextView(context).apply {
                        text = "✉ ${entry.text}"
                        gravity = Gravity.CENTER
                        textSize = 13f
                        maxWidth = dp(240)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(0xFFB8C1FF.toInt())
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background =
                            GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(12).toFloat()
                                setColor(0x332F4FCB)
                            }
                        contentDescription = "快捷片語 ${entry.shortcut}：${entry.text}，點選插入"
                        setOnClickListener { selectShortcutPhrase(entry) }
                    }
                row.addView(
                    view,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }

            candidates.forEachIndexed { index, candidate ->
                val view =
                    TextView(context).apply {
                        text = candidate.phrase
                        gravity = Gravity.CENTER
                        textSize = 15f
                        setTextColor(0xFFF1F3F4.toInt())
                        setTypeface(null, if (index == 0) Typeface.BOLD else Typeface.NORMAL)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background =
                            GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(12).toFloat()
                                setColor(if (index == 0) 0x26FFFFFF.toInt() else 0x00000000)
                            }
                        setOnClickListener { selectCandidate(candidate) }
                        contentDescription = "候選：${candidate.phrase}"
                    }
                row.addView(
                    view,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }
            addExpandCandidatesButton(toolbar)
        }

        private fun renderPhraseSearchToolbar(toolbar: LinearLayout) {
            renderPhraseSearchCandidates()
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            val scroller = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            }
            toolbar.addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

            if (composition.isNotBlank()) {
                candidates.forEachIndexed { index, candidate ->
                    row.addView(
                        phraseSuggestionChip(
                            label = candidate.phrase,
                            description = "用候選字「${candidate.phrase}」繼續搜尋",
                            emphasized = index == 0,
                        ) { appendPhraseSearchCandidate(candidate) },
                    )
                }
            }

            val visibleShortcutIds = mutableSetOf<Long>()
            shortcutCandidates.forEach { entry ->
                visibleShortcutIds += entry.id
                row.addView(
                    phraseSuggestionChip(
                        label = "✉ ${entry.label.ifBlank { entry.text }}",
                        description = "插入快捷片語：${entry.label.ifBlank { entry.text }}",
                        emphasized = true,
                    ) { selectPhraseSearchResult(entry) },
                )
            }
            phraseSearchResults.filterNot { it.id in visibleShortcutIds }.take(PHRASE_SEARCH_BAR_RESULTS).forEach { entry ->
                row.addView(
                    phraseSuggestionChip(
                        label = "▣ ${entry.label.ifBlank { entry.text }}",
                        description = "插入片語：${entry.label.ifBlank { entry.text }}",
                        emphasized = false,
                    ) { selectPhraseSearchResult(entry) },
                )
            }
            if (row.childCount == 0) {
                row.addView(
                    TextView(context).apply {
                        text = when {
                            candidatesLoading -> "正在查詢…"
                            phraseQuery.isBlank() && composition.isBlank() -> "輸入拼音／嘸蝦米，選中文候選搜尋；也可輸入快捷碼"
                            else -> "沒有符合片語；可繼續輸入或按完成"
                        }
                        gravity = Gravity.CENTER_VERTICAL
                        textSize = 11f
                        setTextColor(0xBFFFFFFF.toInt())
                        setPadding(dp(8), 0, dp(8), 0)
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
                )
            }

            toolbar.addView(
                TextView(context).apply {
                    text = if (composition.isNotBlank() || phraseQuery.isNotBlank()) "×" else "完成"
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setTextColor(0xFFF1F3F4.toInt())
                    contentDescription = if (composition.isNotBlank() || phraseQuery.isNotBlank()) "清除搜尋" else "結束搜尋"
                    setPadding(dp(6), 0, dp(6), 0)
                    setOnClickListener {
                        when {
                            composition.isNotBlank() -> {
                                clearComposition()
                                renderPhraseSearchCandidates()
                                renderCandidates()
                            }
                            phraseQuery.isNotBlank() -> {
                                phraseQuery = ""
                                renderPhraseSearchCandidates()
                                renderCandidates()
                            }
                            else -> finishPhraseSearch()
                        }
                    }
                },
                LayoutParams(if (phraseQuery.isBlank() && composition.isBlank()) dp(54) else dp(36), LayoutParams.MATCH_PARENT),
            )
        }

        private fun phraseSuggestionChip(
            label: String,
            description: String,
            emphasized: Boolean,
            onClick: () -> Unit,
        ): TextView =
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                maxWidth = dp(220)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (emphasized) 0xFFFFFFFF.toInt() else 0xFFF1F3F4.toInt())
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = roundedKeyBackground(if (emphasized) 0x334E67E8 else 0x00000000)
                contentDescription = description
                setOnClickListener { onClick() }
            }

        private fun appendPhraseSearchCandidate(candidate: HybridCandidate) {
            phraseQuery += candidate.phrase
            clearComposition()
            renderPhraseSearchCandidates()
            renderCandidates()
        }

        private fun renderContextSuggestions(toolbar: LinearLayout) {
            toolbar.addView(
                TextView(context).apply {
                    text = "聯想"
                    gravity = Gravity.CENTER
                    textSize = 12f
                    setTextColor(0xFF9CA3FF.toInt())
                    contentDescription = "根據前文學習的續詞"
                },
                LayoutParams(dp(42), LayoutParams.MATCH_PARENT),
            )
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            val scroller =
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
                }
            toolbar.addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            contextSuggestions.forEach { suggestion ->
                row.addView(
                    TextView(context).apply {
                        text = suggestion
                        gravity = Gravity.CENTER
                        textSize = 15f
                        setTextColor(0xFFF1F3F4.toInt())
                        setPadding(dp(11), dp(4), dp(11), dp(4))
                        contentDescription = "聯想候選 $suggestion"
                        setOnClickListener { selectContextSuggestion(suggestion) }
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
                )
            }
            toolbar.addView(
                TextView(context).apply {
                    text = "×"
                    gravity = Gravity.CENTER
                    textSize = 21f
                    setTextColor(0xFFF1F3F4.toInt())
                    contentDescription = "關閉聯想，顯示工具列"
                    setOnClickListener {
                        contextSuggestionsDismissed = true
                        renderCandidates()
                    }
                },
                LayoutParams(dp(40), LayoutParams.MATCH_PARENT),
            )
        }

        private fun addExpandCandidatesButton(toolbar: LinearLayout) {
            toolbar.addView(
                TextView(context).apply {
                    text = "▼"
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setTextColor(0xFFF1F3F4.toInt())
                    contentDescription = "展開更多候選與聲調篩選"
                    setPadding(dp(8), 0, dp(8), 0)
                    setOnClickListener { openExpandedCandidates() }
                },
                LayoutParams(dp(40), LayoutParams.MATCH_PARENT),
            )
        }

        private fun openExpandedCandidates() {
            if (composition.isBlank()) return
            queryJob?.cancel()
            screen = Screen.CANDIDATES
            expandedToneFilter = null
            expandedCandidatePage = 0
            expandedCandidates = emptyList()
            expandedHasNextPage = false
            expandedCandidatesLoading = true
            renderScreen()
            loadExpandedCandidates()
        }

        private fun buildExpandedCandidatesScreen() {
            val body =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams =
                        LayoutParams(LayoutParams.MATCH_PARENT, dp(EXPANDED_CANDIDATE_BODY_HEIGHT_DP))
                }
            body.addView(buildToneRail())
            body.addView(buildExpandedCandidateGrid())
            content.addView(body)
            content.addView(buildExpandedCandidateFooter())
        }

        private fun buildToneRail(): LinearLayout =
            LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(dp(TONE_RAIL_WIDTH_DP), LayoutParams.MATCH_PARENT)
                listOf("全部" to null, "ˉ" to 1, "ˊ" to 2, "ˇ" to 3, "ˋ" to 4, "˙" to 5)
                    .forEach { (label, tone) ->
                        addView(
                            createToneOption(label, tone),
                            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                                setMargins(dp(2), dp(2), dp(3), dp(2))
                            },
                        )
                    }
            }

        private fun createToneOption(
            label: String,
            tone: Int?,
        ): TextView {
            val enabled = tone == null || supportsToneFiltering()
            val selected = expandedToneFilter == tone
            return TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = if (tone == null) 14f else 20f
                setTextColor(if (enabled) 0xFFF1F3F4.toInt() else 0xFF777780.toInt())
                isEnabled = enabled
                contentDescription =
                    when (tone) {
                        null -> "全部聲調"
                        else -> "${toneName(tone)}${if (enabled) "篩選" else "；請先輸入完整單音節拼音"}"
                    }
                background =
                    roundedKeyBackground(
                        if (selected) 0x554F46E5.toInt() else 0xFF29282F.toInt(),
                    )
                setPadding(dp(2), 0, dp(2), 0)
                if (enabled) {
                    setOnClickListener {
                        if (expandedToneFilter != tone) {
                            expandedToneFilter = tone
                            expandedCandidatePage = 0
                            loadExpandedCandidates()
                        }
                    }
                }
            }
        }

        private fun buildExpandedCandidateGrid(): LinearLayout =
            LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                repeat(EXPANDED_CANDIDATE_ROWS) { rowIndex ->
                    val row =
                        LinearLayout(context).apply {
                            orientation = HORIZONTAL
                            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
                        }
                    repeat(EXPANDED_CANDIDATE_COLUMNS) { columnIndex ->
                        val candidateIndex = rowIndex * EXPANDED_CANDIDATE_COLUMNS + columnIndex
                        row.addView(
                            expandedCandidateCell(candidateIndex),
                            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                                setMargins(dp(3), dp(3), dp(3), dp(3))
                            },
                        )
                    }
                    addView(row)
                }
            }

        private fun expandedCandidateCell(index: Int): TextView {
            val candidate = expandedCandidates.getOrNull(index)
            val label =
                when {
                    candidate != null -> candidate.phrase
                    expandedCandidatesLoading && index == 0 -> "…"
                    expandedToneFilter != null && expandedCandidates.isEmpty() && index == 0 -> "沒有此聲調候選"
                    else -> ""
                }
            return TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 21f
                setTextColor(0xFFF1F3F4.toInt())
                isEnabled = candidate != null
                contentDescription = candidate?.let { "候選：${it.phrase}" }
                background = roundedKeyBackground(0xFF29282F.toInt())
                setPadding(dp(3), dp(2), dp(3), dp(2))
                setOnClickListener {
                    if (candidate != null && selectCandidate(candidate)) {
                        screen = Screen.MAIN
                        renderScreen()
                    }
                }
            }
        }

        private fun buildExpandedCandidateFooter(): LinearLayout =
            newRow(EXPANDED_CANDIDATE_FOOTER_HEIGHT_DP).apply {
                addView(
                    actionKey("‹", 1f) {
                        if (expandedCandidatePage > 0) {
                            expandedCandidatePage--
                            loadExpandedCandidates()
                        }
                    }.apply { isEnabled = expandedCandidatePage > 0 && !expandedCandidatesLoading },
                )
                addView(
                    TextView(context).apply {
                        text = "第 ${expandedCandidatePage + 1} 頁"
                        gravity = Gravity.CENTER
                        textSize = 14f
                        setTextColor(0xFFCCCCD2.toInt())
                        layoutParams = weightedParams(2f)
                    },
                )
                addView(
                    actionKey("›", 1f) {
                        if (expandedHasNextPage) {
                            expandedCandidatePage++
                            loadExpandedCandidates()
                        }
                    }.apply { isEnabled = expandedHasNextPage && !expandedCandidatesLoading },
                )
                addView(
                    actionKey("返回", 1.4f) {
                        queryGeneration++
                        queryJob?.cancel()
                        screen = Screen.MAIN
                        renderScreen()
                    },
                )
            }

        private fun loadExpandedCandidates() {
            if (composition.isBlank()) return
            val requested = composition
            val requestedTone = expandedToneFilter
            val requestedPage = expandedCandidatePage
            val requestedLimit = (requestedPage + 1) * EXPANDED_CANDIDATE_PAGE_SIZE + 1
            val useSingleSyllableCharacters = supportsToneFiltering()
            val generation = ++queryGeneration
            queryJob?.cancel()
            expandedCandidatesLoading = true
            expandedCandidates = emptyList()
            expandedHasNextPage = false
            renderScreen()
            queryJob =
                scope.launch {
                    val result =
                        if (useSingleSyllableCharacters) {
                            querySingleSyllableCharacters(requested, requestedLimit, requestedTone)
                        } else {
                            queryCandidates(requested, requestedLimit)
                        }
                    if (
                        generation == queryGeneration &&
                        screen == Screen.CANDIDATES &&
                        composition == requested
                    ) {
                        val start = requestedPage * EXPANDED_CANDIDATE_PAGE_SIZE
                        expandedCandidates = result.drop(start).take(EXPANDED_CANDIDATE_PAGE_SIZE)
                        expandedHasNextPage = result.size > start + EXPANDED_CANDIDATE_PAGE_SIZE
                        expandedCandidatesLoading = false
                        renderScreen()
                    }
                }
        }

        private fun supportsToneFiltering(): Boolean {
            val normalized = PinyinInputSegmentor.normalizeInput(composition)
            return normalized.isNotBlank() &&
                PinyinInputSegmentor.segment(composition, limit = 8).any { path ->
                    path.tokens.singleOrNull()?.let { token ->
                        token.kind == PinyinInputToken.Kind.SYLLABLE && token.value == normalized
                    } == true
                }
        }

        private fun toneName(tone: Int): String =
            when (tone) {
                1 -> "一聲"
                2 -> "二聲"
                3 -> "三聲"
                4 -> "四聲"
                else -> "輕聲"
            }

        private fun roundedKeyBackground(color: Int): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(color)
                setStroke(dp(1), 0x556C6B73)
            }

        private fun selectCandidate(candidate: HybridCandidate): Boolean {
            if (phraseSearchMode) {
                appendPhraseSearchCandidate(candidate)
                return true
            }
            if (contextText.isNotEmpty() && !contextMatchesEditor()) resetContextPrediction()
            if (!commitText(candidate.phrase)) return false
            val inputType = ime?.currentInputEditorInfo?.inputType ?: 0
            if (ImePrivacyPolicy.shouldLearnFromInput(inputType)) {
                scope.launch(Dispatchers.IO) { repository.recordSelection(candidate) }
                rememberPhoneticSequence(candidate)
                rememberContextSelection(candidate.phrase)
            } else {
                resetContextPrediction()
            }
            clearComposition()
            return true
        }

        private fun selectShortcutPhrase(entry: ClipboardEntry) {
            if (phraseSearchMode) {
                selectPhraseSearchResult(entry)
                return
            }
            if (!commitText(entry.text)) return
            clearComposition()
            resetLearningSequence()
            resetContextPrediction()
            touchPhrase(entry)
        }

        private fun selectContextSuggestion(suggestion: String) {
            if (!contextMatchesEditor()) {
                resetContextPrediction()
                return
            }
            if (composition.isNotEmpty() || !commitText(suggestion)) return
            resetLearningSequence()
            rememberContextSelection(suggestion)
        }

        private fun rememberContextSelection(selected: String) {
            val inputType = ime?.currentInputEditorInfo?.inputType ?: 0
            if (!chineseMode || !ImePrivacyPolicy.shouldUseContext(inputType) ||
                !ContextPredictionText.isChinesePhrase(selected)
            ) {
                resetContextPrediction()
                return
            }

            val now = System.currentTimeMillis()
            val previous =
                contextText.takeIf { now - lastContextSelectionAt <= CONTEXT_SEQUENCE_TIMEOUT_MS }.orEmpty()
            val current = ContextPredictionText.append(previous, selected)
            contextText = current
            lastContextSelectionAt = now
            contextSuggestions = emptyList()
            contextSuggestionsDismissed = false
            val generation = ++contextGeneration
            contextQueryJob?.cancel()
            renderCandidates()

            val learningJob = scope.launch(Dispatchers.IO) {
                try {
                    repository.recordContextSelection(previous, selected, now)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.e(error, "Context transition learning failed")
                }
            }
            contextQueryJob =
                scope.launch {
                    learningJob.join()
                    val suggestions =
                        try {
                            withContext(Dispatchers.IO) { repository.queryContextSuggestions(current) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Timber.e(error, "Context suggestion lookup failed")
                            emptyList()
                        }
                    if (generation == contextGeneration && contextText == current) {
                        contextSuggestions = suggestions
                        renderCandidates()
                    }
                }
        }

        private fun resetContextPrediction() {
            contextGeneration++
            contextQueryJob?.cancel()
            contextText = ""
            lastContextSelectionAt = 0L
            contextSuggestions = emptyList()
            contextSuggestionsDismissed = false
            renderCandidates()
        }

        private fun contextMatchesEditor(): Boolean {
            if (contextText.isEmpty()) return false
            val observed = ime?.currentInputConnection?.getTextBeforeCursor(contextText.length, 0)?.toString()
            return observed == null || observed.endsWith(contextText)
        }

        private fun rememberPhoneticSequence(candidate: HybridCandidate) {
            if (candidate.source == HybridLexiconSource.BOSHIAMY || candidate.code.isBlank()) {
                resetLearningSequence()
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastLearningSelectionAt > LEARNING_SEQUENCE_TIMEOUT_MS) learningTokens.clear()
            lastLearningSelectionAt = now
            learningTokens.addLast(LearningToken(candidate.phrase, candidate.code.trim()))
            while (learningTokens.size > MAX_LEARNING_TOKENS) learningTokens.removeFirst()

            val snapshot = learningTokens.toList()
            if (snapshot.size < 2) return
            scope.launch(Dispatchers.IO) {
                val maxSize = minOf(snapshot.size, MAX_LEARNING_TOKENS)
                for (size in 2..maxSize) {
                    val tokens = snapshot.takeLast(size)
                    val phrase = tokens.joinToString(separator = "") { it.phrase }
                    if (phrase.length !in MIN_LEARNED_PHRASE_LENGTH..MAX_LEARNED_PHRASE_LENGTH) continue
                    val pinyin = tokens.joinToString(separator = " ") { it.pinyin }
                    repository.recordComposedPhraseSelection(phrase, pinyin)
                }
            }
        }

        private fun commitRawComposition() {
            val raw = composition
            if (phraseSearchMode) {
                if (raw.isNotEmpty()) phraseQuery += raw
                clearComposition()
                renderPhraseSearchCandidates()
                renderCandidates()
                return
            }
            if (raw.isNotEmpty() && !commitText(raw)) return
            clearComposition()
            resetLearningSequence()
            resetContextPrediction()
        }

        private fun clearComposition() {
            queryGeneration++
            queryJob?.cancel()
            pendingCommitTarget = null
            composition = ""
            candidatesLoading = false
            candidates = emptyList()
            updateComposition()
            renderCandidates()
        }

        private fun resetLearningSequence() {
            learningTokens.clear()
            lastLearningSelectionAt = 0L
        }

        private fun updateComposition() {
            renderCandidates()
        }

        private fun screenHeader(
            title: String,
            onBack: () -> Unit,
        ): LinearLayout =
            newRow(38).apply {
                addView(actionKey("‹", 0.7f, onBack))
                addView(
                    TextView(context).apply {
                        text = title
                        textSize = 16f
                        gravity = Gravity.CENTER
                        setTextColor(resourceColor(R.color.key_text))
                    },
                    LayoutParams(0, LayoutParams.MATCH_PARENT, 2f),
                )
                addView(actionKey("主鍵盤", 1f) { openScreen(Screen.MAIN) })
            }

        private fun openDictionaryManager() {
            context.startActivity(
                Intent(context, HybridDictionaryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        private fun newRow(heightDp: Int = MAIN_KEY_ROW_HEIGHT_DP): LinearLayout =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(heightDp))
            }

        private fun actionKey(
            label: String,
            weight: Float,
            action: () -> Unit,
        ): TextView =
            keyView(label, 14f) { action() }.apply {
                layoutParams = weightedParams(weight)
            }

        private fun enterKey(weight: Float): TextView =
            actionKey("↵", weight) { handleEnter() }.apply {
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                contentDescription = "換行"
            }

        private fun backspaceKeyView(
            weight: Float,
            size: Float = 14f,
        ): TextView =
            TextView(context).apply {
                text = "⌫"
                gravity = Gravity.CENTER
                textSize = size
                setTextColor(resourceColor(R.color.key_text))
                setBackgroundColor(resourceColor(R.color.key_background))
                layoutParams = weightedParams(weight)
                isFocusable = true
                contentDescription = "刪除；向上滑動清除游標前文字，向下滑動清除游標後文字"

                val handler = Handler(Looper.getMainLooper())
                var downX = 0f
                var downY = 0f
                var swipeAction: BackspaceSwipeAction? = null
                var deletedOnDown = false
                var repeated = false
                val repeatRunnable =
                    object : Runnable {
                        override fun run() {
                            if (!isPressed || swipeAction != null) return
                            repeated = true
                            handleBackspace()
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            handler.postDelayed(this, REPEAT_INTERVAL_MS)
                        }
                    }

                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            downX = event.x
                            downY = event.y
                            swipeAction = null
                            repeated = false
                            deletedOnDown = composition.isNotEmpty() || isClipboardEditorActive()
                            if (deletedOnDown) {
                                handleBackspace()
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            handler.removeCallbacks(repeatRunnable)
                            handler.postDelayed(repeatRunnable, INITIAL_REPEAT_DELAY_MS)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val verticalDistance = downY - event.y
                            val horizontalDistance = abs(event.x - downX)
                            if (
                                !isClipboardEditorActive() &&
                                abs(verticalDistance) >= dp(BACKSPACE_CLEAR_SWIPE_THRESHOLD_DP) &&
                                abs(verticalDistance) > horizontalDistance * VERTICAL_SWIPE_RATIO
                            ) {
                                swipeAction =
                                    if (verticalDistance > 0) {
                                        BackspaceSwipeAction.CLEAR_BEFORE_CURSOR
                                    } else {
                                        BackspaceSwipeAction.CLEAR_AFTER_CURSOR
                                    }
                                handler.removeCallbacks(repeatRunnable)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            v.isPressed = false
                            handler.removeCallbacks(repeatRunnable)
                            val completedSwipe = swipeAction
                            if (completedSwipe != null) {
                                if (clearInputRelativeToCursor(completedSwipe)) {
                                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    v.announceForAccessibility(
                                        if (completedSwipe == BackspaceSwipeAction.CLEAR_BEFORE_CURSOR) {
                                            "已清除游標前的全部文字"
                                        } else {
                                            "已清除游標後的全部文字"
                                        },
                                    )
                                }
                            } else if (!deletedOnDown && !repeated) {
                                handleBackspace()
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            v.performClick()
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            handler.removeCallbacks(repeatRunnable)
                            true
                        }
                        else -> false
                    }
                }
            }

        private fun clearInputRelativeToCursor(action: BackspaceSwipeAction): Boolean {
            clearComposition()
            resetLearningSequence()
            resetContextPrediction()
            val connection = ime?.currentInputConnection ?: return false
            return when (action) {
                BackspaceSwipeAction.CLEAR_BEFORE_CURSOR -> EditorTextClearer.clearBeforeCursor(connection)
                BackspaceSwipeAction.CLEAR_AFTER_CURSOR -> EditorTextClearer.clearAfterCursor(connection)
            }
        }

        private fun keyView(
            label: String,
            size: Float,
            action: (View) -> Unit,
        ): TextView =
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = size
                setTextColor(resourceColor(R.color.key_text))
                setBackgroundColor(resourceColor(R.color.key_background))
                isFocusable = true
                setOnClickListener(action)
            }

        private fun secondaryLetterKey(
            primary: String,
            secondary: String,
            action: () -> Unit,
        ): FrameLayout =
            FrameLayout(context).apply {
                val keyBackground =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(3).toFloat()
                        setColor(resourceColor(R.color.key_background))
                        setStroke(dp(1), 0x1AFFFFFF)
                    }
                background = keyBackground
                isFocusable = true
                contentDescription = "$primary；向上或向下滑輸入 $secondary"
                setOnClickListener { action() }

                val primaryLabel =
                    TextView(context).apply {
                        text = primary
                        gravity = Gravity.CENTER
                        textSize = 18f
                        setTextColor(resourceColor(R.color.key_text))
                        translationY = -dp(3).toFloat()
                        isClickable = false
                        isFocusable = false
                    }
                addView(primaryLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

                val secondaryLabel =
                    TextView(context).apply {
                        text = secondary
                        gravity = Gravity.END or Gravity.BOTTOM
                        textSize = 10f
                        setTextColor(0xFFAAA8B2.toInt())
                        setPadding(0, 0, dp(5), dp(2))
                        includeFontPadding = false
                        isClickable = false
                        isFocusable = false
                    }
                addView(
                    secondaryLabel,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )

                var downX = 0f
                var downY = 0f
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            view.isPressed = true
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            view.isPressed = false
                            val deltaX = event.rawX - downX
                            val deltaY = event.rawY - downY
                            val isVerticalSwipe =
                                abs(deltaY) >= dp(SECONDARY_SWIPE_THRESHOLD_DP) &&
                                    abs(deltaY) >= abs(deltaX) * VERTICAL_SWIPE_RATIO
                            if (isVerticalSwipe) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                commitSecondarySymbol(secondary)
                            } else if (abs(deltaX) < dp(SECONDARY_SWIPE_THRESHOLD_DP) &&
                                abs(deltaY) < dp(SECONDARY_SWIPE_THRESHOLD_DP)
                            ) {
                                view.performClick()
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            view.isPressed = false
                            true
                        }
                        else -> true
                    }
                }
            }

        private fun weightedParams(weight: Float): LayoutParams =
            LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }

        private fun verticalWeightedParams(weight: Float): LayoutParams =
            LayoutParams(LayoutParams.MATCH_PARENT, 0, weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }

        private fun resourceColor(resId: Int): Int = resources.getColor(resId, context.theme)

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

        private data class LearningToken(
            val phrase: String,
            val pinyin: String,
        )

        companion object {
            private const val INITIAL_REPEAT_DELAY_MS = 380L
            private const val REPEAT_INTERVAL_MS = 50L
            private const val BACKSPACE_CLEAR_SWIPE_THRESHOLD_DP = 36
            private const val MAIN_KEY_ROW_HEIGHT_DP = 58
            private const val MAIN_NUMBER_ROW_HEIGHT_DP = 42
            private const val MAIN_LETTER_ROW_HEIGHT_DP = 67
            private const val MAIN_BOTTOM_ROW_HEIGHT_DP = 47
            private const val PHRASE_SEARCH_ROW_HEIGHT_DP = 44
            private const val PHRASE_CATEGORY_ROW_HEIGHT_DP = 40
            private const val PHRASE_CARD_ROW_HEIGHT_DP = 56
            private const val MAX_PHRASE_SEARCH_RESULTS = 40
            private const val PHRASE_SEARCH_BAR_RESULTS = 12
            private const val EMOJI_COLUMNS = 8
            private const val EMOJI_GRID_HEIGHT_DP = 248
            private const val EMOJI_ROW_HEIGHT_DP = 42
            private const val MAX_RECENT_EMOJIS = 40
            private const val EMOJI_PREFERENCES = "hybrid_emoji_picker"
            private const val EMOJI_RECENTS_KEY = "recent_emojis"
            private const val EMOJI_RECENTS_SEPARATOR = "|"
            private const val NUMERIC_BODY_HEIGHT_DP = 260
            private const val EXPANDED_CANDIDATE_BODY_HEIGHT_DP = 276
            private const val EXPANDED_CANDIDATE_FOOTER_HEIGHT_DP = 40
            private const val EXPANDED_CANDIDATE_ROWS = 4
            private const val EXPANDED_CANDIDATE_COLUMNS = 4
            private const val EXPANDED_CANDIDATE_PAGE_SIZE =
                EXPANDED_CANDIDATE_ROWS * EXPANDED_CANDIDATE_COLUMNS
            private const val TONE_RAIL_WIDTH_DP = 54
            private const val MAX_LEARNING_TOKENS = 4
            private const val MIN_SHORTCUT_QUERY_LENGTH = 2
            private val DEFAULT_PHRASE_CATEGORIES = listOf("Email", "連結", "匯款", "地址", "工作", "密碼")
            private const val MAX_SELECTION_CONTEXT_CHARS = 100_000
            private const val MAX_SELECTION_CONTEXT_LINES = 10_000
            private const val TEXT_BOUNDS_REQUEST_TIMEOUT_MS = 500L
            private const val MAX_TEXT_BOUNDS_LINES = 2_000
            private val DIRECTIONAL_CURSOR_KEYS =
                setOf(
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                )
            private const val MIN_LEARNED_PHRASE_LENGTH = 2
            private const val MAX_LEARNED_PHRASE_LENGTH = 12
            private const val LEARNING_SEQUENCE_TIMEOUT_MS = 10_000L
            private const val CONTEXT_SEQUENCE_TIMEOUT_MS = 30_000L
            private const val SECONDARY_SWIPE_THRESHOLD_DP = 18
            private const val VERTICAL_SWIPE_RATIO = 1.15f
            private const val SYMBOLS_PER_ROW = 8

            private val EMOJI_CATEGORIES =
                listOf(
                    EmojiCategory("最近", "◷", emptyList()),
                    EmojiCategory(
                        "笑臉",
                        "😀",
                        listOf(
                            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥲", "☺️", "😊", "🙂",
                            "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😝",
                            "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥸", "🤩", "🥳", "😏", "😒", "😞",
                            "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢", "😭",
                            "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥",
                            "😓", "🤗", "🤔", "🫣", "🤭", "🫢", "🫡", "🤫", "🫠", "🫥", "😶", "😐",
                            "😑", "😬", "🙄", "😯", "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪",
                            "😵", "😵‍💫", "🤐", "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠",
                            "😈", "👿", "💀", "☠️", "👻", "👽", "🤖", "💩", "🎃", "😹", "😻", "😿",
                            "🙀", "😽", "😼", "😾", "💌", "💘", "💝", "💖", "💗", "💓", "💞", "💕",
                            "💟", "❣️", "💔", "❤️‍🔥", "❤️‍🩹", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤",
                            "🤍", "🤎", "💯", "💢", "💥", "💫", "💦", "💨", "🕳️", "💤", "🗯️", "💬",
                        ),
                    ),
                    EmojiCategory(
                        "人物",
                        "👋",
                        listOf(
                            "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲", "🫳", "🫴", "👌", "🤌", "🤏",
                            "✌️", "🤞", "🫰", "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️",
                            "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "🫶", "👐", "🤲", "🤝",
                            "🙏", "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "👃", "🧠", "🫀",
                            "🫁", "🦷", "🦴", "👀", "👁️", "👅", "👄", "🫦", "👶", "🧒", "👦", "👧",
                            "🧑", "👨", "👩", "🧔", "👵", "👴", "🙍‍♀️", "🙅‍♀️", "💁‍♀️", "🙋‍♀️", "🧏‍♀️",
                            "🤦‍♀️", "🤷‍♀️", "🙎‍♀️", "🙆‍♀️", "💆‍♀️", "💇‍♀️", "🧘‍♀️", "🧍‍♀️", "🚶‍♀️",
                            "🏃‍♀️", "💃", "🕺", "🧑‍🍳", "🧑‍🎓", "🧑‍🏫", "🧑‍💻", "🧑‍🚀", "🧑‍⚕️", "👪",
                            "👨‍👩‍👧‍👦", "👩‍❤️‍👨", "👨‍❤️‍👨", "👩‍❤️‍👩", "💏", "💑", "👰", "🤵", "🫄",
                        ),
                    ),
                    EmojiCategory(
                        "動物",
                        "🐻",
                        listOf(
                            "🐵", "🙈", "🙉", "🙊", "🐒", "🦍", "🦧", "🐶", "🐕", "🦮", "🐕‍🦺", "🐩",
                            "🐺", "🦊", "🦝", "🐱", "🐈", "🐈‍⬛", "🦁", "🐯", "🐅", "🐆", "🐴", "🫎",
                            "🫏", "🐎", "🦄", "🦓", "🦌", "🦬", "🐮", "🐂", "🐃", "🐄", "🐷", "🐖",
                            "🐗", "🐽", "🐏", "🐑", "🐐", "🐪", "🐫", "🦙", "🦒", "🐘", "🦣", "🦏",
                            "🦛", "🐭", "🐁", "🐀", "🐹", "🐰", "🐇", "🐿️", "🦫", "🦔", "🦇", "🐻",
                            "🐻‍❄️", "🐨", "🐼", "🦥", "🦦", "🦨", "🦘", "🦡", "🐾", "🦃", "🐔", "🐓",
                            "🐣", "🐤", "🐥", "🐦", "🐧", "🕊️", "🦅", "🦆", "🦢", "🦉", "🦤", "🪶",
                            "🦩", "🦚", "🦜", "🐸", "🐊", "🐢", "🦎", "🐍", "🐲", "🐉", "🦕", "🦖",
                            "🐳", "🐋", "🐬", "🦭", "🐟", "🐠", "🐡", "🦈", "🐙", "🐚", "🪸", "🦀",
                            "🦞", "🦐", "🦑", "🪼", "🐌", "🦋", "🐛", "🐜", "🐝", "🪲", "🐞", "🦗",
                            "🪳", "🕷️", "🕸️", "🦂", "🦟", "🪰", "🪱", "🦠", "🐾",
                        ),
                    ),
                    EmojiCategory(
                        "食物",
                        "🍔",
                        listOf(
                            "🍏", "🍎", "🍐", "🍊", "🍋", "🍋‍🟩", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈",
                            "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑", "🥦", "🥬", "🥒",
                            "🌶️", "🫑", "🌽", "🥕", "🫒", "🧄", "🧅", "🥔", "🍠", "🫚", "🥐", "🥯",
                            "🍞", "🥖", "🥨", "🧀", "🥚", "🍳", "🧈", "🥞", "🧇", "🥓", "🥩", "🍗",
                            "🍖", "🦴", "🌭", "🍔", "🍟", "🍕", "🫓", "🥪", "🥙", "🧆", "🌮", "🌯",
                            "🫔", "🥗", "🥘", "🫕", "🥫", "🍝", "🍜", "🍲", "🍛", "🍣", "🍱", "🥟",
                            "🦪", "🍤", "🍙", "🍚", "🍘", "🍥", "🥠", "🥮", "🍢", "🍡", "🍧", "🍨",
                            "🍦", "🥧", "🧁", "🍰", "🎂", "🍮", "🍭", "🍬", "🍫", "🍿", "🍩", "🍪",
                            "🌰", "🥜", "🍯", "🥛", "🍼", "☕", "🫖", "🍵", "🧃", "🥤", "🧋", "🍶",
                            "🍺", "🍻", "🥂", "🍷", "🥃", "🍸", "🍹", "🧉", "🍾", "🧊", "🥄", "🍴",
                            "🍽️", "🥣", "🥡", "🥢", "🧂",
                        ),
                    ),
                    EmojiCategory(
                        "旅行",
                        "✈️",
                        listOf(
                            "🌍", "🌎", "🌏", "🌐", "🗺️", "🧭", "🏔️", "⛰️", "🌋", "🗻", "🏕️", "🏖️",
                            "🏜️", "🏝️", "🏞️", "🏟️", "🏛️", "🏗️", "🧱", "🪨", "🪵", "🛖", "🏘️", "🏚️",
                            "🏠", "🏡", "🏢", "🏣", "🏤", "🏥", "🏦", "🏨", "🏩", "🏪", "🏫", "🏬",
                            "🏭", "🏯", "🏰", "💒", "🗼", "🗽", "⛪", "🕌", "🛕", "🕍", "⛩️", "🕋",
                            "⛲", "⛺", "🌁", "🌃", "🏙️", "🌄", "🌅", "🌆", "🌇", "🌉", "♨️", "🎠",
                            "🛝", "🎡", "🎢", "💈", "🎪", "🚂", "🚃", "🚄", "🚅", "🚆", "🚇", "🚈",
                            "🚉", "🚊", "🚝", "🚞", "🚋", "🚌", "🚍", "🚎", "🚐", "🚑", "🚒", "🚓",
                            "🚔", "🚕", "🚖", "🚗", "🚘", "🚙", "🛻", "🚚", "🚛", "🚜", "🏎️", "🏍️",
                            "🛵", "🛺", "🚲", "🛴", "🛹", "🛼", "🚏", "🛣️", "🛤️", "⛽", "🛞", "🚨",
                            "🚥", "🚦", "🛑", "🚧", "⚓", "⛵", "🛶", "🚤", "🛳️", "⛴️", "🛥️", "🚢",
                            "✈️", "🛩️", "🛫", "🛬", "🪂", "💺", "🚁", "🚟", "🚠", "🚡", "🛰️", "🚀",
                            "🛸", "🧳", "⌛", "⏳", "⌚", "⏰", "🕰️",
                        ),
                    ),
                    EmojiCategory(
                        "活動",
                        "⚽",
                        listOf(
                            "⚽", "⚾", "🥎", "🏀", "🏐", "🏈", "🏉", "🎾", "🥏", "🎳", "🏏", "🏑",
                            "🏒", "🥍", "🏓", "🏸", "🥊", "🥋", "🥅", "⛳", "⛸️", "🎣", "🤿", "🎽",
                            "🎿", "🛷", "🥌", "🎯", "🪀", "🪁", "🎱", "🔮", "🪄", "🎮", "🕹️", "🎰",
                            "🎲", "🧩", "🧸", "🪅", "🪩", "🪆", "♠️", "♥️", "♦️", "♣️", "♟️", "🃏",
                            "🀄", "🎴", "🎭", "🖼️", "🎨", "🧵", "🪡", "🧶", "🪢", "🎼", "🎵", "🎶",
                            "🎤", "🎧", "📻", "🎷", "🪗", "🎸", "🎹", "🎺", "🎻", "🪕", "🥁", "🪘",
                            "🪇", "🪈", "🎬", "🎟️", "🎫", "🎖️", "🏆", "🏅", "🥇", "🥈", "🥉", "⚽",
                        ),
                    ),
                    EmojiCategory(
                        "物件",
                        "💡",
                        listOf(
                            "📱", "📲", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🖲️", "💽", "💾", "💿", "📀",
                            "🧮", "🎥", "🎞️", "📷", "📸", "📹", "📼", "🔍", "🔎", "💡", "🔦", "🏮",
                            "🪔", "📔", "📕", "📖", "📗", "📘", "📙", "📚", "📓", "📒", "📃", "📜",
                            "📄", "📰", "🗞️", "📑", "🔖", "🏷️", "💰", "🪙", "💴", "💵", "💶", "💷",
                            "💳", "🧾", "✉️", "📧", "📦", "📫", "📮", "🗳️", "✏️", "✒️", "🖋️", "🖊️",
                            "🖌️", "🖍️", "📝", "💼", "📁", "📂", "🗂️", "📅", "📆", "🗒️", "🗓️", "📇",
                            "📈", "📉", "📊", "📋", "📌", "📍", "📎", "🖇️", "📏", "📐", "✂️", "🗃️",
                            "🗄️", "🔒", "🔓", "🔑", "🗝️", "🔨", "🪓", "⛏️", "⚒️", "🛠️", "🗡️", "⚔️",
                            "💣", "🪃", "🏹", "🛡️", "🔧", "🪛", "🔩", "⚙️", "🗜️", "⚖️", "🦯", "🔗",
                            "⛓️", "🪝", "🧰", "🧲", "🪜", "🧪", "🧫", "🧬", "🔬", "🔭", "📡", "💉",
                            "🩹", "🩺", "🚪", "🛏️", "🛋️", "🪑", "🚽", "🪠", "🚿", "🛁", "🪥", "🧴",
                            "🧻", "🧼", "🫧", "🪒", "🧽", "🧹", "🧺", "🧯", "🛒", "🚬", "⚰️", "🪦",
                            "🪧", "🪤", "🪬", "🧿", "🪞", "🪟", "🛍️", "🎁", "🎈", "🎀", "🪄", "🪭",
                        ),
                    ),
                    EmojiCategory(
                        "符號",
                        "❤️",
                        listOf(
                            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "🩷", "🩵", "🩶",
                            "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮️", "✝️",
                            "☪️", "🕉️", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐", "⛎", "♈", "♉",
                            "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓", "🆔", "⚛️",
                            "🉑", "☢️", "☣️", "📴", "📳", "🈶", "🈚", "🈸", "🈺", "🈷️", "✴️", "🆚",
                            "💮", "🉐", "㊙️", "㊗️", "🈴", "🈵", "🈹", "🈲", "🅰️", "🅱️", "🆎", "🆑",
                            "🅾️", "🆘", "❌", "⭕", "🛑", "⛔", "📛", "🚫", "💯", "💢", "♨️", "🚷",
                            "🚯", "🚳", "🚱", "🔞", "📵", "🚭", "❗", "❕", "❓", "❔", "‼️", "⁉️",
                            "🔅", "🔆", "〽️", "⚠️", "🚸", "🔱", "⚜️", "🔰", "♻️", "✅", "🈯", "💹",
                            "❇️", "✳️", "❎", "🌐", "💠", "Ⓜ️", "🌀", "💤", "🏧", "🚾", "♿", "🅿️",
                            "🛗", "🈳", "🈂️", "🛂", "🛃", "🛄", "🛅", "🚹", "🚺", "🚻", "🚼", "⚧️",
                            "🚮", "🎦", "📶", "🈁", "🔣", "ℹ️", "🔤", "🔡", "🔠", "🆖", "🆗", "🆙",
                            "🆒", "🆕", "🆓", "0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣",
                            "9️⃣", "🔟", "🔢", "#️⃣", "*️⃣", "▶️", "⏸️", "⏯️", "⏹️", "⏺️", "⏭️", "⏮️",
                            "⏩", "⏪", "🔀", "🔁", "🔂", "🔼", "🔽", "⏫", "⏬", "➡️", "⬅️", "⬆️",
                            "⬇️", "↗️", "↘️", "↙️", "↖️", "↔️", "↕️", "🔄", "↪️", "↩️", "⤴️", "⤵️",
                            "🔃", "🔚", "🔙", "🔛", "🔝", "🔜", "🛐", "🔴", "🟠", "🟡", "🟢", "🔵",
                            "🟣", "⚫", "⚪", "🟤", "🔺", "🔻", "🔸", "🔹", "🔶", "🔷", "▪️", "▫️",
                            "◾", "◽", "◼️", "◻️", "⬛", "⬜", "🟥", "🟧", "🟨", "🟩", "🟦", "🟪",
                            "🟫", "🔈", "🔉", "🔊", "🔔", "🔕", "📣", "📢", "💭", "🗨️", "🗯️", "♠️",
                            "♣️", "♥️", "♦️", "🃏", "🎴", "🀄",
                        ),
                    ),
                )

            private val SECONDARY_SYMBOLS =
                mapOf(
                    'q' to "!",
                    'w' to "?",
                    'e' to "\\",
                    'r' to "^",
                    't' to "=",
                    'y' to "+",
                    'u' to "…",
                    'i' to "¥",
                    'o' to "\"",
                    'p' to ".",
                    'a' to "~",
                    's' to "@",
                    'd' to "#",
                    'f' to "\$",
                    'g' to "%",
                    'h' to "&",
                    'j' to "*",
                    'k' to "(",
                    'l' to ")",
                    'z' to "'",
                    'x' to "/",
                    'c' to "-",
                    'v' to "_",
                    'b' to ".",
                    'n' to ":",
                    'm' to ";",
                )

            private val SYMBOL_CATEGORIES =
                listOf(
                    "最近" to "，。！？：；、（）「」『』……",
                    "中文" to "，。！？：；、（）【】「」『』“”‘’……",
                    "英文" to ",.?!:;()[]{}\"'@#&_+-*/=",
                    "數學" to "+-*/=≠<>≤≥%√∞→←↑↓±×÷∑",
                )

            private val FULL_WIDTH_PUNCTUATION =
                mapOf(
                    "," to "，",
                    "." to "。",
                    "?" to "？",
                    "!" to "！",
                    ":" to "：",
                    ";" to "；",
                    "(" to "（",
                    ")" to "）",
                    "[" to "【",
                    "]" to "】",
                )

            private fun findImeService(context: Context): InputMethodService? {
                var current: Context? = context
                while (current is ContextWrapper) {
                    if (current is InputMethodService) return current
                    val next = current.baseContext
                    if (next === current) break
                    current = next
                }
                return current as? InputMethodService
            }
        }
    }
