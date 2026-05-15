package net.devemperor.dictate.state.layout

import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ViewMode
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
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.RESEND,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = ::isResendVisible,
                        enabledResolver = { state -> !state.resend.resendCooldown },
                        alphaResolver = { state -> if (state.resend.resendCooldown) 0.4f else 1f },
                        actionResolver = { _, _ -> Action.ResendAction.ResendLastAudio },
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
                        // B4-VAL F-18: visibility gating happens at the
                        // forKeyboard() mode-selection layer — this slot is
                        // only evaluated when viewMode == KEYBOARD, so the
                        // structurally-always-true predicate is the honest
                        // value here.
                        visibilityPredicate = { true },
                        // B5 F-2: permission-aware — emits
                        // ShowOverlayOnboarding when overlay permission
                        // is missing instead of a silent reducer no-op.
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
                    // B4-VAL F-18: see KEYBOARD_TWO_ROW WIDGET_TOGGLE slot.
                    visibilityPredicate = { true },
                    // B5 F-2: permission-aware resolver (see the
                    // KEYBOARD_TWO_ROW WIDGET_TOGGLE slot).
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
                        enabledResolver = { state -> state.pipeline !is PipelineUiState.Preparing },
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
                    enabledResolver = { state -> state.pipeline !is PipelineUiState.Preparing },
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
                        // Enabled while staging is non-null. The spec also reads `s.isStarting`,
                        // a field not yet on `ReprocessStaging` — C14 will fold it in once
                        // Spec 1 §3 adds the field. For now we accept any non-null staging.
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
    // OVERLAY_5BUTTON (Spec 3 §3.1)
    // ════════════════════════════════════════════════════════════════
    //
    // **Shared between WIDGET and HOVER** — both ViewModes render via the
    // same [LayoutMode]. The differences live inside the resolvers,
    // which branch on `state.viewMode`:
    //
    // - RECORD + SEND: enabled only in WIDGET (HOVER has no
    //   InputConnection target — disabled at 0.4 alpha, OPEN-2).
    // - CLOSE: ViewMode-driven action (WIDGET → toggle back to
    //   KEYBOARD; HOVER → cascade-dismiss the overlay with
    //   SuppressAutoOverlayUntilNextSession).
    // - PAUSE + TRASH: identical behaviour in both ViewModes (recording
    //   lifecycle is independent of ViewMode).
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
                // Row 1: Record + Send + Pause
                RowDescriptor(slots = listOf(
                    ButtonSlot(
                        logicalId = LogicalButtonId.OVERLAY_RECORD,
                        widthPolicy = WidthPolicy.WrapContent,
                        // OPEN-2: visible only when the user can actually
                        // start a recording (no active recording, no live
                        // pipeline). The HOVER-vs-WIDGET distinction is
                        // handled by the enabledResolver below.
                        visibilityPredicate = { state ->
                            state.recording is RecordingState.Idle &&
                                state.pipeline is PipelineUiState.Idle
                        },
                        enabledResolver = { state -> state.viewMode == ViewMode.WIDGET },
                        alphaResolver = { state ->
                            if (state.viewMode == ViewMode.WIDGET) 1f else 0.4f
                        },
                        iconResolver = { R.drawable.ic_baseline_mic_24 },
                        actionResolver = ::resolveOverlayRecordAction,
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.OVERLAY_SEND,
                        widthPolicy = WidthPolicy.FillRemaining,
                        visibilityPredicate = { true },
                        // Enabled only when (a) WIDGET (HOVER has no
                        // InputConnection target), and (b) a recording is
                        // active or paused (nothing to send otherwise).
                        enabledResolver = { state ->
                            state.viewMode == ViewMode.WIDGET &&
                                state.recording.isActiveOrPaused
                        },
                        alphaResolver = { state ->
                            if (state.viewMode == ViewMode.WIDGET &&
                                state.recording.isActiveOrPaused
                            ) 1f else 0.4f
                        },
                        textResolver = { strings.overlaySend },
                        // sessionId placeholder: the recording-→-pipeline
                        // cross-module cascade fills it in (Spec 1 §15.2 /
                        // F-2). Same placeholder convention as the
                        // keyboard-surface SEND in `resolveRecordAction`.
                        actionResolver = { _, _ ->
                            Action.RecordingAction.StopRecordingAndSend(sessionId = "")
                        },
                    ),
                    ButtonSlot(
                        logicalId = LogicalButtonId.OVERLAY_PAUSE,
                        widthPolicy = WidthPolicy.WrapContent,
                        visibilityPredicate = { true },
                        enabledResolver = { state -> state.recording.isActiveOrPaused },
                        alphaResolver = { state ->
                            if (state.recording.isActiveOrPaused) 1f else 0.4f
                        },
                        iconResolver = { state ->
                            // Mirror the keyboard-surface PAUSE icon
                            // convention via the shared helper —
                            // `resolvePauseIcon` swaps mic / pause based
                            // on `RecordingState.Paused`.
                            resolvePauseIcon(state)
                        },
                        actionResolver = ::resolveOverlayPauseAction,
                    ),
                )),
                // Row 2: Trash on the left, Close on the right
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
                        actionResolver = { _, _ -> Action.RecordingAction.CancelRecording },
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
     */
    fun forKeyboard(state: DictateUiState): LayoutMode {
        val pipe = state.pipeline
        val isStaging = pipe is PipelineUiState.ReprocessStaging
        val isPipelineLive = pipe is PipelineUiState.Preparing || pipe is PipelineUiState.Running
        // B4-VAL F-24: every case explicit; `else -> error(...)` guards
        // against future state-shape changes that break exhaustiveness.
        return when {
            isStaging -> KEYBOARD_REPROCESS_STAGING
            isPipelineLive && state.layout.singleRowMode -> KEYBOARD_SINGLE_ROW_SEND_MODE
            isPipelineLive && !state.layout.singleRowMode -> KEYBOARD_TWO_ROW_SEND_MODE
            !isPipelineLive && state.layout.singleRowMode -> KEYBOARD_SINGLE_ROW
            !isPipelineLive && !state.layout.singleRowMode -> KEYBOARD_TWO_ROW
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

