package com.voxpen.app.ime.hybrid

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.voxpen.app.R
import com.voxpen.app.data.local.ClipboardEntry
import com.voxpen.app.data.local.ClipboardEntryType
import com.voxpen.app.data.local.HybridCandidate
import com.voxpen.app.data.local.HybridLexiconSource
import com.voxpen.app.data.repository.ClipboardRepository
import com.voxpen.app.data.repository.HybridInputRepository
import com.voxpen.app.ime.ImePrivacyPolicy
import com.voxpen.app.ime.VoxPenIMEEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.ArrayDeque
import kotlin.math.abs

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
            CLIPBOARD,
        }

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
        private var clipboardEntries: List<ClipboardEntry> = emptyList()
        private var editingClipboardEntry: ClipboardEntry? = null
        private var screen = Screen.MAIN
        private var clipboardType = ClipboardEntryType.HISTORY
        private var selectionMode = false
        private var composition = ""
        private var chineseMode = true
        private var capsLockEnabled = false
        private var candidates: List<HybridCandidate> = emptyList()
        private var candidatesLoading = false
        private var queryJob: Job? = null
        private var queryGeneration = 0L
        private var pendingCommitTarget: String? = null
        private var clipboardRefreshJob: Job? = null
        private var lastLearningSelectionAt = 0L
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
            clipboardRefreshJob?.cancel()
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
            scope.cancel()
            super.onDetachedFromWindow()
        }

        fun resetToMain() {
            screen = Screen.MAIN
            selectionMode = false
            renderScreen()
        }

        fun hasPendingComposition(): Boolean = composition.isNotBlank()

        fun showMainScreen() {
            openScreen(Screen.MAIN)
        }

        fun showEditScreen() {
            openScreen(Screen.EDIT)
        }

        fun toggleEditScreen() {
            openScreen(if (screen == Screen.EDIT) Screen.MAIN else Screen.EDIT)
        }

        fun showNumericScreen() {
            openScreen(Screen.NUMERIC)
        }

        fun showSymbolsScreen() {
            openScreen(Screen.SYMBOLS)
        }

        fun showDictionaryScreen() {
            openDictionaryManager()
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
            when (screen) {
                Screen.MAIN -> buildMainScreen()
                Screen.EDIT -> buildEditScreen()
                Screen.NUMERIC -> buildNumericScreen()
                Screen.SYMBOLS -> buildSymbolsScreen()
                Screen.CLIPBOARD -> buildClipboardScreen()
            }
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
            val row = newRow(MAIN_KEY_ROW_HEIGHT_DP)
            "1234567890".forEach { value ->
                row.addView(actionKey(value.toString(), 1f) { commitNumber(value) })
            }
            content.addView(row)
        }

        private fun addBottomLetterRow() {
            val row =
                newRow().apply {
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
            val bottom = newRow()
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
            if (composition.isNotEmpty()) commitRawComposition()
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
                newRow().apply {
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
                    (view as? TextView)?.text = if (selectionMode) "選取中" else "選擇"
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
            content.addView(screenHeader("數字鍵盤") { openScreen(Screen.MAIN) })
            val body = newRow(220)
            val operators = LinearLayout(context).apply { orientation = VERTICAL }
            listOf("+", "-", "*", "/", "=", "%").forEach { symbol ->
                operators.addView(
                    actionKey(symbol, 1f) { commitText(symbol) }.apply {
                        layoutParams = verticalWeightedParams(1f)
                    },
                )
            }
            body.addView(operators, LayoutParams(dp(58), LayoutParams.MATCH_PARENT))

            val numbers = LinearLayout(context).apply { orientation = VERTICAL }
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { rowValues ->
                val row = newRow(52)
                rowValues.forEach { value -> row.addView(actionKey(value, 1f) { commitText(value) }) }
                numbers.addView(row)
            }
            val last = newRow(52)
            last.addView(actionKey("0", 2f) { commitText("0") })
            last.addView(backspaceKeyView(1f, 18f))
            last.addView(enterKey(1f))
            numbers.addView(last)
            body.addView(numbers, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            content.addView(body)
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
                        clipboardType = type
                        renderScreen()
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
                        text = entry.text
                        textSize = 15f
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(resourceColor(R.color.key_text))
                        setPadding(dp(8), 0, dp(8), 0)
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
            clipboardDeleteButton?.visibility = if (entry == null) View.GONE else View.VISIBLE
            clipboardEditorContainer.visibility = View.VISIBLE
            clipboardEditor.requestFocus()
        }

        private fun hideClipboardEditor() {
            editingClipboardEntry = null
            clipboardDeleteButton?.visibility = View.GONE
            clipboardEditorContainer.visibility = View.GONE
            clipboardEditor.text?.clear()
        }

        private fun saveClipboardEditor() {
            val text = clipboardEditor.text?.toString().orEmpty()
            val entry = editingClipboardEntry
            scope.launch(Dispatchers.IO) {
                if (entry == null) {
                    when (clipboardType) {
                        ClipboardEntryType.COMMON -> clipboardRepository.addCommonPhrase(text)
                        ClipboardEntryType.SYMBOL -> clipboardRepository.addSymbol(text)
                        ClipboardEntryType.HISTORY -> false
                    }
                } else {
                    clipboardRepository.update(entry, text)
                }
                launch(Dispatchers.Main) {
                    hideClipboardEditor()
                    refreshClipboardEntries()
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
            if (!ImePrivacyPolicy.shouldLearnFromInput(ime?.currentInputEditorInfo?.inputType ?: 0)) return
            commitText(entry.text)
            scope.launch(Dispatchers.IO) { clipboardRepository.touch(entry) }
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
            moveCursor(KeyEvent.KEYCODE_MOVE_HOME, forceSelection = true)
        }

        private fun selectToEnd() {
            moveCursor(KeyEvent.KEYCODE_MOVE_END, forceSelection = true)
        }

        private fun moveCursor(
            keyCode: Int,
            forceSelection: Boolean = false,
        ) {
            val connection = ime?.currentInputConnection ?: return
            val metaState = if (selectionMode || forceSelection) KeyEvent.META_SHIFT_ON else 0
            connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
            connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        }

        private fun openScreen(next: Screen) {
            if (screen == Screen.MAIN && composition.isNotEmpty() && next != Screen.MAIN) {
                commitRawComposition()
            }
            screen = next
            renderScreen()
        }

        private fun handleLetter(letter: Char) {
            if (isClipboardEditorActive()) {
                insertEditorText(letter.toString())
                return
            }
            val inputType = ime?.currentInputEditorInfo?.inputType ?: 0
            if (!chineseMode || capsLockEnabled || ImePrivacyPolicy.isSensitiveInput(inputType)) {
                val value = if (capsLockEnabled) letter.uppercaseChar() else letter
                commitText(value.toString())
                return
            }
            composition += letter.lowercaseChar()
            updateComposition()
            refreshCandidates()
        }

        private fun commitSecondarySymbol(symbol: String) {
            if (composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate {
                    commitText(symbol)
                    resetLearningSequence()
                }
            } else {
                commitText(symbol)
                resetLearningSequence()
            }
        }

        private fun handleBackspace() {
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
            }
        }

        private fun handleSpace() {
            if (isClipboardEditorActive()) {
                commitText(" ")
                return
            }
            if (composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate()
            } else {
                resetLearningSequence()
                commitText(" ")
            }
        }

        private fun handleEnter() {
            if (isClipboardEditorActive()) {
                commitText("\n")
                return
            }
            if (composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate()
            } else {
                resetLearningSequence()
                ime?.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }

        private fun commitPunctuation(value: String) {
            val punct = if (chineseMode) FULL_WIDTH_PUNCTUATION[value] ?: value else value
            if (composition.isNotEmpty()) {
                commitCompositionOrFirstCandidate {
                    commitText(punct)
                    resetLearningSequence()
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
                    } else if (firstCandidate == null && commitText(target)) {
                        clearComposition()
                        onFinished?.invoke()
                    }
            }
        }

        private suspend fun queryCandidates(input: String): List<HybridCandidate> =
            try {
                withContext(Dispatchers.IO) { repository.query(input) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Offline Pinyin candidate lookup failed (input length=%d)", input.length)
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
            clipboardEditorContainer.visibility == View.VISIBLE && clipboardEditor.hasFocus()

        private fun insertEditorText(value: String): Boolean {
            val editable = clipboardEditor.text ?: return false
            val start = clipboardEditor.selectionStart.coerceAtLeast(0).coerceAtMost(editable.length)
            val end = clipboardEditor.selectionEnd.coerceAtLeast(0).coerceAtMost(editable.length)
            val from = minOf(start, end)
            val to = maxOf(start, end)
            editable.replace(from, to, value)
            clipboardEditor.setSelection((from + value.length).coerceAtMost(editable.length))
            return true
        }

        private fun deleteEditorSelection() {
            val editable = clipboardEditor.text ?: return
            val start = clipboardEditor.selectionStart.coerceAtLeast(0).coerceAtMost(editable.length)
            val end = clipboardEditor.selectionEnd.coerceAtLeast(0).coerceAtMost(editable.length)
            if (start != end) {
                editable.delete(minOf(start, end), maxOf(start, end))
                clipboardEditor.setSelection(minOf(start, end))
            } else if (start > 0) {
                editable.delete(start - 1, start)
                clipboardEditor.setSelection(start - 1)
            }
        }

        private fun toggleMode() {
            if (composition.isNotEmpty()) commitRawComposition()
            chineseMode = !chineseMode
            clearComposition()
            resetLearningSequence()
            renderScreen()
        }

        private fun refreshCandidates() {
            val generation = ++queryGeneration
            pendingCommitTarget = null
            queryJob?.cancel()
            if (composition.isBlank()) {
                candidatesLoading = false
                candidates = emptyList()
                renderCandidates()
                return
            }
            candidates = emptyList()
            candidatesLoading = true
            renderCandidates()
            val requested = composition
            queryJob =
                scope.launch {
                    delay(35)
                    val result = queryCandidates(requested)
                    if (generation == queryGeneration && composition == requested) {
                        candidates = result
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
            toolbar.removeAllViews()
            val showCandidateMode = composition.isNotBlank()
            if (!showCandidateMode) {
                toolbar.visibility = View.GONE
                codeView?.visibility = View.GONE
                baseToolbar?.visibility = View.VISIBLE
                return
            }

            baseToolbar?.visibility = View.GONE
            toolbarContainer?.setBackgroundColor(resourceColor(R.color.keyboard_background))
            codeView?.apply {
                text = composition.uppercase()
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

            if (candidates.isEmpty()) {
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
                return
            }

            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            val scroller =
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
                }
            toolbar.addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

            candidates.forEachIndexed { index, candidate ->
                val view =
                    TextView(context).apply {
                        val numberPrefix = if (index < 9) "${index + 1}." else ""
                        text = "$numberPrefix${candidate.phrase}"
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
                        contentDescription = "候選 ${index + 1}: ${candidate.phrase}"
                    }
                row.addView(
                    view,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }
        }

        private fun selectCandidate(candidate: HybridCandidate): Boolean {
            if (!commitText(candidate.phrase)) return false
            val inputType = ime?.currentInputEditorInfo?.inputType ?: 0
            if (ImePrivacyPolicy.shouldLearnFromInput(inputType)) {
                scope.launch(Dispatchers.IO) { repository.recordSelection(candidate) }
                rememberPhoneticSequence(candidate)
            }
            clearComposition()
            return true
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
            if (raw.isNotEmpty() && !commitText(raw)) return
            clearComposition()
            resetLearningSequence()
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

                val handler = Handler(Looper.getMainLooper())
                val repeatRunnable =
                    object : Runnable {
                        override fun run() {
                            if (!isPressed) return
                            handleBackspace()
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            handler.postDelayed(this, REPEAT_INTERVAL_MS)
                        }
                    }

                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            handleBackspace()
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            handler.removeCallbacks(repeatRunnable)
                            handler.postDelayed(repeatRunnable, INITIAL_REPEAT_DELAY_MS)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            handler.removeCallbacks(repeatRunnable)
                            v.performClick()
                            true
                        }
                        else -> false
                    }
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
            private const val MAIN_KEY_ROW_HEIGHT_DP = 58
            private const val MAX_LEARNING_TOKENS = 4
            private const val MIN_LEARNED_PHRASE_LENGTH = 2
            private const val MAX_LEARNED_PHRASE_LENGTH = 12
            private const val LEARNING_SEQUENCE_TIMEOUT_MS = 10_000L
            private const val SECONDARY_SWIPE_THRESHOLD_DP = 18
            private const val VERTICAL_SWIPE_RATIO = 1.15f
            private const val SYMBOLS_PER_ROW = 8

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
