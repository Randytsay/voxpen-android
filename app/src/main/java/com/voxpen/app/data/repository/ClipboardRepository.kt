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
        ): Boolean =
            saveOrTouch(
                text = text,
                type = ClipboardEntryType.COMMON,
                groupName = groupName,
                pinned = true,
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
        ): Boolean {
            val cleaned = cleanText(text) ?: return false
            val duplicate = dao.findExact(entry.type, cleaned, entry.groupName)
            if (duplicate != null && duplicate.id != entry.id) return false
            dao.updateText(entry.id, cleaned, System.currentTimeMillis())
            return true
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

        suspend fun delete(entry: ClipboardEntry) {
            dao.delete(entry.id)
        }

        private suspend fun saveOrTouch(
            text: String,
            type: ClipboardEntryType,
            groupName: String,
            pinned: Boolean,
        ): Boolean {
            val cleaned = cleanText(text) ?: return false
            val normalizedGroup = groupName.trim().take(MAX_GROUP_CHARS)
            val now = System.currentTimeMillis()
            val existing = dao.findExact(type.name, cleaned, normalizedGroup)
            if (existing != null) {
                dao.touch(existing.id, now)
                if (pinned && !existing.isPinned) dao.setPinned(existing.id, true)
            } else {
                val inserted =
                    dao.insert(
                        ClipboardEntry(
                            text = cleaned,
                            type = type.name,
                            groupName = normalizedGroup,
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

        companion object {
            const val MAX_HISTORY_ENTRIES = 50
            const val MAX_TEXT_CHARS = 2_000
            private const val MAX_GROUP_CHARS = 40
        }
    }
