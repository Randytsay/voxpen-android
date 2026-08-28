package com.voxpen.app.ime

import android.text.InputType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ImePrivacyPolicyTest {
    @Test
    fun `normal text allows learning`() {
        assertThat(
            ImePrivacyPolicy.shouldLearnFromInput(InputType.TYPE_CLASS_TEXT),
        ).isTrue()
    }

    @Test
    fun `text password disables learning and memory`() {
        val inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD

        assertThat(ImePrivacyPolicy.shouldLearnFromInput(inputType)).isFalse()
        assertThat(ImePrivacyPolicy.shouldUseCorrectionMemory(inputType)).isFalse()
    }

    @Test
    fun `visible password disables learning`() {
        val inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

        assertThat(ImePrivacyPolicy.shouldLearnFromInput(inputType)).isFalse()
    }

    @Test
    fun `web password disables learning`() {
        val inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

        assertThat(ImePrivacyPolicy.shouldLearnFromInput(inputType)).isFalse()
    }

    @Test
    fun `number password disables learning`() {
        val inputType =
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD

        assertThat(ImePrivacyPolicy.shouldLearnFromInput(inputType)).isFalse()
    }

    @Test
    fun `context memory follows the same privacy policy`() {
        assertThat(ImePrivacyPolicy.shouldUseContext(InputType.TYPE_CLASS_TEXT)).isTrue()
        assertThat(
            ImePrivacyPolicy.shouldUseContext(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ),
        ).isFalse()
    }
}
