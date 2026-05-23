package net.devemperor.dictate.core

import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionOrigin

/**
 * Tracks the current session and caches the last keyboard-initiated session.
 *
 * Strategy for [getLastKeyboardSession]:
 * 1. RAM cache hit → return immediately
 * 2. DB query for the latest session with origin = [SessionOrigin.KEYBOARD] → populate cache
 * 3. Still null → return null
 *
 * The cache is invalidated on session deletion and repopulated lazily on next access.
 *
 * Thread safety: volatile fields for cross-thread visibility (main thread reads,
 * background threads write after API calls).
 *
 * Phase 9 removed the legacy `lastSessionId`/`lastOutput`/`reuseLastSession`/
 * `persistToPrefs`/`restoreLastSessionIdFromPrefs`/`restoreLastOutputFromDb`
 * APIs. The DB is now the sole source of truth for "the last keyboard session";
 * the RAM cache is a pure performance optimisation.
 */
class SessionTracker(
    private val sessionDao: SessionDao
) {

    // Settable from the pipeline orchestrator (Chunk 2 persist-first flow).
    // Finding SEC-5-3: notifySessionCreated was never defined on SessionTracker —
    // the orchestrator now writes currentSessionId directly after persisting.
    @Volatile var currentSessionId: String? = null
    @Volatile var currentStepId: String? = null
        private set
    @Volatile var currentTranscriptionId: String? = null
        private set

    @Volatile private var cachedLastKeyboardSession: SessionEntity? = null

    /**
     * Sets the current transcription ID (clears step — transcription is the latest artifact).
     */
    fun setTranscription(id: String) {
        currentTranscriptionId = id
        currentStepId = null
    }

    /**
     * Sets the current step ID (clears transcription — step is the latest artifact).
     */
    fun setStep(id: String) {
        currentStepId = id
        currentTranscriptionId = null
    }

    /**
     * Returns the last session that was initiated from the keyboard,
     * or null if no such session exists.
     *
     * RAM-first, DB-fallback, with read-through caching.
     */
    fun getLastKeyboardSession(): SessionEntity? {
        cachedLastKeyboardSession?.let { return it }

        val fromDb = sessionDao.findLatestByOrigin(SessionOrigin.KEYBOARD.name)
        cachedLastKeyboardSession = fromDb
        return fromDb
    }

    /**
     * Returns the most recent "unfinished recording" — `RECORDING_INTERRUPTED`
     * (process-death survivor) or `RECORDED` (audio complete, transcription
     * never finished) — whose `created_at` is at least `now - freshnessMs`,
     * i.e. fresh enough to be eligible for auto-continuation (B2 /
     * ADR-0008; 2026-05-23 extension to RECORDED, see
     * [net.devemperor.dictate.database.dao.SessionDao.findLatestUnfinishedRecording]).
     *
     * Synchronous DB read; no RAM cache (the candidate is volatile —
     * the next [PipelineRecovery] pass promotes stale rows to FAILED
     * and the user's next Record-click either accepts the candidate
     * or starts fresh, so caching would only race the cleanup).
     *
     * @param freshnessMs window in milliseconds; rows older than
     *   `now - freshnessMs` are filtered out.
     * @param nowMs current clock (default `System.currentTimeMillis`)
     *   — injected for tests.
     */
    fun findContinuationCandidate(
        freshnessMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): SessionEntity? =
        sessionDao.findLatestUnfinishedRecording(nowMs - freshnessMs)

    /**
     * Called by the pipeline after a new keyboard session finishes.
     * Updates the RAM cache — the DB was already written by the pipeline.
     */
    fun notifyKeyboardSessionCompleted(session: SessionEntity) {
        cachedLastKeyboardSession = session
    }

    /**
     * Invalidates the cache — call after a session is deleted from history,
     * or when a pipeline run starts so the next getLastKeyboardSession() reads
     * fresh DB state.
     */
    fun invalidateLastKeyboardCache() {
        cachedLastKeyboardSession = null
    }

    /**
     * Clears the transient "current session" tracking fields (session id,
     * transcription id, step id). Called at the end of a pipeline run so the
     * next session-start guard doesn't block.
     *
     * Replaces the legacy [resetSession] method which also wrote to the
     * removed `lastSessionId`/`lastOutput` fields; the DB is now the source of
     * truth for those values.
     */
    fun clearCurrent() {
        currentSessionId = null
        currentStepId = null
        currentTranscriptionId = null
    }
}
