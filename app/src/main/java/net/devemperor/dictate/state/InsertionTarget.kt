package net.devemperor.dictate.state

/**
 * Where the pipeline result will be inserted. Captured at `StartRecording`
 * time so the pipeline knows the destination from the very first action.
 *
 * - [INPUT_CONNECTION] — the active `InputConnection` of the keyboard's
 *   target editor (normal IME use). Result is `commitText`'d.
 * - [REPROCESS_STAGING] — Resend-long-press path. The pipeline runs but
 *   the result lands in [PipelineUiState.ReprocessStaging] for the user
 *   to edit the queue/language before sending.
 *
 * **Why an enum, not a sealed class?** Targets carry no payload — they
 * are a routing discriminator only. The actual destination (the
 * `InputConnection` instance, the session-id of the staging slot) is
 * resolved by the pipeline at run-time via `ModuleServices`.
 *
 * @see net.devemperor.dictate.state.PipelineUiState.ReprocessStaging
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §3
 */
enum class InsertionTarget {
    /** Normal IME use — result is committed to the active `InputConnection`. */
    INPUT_CONNECTION,

    /** Resend-long-press — result is staged for user editing before send. */
    REPROCESS_STAGING,
}
