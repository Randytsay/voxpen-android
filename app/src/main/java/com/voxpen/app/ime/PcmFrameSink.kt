package com.voxpen.app.ime

/**
 * Receives bounded, fixed-size PCM frames from [AudioRecorder]. Implementations
 * must return quickly; returning false only means the streaming path dropped a
 * frame. The recorder always keeps its local PCM copy for fallback.
 */
fun interface PcmFrameSink {
    fun offer(frame: ByteArray): Boolean
}
