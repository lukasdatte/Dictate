package net.devemperor.dictate.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionEntity
import java.io.File

/**
 * Persistence bridge for [Recording]s. Handles the filesystem side
 * (copy from cache to persistent storage, Opus compression) and the DB
 * side (reading session metadata, deleting audio).
 *
 * This is the single place where audio files get promoted from cache
 * to persistent storage. No other code should write to files/recordings/.
 */
class RecordingRepository(private val context: Context) {

    companion object {
        private const val TAG = "RecordingRepository"
        private const val RECORDINGS_DIR = "recordings"
        private const val EXT_M4A = "m4a"
        private const val EXT_OPUS = "opus"

        @Suppress("unused") // Kept for when the Opus stub below is wired up.
        private const val OPUS_BITRATE_BPS = 16_000
    }

    private val recordingsDir: File by lazy {
        File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
    }

    /**
     * Copies the cache file into persistent storage and returns a [Recording].
     * This is SYNCHRONOUS and fast (file copy only, no network).
     *
     * Note: Opus compression is currently a stub ([compressToOpus]). Callers
     * that eventually want Opus-encoded output MUST invoke it explicitly once
     * the encoder is wired in — it is no longer called unconditionally here,
     * so a silent no-op cannot masquerade as a successful compression.
     *
     * @throws java.io.IOException if the copy fails
     */
    fun persistFromCache(cacheFile: File, sessionId: String): Recording {
        val dest = persistentFileFor(sessionId)
        cacheFile.copyTo(dest, overwrite = true)
        return Recording(dest, sessionId)
    }

    /**
     * Extracts the duration (in seconds) from the audio file via
     * [MediaMetadataRetriever]. Returns 0 on failure (file missing, corrupt,
     * format unreadable, ...).
     *
     * Lives on the repository (not on [Recording]) so the value object stays
     * JVM-testable and free of Android framework imports. Used by the
     * recording pipeline AND by
     * [net.devemperor.dictate.database.DurationHealingJob] (single source of
     * truth — resolves Open Decision SA-1).
     *
     * Exposed as a member function so Java callers can dispatch through the
     * repository instance.
     */
    fun extractDurationSeconds(file: File): Long {
        if (!file.exists()) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            ms / 1000L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract duration for ${file.absolutePath}", e)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * **STUB — not yet implemented.** Currently returns the input [Recording]
     * unchanged. Kept in the API so call-sites can start migrating once an
     * encoder is wired in.
     *
     * TODO: Wire up an Opus encoder (NDK libopus, or a Java port such as
     * `concentus`). Once implemented, it should:
     *   1. Encode [recording.audioFile] (m4a) into an `.opus` file at
     *      ~[OPUS_BITRATE_BPS] bps for ~90% storage reduction.
     *   2. Delete the m4a source AFTER successful encoding (crash mid-encode
     *      must leave a valid m4a fallback on disk).
     *   3. Return a new [Recording] pointing at the `.opus` file.
     *
     * Whisper accepts both m4a and Opus natively — re-encoding is not
     * required when re-transcribing an Opus-compressed recording.
     */
    fun compressToOpus(recording: Recording): Recording {
        Log.d(TAG, "compressToOpus: stub — returning m4a unchanged for ${recording.sessionId}")
        return recording
    }

    /**
     * Loads a [Recording] given a session ID. Returns [LoadResult.FileMissing]
     * if the audio file no longer exists on disk.
     */
    fun loadBySessionId(sessionId: String): LoadResult {
        val session = DictateDatabase.getInstance(context).sessionDao().getById(sessionId)
            ?: return LoadResult.SessionNotFound

        val path = session.audioFilePath
            ?: return LoadResult.FileMissing(session)

        val file = File(path)
        if (!file.exists()) return LoadResult.FileMissing(session)

        return LoadResult.Available(Recording(file, sessionId), session)
    }

    /**
     * Deletes **all** audio files a session references, then clears both audio
     * columns. Transcription and processing results are preserved.
     *
     * F-113 (delete side): a RECORDED multi-segment session owns N files listed
     * in `audio_file_paths` (ADR-0007) plus, potentially, the persistent
     * `filesDir/recordings/{sid}.m4a` copy. The previous implementation deleted
     * only the single legacy `audio_file_path`, orphaning segments 2..N (and any
     * persistent copy) on disk indefinitely. This now sweeps:
     *   - every `audio_file_paths` entry,
     *   - the legacy `audio_file_path`,
     *   - the persistent `recordings/{sid}.m4a` copy (via [persistentFileFor],
     *     the same path logic [persistFromCache] writes).
     *
     * After deletion, [loadBySessionId] returns [LoadResult.FileMissing],
     * hiding the reprocess/play buttons automatically.
     *
     * Best-effort ordering: files are removed from disk before the DB row is
     * cleared. If the process dies between the file-delete and the DB-update,
     * [net.devemperor.dictate.database.DurationHealingJob] reconciles the
     * inconsistency on the next launch — it promotes sessions whose audio
     * points at a missing file to FAILED with an UNKNOWN error type, so the UI
     * stays consistent.
     *
     * @return true if deletion ran (files gone or already absent), false on DB error
     */
    fun deleteBySessionId(sessionId: String): Boolean {
        val dao = DictateDatabase.getInstance(context).sessionDao()
        val session = dao.getById(sessionId) ?: return false

        // De-dupe: the legacy column commonly mirrors segment 1, and the
        // persistent copy shares the recordings/{sid}.m4a path with nothing
        // else — but a set keeps a double-delete harmless if they ever alias.
        val paths = buildSet {
            addAll(session.audioFilePaths)
            session.audioFilePath?.let { add(it) }
            add(persistentFileFor(sessionId).absolutePath)
        }
        for (path in paths) {
            if (path.isEmpty()) continue
            val file = File(path)
            if (file.exists()) file.delete()
        }

        dao.clearAudioFilePath(sessionId)
        return true
    }

    /**
     * The persistent-storage path for a session's promoted audio copy.
     * Single source of the `recordings/{sid}.m4a` layout that
     * [persistFromCache] writes — reused here so the delete side cannot drift
     * from the write side.
     */
    private fun persistentFileFor(sessionId: String): File =
        File(recordingsDir, "$sessionId.$EXT_M4A")

    sealed class LoadResult {
        data class Available(val recording: Recording, val session: SessionEntity) : LoadResult()
        data class FileMissing(val session: SessionEntity) : LoadResult()
        object SessionNotFound : LoadResult()
    }
}
