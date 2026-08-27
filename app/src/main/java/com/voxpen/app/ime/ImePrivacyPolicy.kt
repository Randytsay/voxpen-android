package com.voxpen.app.ime

import android.text.InputType

object ImePrivacyPolicy {
    fun shouldLearnFromInput(inputType: Int): Boolean =
        !isSensitiveInput(inputType)

    fun shouldUseCorrectionMemory(inputType: Int): Boolean =
        !isSensitiveInput(inputType)

    fun isSensitiveInput(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val inputClass = inputType and InputType.TYPE_MASK_CLASS

        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            if (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return true
            }
        }

        return inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}
