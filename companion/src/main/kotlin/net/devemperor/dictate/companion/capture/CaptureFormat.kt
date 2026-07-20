package net.devemperor.dictate.companion.capture

import javax.sound.sampled.AudioFormat

/**
 * The one fixed capture format for the desktop host: **16 kHz, mono, 16-bit signed little-endian
 * PCM**, written as WAV (desktop-host.md §4.1, decision D4.2).
 *
 * Fixed on purpose: it is the lowest-common-denominator every transcription provider accepts, it
 * needs no encoder (so no new dependency against the ADR-0015 Kotlin ceiling — javax.sound is in the
 * JDK), and a single format means [WavConcat] can merge rolling segments by raw byte concatenation.
 *
 * At this format the WAV grows ~1.92 MB/min (16000 · 2 bytes · 60 s); the provider upload-limit
 * budget this implies is documented in the D1b chunk report (§4.5, spec §15 Gap 2).
 */
object CaptureFormat {

    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_FRAME = CHANNELS * (BITS_PER_SAMPLE / 8)

    /** Bytes of PCM per second — the divisor when turning a byte count into a duration. */
    const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * BYTES_PER_FRAME

    /**
     * A read buffer sized so `TargetDataLine.read` returns roughly every 100 ms — the cadence the
     * amplitude feed emits at (~10 Hz, §4.4). `16000 · 2 · 0.1 = 3200` bytes.
     */
    const val READ_BUFFER_BYTES = BYTES_PER_SECOND / 10

    /** The javax.sound descriptor for the fixed format (signed, little-endian). */
    fun audioFormat(): AudioFormat = AudioFormat(
        SAMPLE_RATE_HZ.toFloat(),
        BITS_PER_SAMPLE,
        CHANNELS,
        /* signed = */ true,
        /* bigEndian = */ false,
    )
}
