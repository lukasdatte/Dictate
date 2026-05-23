package net.devemperor.dictate.state.layout

import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.WidgetState
import net.devemperor.dictate.state.infobar.InfoBarSelector
import net.devemperor.dictate.state.isActiveOrPaused

/**
 * The single source of truth for "which buttons live in which layout
 * mode" — the data-side of the keyboard-layout refactor (ADR-0004).
 *
 * # What lives here
 *
 * - Six [LayoutMode] instances: 5 KEYBOARD modes (Two-Row / Single-Row, with
 *   their SEND-MODE variants, plus REPROCESS_STAGING) + 1 OVERLAY mode.
 *   The OVERLAY_5BUTTON body is contributed by Spec 3 § / B5; only the id
 *   slot is reserved here so C12 compiles standalone.
 * - [forKeyboard] — the deterministic state-→-mode selector used by the
 *   [KeyboardLayoutManager] (Spec 2 §8.6).
 *
 * # What deliberately does NOT live here
 *
 * - **No View references.** Slots reference [LogicalButtonId] only.
 * - **No Android Context.** Strings come in via [LayoutStrings] (the
 *   catalog is instantiated with them per IME `onCreateInputView`).
 * - **No reducer logic.** Slots emit `Action`s; the modules' reducers do
 *   the state mutation.
 *
 * # The OVERLAY_5BUTTON nested-object idiom (C-5 / Spec 3 §3.1)
 *
 * Spec 3 originally declared `object OVERLAY_5BUTTON : LayoutMode(...)`
 * as a top-level singleton, but Spec 2 §4 + cross-spec references use
 * `LayoutCatalog.OVERLAY_5BUTTON` (qualified-member access). The
 * resolution per Phase-C C-4 is to declare it **inside** [LayoutCatalog]
 * — the placeholder property below is the anchor; B5/C16 fills in the
 * `LayoutMode` body. Until B5 lands, the property is a fall-through
 * mode with an empty row so the type-system stays sound and tests can
 * reference the symbol.
 *
 * # Lifecycle
 *
 * One [LayoutCatalog] instance per IME `onCreateInputView` (the View
 * lifecycle owns the [LayoutStrings] / `Context` it needs). The catalog
 * is otherwise immutable — re-render is a re-evaluation of the resolvers,
 * not a re-instantiation of the catalog.
 *
 * @property strings string-resource bundle, injected at construction so
 *   the catalog stays Android-loose for tests.
 *
 * @see net.devemperor.dictate.state.layout.KeyboardLayoutManager
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §8
 * @see docs/decisions/0004-ui-layout-catalog-motionlayout.md §3
 */
class LayoutCatalog(private val strings: LayoutStrings) {

    // ════════════════════════════════════════════════════════════════
    // KEYBOARD_TWO_ROW (Spec 2 §8.1)
    // ════════════════════════════════════════════════════════════════

    val KEYBOARD_TWO_ROW: LayoutMode = LayoutMode(
            id = LayoutModeId.KEYBOARD_TWO_ROW,
            backend = BackendType.IME_VIEW,
            sceneStateId = R.id.two_row_state,
            rows = listOf(
                // Row 1 (formerly action_row): record / resend / backspace / audio-focus (gone) / widget-toggle
                RowDescriptor(slots = listOf(
                    ButtonSlot(
                        logicalId = LogicalButtonId.RECORD,
                        widthPolicy = WidthPolicy.FillRemaining,
                        visibilityPredicate = { true },
                        textResolver = { state -> resolveRecordButtonText(state, strings) },
                        enabledResolver = { state -> state.recording !is RecordingState.Preparing },
                        actionResolver = ::resolveRecordAction,
                        // G2 — RECORD long-press 2-mode (render-path-cutover.md
                        // §3 / §7 A1). Only the standard (non-pipeline) modes
                        // carry it: the legacy `onRecordLongClicked` is a
                        // recording-state handler. Dormant until CR4 (RR-1).
                        longClickResolver = ::resolveRecordLongPressAction,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.RESEND,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = ::isResendVisible,
                        enabledResolver = { state -> !state.resend.resendCooldown },
                        alphaResolver = { state -> if (state.resend.resendCooldown) 0.4f else 1f },
                        actionResolver = { _, _ -> Action.ResendAction.ResendLastAudio },
                        // Long-press → ReprocessStaging entry (Spec 2 §6,
                        // behaviour-identical to legacy `onResendLongClicked`).
                        // CR1 moves this from the hardcoded ImeViewBackend
                        // wire to the catalog (the backend's RESEND
                        // OnLongClickListener now reads this resolver).
                        longClickResolver = { _, _ -> Action.ResendAction.ResendLastAudioLong },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.BACKSPACE,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.AUDIO_FOCUS,
                        widthPolicy = WidthPolicy.WrapContent,
                        // Audio-focus is single-row-only in two-row layout (Spec 2 §8.1).
                        visibilityPredicate = { false },
                        iconResolver = ::resolveAudioFocusIconForSlot,
                        actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.WIDGET_TOGGLE,
                        widthPolicy = WidthPolicy.WrapContent,
                        // 2026-05-22 — Widget-toggle has been relocated
                        // out of the main action row and into the
                        // edit-bar above (R.id.edit_widget_toggle_btn).
                        // The slot stays as a structural placeholder
                        // (a backward-compatible GONE view in the
                        // MotionLayout) while EditBarController owns
                        // the live click + visibility surface.
                        visibilityPredicate = { false },
                        actionResolver = ::resolveWidgetToggleAction,
                    ),
                )),
                // Row 2 (formerly input_row): trash / space / pause / enter
                RowDescriptor(slots = listOf(
                    ButtonSlot(
                        logicalId = LogicalButtonId.TRASH,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = ::isTrashVisible,
                        actionResolver = ::resolveTrashAction,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.SPACE,
                        widthPolicy = WidthPolicy.FillRemaining,
                        visibilityPredicate = { true },
                        actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.PAUSE,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = ::isPauseVisible,
                        enabledResolver = { state -> state.recording.isActiveOrPaused },
                        alphaResolver = { state -> if (state.recording.isActiveOrPaused) 1f else 0.4f },
                        iconResolver = ::resolvePauseIcon,
                        actionResolver = ::resolvePauseAction,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.ENTER,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey },
                    ),
                )),
            ),
        )

    // ════════════════════════════════════════════════════════════════
    // KEYBOARD_SINGLE_ROW (Spec 2 §8.2)
    // ════════════════════════════════════════════════════════════════

    val KEYBOARD_SINGLE_ROW: LayoutMode = LayoutMode(
            id = LayoutModeId.KEYBOARD_SINGLE_ROW,
            backend = BackendType.IME_VIEW,
            sceneStateId = R.id.single_row_state,
            rows = listOf(RowDescriptor(slots = listOf(
                ButtonSlot(
                    logicalId = LogicalButtonId.TRASH,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = ::isTrashVisible,
                    actionResolver = ::resolveTrashAction,
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.RECORD,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { true },
                    textResolver = { state -> resolveRecordButtonText(state, strings) },
                    enabledResolver = { state -> state.recording !is RecordingState.Preparing },
                    actionResolver = ::resolveRecordAction,
                    // G2 — RECORD long-press 2-mode (see KEYBOARD_TWO_ROW
                    // RECORD slot). Dormant until CR4 (RR-1).
                    longClickResolver = ::resolveRecordLongPressAction,
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.SPACE,
                    widthPolicy = WidthPolicy.FillRemaining,
                    visibilityPredicate = { true },
                    actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.PAUSE,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = ::isPauseVisible,
                    enabledResolver = { state -> state.recording.isActiveOrPaused },
                    alphaResolver = { state -> if (state.recording.isActiveOrPaused) 1f else 0.4f },
                    iconResolver = ::resolvePauseIcon,
                    actionResolver = ::resolvePauseAction,
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.BACKSPACE,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { true },
                    actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.ENTER,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { true },
                    actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.RESEND,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = ::isResendVisible,
                    enabledResolver = { state -> !state.resend.resendCooldown },
                    alphaResolver = { state -> if (state.resend.resendCooldown) 0.4f else 1f },
                    actionResolver = { _, _ -> Action.ResendAction.ResendLastAudio },
                    // Long-press → ReprocessStaging (see KEYBOARD_TWO_ROW
                    // RESEND slot).
                    longClickResolver = { _, _ -> Action.ResendAction.ResendLastAudioLong },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.AUDIO_FOCUS,
                    widthPolicy = WidthPolicy.WrapContent,
                    // Single-row layout: audio-focus is the one slot that differs from two-row.
                    visibilityPredicate = { true },
                    iconResolver = ::resolveAudioFocusIconForSlot,
                    actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.WIDGET_TOGGLE,
                    widthPolicy = WidthPolicy.WrapContent,
                    // 2026-05-22 — Widget-toggle relocated to edit-bar
                    // (see KEYBOARD_TWO_ROW WIDGET_TOGGLE slot for the
                    // rationale). Structurally GONE in the main row.
                    visibilityPredicate = { false },
                    actionResolver = ::resolveWidgetToggleAction,
                ),
            ))),
        )

    // ════════════════════════════════════════════════════════════════
    // KEYBOARD_TWO_ROW_SEND_MODE (Spec 2 §8.3)
    // ════════════════════════════════════════════════════════════════
    //
    // Architectural-note: TRASH / PAUSE are hardcoded `{ false }` — DO NOT
    // replace with `::isTrashVisible` / `::isPauseVisible`. The hardcoded
    // value is the bug #1.1 #3a eliminator (Spec 2 §8.3 prose).

    val KEYBOARD_TWO_ROW_SEND_MODE: LayoutMode = LayoutMode(
            id = LayoutModeId.KEYBOARD_TWO_ROW_SEND_MODE,
            backend = BackendType.IME_VIEW,
            sceneStateId = R.id.two_row_send_mode_state,
            rows = listOf(
                RowDescriptor(slots = listOf(
                    ButtonSlot(
                        logicalId = LogicalButtonId.RECORD,
                        widthPolicy = WidthPolicy.FillRemaining,
                        visibilityPredicate = { true },
                        textResolver = { state -> resolveRecordButtonTextPipeline(state, strings) },
                        // Post-cutover hotfix #AE-OPTIK2 — `enabledResolver`
                        // intentionally OMITTED (defaults to { true }). An
                        // earlier `{ state.pipeline !is Preparing }` gate
                        // disabled the button during the 500ms–2s upload
                        // window — and Android's View.onTouchEvent swallows
                        // touches on `isEnabled=false`, so the click listener
                        // never fired. That meant the double-tap-to-toggle
                        // auto-enter feature was unreachable in Preparing
                        // (the most common landing zone), the per-run
                        // `autoEnterActive` flag stayed false, and neither
                        // the ↵ indicator nor the end-of-pipeline Enter
                        // were produced. `resolveRecordActionPipeline` is
                        // the single source of truth for "is this click
                        // meaningful?" — post-#AE-DEEP2 it accepts both
                        // Preparing and Running and returns null elsewhere.
                        actionResolver = ::resolveRecordActionPipeline,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.RESEND,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { false },
                        actionResolver = { _, _ -> null },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.BACKSPACE,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.AUDIO_FOCUS,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { false },
                        actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.WIDGET_TOGGLE,
                        widthPolicy = WidthPolicy.WrapContent,
                        // Hardcoded false during pipeline — see Spec 2 §8.3 / Phase-B S-6.
                        visibilityPredicate = { false },
                        actionResolver = { _, _ -> null },
                    ),
                )),
                RowDescriptor(slots = listOf(
                    ButtonSlot(
                        logicalId = LogicalButtonId.TRASH,
                        widthPolicy = WidthPolicy.WrapContent,
                        // Hardcoded false — bug #3a eliminator (Spec 2 §8.3 architecture-note).
                        visibilityPredicate = { false },
                        actionResolver = ::resolveTrashAction,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.SPACE,
                        widthPolicy = WidthPolicy.FillRemaining,
                        visibilityPredicate = { true },
                        actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.PAUSE,
                        widthPolicy = WidthPolicy.WrapContent,
                        // Hardcoded false — bug #3a eliminator.
                        visibilityPredicate = { false },
                        actionResolver = { _, _ -> null },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.ENTER,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey },
                    ),
                )),
            ),
        )

    // ════════════════════════════════════════════════════════════════
    // KEYBOARD_SINGLE_ROW_SEND_MODE (Spec 2 §8.3)
    // ════════════════════════════════════════════════════════════════

    val KEYBOARD_SINGLE_ROW_SEND_MODE: LayoutMode = LayoutMode(
            id = LayoutModeId.KEYBOARD_SINGLE_ROW_SEND_MODE,
            backend = BackendType.IME_VIEW,
            sceneStateId = R.id.single_row_send_mode_state,
            rows = listOf(RowDescriptor(slots = listOf(
                ButtonSlot(
                    logicalId = LogicalButtonId.TRASH,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { false },
                    actionResolver = ::resolveTrashAction,
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.RECORD,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { true },
                    textResolver = { state -> resolveRecordButtonTextPipeline(state, strings) },
                    // Post-cutover hotfix #AE-OPTIK2 — `enabledResolver`
                    // intentionally OMITTED. See the TWO_ROW_SEND_MODE
                    // record slot above for the full rationale (Android
                    // swallows clicks on disabled views, killing the
                    // double-tap auto-enter in the Preparing window).
                    actionResolver = ::resolveRecordActionPipeline,
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.SPACE,
                    widthPolicy = WidthPolicy.FillRemaining,
                    visibilityPredicate = { true },
                    actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.PAUSE,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { false },
                    actionResolver = { _, _ -> null },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.BACKSPACE,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { true },
                    actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.ENTER,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { true },
                    actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.RESEND,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { false },
                    actionResolver = { _, _ -> null },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.AUDIO_FOCUS,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { true },
                    iconResolver = ::resolveAudioFocusIconForSlot,
                    actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref },
                ),
                ButtonSlot(
                    logicalId = LogicalButtonId.WIDGET_TOGGLE,
                    widthPolicy = WidthPolicy.WrapContent,
                    visibilityPredicate = { false },
                    actionResolver = { _, _ -> null },
                ),
            ))),
        )

    // ════════════════════════════════════════════════════════════════
    // KEYBOARD_REPROCESS_STAGING (Spec 2 §8.4)
    // ════════════════════════════════════════════════════════════════

    val KEYBOARD_REPROCESS_STAGING: LayoutMode = LayoutMode(
            id = LayoutModeId.KEYBOARD_REPROCESS_STAGING,
            backend = BackendType.IME_VIEW,
            sceneStateId = R.id.reprocess_staging_state,
            rows = listOf(
                RowDescriptor(slots = listOf(
                    ButtonSlot(
                        logicalId = LogicalButtonId.RECORD,
                        widthPolicy = WidthPolicy.FillRemaining,
                        visibilityPredicate = { true },
                        textResolver = { state -> resolveRecordButtonTextStaging(state, strings) },
                        // Enabled whenever the pipeline is in ReprocessStaging.
                        // Single-submit is guarded by the FSM
                        // `ReprocessStaging → Preparing` edge (PipelineModule
                        // `SendStaging` arm, B1-VAL-W1 option b), not by an
                        // enabled-state flag — `ReprocessStaging` carries no
                        // `isStarting` field (Spec 1 §3).
                        enabledResolver = { state -> state.pipeline is PipelineUiState.ReprocessStaging },
                        actionResolver = ::resolveSendStagingAction,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.RESEND,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { false },
                        actionResolver = { _, _ -> null },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.BACKSPACE,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        actionResolver = { _, _ -> Action.KeyboardInputAction.Backspace },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.AUDIO_FOCUS,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { false },
                        actionResolver = { _, _ -> Action.AudioAction.ToggleAudioFocusPref },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.WIDGET_TOGGLE,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { false },
                        actionResolver = { _, _ -> null },
                    ),
                )),
                RowDescriptor(slots = listOf(
                    ButtonSlot(
                        logicalId = LogicalButtonId.TRASH,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        actionResolver = ::resolveCancelStagingAction,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.SPACE,
                        widthPolicy = WidthPolicy.FillRemaining,
                        visibilityPredicate = { true },
                        actionResolver = { _, _ -> Action.KeyboardInputAction.SpaceKey },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.PAUSE,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        enabledResolver = { false },
                        alphaResolver = { 0.4f },
                        actionResolver = { _, _ -> null },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.ENTER,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        actionResolver = { _, _ -> Action.KeyboardInputAction.EnterKey },
                    ),
                )),
            ),
        )

    // ════════════════════════════════════════════════════════════════
    // OVERLAY_5BUTTON — Variante 2a (dictate-widget-integration §6.5)
    // ════════════════════════════════════════════════════════════════
    //
    // **Shared between WIDGET and HOVER** — both ViewModes render via the
    // same [LayoutMode]. The differences live inside the resolvers,
    // which branch on `state.viewMode`:
    //
    // - RECORD: state-driven text/action covering all recording states
    //   AND both live pipeline sub-states (Preparing / Running) — i.e.
    //   start, send, auto-enter-toggle. The previous standalone
    //   OVERLAY_SEND was merged into this slot per the 2026-05-21
    //   user-decision ("exakt den gleichen Button … reichen Button …
    //   wiederverwendbar"). Enabled only in WIDGET (HOVER has no
    //   InputConnection target).
    // - CLOSE: ViewMode-driven action (WIDGET → toggle back to
    //   KEYBOARD; HOVER → cascade-dismiss the overlay with
    //   SuppressAutoOverlayUntilNextSession).
    // - PAUSE + TRASH: identical behaviour in both ViewModes (recording
    //   lifecycle is independent of ViewMode); resolvers point directly
    //   at the keyboard-surface bodies (`::resolvePauseAction`,
    //   `::resolveTrashAction`) — DRY-Cleanup per §8.2 Chunks 2.5+2.7.
    //
    // # `sceneStateId = null` — no MotionLayout transition
    //
    // The overlay surface is a flat `LinearLayout` attached to a
    // `WindowManager` window; there is no MotionScene to drive.
    // [LayoutMode.sceneStateId] is `null` so the renderer skips its
    // MotionLayout fan-out path entirely.

    val OVERLAY_5BUTTON: LayoutMode by lazy {
        LayoutMode(
            id = LayoutModeId.OVERLAY_5BUTTON,
            backend = BackendType.OVERLAY_WINDOW,
            sceneStateId = null,
            rows = listOf(
                // Row 1: Record (merged RECORD+SEND, FillRemaining width
                //        for the textual SEND state — mirrors the
                //        keyboard `record_btn` layout 1:1).
                RowDescriptor(slots = listOf(
                    ButtonSlot(
                        logicalId = LogicalButtonId.OVERLAY_RECORD,
                        widthPolicy = WidthPolicy.FillRemaining,
                        // Always visible — the WIDGET vs HOVER distinction
                        // lives on the `enabled` / `alpha` axes
                        // (resolveOverlayRecordEnabled) so HOVER shows the
                        // disabled SEND label rather than hiding the button
                        // entirely. Same shape as the keyboard RECORD slot.
                        visibilityPredicate = { true },
                        textResolver = { state ->
                            resolveOverlayRecordButtonText(state, strings)
                        },
                        enabledResolver = ::resolveOverlayRecordEnabled,
                        alphaResolver = { state ->
                            if (resolveOverlayRecordEnabled(state)) 1f else 0.4f
                        },
                        actionResolver = ::resolveOverlayRecordAction,
                    ),
                )),
                // Row 2: Trash on the left, Pause + Close on the right.
                RowDescriptor(slots = listOf(
                    ButtonSlot(
                        logicalId = LogicalButtonId.OVERLAY_TRASH,
                        widthPolicy = WidthPolicy.WrapContent,
                        // Trash is visible whenever there's something to
                        // cancel: an active recording OR a live pipeline.
                        visibilityPredicate = { state ->
                            state.recording.isActiveOrPaused ||
                                state.pipeline !is PipelineUiState.Idle
                        },
                        // DRY: same body as the keyboard TRASH slot — the
                        // ReprocessStaging branch is structurally
                        // unreachable in the overlay (staging is
                        // KEYBOARD-only per Spec 3 §10) but referencing
                        // the keyboard resolver keeps the two surfaces in
                        // lockstep (§8.2 Chunk 2.7).
                        actionResolver = ::resolveTrashAction,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.OVERLAY_PAUSE,
                        widthPolicy = WidthPolicy.WrapContent,
                        // 2026-05-22 — pause-btn is the stand-alone pause
                        // surface in BOTH overlay modes (widget visible
                        // and HOVER pipeline-fallback). Supersedes B3.4's
                        // "hidden while widget is visible" rule: the
                        // record-btn used to morph into pause-toggle, but
                        // post-refactor the record-btn keeps its
                        // start/send role and OVERLAY_PAUSE is the only
                        // pause UI on both surfaces. Visible whenever a
                        // recording is in flight; hidden in Idle so the
                        // overlay stays compact.
                        visibilityPredicate = { state ->
                            state.recording.isActiveOrPaused
                        },
                        enabledResolver = { state -> state.recording.isActiveOrPaused },
                        alphaResolver = { state ->
                            if (state.recording.isActiveOrPaused) 1f else 0.4f
                        },
                        // Mirror the keyboard-surface PAUSE icon convention
                        // via the shared helper — `resolvePauseIcon` swaps
                        // mic / pause based on `RecordingState.Paused`.
                        iconResolver = ::resolvePauseIcon,
                        // DRY: same body as the keyboard PAUSE slot — the
                        // previous `resolveOverlayPauseAction` was a
                        // byte-identical duplicate; deleted in §8.2 Chunk
                        // 2.6 / §10.2 OQ-2.
                        actionResolver = ::resolvePauseAction,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.OVERLAY_CLOSE,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        actionResolver = ::resolveOverlayCloseAction,
                    ),
                )),
            ),
        )
    }

    // ════════════════════════════════════════════════════════════════
    // Mode selection (Spec 2 §8.6)
    // ════════════════════════════════════════════════════════════════

    /**
     * Deterministic state-→-mode mapping for the KEYBOARD ViewMode.
     *
     * Decision tree (Spec 2 §8.6):
     *
     * | Pipeline state                 | smallRow? | → LayoutMode                  |
     * |--------------------------------|-----------|-------------------------------|
     * | `ReprocessStaging`             | any       | KEYBOARD_REPROCESS_STAGING    |
     * | `Preparing` / `Running`        | `true`    | KEYBOARD_SINGLE_ROW_SEND_MODE |
     * | `Preparing` / `Running`        | `false`   | KEYBOARD_TWO_ROW_SEND_MODE    |
     * | `Idle`                         | `true`    | KEYBOARD_SINGLE_ROW           |
     * | `Idle`                         | `false`   | KEYBOARD_TWO_ROW              |
     *
     * **Why no `singleRow` branch for ReprocessStaging?** Spec 2 §8.8
     * Edge-Case 1: staging is workflow-fokussiert (editable queue +
     * language chip), single-row makes no sense — stays two-row even
     * when the user has small-mode enabled.
     *
     * **InfoBar force-expand (2026-05-22).** When the state-derived
     * info-bar surface is non-empty, the keyboard is forced into the
     * two-row layout regardless of the user's `singleRowMode`
     * preference: a collapsed single row leaves the info-bar cramped
     * and competing with the keyboard content (the user explicitly
     * asked for "komplett expandierter Modus" whenever an info message
     * is present). The override is **transient + computed** — it masks
     * `singleRowMode` for the duration the bar is shown and never
     * mutates the persisted `Pref.SingleRowMode`, so the keyboard
     * returns to the user's preference once the bar is dismissed. This
     * mirrors the `ReprocessStaging` precedent above, which likewise
     * ignores `singleRowMode` for a workflow reason.
     */
    fun forKeyboard(state: DictateUiState): LayoutMode {
        val pipe = state.pipeline
        val isStaging = pipe is PipelineUiState.ReprocessStaging
        val isPipelineLive = pipe is PipelineUiState.Preparing || pipe is PipelineUiState.Running
        // InfoBar force-expand: a visible info-bar masks the single-row
        // preference (see KDoc). Computed, not persisted.
        val infoBarActive = InfoBarSelector.select(state).isNotEmpty()
        val singleRow = state.layout.singleRowMode && !infoBarActive
        // B4-VAL F-24: every case explicit; `else -> error(...)` guards
        // against future state-shape changes that break exhaustiveness.
        return when {
            isStaging -> KEYBOARD_REPROCESS_STAGING
            isPipelineLive && singleRow -> KEYBOARD_SINGLE_ROW_SEND_MODE
            isPipelineLive && !singleRow -> KEYBOARD_TWO_ROW_SEND_MODE
            !isPipelineLive && singleRow -> KEYBOARD_SINGLE_ROW
            !isPipelineLive && !singleRow -> KEYBOARD_TWO_ROW
            else -> error("forKeyboard: impossible state shape (pipe=$pipe, layout=${state.layout})")
        }
    }

    /**
     * Returns every [LayoutMode] known to this catalog — used for
     * test-time iteration and the `KeyboardLayoutManager`'s integrity
     * checks. List order matches the [LayoutModeId] enum order so a
     * by-index lookup gives stable results.
     */
    fun allModes(): List<LayoutMode> = listOf(
        KEYBOARD_TWO_ROW,
        KEYBOARD_SINGLE_ROW,
        KEYBOARD_TWO_ROW_SEND_MODE,
        KEYBOARD_SINGLE_ROW_SEND_MODE,
        KEYBOARD_REPROCESS_STAGING,
        OVERLAY_5BUTTON,
    )
}

