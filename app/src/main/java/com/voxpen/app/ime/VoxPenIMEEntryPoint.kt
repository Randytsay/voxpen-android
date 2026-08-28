package com.voxpen.app.ime

import com.voxpen.app.billing.ProStatusResolver
import com.voxpen.app.billing.UsageLimiter
import com.voxpen.app.data.local.ApiKeyManager
import com.voxpen.app.data.local.ContextMemoryManager
import com.voxpen.app.data.local.PersonalLearningPreferences
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.local.RecordingStore
import com.voxpen.app.data.repository.CorrectionMemoryRepository
import com.voxpen.app.data.repository.DictionaryRepository
import com.voxpen.app.data.repository.HybridInputRepository
import com.voxpen.app.data.repository.TranscriptionRepository
import com.voxpen.app.domain.usecase.EditTextUseCase
import com.voxpen.app.domain.usecase.RefineTextUseCase
import com.voxpen.app.domain.usecase.TranscribeAudioUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VoxPenIMEEntryPoint {
    fun transcribeAudioUseCase(): TranscribeAudioUseCase

    fun refineTextUseCase(): RefineTextUseCase

    fun editTextUseCase(): EditTextUseCase

    fun apiKeyManager(): ApiKeyManager

    fun contextMemoryManager(): ContextMemoryManager

    fun preferencesManager(): PreferencesManager

    fun dictionaryRepository(): DictionaryRepository

    fun correctionMemoryRepository(): CorrectionMemoryRepository

    fun hybridInputRepository(): HybridInputRepository

    fun personalLearningPreferences(): PersonalLearningPreferences

    fun transcriptionRepository(): TranscriptionRepository

    fun recordingStore(): RecordingStore

    fun usageLimiter(): UsageLimiter

    fun proStatusResolver(): ProStatusResolver
}
