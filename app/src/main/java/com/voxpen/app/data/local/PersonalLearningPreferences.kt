package com.voxpen.app.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonalLearningPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val preferences =
            context.getSharedPreferences(
                FILE_NAME,
                Context.MODE_PRIVATE,
            )

        private val _enabled =
            MutableStateFlow(
                preferences.getBoolean(
                    KEY_ENABLED,
                    DEFAULT_ENABLED,
                ),
            )

        val enabled: StateFlow<Boolean> =
            _enabled.asStateFlow()

        fun setEnabled(enabled: Boolean) {
            preferences
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()

            _enabled.value = enabled
        }

        companion object {
            private const val FILE_NAME = "voxpen_personal_learning"
            private const val KEY_ENABLED = "enabled"
            const val DEFAULT_ENABLED = true
        }
    }
