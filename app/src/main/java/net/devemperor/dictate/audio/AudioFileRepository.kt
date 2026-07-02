package net.devemperor.dictate.audio

import java.io.File

/**
 * Repository that owns the multi-file audio storage layout for recording
 * sessions (ADR-0007 Multi-File Audio Repository).
 *
 * **Sole owner of the on-disk naming convention.** Implementations write
 * segment files under a dedicated subdirectory using a deterministic
 * `{prefix}{sessionId}{infix}{N}{ext}` scheme. Callers receive
 * [File] handles only — they never construct path strings themselves.
 * This is the architectural invariant that lets Pipeline / State /
 * Recovery layers see "one audio per session" while the repository
 * silently manages N segments.
 *
 * **Why a repository instead of extending [net.devemperor.dictate.state.AudioFileFactory]?**
 * The factory's `allocate()` is session-id-less by design — every
 * call returns a fresh random-named file. ADR-0007's "Resume after
 * Cold-Start" requires deterministic re-discovery of *previous*
 * segments belonging to a known session, which forces a session-id-
 * keyed API. The two coexist during the migration window: the legacy
 * factory continues to serve the live-recording path until Phase 3 of
 * the rollout flips every call site to this repository.
 *
 * **Threading.** All methods except [readForPipeline] are O(`listFiles`)
 * and safe to call from the main thread (the dispatch loop is
 * `Dispatchers.Main.immediate`-confined per [net.devemperor.dictate.state.DictateOrchestrator]).
 * [readForPipeline] is `suspend` and must hop to `Dispatchers.IO`
 * internally — single-segment sessions return synchronously (zero-copy),
 * multi-segment sessions go through MediaMuxer concatenation.
 *
 * @see net.devemperor.dictate.core.CacheDirAudioFileRepository
 * @see docs/decisions/0007-audio-multi-file-repository.md
 */
interface AudioFileRepository {

    /**
     * Allocate the first segment file path for a fresh session. The
     * file is **not** created on disk by this call — `MediaRecorder.start()`
     * (run later in the recording pipeline) materialises it.
     *
     * @throws java.io.IOException when the audio cache directory cannot
     *   be created (storage full, FS permission). Callers MUST catch
     *   and translate to a user-visible toast; the reducer never sees
     *   the failure (R.2 pure-reducer invariant).
     */
    @Throws(java.io.IOException::class)
    fun allocateFirst(sessionId: String): File

    /**
     * Allocate the next segment file path for an existing session
     * (Cold-Resume / "Fortsetzen" path). The segment index is the
     * highest existing index for [sessionId] plus one, or 1 when no
     * segments exist yet (idempotent with [allocateFirst]).
     *
     * @throws java.io.IOException when the audio cache directory cannot
     *   be created.
     */
    @Throws(java.io.IOException::class)
    fun allocateNext(sessionId: String): File

    /**
     * Return every existing segment for [sessionId], sorted ascending
     * by segment index. Empty list when no segments exist (or the
     * cache dir is gone).
     *
     * Filenames that match the session prefix but have a malformed
     * index (e.g. `sess_x_seg.m4a` without a number) are skipped, not
     * thrown — the repository is tolerant of foreign content in the
     * audio directory.
     */
    fun segments(sessionId: String): List<File>

    /**
     * Return every segment of [sessionId] that holds actual recorded
     * audio — i.e. [segments] minus zero-length artifacts.
     *
     * **Why this exists (F-012 / F-014 / F-047 — the rolling-segment
     * consumer trio).** The rolling-segment durability fix
     * ([net.devemperor.dictate.core.RecordingHardwareAdapter]'s
     * always-one-ahead pre-arm, 2026-06-10) hands the framework the
     * *next* output file via `setNextOutputFile` eagerly. Android
     * creates that file immediately (0 bytes) but the recorder never
     * rolls into it before `stop()`, so **every healthy recording**
     * ends with a guaranteed empty trailing `sess_{sid}_seg{N}.m4a`.
     *
     * Three read consumers must ignore that artifact, and each used to
     * mis-interpret the raw [segments] list:
     *  - [readForPipeline] saw ≥2 segments and muxed every recording,
     *    counting the empty tail as a skipped segment →
     *    false [PipelineAudioResult.PartialRecovery] (F-012).
     *  - the continuation codec-param lookup read the *last* segment —
     *    now always the unreadable empty tail → aborted continuation
     *    (F-014).
     *  - history duration summed only the first segment (F-047).
     *
     * This method is the **single place** the "which segments carry
     * real audio" rule lives, so consumers migrate onto it instead of
     * re-filtering the raw list locally.
     *
     * Only zero-length files are dropped. A non-empty but truncated
     * segment (crash without a `moov` atom) is genuine partial data
     * and stays in the list — the muxer skips it and reports a *real*
     * PartialRecovery, and the continuation lookup skips past it to the
     * last readable segment.
     *
     * [allocateNext] intentionally keeps using raw [segments]: the
     * pre-armed empty file still occupies its index, so the next
     * allocation must go beyond it.
     */
    fun significantSegments(sessionId: String): List<File> =
        segments(sessionId).filter { it.length() > 0L }

    /**
     * Return a [PipelineAudioResult] the Pipeline layer can upload.
     * The semantic contract: regardless of segment count, the
     * result's `file` holds the readable audio for [sessionId].
     *
     *  - **Single segment:** [PipelineAudioResult.Complete] with
     *    the segment file itself (zero-copy, no validation —
     *    corrupted single segments surface as Whisper-upload 4xx
     *    errors, not as a recovery decision).
     *  - **Multiple segments, all readable:**
     *    [PipelineAudioResult.Complete] with a transient
     *    `{prefix}{sessionId}{merged}{ext}` file produced by
     *    MediaMuxer-level concatenation. The transient is owned by
     *    the repository — [deleteAll] removes it alongside the
     *    segments.
     *  - **Multiple segments, some unreadable:**
     *    [PipelineAudioResult.PartialRecovery] with the merged file
     *    (readable segments only) plus the indices of skipped
     *    segments and an estimate of lost audio duration. The
     *    caller persists the metadata into the session's
     *    `lastErrorMessage` so the Partial-Recovery InfoBar
     *    producer (B4) can surface a warning after pipeline
     *    completion.
     *  - **No segments OR every segment unreadable:** `null` (the
     *    caller propagates an audio-missing error — see
     *    `PipelineRecovery` ghost-cleanup).
     *
     * Concatenation runs on `Dispatchers.IO`.
     */
    suspend fun readForPipeline(sessionId: String): PipelineAudioResult?

    /**
     * Delete every segment and any transient merged file for
     * [sessionId]. Best-effort — individual `File.delete()` failures
     * are logged and ignored (the periodic cleanup pass catches them).
     */
    fun deleteAll(sessionId: String)

    /**
     * Find session-ids that have files on disk but are not in
     * [knownSessionIds]. Used by orphan cleanup at service start so
     * abandoned recordings (FGS killed mid-flight, user wiped DB) do
     * not occupy storage forever.
     */
    fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String>

    /**
     * Return every repository-owned file (segments + transient merged
     * files) grouped by session-id. Used by [CacheAudioCleanupJob]
     * (recording-stack-completion §4.5) for the per-session retention
     * decision: a session-id whose status is not in
     * [net.devemperor.dictate.database.dao.SessionDao.findActiveSessionIds]
     * AND whose newest file is older than the TTL is safe to delete.
     *
     * Foreign content in the audio directory (files not matching the
     * `sess_*` prefix) is silently skipped — the repository owns only
     * its own naming scheme.
     */
    fun listAllOwnedFiles(): Map<String, List<File>>
}
