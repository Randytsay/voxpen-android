package com.voxpen.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SrtParserTest {
    @Test
    fun `parses standard indexed cues`() {
        val content =
            """
            1
            00:00:01,000 --> 00:00:02,500
            Hello world

            2
            00:00:03,000 --> 00:00:04,000
            Second cue
            """.trimIndent()

        val segments = SrtParser.parse(content).getOrThrow()

        assertThat(segments).hasSize(2)
        assertThat(segments[0].startMs).isEqualTo(1000)
        assertThat(segments[0].endMs).isEqualTo(2500)
        assertThat(segments[0].text).isEqualTo("Hello world")
        assertThat(segments[1].text).isEqualTo("Second cue")
    }

    @Test
    fun `accepts cues without index lines`() {
        val content = "00:00:00,000 --> 00:00:01,000\nNo index here"

        val segments = SrtParser.parse(content).getOrThrow()

        assertThat(segments).hasSize(1)
        assertThat(segments[0].text).isEqualTo("No index here")
    }

    @Test
    fun `handles BOM CRLF dot fractions coordinates and multiline text`() {
        val content =
            "\uFEFF1\r\n" +
                "00:00:01.5 --> 00:00:02.50 X1:0 Y1:0 X2:100 Y2:50\r\n" +
                "First line\r\nSecond line\r\n"

        val segment = SrtParser.parse(content).getOrThrow().single()

        assertThat(segment.startMs).isEqualTo(1500)
        assertThat(segment.endMs).isEqualTo(2500)
        assertThat(segment.text).isEqualTo("First line\nSecond line")
    }

    @Test
    fun `skips blank cues`() {
        val content =
            """
            1
            00:00:01,000 --> 00:00:02,000

            2
            00:00:03,000 --> 00:00:04,000
            Kept cue
            """.trimIndent()

        val segments = SrtParser.parse(content).getOrThrow()

        assertThat(segments).hasSize(1)
        assertThat(segments[0].text).isEqualTo("Kept cue")
    }

    @Test
    fun `rejects empty or malformed SRT`() {
        val empty = SrtParser.parse("")
        val malformed = SrtParser.parse("not a cue\n00:00:01,000 --> 00:00:02,000\ntext")

        assertThat(empty.isFailure).isTrue()
        assertThat(empty.exceptionOrNull()?.message).contains("no subtitle cues")
        assertThat(malformed.isFailure).isTrue()
        assertThat(malformed.exceptionOrNull()?.message).contains("expected SRT index or timing line")
    }

    @Test
    fun `rejects timestamps without fractional seconds`() {
        val result = SrtParser.parse("00:00:01 --> 00:00:02,000\nNo fraction")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("missing fraction")
    }
}
