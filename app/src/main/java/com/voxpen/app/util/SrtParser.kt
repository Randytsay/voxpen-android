package com.voxpen.app.util

import com.voxpen.app.data.repository.TranscriptionSegment

/**
 * Parses SRT subtitle files into timestamped segments.
 *
 * Accepts optional cue indexes, BOM, CRLF/CR line endings, comma or dot
 * milliseconds, 1-3 digit fractions, trailing cue coordinates, and multiline
 * cue text. Blank cues are ignored.
 */
object SrtParser {
    fun parse(content: String): Result<List<TranscriptionSegment>> =
        runCatching {
            val normalized =
                content
                    .removePrefix("\uFEFF")
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
            val lines = normalized.lines()
            val segments = mutableListOf<TranscriptionSegment>()

            var i = 0
            while (i < lines.size) {
                while (i < lines.size && lines[i].isBlank()) i++
                if (i >= lines.size) break

                val (timingLine, timingIndex) = resolveTimingLine(lines, i)
                val (startMs, endMs) = parseTimingLine(timingLine)
                i = timingIndex + 1

                val textLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    textLines += lines[i].trimEnd()
                    i++
                }
                val text = textLines.joinToString("\n").trim()
                if (text.isNotEmpty()) {
                    segments += TranscriptionSegment(startMs, endMs, text)
                }
            }

            if (segments.isEmpty()) error("SRT file contains no subtitle cues")
            segments
        }

    private fun resolveTimingLine(
        lines: List<String>,
        start: Int,
    ): Pair<String, Int> {
        var i = start
        return when {
            lines[i].contains("-->") -> lines[i] to i
            isIndexLine(lines[i]) -> {
                i++
                while (i < lines.size && lines[i].isBlank()) i++
                if (i >= lines.size) error("SRT cue missing timing line after index")
                val next = lines[i]
                if (!next.contains("-->")) {
                    error("expected SRT timing line after index, got: $next")
                }
                next to i
            }
            else -> error("expected SRT index or timing line, got: ${lines[i]}")
        }
    }

    private fun isIndexLine(line: String): Boolean =
        line.isNotEmpty() && line.all { it in '0'..'9' }

    private fun parseTimingLine(line: String): Pair<Long, Long> {
        val trimmed = line.trim()
        val arrow = trimmed.indexOf("-->")
        if (arrow < 0) error("invalid SRT timing line: $line")
        val startPart = trimmed.substring(0, arrow).trim()
        val endPart =
            trimmed.substring(arrow + 3).trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return parseTimestamp(startPart) to parseTimestamp(endPart)
    }

    private fun parseTimestamp(ts: String): Long {
        val separator = ts.indexOfFirst { it == ',' || it == '.' }
        if (separator < 0) error("invalid SRT timestamp (missing fraction): $ts")
        val timePart = ts.substring(0, separator)
        val fractionMs =
            ts.substring(separator + 1)
                .take(3)
                .padEnd(3, '0')
                .toIntOrNull()
                ?: error("invalid SRT timestamp: $ts")
        val parts = timePart.split(":")
        if (parts.size != 3) error("invalid SRT timestamp (expected h:m:s): $ts")
        val hours = parts[0].toLongOrNull() ?: error("invalid SRT timestamp hours: $ts")
        val minutes = parts[1].toLongOrNull() ?: error("invalid SRT timestamp minutes: $ts")
        val seconds = parts[2].toLongOrNull() ?: error("invalid SRT timestamp seconds: $ts")
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + fractionMs
    }
}
