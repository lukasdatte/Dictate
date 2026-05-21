package net.devemperor.dictate.database.entity

/**
 * Persisted lifecycle state of a [SessionEntity].
 *
 * Lifecycle order (forward path):
 *   RECORDING → RECORDED → TRANSCRIBING → COMPLETED
 * Terminal exits from any non-terminal: FAILED, CANCELLED.
 *
 * Before the M3→M4 migration (Spec 1 §6.1) the live states `RECORDING`
 * and `TRANSCRIBING` were NOT persisted — they lived only in
 * [net.devemperor.dictate.core.ActiveJobRegistry] (process-local). With
 * the pipeline-service refactor (Spec 1 §6 + §11.6) those live states
 * are also written to the DB so an OOM death during the active phase
 * becomes detectable and recoverable. `ActiveJobRegistry` continues to
 * exist as a performance cache + single-job lock (see §6.1.1).
 *
 * Persistenz-Vertrag (Spec 1 §6.2 R.17): the [Effect.PersistStatus]
 * handler writes DB first, then updates `ActiveJobRegistry`.
 *
 * Follows the Double-Enum pattern (see docs/DATABASE-PATTERNS.md):
 * the SQL column has a CHECK constraint matching these values exactly
 * (see [net.devemperor.dictate.database.migration.MIGRATION_3_4]).
 *
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §6.1 §6.1.3
 */
enum class SessionStatus {
    /**
     * Recording is in progress — microphone is open, the audio file is
     * being written. Persisted at recording-start (Spec 1 §6.2).
     * Recovery on cold-start:
     *   - audio segments still on disk and Rolling-Segments finalised at
     *     least one of them → promote to [RECORDING_INTERRUPTED] for
     *     auto-continuation (B2 + ADR-0008).
     *   - audio missing or partial-only → fall back to [FAILED].
     */
    RECORDING,

    /**
     * Recording was in progress when the process died, but the
     * Rolling-Segments machinery left at least one readable segment
     * on disk. On the next Record-click within the
     * `ContinuationFreshnessMs` window the recorder reuses this
     * session-id and appends a fresh segment via
     * `AudioFileRepository.allocateNext`; the final
     * `readForPipeline` concatenates everything. Stale rows
     * (older than the freshness floor) are demoted to [FAILED] +
     * audio deleted on the next recovery pass.
     *
     * Introduced by B2 of `dictate-widget-state-and-recovery`
     * (ADR-0008 §"Auto-Continuation"). Schema-version 6.
     */
    RECORDING_INTERRUPTED,

    /**
     * Audio file is closed and persistent; the pipeline has either not
     * run yet or was aborted before any result was written. Surfaces in
     * the history list with a "pending" badge and a Resume button.
     */
    RECORDED,

    /**
     * Pipeline is running — audio upload + transcription + processing
     * steps. Persisted at pipeline-start (Spec 1 §6.2). Recovery on
     * cold-start downgrades to RECORDED (the user clicks Restart; no
     * auto-resume per D4 / OPEN-4).
     */
    TRANSCRIBING,

    /**
     * Pipeline finished successfully and the result text is in the DB.
     * The `inserted_at` column distinguishes "result available, not yet
     * inserted into the editor" (`NULL`) from "user already saw the
     * text" (non-null, used by the 7-day cleanup policy in §6.2 R.17).
     */
    COMPLETED,

    /**
     * Pipeline terminated with an error (API, quota, network, or a
     * recording-lost promotion from RECORDING). `lastErrorType` and
     * `lastErrorMessage` carry the diagnostic context.
     */
    FAILED,

    /**
     * User explicitly cancelled the session. `lastErrorType` is `NULL`
     * by convention (Spec 1 §6.1 — `ErrorType.CANCELLED` is intentionally
     * not persisted; cancellation is expressed by `status` alone).
     */
    CANCELLED
}
