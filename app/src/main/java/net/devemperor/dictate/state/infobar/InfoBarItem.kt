package net.devemperor.dictate.state.infobar

import net.devemperor.dictate.state.Action

/**
 * A single entry in the info-bar surface (ADR-0006).
 *
 * **Items are state-derived.** [InfoBarSelector] computes the live
 * list of items on every state emit; this class is the renderer-facing
 * shape, not a stored state-axis. Two items with the same [id] from
 * consecutive emits are treated as "the same" (the renderer can skip
 * an animation), even though the wrapping list is rebuilt fresh.
 *
 * **Dismiss is the natural-source mutation — when present.** [dismissAction],
 * if non-null, MUST mutate the state-axis that caused the item to surface
 * (ADR-0006 §"Dismiss = natural-source mutation"). That contract is the
 * single structural guarantee against item resurrection — the renderer
 * dispatches `dismissAction`, the state changes, the selector re-evaluates
 * without the item.
 *
 * **Confirm and dismiss are both optional.** A pure-info item that just
 * communicates state without offering user action sets BOTH to `null` —
 * the renderer hides both buttons and the item lives exactly as long as
 * its source condition in the selector holds (e.g. "unfinished recording
 * restored" tied to `state.recording is RecordingState.Interrupted`; the
 * item vanishes the moment a keyboard action transitions the recording
 * out of `Interrupted`, so the no-resurrection guarantee is preserved via
 * the source-condition lifecycle instead of via the dismiss button).
 * Single-action items (e.g. internet-error) keep `confirmAction` null and
 * `dismissAction` non-null; full action items supply both.
 *
 * Future-extensible: an `extraActions: List<NamedAction>` slot can be
 * added without breaking existing items (default `emptyList()`). The
 * field is intentionally **not** present today (YAGNI per the user's
 * brief).
 *
 * @property id stable per-producer identifier. Two emits of the same
 *   pending-insert session share the same id even as the wrapping
 *   `List<InfoBarItem>` is rebuilt. Convention:
 *   `"{producer}:{stableKey}"` — e.g. `"pending-insert:abc-123"`.
 * @property createdAt epoch milliseconds the underlying trigger came
 *   into existence. Items are sorted ascending by [createdAt] (per the
 *   user's "Entstehungszeitpunkt" rule). Producers without a true
 *   event timestamp (e.g. SP-flag-driven `update`, `rate`) use
 *   `BuildConfig.VERSION_BUILD_TIME` as a stable proxy so sort order
 *   is deterministic across emits.
 * @property message visual payload (see [InfoBarMessage]).
 * @property confirmAction `Action` dispatched on the confirm-button
 *   click. `null` means "no positive action" — renderer hides the
 *   confirm button.
 * @property dismissAction `Action` dispatched on the dismiss-button
 *   click. When non-null, MUST mutate the state-axis that caused the
 *   item to surface (otherwise the item resurrects on the next emit).
 *   `null` means "no dismiss button" — pure-info items rely on the
 *   selector's source condition for their lifecycle (see KDoc above).
 *
 * @see InfoBarMessage
 * @see InfoBarSelector
 * @see InfoBarStyle
 * @see docs/decisions/0006-ui-info-bar-state-derived-items.md
 */
data class InfoBarItem(
    val id: String,
    val createdAt: Long,
    val message: InfoBarMessage,
    val confirmAction: Action? = null,
    val dismissAction: Action? = null,
)
