package net.devemperor.dictate.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File

/**
 * Reads [CodecParams] from a finalised audio segment via Android's
 * [MediaExtractor].
 *
 * Used by the B1.2 Cold-Resume path: before allocating a new
 * [android.media.MediaRecorder] that will append a segment to an
 * existing session, the [net.devemperor.dictate.core.RecordingHardwareAdapter]
 * reads the codec params from the previous segment so the new recorder
 * matches them exactly. Without this match the eventual
 * [AudioFileRepository.readForPipeline] MediaMuxer concat would fail
 * (`addTrack` rejects format-incompatible segments — ADR-0007
 * Failure-Modes §1).
 *
 * **Failure semantics.** Returns `null` when the file is missing,
 * empty, or cannot be opened as a media container (e.g. partial MP4
 * without `moov` — a crash-interrupted segment). Callers fall back to
 * [CodecParams.DEFAULT_AAC_M4A]; the user-visible effect is at most
 * one segment recorded with default params instead of inherited ones,
 * which `MediaMuxer.addTrack` will still accept as long as the
 * defaults match the rest of the chain.
 */
object AudioCodecReader {

    private const val TAG = "AudioCodecReader"

    /**
     * Return the [CodecParams] of the first audio track in [file], or
     * `null` if the file cannot be read or has no usable track.
     *
     * The function does not throw — all failure modes (missing file,
     * unreadable container, missing keys in [MediaFormat]) map to
     * `null` so the caller can transparently fall back.
     */
    fun readCodecParams(file: File): CodecParams? {
        if (!file.exists() || file.length() == 0L) {
            return null
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount == 0) {
                Log.w(TAG, "No tracks in ${file.name}")
                return null
            }
            val fmt = extractor.getTrackFormat(0)
            CodecParams(
                sampleRate = fmt.optInt(MediaFormat.KEY_SAMPLE_RATE)
                    ?: return null.also { Log.w(TAG, "Missing KEY_SAMPLE_RATE in ${file.name}") },
                channelCount = fmt.optInt(MediaFormat.KEY_CHANNEL_COUNT)
                    ?: return null.also { Log.w(TAG, "Missing KEY_CHANNEL_COUNT in ${file.name}") },
                // KEY_BIT_RATE is optional in some MediaFormat outputs;
                // fall back to the default when absent.
                bitRate = fmt.optInt(MediaFormat.KEY_BIT_RATE)
                    ?: CodecParams.DEFAULT_AAC_M4A.bitRate,
                mimeType = fmt.getString(MediaFormat.KEY_MIME)
                    ?: CodecParams.DEFAULT_AAC_M4A.mimeType,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read codec params from ${file.name}", e)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.optInt(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null
}
