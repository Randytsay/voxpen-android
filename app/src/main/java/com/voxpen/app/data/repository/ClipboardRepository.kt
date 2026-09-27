package com.voxpen.app.data.repository

import com.voxpen.app.data.local.AppDatabase
import com.voxpen.app.data.local.ClipboardEntry
import com.voxpen.app.data.local.ClipboardEntryType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardRepository
    @Inject
    constructor(
        database: AppDatabase,
    ) {
        private val dao = database.clipboardDao()

        suspend fun getEntries(type: ClipboardEntryType): List<ClipboardEntry> = dao.getByType(type.name)

        suspend fun recordHistory(text: String): Boolean =
            saveOrTouch(
                text = text,
                type = ClipboardEntryType.HISTORY,
                groupName = "",
                pinned = false,
            )

        suspend fun addCommonPhrase(
            text: String,
            groupName: String = "",
            shortcut: String = "",
            label: String = "",
        ): Boolean =
            saveOrTouch(
                text = text,
                type = ClipboardEntryType.COMMON,
                groupName = groupName,
                pinned = false,
                shortcut = shortcut,
                label = label,
            )

        suspend fun addSymbol(
            text: String,
            groupName: String = "自訂",
        ): Boolean =
            saveOrTouch(
                text = text,
                type = ClipboardEntryType.SYMBOL,
                groupName = groupName,
                pinned = true,
            )

        suspend fun update(
            entry: ClipboardEntry,
            text: String,
            shortcut: String = entry.shortcut,
            groupName: String = entry.groupName,
            label: String = entry.label,
        ): Boolean {
            val cleaned = cleanText(text) ?: return false
            val normalizedGroup = groupName.trim().take(MAX_GROUP_CHARS)
            val cleanedLabel = label.trim().take(MAX_LABEL_CHARS)
            val duplicate = dao.findExact(entry.type, cleaned, normalizedGroup)
            if (duplicate != null && duplicate.id != entry.id) return false
            val cleanedShortcut = cleanShortcut(shortcut)
            if (entry.type == ClipboardEntryType.COMMON.name && cleanedShortcut.isNotEmpty()) {
                val shortcutOwner = dao.findByShortcut(cleanedShortcut)
                if (shortcutOwner != null && shortcutOwner.id != entry.id) return false
            }
            if (entry.type == ClipboardEntryType.COMMON.name) {
                dao.updateCommonPhrase(
                    entry.id,
                    cleaned,
                    normalizedGroup,
                    cleanedShortcut,
                    cleanedLabel,
                    System.currentTimeMillis(),
                )
            } else {
                dao.updateText(entry.id, cleaned, System.currentTimeMillis())
            }
            return true
        }

        suspend fun findShortcutMatches(shortcut: String): List<ClipboardEntry> {
            val cleaned = cleanShortcut(shortcut)
            if (cleaned.isBlank()) return emptyList()
            return dao.findShortcutMatches(cleaned)
        }

        suspend fun touch(entry: ClipboardEntry) {
            dao.touch(entry.id, System.currentTimeMillis())
        }

        suspend fun setPinned(
            entry: ClipboardEntry,
            pinned: Boolean,
        ) {
            dao.setPinned(entry.id, pinned)
        }

        suspend fun setFavorite(
            entry: ClipboardEntry,
            favorite: Boolean,
        ) {
            dao.setFavorite(entry.id, favorite)
        }

        suspend fun delete(entry: ClipboardEntry) {
            dao.delete(entry.id)
        }

        private suspend fun saveOrTouch(
            text: String,
            type: ClipboardEntryType,
            groupName: String,
            pinned: Boolean,
            shortcut: String = "",
            label: String = "",
        ): Boolean {
            val cleaned = cleanText(text) ?: return false
            val normalizedGroup = groupName.trim().take(MAX_GROUP_CHARS)
            val normalizedShortcut = if (type == ClipboardEntryType.COMMON) cleanShortcut(shortcut) else ""
            val normalizedLabel = if (type == ClipboardEntryType.COMMON) label.trim().take(MAX_LABEL_CHARS) else ""
            val now = System.currentTimeMillis()
            val existing = dao.findExact(type.name, cleaned, normalizedGroup)
            if (normalizedShortcut.isNotEmpty()) {
                val shortcutOwner = dao.findByShortcut(normalizedShortcut)
                if (shortcutOwner != null && shortcutOwner.id != existing?.id) return false
            }
            if (existing != null) {
                // Saving an existing phrase is not a use: only a successful IME insertion
                // should affect recency and frequency. History duplicates still represent
                // a new copy event and retain the previous touch behavior.
                if (type == ClipboardEntryType.HISTORY) dao.touch(existing.id, now)
                if (pinned && !existing.isPinned) dao.setPinned(existing.id, true)
                if (type == ClipboardEntryType.COMMON &&
                    (existing.shortcut != normalizedShortcut || existing.label != normalizedLabel)
                ) {
                    dao.updateCommonPhrase(
                        existing.id,
                        cleaned,
                        normalizedGroup,
                        normalizedShortcut,
                        normalizedLabel.ifBlank { existing.label },
                        now,
                    )
                }
            } else {
                val inserted =
                    dao.insert(
                        ClipboardEntry(
                            text = cleaned,
                            type = type.name,
                            groupName = normalizedGroup,
                            shortcut = normalizedShortcut,
                            label = normalizedLabel,
                            isPinned = pinned,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                if (inserted <= 0) return false
            }
            if (type == ClipboardEntryType.HISTORY) {
                dao.trimUnpinned(type.name, MAX_HISTORY_ENTRIES)
            }
            return true
        }

        private fun cleanText(text: String): String? = text.trim().take(MAX_TEXT_CHARS).takeIf { it.isNotBlank() }

        private fun cleanShortcut(shortcut: String): String =
            shortcut.trim().lowercase().filterNot(Char::isWhitespace).take(MAX_SHORTCUT_CHARS)

        companion object {
            const val MAX_HISTORY_ENTRIES = 50
            const val MAX_TEXT_CHARS = 2_000
            const val MAX_SHORTCUT_CHARS = 32
            private const val MAX_GROUP_CHARS = 40
            private const val MAX_LABEL_CHARS = 60
        }
    }
