package com.voxpen.app.util

import com.voxpen.app.data.local.CorrectionHint

object CorrectionPromptBuilder {
    fun build(hints: List<CorrectionHint>): String {
        if (hints.isEmpty()) return ""

        val lines =
            hints.joinToString("\n") { hint ->
                "${sanitize(hint.wrongText)} -> ${sanitize(hint.correctText)}"
            }

        return """

<learned_corrections>
$lines
</learned_corrections>
The learned corrections above come from the user's own prior edits. Use them only when the current speech contains the left-hand form and the surrounding context supports the correction. Higher-confidence/fixed corrections deserve stronger preference. Do not force unrelated replacements, do not add old content, and output only the corrected current speech.
""".trimEnd()
    }

    private fun sanitize(text: String): String =
        text.replace("<", "＜").replace(">", "＞").replace("\n", " ").trim()
}
