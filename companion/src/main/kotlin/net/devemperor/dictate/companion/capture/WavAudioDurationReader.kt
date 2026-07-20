package net.devemperor.dictate.companion.capture

import net.devemperor.dictate.ai.port.AudioDurationReader
import java.io.File

/**
 * Companion backing for the shared [AudioDurationReader] port (shared-ai-extraktion.md §4.4): the
 * whole-second length of a fixed-format WAV, read from its `data` chunk size — the desktop analogue of
 * Android's `MediaMetadataRetriever`.
 *
 * Returns `-1` on any failure, exactly like the Android reader, so the AI core's `-1` fallback path
 * stays identical across hosts.
 */
object WavAudioDurationReader : AudioDurationReader {

    override fun durationSeconds(file: File): Long = try {
        WavHeader.dataChunk(file).length / CaptureFormat.BYTES_PER_SECOND
    } catch (e: Exception) {
        -1
    }
}
