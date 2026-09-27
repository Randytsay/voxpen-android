package com.voxpen.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.AppDatabase
import com.voxpen.app.data.local.ClipboardDao
import com.voxpen.app.data.local.ClipboardEntry
import com.voxpen.app.data.local.ClipboardEntryType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ClipboardRepositoryTest {
    private val database = mockk<AppDatabase>()
    private val dao = mockk<ClipboardDao>()
    private lateinit var repository: ClipboardRepository

    @BeforeEach
    fun setUp() {
        every { database.clipboardDao() } returns dao
        repository = ClipboardRepository(database)
    }

    @Test
    fun `record history inserts and trims unpinned entries`() =
        runTest {
            val inserted = slot<ClipboardEntry>()
            coEvery { dao.findExact(ClipboardEntryType.HISTORY.name, "常用內容", "") } returns null
            coEvery { dao.insert(capture(inserted)) } returns 7L
            coEvery {
                dao.trimUnpinned(
                    ClipboardEntryType.HISTORY.name,
                    ClipboardRepository.MAX_HISTORY_ENTRIES,
                )
            } returns Unit

            val result = repository.recordHistory("  常用內容  ")

            assertThat(result).isTrue()
            assertThat(inserted.captured.text).isEqualTo("常用內容")
            assertThat(inserted.captured.type).isEqualTo(ClipboardEntryType.HISTORY.name)
            assertThat(inserted.captured.isPinned).isFalse()
            coVerify { dao.trimUnpinned(ClipboardEntryType.HISTORY.name, ClipboardRepository.MAX_HISTORY_ENTRIES) }
        }

    @Test
    fun `recording an existing history item touches it instead of duplicating`() =
        runTest {
            val existing =
                ClipboardEntry(
                    id = 9,
                    text = "已存在",
                    type = ClipboardEntryType.HISTORY.name,
                    createdAt = 1,
                    updatedAt = 1,
                )
            coEvery { dao.findExact(ClipboardEntryType.HISTORY.name, "已存在", "") } returns existing
            coEvery { dao.touch(9, any()) } returns Unit
            coEvery {
                dao.trimUnpinned(
                    ClipboardEntryType.HISTORY.name,
                    ClipboardRepository.MAX_HISTORY_ENTRIES,
                )
            } returns Unit

            val result = repository.recordHistory("已存在")

            assertThat(result).isTrue()
            coVerify(exactly = 1) { dao.touch(9, any()) }
            coVerify(exactly = 0) { dao.insert(any()) }
        }

    @Test
    fun `common phrase is not automatically pinned and symbols use their own type`() =
        runTest {
            coEvery { dao.findExact(any(), any(), any()) } returns null
            coEvery { dao.insert(any()) } returnsMany listOf(1L, 2L)
            coEvery { dao.trimUnpinned(any(), any()) } returns Unit

            assertThat(repository.addCommonPhrase("回覆內容")).isTrue()
            assertThat(repository.addSymbol("→")).isTrue()

            coVerify { dao.insert(match { it.type == ClipboardEntryType.COMMON.name && !it.isPinned && !it.isFavorite }) }
            coVerify { dao.insert(match { it.type == ClipboardEntryType.SYMBOL.name && it.isPinned }) }
        }

    @Test
    fun `saving an existing common phrase does not count as using it`() =
        runTest {
            val existing =
                ClipboardEntry(
                    id = 12,
                    text = "測試片語",
                    type = ClipboardEntryType.COMMON.name,
                    createdAt = 1,
                    updatedAt = 1,
                )
            coEvery { dao.findExact(ClipboardEntryType.COMMON.name, "測試片語", "") } returns existing

            assertThat(repository.addCommonPhrase("測試片語")).isTrue()

            coVerify(exactly = 0) { dao.touch(any(), any()) }
            coVerify(exactly = 0) { dao.insert(any()) }
        }
}
