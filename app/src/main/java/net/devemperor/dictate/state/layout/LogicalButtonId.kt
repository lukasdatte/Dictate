package net.devemperor.dictate.state.layout

/**
 * Logical identifier for every button surface the [LayoutCatalog] knows
 * about. The IME-side render backend ([net.devemperor.dictate.state.layout.RenderBackend])
 * owns the mapping from a [LogicalButtonId] to a concrete `android.view.View`;
 * `ButtonSlot` references **only** the logical id, so layout-mode definitions
 * stay decoupled from `R.id.*` resources.
 *
 * **Why an enum (not a sealed class)?** All ids are payload-free leaves with
 * stable order — enum gives us:
 *
 * 1. **Compile-time exhaustivity** for renderer `when (id)` dispatchers
 *    (Spec 2 §6 `buttonViews` map).
 * 2. **Identity comparison via `===`** (and a free `entries` iterator) without
 *    boilerplate.
 * 3. **Map-friendly hashing** — `Map<LogicalButtonId, View>` is O(1) without
 *    `equals/hashCode` plumbing.
 *
 * **KEYBOARD vs OVERLAY ids.** The KEYBOARD-modus ids are consumed by the
 * `ImeViewBackend` (Spec 2 §6, C14); the OVERLAY ids are consumed by the
 * `OverlayBackend` (Spec 3, B5/C16). Both backends share the same
 * `ButtonSlot` machinery so we keep one flat enum here instead of two
 * package-private ones — adding a new button in one surface stays a
 * one-line enum edit + one slot in the relevant `LayoutMode`.
 *
 * @see net.devemperor.dictate.state.layout.ButtonSlot
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §3.1
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
enum class LogicalButtonId {
    // ─── KEYBOARD-modus slots (ImeViewBackend, Spec 2 §6) ────────────
    /** `record_btn` — Idle: "Dictate", Recording: "Send", Pipeline: counter. */
    RECORD,

    /** `resend_btn` — gated by [net.devemperor.dictate.state.layout.isResendVisible]. */
    RESEND,

    /**
     * `secondary_record_btn` — the mic button offered **only** during a live
     * pipeline run (the SEND_MODE layouts). A tap starts a NEW recording
     * that queues behind the active run, letting the user dictate the next
     * thought while the current one is still processing (ADR-0009). Never
     * rendered in the standard / staging modes (slot predicate `{ false }`)
     * and never while a recording is already in flight (single-MediaRecorder
     * gate). @see docs/decisions/0009-pipeline-run-queue-serialized-concurrency.md
     */
    RECORD_SECONDARY,

    /** `backspace_btn`. */
    BACKSPACE,

    /**
     * `audio_focus_btn` — visible only in SINGLE_ROW variants (Spec 2 §8.2),
     * gone in TWO_ROW. Icon resolver drives the volume_up / volume_off icon.
     */
    AUDIO_FOCUS,

    /**
     * `widget_toggle_btn` — toggles KEYBOARD → WIDGET (Spec 3 OPEN-2).
     * Visible only when `viewMode == ViewMode.KEYBOARD` AND the pipeline is
     * not in SEND_MODE / REPROCESS_STAGING.
     */
    WIDGET_TOGGLE,

    /** `trash_btn` — cancel-recording / cancel-staging. */
    TRASH,

    /** `space_btn`. */
    SPACE,

    /** `pause_btn` — pause / resume current recording. */
    PAUSE,

    /** `enter_btn`. */
    ENTER,

    // ─── OVERLAY-modus slots (OverlayBackend, Spec 3 — B5/C16) ───────
    /**
     * Overlay floating-widget record-button.
     *
     * **Variante 2a (dictate-widget-integration §6.5):** this single
     * slot covers both record-start (Idle → Active) and send (Active|
     * Paused → StopRecordingAndSend) in the WIDGET ViewMode — exactly
     * mirroring the keyboard `RECORD` slot. The previous standalone
     * `OVERLAY_SEND` was merged into this slot per the 2026-05-21
     * user-decision ("exakt den gleichen Button … reichen Button …
     * wiederverwendbar"). In HOVER the Active|Paused branch returns
     * `null` because no `InputConnection` target exists.
     */
    OVERLAY_RECORD,

    /** Overlay-5-button: pause / resume. */
    OVERLAY_PAUSE,

    /** Overlay-5-button: trash / cancel. */
    OVERLAY_TRASH,

    /** Overlay-5-button: close — differential behaviour per ViewMode (Spec 3 §1.2). */
    OVERLAY_CLOSE,
}
