package net.devemperor.dictate.companion.capture

import kotlin.math.abs

/**
 * Turns a just-read block of 16-bit signed little-endian PCM into a single peak magnitude in the
 * same `0..32767` range Android's `MediaRecorder.getMaxAmplitude()` produces (desktop-host.md §4.4).
 *
 * The peak (not RMS) is chosen for parity with the phone: the shared
 * [net.devemperor.dictate.core.AmplitudeProcessor] was tuned against `getMaxAmplitude` peaks, so
 * feeding it a peak reproduces the same waveform on the desktop as on the phone. Pure — no javax.sound
 * types — so it is unit-testable without a capture line.
 */
object PcmAmplitude {

    /**
     * Peak absolute sample amplitude across the first [len] bytes of [buffer].
     *
     * [len] is honoured (not `buffer.size`) because `TargetDataLine.read` fills only part of the
     * buffer on a short read; folding the stale tail in would report a phantom level. An odd [len]
     * ignores the trailing half-sample rather than reading past it.
     */
    fun peak(buffer: ByteArray, len: Int): Int {
        var peak = 0
        var i = 0
        val end = len - 1 // need two bytes for a sample
        while (i < end) {
            // low byte unsigned, high byte sign-extended → signed 16-bit little-endian.
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            // Clamp to 32767: a full-scale negative sample is -32768, and abs(-32768) = 32768,
            // one above Android getMaxAmplitude's 0..32767 range the shared AmplitudeProcessor is tuned for.
            val magnitude = minOf(abs(sample), 32767)
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }
}
