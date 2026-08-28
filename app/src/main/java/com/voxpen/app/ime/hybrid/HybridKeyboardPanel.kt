package com.voxpen.app.ime.hybrid

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.voxpen.app.R
import com.voxpen.app.data.local.HybridCandidate
import com.voxpen.app.data.local.HybridLexiconSource
import com.voxpen.app.data.repository.HybridInputRepository
import com.voxpen.app.ime.ImePrivacyPolicy
import com.voxpen.app.ime.VoxPenIMEEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HybridKeyboardPanel
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val repository: HybridInputRepository =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                VoxPenIMEEntryPoint::class.java,
            ).hybridInputRepository()
        private val ime: InputMethodService? = findImeService(context)

        private val compositionView = TextView(context)
        private val candidateRow = LinearLayout(context)
        private val modeButton = TextView(context)
        private val learningTokens = ArrayDeque<LearningToken>()
        private var composition = ""
        private var chineseMode = true
        private var candidates: List<HybridCandidate> = emptyList()
        private var queryJob: Job? = null
        private var lastLearningSelectionAt = 0L

        init {
            orientation = VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setBackgroundColor(resourceColor(R.color.keyboard_background))
            buildCompositionAndCandidates()
            buildKeyboardRows()
            scope.launch(Dispatchers.IO) {
                repository.ensureBootstrapLexicon()
            }
        }

        override fun onDetachedFromWindow() {
            queryJob?.cancel()
            scope.cancel()
            super.onDetachedFromWindow()
        }

        private fun buildCompositionAndCandidates() {
            compositionView.apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(26))
                gravity = Gravity.CENTER_VERTICAL
                textSize = 13f
                setTextColor(resourceColor(R.color.key_text))
                setPadding(dp(8), 0, dp(8), 0)
                visibility = View.GONE
            }
            addView(compositionView)

            candidateRow.orientation = HORIZONTAL
            val scroller =
                HorizontalScrollView(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(40))
                    isHorizontalScrollBarEnabled = false
                    addView(
                        candidateRow,
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
                    )
                }
            addView(scroller)
        }

        private fun buildKeyboardRows() {
            addLetterRow("qwertyuiop")
            addLetterRow("asdfghjkl", horizontalPadding = dp(14))
            addLetterRow("zxcvbnm", horizontalPadding = dp(28))

            val bottom = newRow()
            modeButton.apply {
                text = "中"
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(resourceColor(R.color.key_text))
                setBackgroundColor(resourceColor(R.color.key_background))
                setOnClickListener { toggleMode() }
            }
            bottom.addView(modeButton, weightedParams(1.1f))

            bottom.addView(actionKey("詞庫", 1.3f) { openDictionaryManager() })
            bottom.addView(actionKey("，", 0.8f) { commitPunctuation("，") })
            bottom.addView(actionKey("空白", 3.2f) { handleSpace() })
            bottom.addView(actionKey("。", 0.8f) { commitPunctuation("。") })
            bottom.addView(actionKey("⌫", 1.1f) { handleBackspace() })
            bottom.addView(actionKey("↵", 1.1f) { handleEnter() })
            addView(bottom)
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
                val key =
                    TextView(context).apply {
                        text = letter.toString()
                        gravity = Gravity.CENTER
                        textSize = 18f
                        setTextColor(resourceColor(R.color.key_text))
                        setBackgroundColor(resourceColor(R.color.key_background))
                        setOnClickListener { handleLetter(letter) }
                    }
                row.addView(key, weightedParams(1f))
            }
            addView(row)
        }

        private fun newRow(): LinearLayout =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46))
            }

        private fun actionKey(
            label: String,
            weight: Float,
            action: () -> Unit,
        ): TextView =
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(resourceColor(R.color.key_text))
                setBackgroundColor(resourceColor(R.color.key_background))
                layoutParams = weightedParams(weight)
                setOnClickListener { action() }
            }

        private fun weightedParams(weight: Float): LayoutParams =
            LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }

        private fun handleLetter(letter: Char) {
            val inputType = ime?.currentInputEditorInfo?.inputType ?: 0
            if (!chineseMode || ImePrivacyPolicy.isSensitiveInput(inputType)) {
                ime?.currentInputConnection?.commitText(letter.toString(), 1)
                return
            }
            composition += letter.lowercaseChar()
            updateComposition()
            refreshCandidates()
        }

        private fun handleBackspace() {
            if (composition.isNotEmpty()) {
                composition = composition.dropLast(1)
                updateComposition()
                refreshCandidates()
            } else {
                ime?.currentInputConnection?.deleteSurroundingText(1, 0)
            }
        }

        private fun handleSpace() {
            if (composition.isNotEmpty()) {
                if (candidates.isNotEmpty()) {
                    selectCandidate(candidates.first())
                } else {
                    commitRawComposition()
                }
            } else {
                resetLearningSequence()
                ime?.currentInputConnection?.commitText(" ", 1)
            }
        }

        private fun handleEnter() {
            if (composition.isNotEmpty()) {
                if (candidates.isNotEmpty()) {
                    selectCandidate(candidates.first())
                } else {
                    commitRawComposition()
                }
            } else {
                resetLearningSequence()
                ime?.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }

        private fun commitPunctuation(value: String) {
            if (composition.isNotEmpty()) {
                if (candidates.isNotEmpty()) {
                    selectCandidate(candidates.first())
                } else {
                    commitRawComposition()
                }
            }
            ime?.currentInputConnection?.commitText(value, 1)
            resetLearningSequence()
        }

        private fun toggleMode() {
            if (composition.isNotEmpty()) commitRawComposition()
            chineseMode = !chineseMode
            modeButton.text = if (chineseMode) "中" else "EN"
            clearComposition()
            resetLearningSequence()
        }

        private fun refreshCandidates() {
            queryJob?.cancel()
            if (composition.isBlank()) {
                candidates = emptyList()
                renderCandidates()
                return
            }
            val requested = composition
            queryJob =
                scope.launch {
                    delay(35)
                    val result = repository.query(requested)
                    if (composition == requested) {
                        candidates = result
                        renderCandidates()
                    }
                }
        }

        private fun renderCandidates() {
            candidateRow.removeAllViews()
            candidates.forEachIndexed { index, candidate ->
                val prefix =
                    when (candidate.source) {
                        HybridLexiconSource.BOSHIAMY -> "嘸 "
                        HybridLexiconSource.PERSONAL -> "★ "
                        HybridLexiconSource.BAIDU -> "B "
                        HybridLexiconSource.PINYIN -> ""
                    }
                val view =
                    TextView(context).apply {
                        text = "$prefix${candidate.phrase}"
                        gravity = Gravity.CENTER
                        textSize = 16f
                        setTextColor(resourceColor(R.color.key_text))
                        setPadding(dp(12), 0, dp(12), 0)
                        setBackgroundColor(resourceColor(R.color.key_background))
                        setOnClickListener { selectCandidate(candidate) }
                        contentDescription = "候選 ${index + 1}: ${candidate.phrase}"
                    }
                candidateRow.addView(
                    view,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    },
                )
            }
        }

        private fun selectCandidate(candidate: HybridCandidate) {
            val inputType = ime?.currentInputEditorInfo?.inputType ?: 0
            ime?.currentInputConnection?.commitText(candidate.phrase, 1)
            if (ImePrivacyPolicy.shouldLearnFromInput(inputType)) {
                scope.launch(Dispatchers.IO) {
                    repository.recordSelection(candidate)
                }
                rememberPhoneticSequence(candidate)
            }
            clearComposition()
        }

        private fun rememberPhoneticSequence(candidate: HybridCandidate) {
            if (candidate.source == HybridLexiconSource.BOSHIAMY || candidate.code.isBlank()) {
                resetLearningSequence()
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastLearningSelectionAt > LEARNING_SEQUENCE_TIMEOUT_MS) {
                learningTokens.clear()
            }
            lastLearningSelectionAt = now
            learningTokens.addLast(
                LearningToken(
                    phrase = candidate.phrase,
                    pinyin = candidate.code.trim(),
                ),
            )
            while (learningTokens.size > MAX_LEARNING_TOKENS) {
                learningTokens.removeFirst()
            }

            val snapshot = learningTokens.toList()
            if (snapshot.size < 2) return
            scope.launch(Dispatchers.IO) {
                val maxSize = minOf(snapshot.size, MAX_LEARNING_TOKENS)
                for (size in 2..maxSize) {
                    val tokens = snapshot.takeLast(size)
                    val phrase = tokens.joinToString(separator = "") { it.phrase }
                    if (phrase.length !in MIN_LEARNED_PHRASE_LENGTH..MAX_LEARNED_PHRASE_LENGTH) {
                        continue
                    }
                    val pinyin = tokens.joinToString(separator = " ") { it.pinyin }
                    repository.learnPersonalPhrase(phrase, pinyin)
                }
            }
        }

        private fun commitRawComposition() {
            val raw = composition
            if (raw.isNotEmpty()) {
                ime?.currentInputConnection?.commitText(raw, 1)
            }
            clearComposition()
            resetLearningSequence()
        }

        private fun clearComposition() {
            composition = ""
            candidates = emptyList()
            updateComposition()
            renderCandidates()
        }

        private fun resetLearningSequence() {
            learningTokens.clear()
            lastLearningSelectionAt = 0L
        }

        private fun updateComposition() {
            compositionView.text = composition
            compositionView.visibility = if (composition.isBlank()) View.GONE else View.VISIBLE
        }

        private fun openDictionaryManager() {
            context.startActivity(
                Intent(context, HybridDictionaryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        private fun resourceColor(resId: Int): Int =
            resources.getColor(resId, context.theme)

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()

        private data class LearningToken(
            val phrase: String,
            val pinyin: String,
        )

        companion object {
            private const val MAX_LEARNING_TOKENS = 4
            private const val MIN_LEARNED_PHRASE_LENGTH = 2
            private const val MAX_LEARNED_PHRASE_LENGTH = 12
            private const val LEARNING_SEQUENCE_TIMEOUT_MS = 10_000L

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
