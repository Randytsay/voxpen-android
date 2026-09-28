package com.voxpen.app.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads subtitle files with a hard limit before decoding or sending text to an LLM. */
object SrtFileReader {
    const val MAX_BYTES = 5 * 1024 * 1024

    fun read(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) {
                throw IllegalArgumentException("SRT file too large (max 5 MB).")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
