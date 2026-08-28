package com.voxpen.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores only successfully committed text, partitioned by the target app package.
 * This deliberately uses DataStore rather than Room so it adds no schema migration.
 */
@Singleton
class ContextMemoryManager
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val json: Json,
    ) {
        suspend fun getRecentInputs(packageName: String): List<String> {
            val normalizedPackage = packageName.trim()
            if (normalizedPackage.isBlank()) return emptyList()
            return decode(dataStore.data.first()[CONTEXT_HISTORY_KEY])[normalizedPackage].orEmpty()
        }

        suspend fun append(
            packageName: String,
            text: String,
        ) {
            val normalizedPackage = packageName.trim()
            val entry = text.trim().take(MAX_ENTRY_CHARS)
            if (normalizedPackage.isBlank() || entry.isBlank()) return

            dataStore.edit { preferences ->
                val histories = decode(preferences[CONTEXT_HISTORY_KEY]).toMutableMap()
                histories[normalizedPackage] =
                    (histories[normalizedPackage].orEmpty() + entry).takeLast(MAX_ENTRIES)
                preferences[CONTEXT_HISTORY_KEY] = json.encodeToString(histories)
            }
        }

        private fun decode(raw: String?): Map<String, List<String>> =
            runCatching {
                json.decodeFromString<Map<String, List<String>>>(raw ?: "{}")
            }.getOrDefault(emptyMap())

        companion object {
            const val MAX_ENTRIES = 5
            const val MAX_ENTRY_CHARS = 2_000
            private val CONTEXT_HISTORY_KEY = stringPreferencesKey("recent_context_by_app")
        }
    }
