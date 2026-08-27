package com.voxpen.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.CorrectionManualLevel
import com.voxpen.app.data.local.CorrectionMemoryEntity
import org.junit.jupiter.api.Test

class CorrectionMemoryRepositoryTest {
    @Test
    fun `confidence rises with repeated corrections`() {
        assertThat(CorrectionMemoryRepository.confidenceForHits(1)).isEqualTo(0.45)
        assertThat(CorrectionMemoryRepository.confidenceForHits(2)).isEqualTo(0.65)
        assertThat(CorrectionMemoryRepository.confidenceForHits(3)).isEqualTo(0.82)
        assertThat(CorrectionMemoryRepository.confidenceForHits(4)).isEqualTo(0.90)
        assertThat(CorrectionMemoryRepository.confidenceForHits(5)).isEqualTo(0.95)
        assertThat(CorrectionMemoryRepository.confidenceForHits(20)).isEqualTo(0.95)
    }

    @Test
    fun `manual level overrides automatic confidence without deleting it`() {
        val entity =
            CorrectionMemoryEntity(
                wrongText = "帶師兄",
                correctText = "戴師兄",
                hitCount = 5,
                autoConfidence = 0.95,
                manualLevel = CorrectionManualLevel.LOW.name,
            )

        assertThat(
            CorrectionMemoryRepository.effectiveConfidence(entity),
        ).isEqualTo(0.35)
        assertThat(entity.autoConfidence).isEqualTo(0.95)
    }

    @Test
    fun `fixed and high rules are direct while low and medium are hints`() {
        assertThat(
            CorrectionMemoryRepository.shouldDirectlyApply(CorrectionManualLevel.FIXED, 0.0),
        ).isTrue()
        assertThat(
            CorrectionMemoryRepository.shouldDirectlyApply(CorrectionManualLevel.HIGH, 0.0),
        ).isTrue()
        assertThat(
            CorrectionMemoryRepository.shouldDirectlyApply(CorrectionManualLevel.MEDIUM, 1.0),
        ).isFalse()
        assertThat(
            CorrectionMemoryRepository.shouldDirectlyApply(CorrectionManualLevel.LOW, 1.0),
        ).isFalse()
    }

    @Test
    fun `auto rules only become direct at conservative threshold`() {
        assertThat(
            CorrectionMemoryRepository.shouldDirectlyApply(CorrectionManualLevel.AUTO, 0.82),
        ).isFalse()
        assertThat(
            CorrectionMemoryRepository.shouldDirectlyApply(CorrectionManualLevel.AUTO, 0.90),
        ).isTrue()
    }
}
