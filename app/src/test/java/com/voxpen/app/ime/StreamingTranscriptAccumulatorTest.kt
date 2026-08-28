package com.voxpen.app.ime

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StreamingTranscriptAccumulatorTest {
    @Test
    fun `interim replaces previous hypothesis and is never stable`() {
        val accumulator = StreamingTranscriptAccumulator()

        accumulator.acceptInterim("今天")
        accumulator.acceptInterim("今天戴師兄")

        assertThat(accumulator.snapshot().previewText).isEqualTo("今天戴師兄")
        assertThat(accumulator.stableFinalText()).isEmpty()
    }

    @Test
    fun `final clears interim and duplicate segment id is ignored`() {
        val accumulator = StreamingTranscriptAccumulator()

        accumulator.acceptInterim("帶師兄")
        accumulator.acceptFinal("戴師兄", segmentId = "segment-1")
        accumulator.acceptFinal("戴師兄", segmentId = "segment-1")

        assertThat(accumulator.stableFinalText()).isEqualTo("戴師兄")
        assertThat(accumulator.snapshot().interimText).isEmpty()
    }

    @Test
    fun `merges CJK boundary without inserting a space`() {
        assertThat(TranscriptOverlapMerger.merge("今天戴", "戴師兄要去台中"))
            .isEqualTo("今天戴師兄要去台中")
    }

    @Test
    fun `merges English boundary with a space`() {
        assertThat(TranscriptOverlapMerger.merge("hello", "world"))
            .isEqualTo("hello world")
    }
}
