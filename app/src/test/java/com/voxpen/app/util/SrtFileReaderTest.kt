package com.voxpen.app.util

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

class SrtFileReaderTest {
    @Test
    fun `reads UTF8 subtitles`() {
        val content = "1\n00:00:01,000 --> 00:00:02,000\n你好"

        val result = SrtFileReader.read(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))

        assertThat(result).isEqualTo(content)
    }

    @Test
    fun `rejects bytes beyond five megabytes while reading`() {
        val content = ByteArray(SrtFileReader.MAX_BYTES + 1)

        val failure = runCatching { SrtFileReader.read(ByteArrayInputStream(content)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("too large")
    }
}
