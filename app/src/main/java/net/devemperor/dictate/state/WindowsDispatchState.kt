package net.devemperor.dictate.state

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * Sessions whose final text is in flight to the Windows companion (ADR-0019). Owned by
 * `WindowsDispatchModule`.
 *
 * They are terminal in the pipeline FSM (the run-queue has drained) but NOT yet acknowledged:
 * neither `MarkSessionInserted` nor `AddPendingInsertSession` has fired. Exactly one acknowledge
 * will happen once the HTTP call returns — or on IME teardown.
 *
 * @property inFlight the dispatches still awaiting their HTTP result.
 * @property notice a transient, dismissible InfoBar hint (ADR-0006 state-derived). Set by a
 *   `CLIPBOARD_ONLY` success or any failure; cleared by a new dispatch or a dismiss.
 */
data class WindowsDispatchState(
    val inFlight: PersistentList<InFlightDispatch> = persistentListOf(),
    val notice: DispatchNotice? = null,
)

/**
 * One dispatch awaiting its HTTP result.
 *
 * @property acknowledgeOnSuccess whether a success must acknowledge this session (`markInserted`).
 *   `true` for a fresh terminal session (IME seam or headless sink) and for a still-pending history
 *   row; `false` for a re-send of an already-acknowledged row (a pure re-send must not touch
 *   `inserted_at`).
 * @property surfacedAsPending whether a pending part for this session ALREADY exists. Two ways it
 *   becomes true: at [Action.WindowsDispatchAction.Started] time (a re-sent still-pending history
 *   row), or later via [Action.WindowsDispatchAction.MarkSurfaced] (the IME-teardown cascade
 *   surfaced this in-flight text as a pending part). This flag REPLACES a cross-axis read of
 *   `pendingSessions`: with a second producer (the headless sink) firing from the pipeline
 *   executor thread, "has the cascade's AddOne been reduced yet?" is no longer worth defending —
 *   keeping the fact in our own axis makes the Succeeded/Failed resolution deterministic on both
 *   paths.
 */
data class InFlightDispatch(
    val sessionId: String,
    val text: String,
    val createdAt: Long,
    val acknowledgeOnSuccess: Boolean,
    val surfacedAsPending: Boolean,
    /**
     * PC-only terminal mode (pc-dictation-activity): when `true`, a [Action.WindowsDispatchAction.Failed]
     * must NOT surface a local "Tap to paste" pending part. The dispatch originated from the
     * full-screen PC-dictation Activity, which has no IME host — a pending part (ADR-0011) presumes
     * one. The failure surfaces in the Activity (visible error + retry) instead; the text stays
     * durably recoverable via `final_output_text` (ADR-0013 §3). Captured at [Action.WindowsDispatchAction.Started]
     * time, the same way [acknowledgeOnSuccess] / [surfacedAsPending] are, so the Failed arm reads
     * it from this axis without a cross-module lookup.
     */
    val suppressPendingFallback: Boolean = false,
)

/**
 * A transient, dismissible InfoBar notice derived from [WindowsDispatchState.notice]
 * (ADR-0006 state-derived InfoBar). The rendering wiring lands with the seams (Block 3b);
 * in this package the reducer only produces the value.
 */
sealed interface DispatchNotice {
    /**
     * INFO — dismiss-only. The text reached the PC but was placed on the clipboard, not typed
     * (ADR-0018 `CLIPBOARD_ONLY`). The user must be told, or they will believe it was typed.
     */
    data object ClipboardOnly : DispatchNotice

    /**
     * ERROR — the dispatch failed. [kind] is WINDOWS_UNREACHABLE (dismiss-only) or WINDOWS_UNAUTHORIZED.
     *
     * @property sessionId the failed session, carried so a PC-only-mode surface (the PC-dictation
     *   Activity) can offer a **retry** that re-dispatches this exact session. `null` for the
     *   in-keyboard InfoBar path, which does not retry per-session. (pc-dictation-activity)
     */
    data class Error(val kind: PipelineErrorKind, val sessionId: String? = null) : DispatchNotice
}
