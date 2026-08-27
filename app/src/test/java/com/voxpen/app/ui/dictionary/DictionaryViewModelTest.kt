package com.voxpen.app.ui.dictionary

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voxpen.app.billing.ProSource
import com.voxpen.app.billing.ProStatus
import com.voxpen.app.billing.ProStatusResolver
import com.voxpen.app.data.local.DictionaryEntry
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.repository.DictionaryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryViewModelTest {
    private val repository: DictionaryRepository = mockk()
    private val preferencesManager: PreferencesManager = mockk()
    private val proStatusResolver: ProStatusResolver = mockk()

    private val testDispatcher =
        UnconfinedTestDispatcher()

    private val entriesFlow =
        MutableStateFlow<List<DictionaryEntry>>(
            emptyList(),
        )

    private val countFlow =
        MutableStateFlow(0)

    private val proStatusFlow =
        MutableStateFlow<ProStatus>(
            ProStatus.Free,
        )

    private val importantWordsFlow =
        MutableStateFlow<Set<String>>(
            emptySet(),
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(
            testDispatcher,
        )

        every {
            repository.getAll()
        } returns entriesFlow

        every {
            repository.count()
        } returns countFlow

        every {
            proStatusResolver.proStatus
        } returns proStatusFlow

        every {
            preferencesManager.importantWordsFlow
        } returns importantWordsFlow

        coEvery {
            preferencesManager.setImportantWord(
                any(),
                any(),
            )
        } returns Unit
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() =
        DictionaryViewModel(
            repository = repository,
            preferencesManager = preferencesManager,
            proStatusResolver = proStatusResolver,
        )

    @Test
    fun `should expose entries from repository`() =
        runTest {
            val vm =
                createViewModel()

            val entry =
                DictionaryEntry(
                    id = 1,
                    word = "語墨",
                    createdAt = 1000L,
                )

            entriesFlow.value =
                listOf(entry)

            vm.entries.test {
                assertThat(
                    awaitItem(),
                ).containsExactly(
                    entry,
                )
            }
        }

    @Test
    fun `should expose count from repository`() =
        runTest {
            val vm =
                createViewModel()

            countFlow.value =
                3

            vm.count.test {
                assertThat(
                    awaitItem(),
                ).isEqualTo(
                    3,
                )
            }
        }

    @Test
    fun `should expose isPro from pro status resolver`() =
        runTest {
            val vm =
                createViewModel()

            proStatusFlow.value =
                ProStatus.Pro(
                    ProSource.GOOGLE_PLAY,
                )

            vm.isPro.test {
                assertThat(
                    awaitItem(),
                ).isTrue()
            }
        }

    @Test
    fun `should expose important words from preferences`() =
        runTest {
            val vm =
                createViewModel()

            importantWordsFlow.value =
                setOf(
                    "OPC-3",
                    "WebCTRL",
                )

            vm.importantWords.test {
                assertThat(
                    awaitItem(),
                ).containsExactly(
                    "OPC-3",
                    "WebCTRL",
                )
            }
        }

    @Test
    fun `should add word via repository`() =
        runTest {
            coEvery {
                repository.add(
                    any(),
                )
            } returns 1L

            val vm =
                createViewModel()

            vm.addWord(
                "語墨",
            )

            coVerify {
                repository.add(
                    "語墨",
                )
            }
        }

    @Test
    fun `should not add blank word`() =
        runTest {
            val vm =
                createViewModel()

            vm.addWord(
                "   ",
            )

            coVerify(
                exactly = 0,
            ) {
                repository.add(
                    any(),
                )
            }
        }

    @Test
    fun `should detect duplicate when insert returns negative one`() =
        runTest {
            coEvery {
                repository.add(
                    any(),
                )
            } returns -1L

            val vm =
                createViewModel()

            vm.addWord(
                "語墨",
            )

            vm.showDuplicateToast.test {
                assertThat(
                    awaitItem(),
                ).isTrue()
            }
        }

    @Test
    fun `should mark normal word as important`() =
        runTest {
            val vm =
                createViewModel()

            importantWordsFlow.value =
                emptySet()

            vm.toggleImportantWord(
                "OPC-3",
            )

            coVerify {
                preferencesManager
                    .setImportantWord(
                        word = "OPC-3",
                        important = true,
                    )
            }
        }

    @Test
    fun `should unmark existing important word`() =
        runTest {
            importantWordsFlow.value =
                setOf(
                    "OPC-3",
                )

            val vm =
                createViewModel()

            vm.toggleImportantWord(
                "OPC-3",
            )

            coVerify {
                preferencesManager
                    .setImportantWord(
                        word = "OPC-3",
                        important = false,
                    )
            }
        }

    @Test
    fun `should remove entry via repository`() =
        runTest {
            val entry =
                DictionaryEntry(
                    id = 1,
                    word = "test",
                    createdAt = 1000L,
                )

            coEvery {
                repository.remove(
                    entry,
                )
            } returns Unit

            val vm =
                createViewModel()

            vm.removeWord(
                entry,
            )

            coVerify {
                repository.remove(
                    entry,
                )
            }
        }

    @Test
    fun `should remove important flag when deleting entry`() =
        runTest {
            val entry =
                DictionaryEntry(
                    id = 1,
                    word = "OPC-3",
                    createdAt = 1000L,
                )

            coEvery {
                repository.remove(
                    entry,
                )
            } returns Unit

            val vm =
                createViewModel()

            vm.removeWord(
                entry,
            )

            coVerify {
                preferencesManager
                    .setImportantWord(
                        word = "OPC-3",
                        important = false,
                    )
            }
        }

    @Test
    fun `should report limit reached for Free user at configured limit`() =
        runTest {
            val vm =
                createViewModel()

            countFlow.value =
                DictionaryViewModel
                    .FREE_DICTIONARY_LIMIT

            proStatusFlow.value =
                ProStatus.Free

            vm.isLimitReached.test {
                assertThat(
                    awaitItem(),
                ).isTrue()
            }
        }

    @Test
    fun `should not report limit reached for Pro user`() =
        runTest {
            val vm =
                createViewModel()

            countFlow.value =
                DictionaryViewModel
                    .FREE_DICTIONARY_LIMIT

            proStatusFlow.value =
                ProStatus.Pro(
                    ProSource.GOOGLE_PLAY,
                )

            vm.isLimitReached.test {
                assertThat(
                    awaitItem(),
                ).isFalse()
            }
        }

    @Test
    fun `should not report limit reached for Personal user`() =
        runTest {
            val vm =
                createViewModel()

            countFlow.value =
                DictionaryViewModel
                    .FREE_DICTIONARY_LIMIT

            proStatusFlow.value =
                ProStatus.Pro(
                    ProSource.PERSONAL,
                )

            vm.isLimitReached.test {
                assertThat(
                    awaitItem(),
                ).isFalse()
            }
        }

    @Test
    fun `should not add word when limit is reached for Free user`() =
        runTest {
            val vm =
                createViewModel()

            countFlow.value =
                DictionaryViewModel
                    .FREE_DICTIONARY_LIMIT

            proStatusFlow.value =
                ProStatus.Free

            vm.isLimitReached.test {
                assertThat(
                    awaitItem(),
                ).isTrue()
            }

            vm.addWord(
                "語墨",
            )

            coVerify(
                exactly = 0,
            ) {
                repository.add(
                    any(),
                )
            }
        }
}
