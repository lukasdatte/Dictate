package net.devemperor.dictate.state

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import java.io.File

/**
 * Single source of truth for the Dictate IME's UI state.
 *
 * Immutable hierarchical container. Mutations produce a new instance via
 * `copy()`. Listed sub-state axes are owned by exactly one
 * [DictateModule] each (single-owner-per-axis invariant — see ADR-0001).
 *
 * **Why hierarchical sub-states (F-10)?** A flat 30+-field data class would
 * have been a "knows-everything" object — the same SRP anti-pattern that
 * motivated the modular-orchestrator refactor. Splitting by domain axis
 * lets each module reduce on its own sub-state type while the orchestrator
 * keeps the top-level glue via the lens pattern.
 *
 * **Why `PersistentList` (F-9)?** `pendingSessions` is mutated frequently
 * by the DB subscriber; using `kotlinx.collections.immutable.PersistentList`
 * preserves structural sharing across mutations. Round-trip via
 * `toMutableList()` is forbidden pattern (e) — see
 * `docs/architecture/state-architecture/forbidden-patterns.md`.
 *
 * **Axes (15 sub-state fields, single-owner-per-axis throughout):**
 *
 * | # | Field | Owner module | Notes |
 * |---|-------|--------------|-------|
 * | 1 | [recording] | RecordingModule | sealed FSM with audioFile/useBluetooth payload |
 * | 2 | [pipeline] | PipelineModule | sealed FSM with sessionId payload |
 * | 3 | [viewMode] | ViewModeModule | KEYBOARD / WIDGET / HOVER (Triangle-FSM, ADR-0005 — superseded by ADR-0008, to be removed in B3) |
 * | 3a | [widget] | WidgetModule | floating-overlay axis (Hidden/Visible+origin), B3 / ADR-0008 |
 * | 3b | [imeViewVisible] | WidgetModule | IME-View visibility (orthogonal to widget), B3 / ADR-0008 |
 * | 4 | [layout] | LayoutModule | contentArea + 3 booleans (Pref-mirror) |
 * | 5 | [overlay] | OverlayModule | 4 floats (positions) + 4 booleans (perm / pref / suppress / onboarding) |
 * | 6 | [audio] | AudioModule | AudioFocus + BluetoothSco + vibration |
 * | 7 | [resend] | ResendModule | lastAudioExists + enabled + cooldown + manual-paste hint (IME-service-death recovery, F-1) |
 * | 8 | [livePrompt] | LivePromptModule | chain state |
 * | 9 | [language] | LanguageModule | effective + override |
 * | 10 | [features] | FeatureToggleModule | 5 user-toggles |
 * | 11 | [theming] | ThemingModule | theme + accent + overlay-chars + speed |
 * | 12 | [pendingSessions] | PendingSessionsModule | PersistentList, DB-subscriber-driven |
 * | 13 | [interruption] | InterruptionModule | audio-focus / headset interruption events; `lastInterruption` non-null while an interruption-caused pause is live (active since F-036, 2026-07-02) |
 * | 14 | [keyboardInput] | KeyboardInputModule | host-editor `EditorInfo` snapshot driving Enter-button icon + action |
 * | 15 | [infoHints] | InfoHintModule | transient pipeline errors + Update/Rate/Donate hints (info-bar producers, ADR-0006) |
 *
 * @see net.devemperor.dictate.state.DictateModule
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Module inventory"
 * @see docs/architecture/state-architecture/state-and-actions.md §3
 */
data class DictateUiState(

    // ─── Hot-path FSMs (sealed classes, dedicated reducer modules) ───
    val recording: RecordingState,
    val pipeline: PipelineUiState,

    /**
     * **Legacy** Triangle-FSM (KEYBOARD / WIDGET / HOVER) per ADR-0005.
     * Owned by `ViewModeModule`. **Superseded by ADR-0008 — kept live
     * until B3.2-B3.5 finish migrating reducers + resolvers + predicates.**
     * Do not introduce new readers; consume the new [widget] /
     * [imeViewVisible] axes instead.
     */
    val viewMode: ViewMode,

    /**
     * Floating-overlay axis (B3 / ADR-0008). Replaces [viewMode]'s
     * WIDGET / HOVER. Owned by `WidgetModule`.
     */
    val widget: WidgetState,

    /**
     * Is the IME-View currently rendered? Orthogonal to [widget] —
     * both can be true simultaneously during transitions. Owned by
     * `WidgetModule`. The IME-Service flips this via
     * `Action.WidgetAction.OnImeViewShown` / `OnImeViewHidden`
     * dispatched from `onStartInputView` / `onFinishInputView`.
     */
    val imeViewVisible: Boolean,

    // ─── Layout / UI-mode ───
    val layout: LayoutState,
    val overlay: OverlayState,

    // ─── Subsystems (public state snapshots) ───
    val audio: AudioState,
    val resend: ResendState,
    val livePrompt: LivePromptState,
    val language: LanguageState,

    // ─── Pref-mirror (filled by PipelinePrefMirror) ───
    val features: FeatureToggles,
    val theming: ThemingState,

    // ─── DB-subscriber-driven ───
    val pendingSessions: PersistentList<PendingSession>,

    // ─── Interruption axis (audio-focus loss / headset unplug) ───
    val interruption: InterruptionState = InterruptionState(),

    /**
     * Keyboard-input sub-state. Currently carries the host-editor
     * `EditorInfo` snapshot that drives the Enter-button's icon and
     * action so both sides cannot drift apart. Owned by
     * `KeyboardInputModule` (`docs/plans/2026-05-23 - dictate-enter-button-host-action`).
     */
    val keyboardInput: KeyboardInputState = KeyboardInputState(),

    /**
     * Info-hint sub-state — transient pipeline errors + engagement
     * hints surfaced through the state-derived info bar. Owned by
     * `InfoHintModule` (ADR-0006 migration completion, research
     * `2026-07-02 - infobar-consolidation.md`).
     */
    val infoHints: InfoHintState = InfoHintState(),
) {
    companion object {
        /**
         * The "boot" state — used as the initial value of the store
         * before any module reducer runs.
         */
        fun initial(): DictateUiState = DictateUiState(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
            viewMode = ViewMode.KEYBOARD,
            widget = WidgetState.Hidden,
            imeViewVisible = true,
            layout = LayoutState(),
            overlay = OverlayState(),
            audio = AudioState(),
            resend = ResendState(),
            livePrompt = LivePromptState(),
            language = LanguageState(effective = "system"),
            features = FeatureToggles(),
            theming = ThemingState(),
            pendingSessions = persistentListOf(),
            interruption = InterruptionState(),
            keyboardInput = KeyboardInputState(),
            infoHints = InfoHintState(),
        )
    }
}

// ════════════════════════════════════════════════════════════════════
// Sub-state types
// ════════════════════════════════════════════════════════════════════

/**
 * Recording lifecycle FSM. Owned by `RecordingModule`.
 *
 * The sealed hierarchy structurally rules out impossible combinations
 * (e.g. preparing + paused). Each non-idle state carries the
 * `audioFile` so reducers don't need to read hardware (Pure-Reducer
 * Invariant, R.2).
 *
 * **`useBluetooth` invariant:** captured at `Preparing` time and
 * propagated through `Active`/`Paused` — Cross-Module-Invariant-Sicherung
 * for the Bluetooth-SCO pause-resume cycle (Issue 2.1.8 / Option C).
 *
 * **`sessionId` source (F-10, Epic §4 Block A2):** the session UUID is
 * minted by the click resolver (`StartRecording.sessionId`), carried
 * verbatim through `Preparing → Active → Paused`, and read back on
 * `StopRecordingAndSend` so the pipeline trigger gets the *same* id the
 * recording was started with. This replaces the earlier empty-string
 * sentinel that `StopRecordingAndSend` carried as a payload (the payload
 * is gone — the id lives in the FSM, the single source). Same
 * propagation invariant as `useBluetooth`/`audioFile`: the value is
 * captured once at `Preparing` and never re-derived. Spec 1 §15.2/§3
 * predate F-10 and show these variants without `sessionId`; Epic §4
 * Block A2 explicitly authorises adding it here as the clean source.
 * The FSM transition graph is unchanged — only the payload widens.
 */
sealed interface RecordingState {
    /** No recording in progress. */
    data object Idle : RecordingState

    /**
     * `MediaRecorder.allocate()` has been called; we are waiting for
     * `prepare()` to complete and report ready via
     * `Action.RecordingAction.MediaRecorderReady`.
     */
    data class Preparing(
        val useBluetooth: Boolean,
        val audioFile: File,
        val sessionId: String,
        /**
         * `true` while a BT-mic recording is waiting for the
         * Bluetooth-SCO handshake to resolve before the
         * `MediaRecorder` is allocated (C6-IMPL-1 / B2-C6-W1). The
         * deferred `AllocateMediaRecorder` fires only once
         * `Action.RecordingAction.ScoRouteResolved` arrives — so the
         * recorder source matches the actual SCO outcome
         * (`VOICE_COMMUNICATION` iff SCO connected, `MIC` on
         * fail/timeout). Mirrors the legacy
         * `RecordingStateController` `Preparing → onScoConnected /
         * onScoFailed → proceedStartRecording` wait
         * (`RecordingStateController.kt:135-139,:300-321`).
         *
         * `false` (default) for the non-BT path — allocation happens
         * immediately at `StartRecording`, unchanged from before.
         */
        val awaitingSco: Boolean = false,
        /**
         * The insertion target captured at `StartRecording`, carried
         * through the SCO wait so the deferred `AllocateMediaRecorder`
         * (fired on `ScoRouteResolved`) has it without re-reading. Only
         * non-null when [awaitingSco] is `true` (the deferred-allocate
         * path); `null` on the immediate-allocate path where the target
         * was already consumed by the synchronous
         * `Effect.AllocateMediaRecorder`.
         */
        val target: InsertionTarget? = null,
    ) : RecordingState

    /** Recording actively writing to [audioFile]. */
    data class Active(
        val useBluetooth: Boolean,
        val audioFile: File,
        val sessionId: String,
    ) : RecordingState

    /**
     * Recording paused (resumable). The same [audioFile] is reused on
     * resume — `MediaRecorder.pause()` keeps the file handle alive.
     */
    data class Paused(
        val useBluetooth: Boolean,
        val audioFile: File,
        val sessionId: String,
    ) : RecordingState

    /**
     * A recording that was cut off by process death — the FGS is torn
     * down when the user switches keyboards (see
     * `DictatePipelineService.onDestroy`) — and recovered from disk on
     * the next keyboard open. Rendered exactly like [Paused] (frozen
     * timer at [elapsedMs], the same button row) so the user sees the
     * recording "as if it had just been briefly paused" — the explicit
     * user requirement (2026-05-22).
     *
     * **Why a distinct state and not [Paused]:** [Paused] is backed by a
     * live `MediaRecorder` — its `ResumeRecording` arm calls
     * `ResumeMediaRecorder`. An interrupted recording has no live
     * recorder; its audio lives only as on-disk segments. Resuming it
     * means `StartRecordingContinuation` (allocate a fresh recorder,
     * codec-matched to the prior segments), not `resume()`. Modelling it
     * as [Paused] would force a meaningless `audioFile` / `useBluetooth`
     * and make [Paused] lie about owning a recorder. [Interrupted]
     * carries exactly what it needs and nothing fake.
     *
     * Set by the recovery pass (`Action.RecordingAction.SurfaceInterruptedRecording`)
     * after `PipelineRecovery` promoted the row to `RECORDING_INTERRUPTED`.
     * Continuing routes through `Action.RecordingAction.StartRecordingContinuation`
     * (the same path a Record-tap from [Idle] takes); discarding routes
     * through `Action.RecordingAction.DiscardInterruptedSession`.
     *
     * @property sessionId the `RECORDING_INTERRUPTED` session surfaced.
     * @property elapsedMs accumulated duration of the already-recorded
     *   segments — drives the frozen timer display (the user's "0:08").
     */
    data class Interrupted(
        val sessionId: String,
        val elapsedMs: Long,
    ) : RecordingState
}

/**
 * `true` when recording is either [RecordingState.Active] or
 * [RecordingState.Paused].
 *
 * Centralised here because three modules + several `ButtonSlot` predicates
 * spell this same check out in Spec 1 §15.2, Spec 3 §3.1, Spec 3 §4.8, and
 * `ViewModeModule.computeViewMode`. Inlining it loses DRY (and re-introduces
 * the L-3 finding from Phase-B validation: "`predTrashVisible` /
 * `predPauseVisible` are literally identical strings"). Keep one
 * canonical predicate next to the FSM definition.
 *
 * **Why not include [RecordingState.Preparing]?** Preparing is a
 * pre-active transient (`MediaRecorder.prepare()` is in flight). Pipeline-
 * active and view-mode checks treat it separately via `is Preparing`
 * branches; conflating it into this predicate would over-trigger HOVER
 * and pause-cascades.
 */
val RecordingState.isActiveOrPaused: Boolean
    get() = this is RecordingState.Active || this is RecordingState.Paused

/**
 * The audio file the current recording session writes to, or `null` when
 * no session is in flight ([RecordingState.Idle]).
 *
 * Post-cutover (Epic `dictate-cutover-completion` D-14, C9-C2) the
 * orchestrator's [RecordingState] is the **single authoritative source**
 * for the in-flight recording's audio file — the legacy
 * `DictateInputMethodService.audioFile` IME field was deleted. All three
 * non-idle FSM states ([Preparing]/[Active]/[Paused]) carry the same
 * `audioFile` handle (minted once at `StartRecording`, Spec 1 §15.2), so
 * this canonical accessor is the read path the IME's
 * `captureFreshConfigSnapshot` uses on the fresh-recording send-tap.
 *
 * Centralised here next to [isActiveOrPaused] for the same DRY reason:
 * the sealed-interface payload extraction would otherwise be spelled out
 * with an `is`-cascade at every IME read site.
 */
val RecordingState.audioFileOrNull: File?
    get() = when (this) {
        is RecordingState.Preparing -> audioFile
        is RecordingState.Active -> audioFile
        is RecordingState.Paused -> audioFile
        // An interrupted recording has no single in-flight audio file —
        // its audio is the multi-segment list on disk. Continuing it
        // re-resolves the segments via ContinuationLookup.
        is RecordingState.Interrupted -> null
        RecordingState.Idle -> null
    }

/**
 * `true` when the IME may commit transcript text (or send Enter) into
 * the current host field — i.e. the IME-View is on screen and therefore
 * backs `getCurrentInputConnection()` with a real, focused target.
 *
 * **The host-commit decision keys on [imeViewVisible] and ONLY on it.**
 * [widget] is deliberately not consulted: the floating widget is
 * orthogonal to the IME-View ([imeViewVisible]'s KDoc — both can be true
 * at once, the normal "dictate with the widget floating over the
 * keyboard" flow). A widget-based gate wrongly blocked every commit
 * while the widget floated, so a widget Send produced a transcript that
 * never reached the focused field — it was always deferred to
 * Pending-Insert (2026-05-22 regression, ADR-0008 §"Send-during-widget").
 *
 * Canonical, named home for the rule so the two enforcement sites —
 * `DictateInputMethodService.canCommitToHost` (the commit/Enter guard)
 * and `resolveOverlayRecordAction` (the overlay Send action-resolver) —
 * cannot drift onto different axes again. The earlier bug was exactly a
 * guard whose name said "canCommitToHost" while its body checked
 * `widget`; a single named predicate makes that name-vs-body drift
 * structurally impossible.
 */
val DictateUiState.canCommitToHost: Boolean
    get() = imeViewVisible

/**
 * Pipeline progress FSM. Owned by `PipelineModule`.
 *
 * Each non-idle state carries the `sessionId` (UUID string per R.15)
 * so callers can disambiguate concurrent submissions. ReprocessStaging
 * is a sub-state of the pipeline FSM, not a separate axis.
 */
/**
 * Lifecycle status of one [StepRowItem] inside [PipelineUiState.Running.stepHistory].
 *
 * Phase 5.A of `2026-05-21 - dictate-render-cutover-completion-vol2`
 * introduces the step-history list to absorb the per-step UI state the
 * legacy renderer carried in its own `core.PipelineUiState.Running`.
 * The orchestrator state becomes the SoT; the renderer becomes a
 * reactive consumer in Phase 5.B.
 */
enum class StepStatus {
    /** The step has started; ProgressBar is animating. */
    RUNNING,

    /** The step finished successfully; ✓ icon, duration recorded. */
    COMPLETED,

    /** The step failed; ✕ icon, duration recorded. The pipeline may
     *  still continue (queued prompts keep running) — only the row
     *  itself is marked failed. See `PipelineModule` `StepFailed` arm. */
    FAILED,
}

/**
 * One row of the running pipeline's step-row UI. Stored in
 * [PipelineUiState.Running.stepHistory]; consumed by
 * `PipelineStepRowRenderer` (Phase 5.B) to inflate / update the step
 * rows reactively from the orchestrator state instead of via imperative
 * `addRunningStep` / `completeStep` / `failStep` calls.
 *
 * @property stepName label rendered into the row's `nameTv`.
 * @property status step lifecycle marker — drives the icon (✓ / ✕) and
 *   the ProgressBar visibility.
 * @property startedAtMs wall-clock ms (from `ReducerContext.now`) at
 *   which the row entered [StepStatus.RUNNING]. Used to compute
 *   [durationMs] on completion / failure.
 * @property durationMs duration in ms once the step has finished (i.e.
 *   `status != RUNNING`). Zero while the step is still RUNNING.
 */
data class StepRowItem(
    val stepName: String,
    val status: StepStatus,
    val startedAtMs: Long,
    val durationMs: Long = 0L,
)

sealed interface PipelineUiState {
    /** No pipeline running. */
    data object Idle : PipelineUiState

    /**
     * Audio uploaded, waiting for the first `StepStarted`.
     *
     * **[autoEnterActive] (Post-cutover #AE-DEEP2):** The per-run auto-enter
     * override is carried already in `Preparing`, not only in `Running`. The
     * "second SEND-tap" the user makes to toggle auto-enter typically lands
     * during the 500ms–2s upload window between `Preparing` and the first
     * runner-callback that triggers `StartPipeline` (`Preparing → Running`).
     * Without a Preparing-side flag the tap silently no-ops in both click
     * paths (catalog `resolveRecordActionPipeline` returns null for Preparing;
     * the legacy QWERTZ path's orchestrator-dispatch guard rejects Preparing).
     * Defaults to `false`; the `Preparing → Running` reducer arm merges this
     * value with the runner-provided default (whichever is `true` wins).
     */
    data class Preparing(
        val sessionId: String,
        val autoEnterActive: Boolean = false,
    ) : PipelineUiState

    /**
     * Pipeline running; progress UI is active.
     *
     * **F-13 progress counters (2026-05-15):** [completedSteps] /
     * [totalSteps] / [elapsedMs] feed the live record-button label
     * (e.g. `"2/3 ↵  0:08"`, see
     * [net.devemperor.dictate.state.layout.resolveRecordButtonTextPipeline])
     * and the FGS progress notification (Epic §4 Block A1, AC-4). They
     * are additive defaulted fields — every existing `Running(...)`
     * construction site stays source-compatible.
     *
     * @property completedSteps number of pipeline steps finished so far
     *   (incremented by `StepCompleted`). `0` until the first step
     *   completes.
     * @property totalSteps total pipeline steps for this run, set once from
     *   `StartPipeline.totalSteps` on `Preparing → Running`; never
     *   re-stamped — `StepStarted` carries no total in its payload (see
     *   PipelineModule Dev-1). `0` means "unknown" and the label formatter
     *   renders it as such.
     * @property startedAtMs wall-clock ms (from [ReducerContext.now]) at
     *   the `Preparing → Running` transition. The basis for [elapsedMs];
     *   not rendered directly. `0L` only in the defaulted (test) case
     *   where the reducer did not set it.
     * @property elapsedMs ms elapsed since [startedAtMs], stamped on every
     *   counter-affecting transition (`StepStarted` / `StepCompleted`)
     *   from [ReducerContext.now]. The reducer is the only legal time
     *   source (R.2 / pure-reducer invariant), so the UI reads this
     *   field rather than computing `now - startedAtMs` itself.
     */
    data class Running(
        val sessionId: String,
        val target: InsertionTarget,
        val autoEnterActive: Boolean = false,
        val completedSteps: Int = 0,
        val totalSteps: Int = 0,
        val startedAtMs: Long = 0L,
        val elapsedMs: Long = 0L,
        /**
         * Phase 5.A of `2026-05-21 - dictate-render-cutover-completion-vol2` —
         * `true` once any [Action.PipelineAction.StepFailed] arm has fired
         * during this run, `false` otherwise. Drives the
         * `RecordButtonColorController` side-channel that paints the
         * record-button text red while at least one step has failed in the
         * current pipeline.
         *
         * **Important Q6 semantics:** a `StepFailed` event does NOT end
         * the pipeline. `executeQueuedPrompts` continues with the next
         * queued prompt; `hasFailure` stays `true` until the pipeline
         * actually ends (`PipelineDone` / `PipelineFailed` / `CancelPipeline`
         * → `Idle`), at which point the flag is wiped along with the
         * branch transition.
         */
        val hasFailure: Boolean = false,
        /**
         * Phase 5.A of `2026-05-21 - dictate-render-cutover-completion-vol2` —
         * append-only log of step-row items for this run. Each
         * `StepStarted` appends a [StepStatus.RUNNING] entry;
         * `StepCompleted` finalises the last `RUNNING` entry to
         * `COMPLETED` + `durationMs`; `StepFailed` finalises to `FAILED`.
         * `StartPipeline` resets the list to `persistentListOf()`.
         *
         * The renderer ([net.devemperor.dictate.state.render.PipelineStepRowRenderer],
         * to be reduced to a reactive consumer in Phase 5.B) inflates
         * the row views by diffing against this list — replacing the
         * legacy imperative `addRunningStep` / `completeStep` /
         * `failStep` API.
         */
        val stepHistory: kotlinx.collections.immutable.PersistentList<StepRowItem> =
            kotlinx.collections.immutable.persistentListOf(),
    ) : PipelineUiState

    /**
     * Resend-long-press: the user is editing the prompt queue and language
     * before re-submitting. The large record button acts as a Send trigger
     * in this state.
     *
     * **F-12 single-submit guard (2026-05-15, B1-VAL-W1 option b).** This
     * variant deliberately does **not** carry an `isStarting` flag. The
     * canonical new-state spec (Spec 1 §3) defines `ReprocessStaging` as
     * `(sessionId, transcript)` only. Double-submit protection is the FSM
     * `ReprocessStaging → Preparing` edge in `PipelineModule`'s
     * `SendStaging` arm: the first tap transitions to `Preparing`; a
     * second tap arrives with `pipeline is Preparing` and reduces to
     * `null`. Dispatch is main-thread-confined (ADR-0001) so the two taps
     * are serialized — the FSM edge is the guard, not a state flag. The
     * legacy `core/PipelineUiState.kt` `isStarting` was a dead field
     * (never read even in legacy); it is not carried into the new module.
     * See `docs/plans/2026-05-15 - dictate-cutover-completion/research/sendstaging-isstarting-guard-semantics.md`.
     */
    data class ReprocessStaging(
        val sessionId: String,
        val transcript: String,
    ) : PipelineUiState
}

/**
 * Name of the currently-running step in [PipelineUiState.Running], or
 * `null` when no step is in progress (e.g. between two steps or right
 * after [Action.PipelineAction.StartPipeline]).
 *
 * Derived from [PipelineUiState.Running.stepHistory] so the orchestrator
 * state has a single source of truth — no separate `currentStepName`
 * field that could drift out of sync with the history list. This is
 * the Q3 decision from
 * `2026-05-21 - dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md §7 Q3`.
 */
val PipelineUiState.Running.currentStepName: String?
    get() = stepHistory.lastOrNull { it.status == StepStatus.RUNNING }?.stepName

/**
 * Triangle-FSM mode. Owned by `ViewModeModule`. See ADR-0005 for the
 * deterministic `computeViewMode(imeViewVisible, userPrefersWidget, pipelineActive)`
 * transition table.
 */
enum class ViewMode {
    /** Normal IME mode — full keyboard visible in the IME-View. */
    KEYBOARD,

    /** User opted into the floating overlay; Send is functional. */
    WIDGET,

    /** Auto-mode after IME-View hidden during pipeline — Send disabled. */
    HOVER,
}

// ════════════════════════════════════════════════════════════════════
// WidgetState (B3 / ADR-0008 — Surface-Axes)
// ════════════════════════════════════════════════════════════════════
//
// The legacy [ViewMode] enum collapsed three orthogonal facts —
// "which surface is rendered", "who triggered the surface", "is a
// pipeline / recording in flight" — into a single field. [computeViewMode]
// then untangled them per dispatch via a truth-table whose row-priority
// silently dropped the *origin* of a WIDGET surface (user-toggled vs
// pipeline-fallback). ADR-0008 splits this into two strictly
// independent axes:
//
//  1. [WidgetState] — is the floating overlay visible, and if so why?
//     Hidden | Visible(USER) | Visible(PIPELINE).
//  2. `imeViewVisible: Boolean` — is the IME-View rendered? Top-level
//     field on [DictateUiState] (added alongside `widget`).
//
// Both axes can be true simultaneously (widget *and* keyboard rendered
// at the same time during a transition). [ViewMode] derives a single
// surface; the new model lets each axis stay live independently and
// preserves user intent across IME-View lifecycle events.
//
// **Migration status:** B3.1 introduces the data types only. Both
// axes co-exist with [ViewMode] until B3.2 — B3.5 migrate reducers,
// resolvers, predicates, and finally remove the legacy enum.

/**
 * Floating-overlay visibility axis (B3 / ADR-0008 §"Surface-Axes").
 *
 * Replaces [ViewMode]'s WIDGET / HOVER values; the keyboard-axis lives
 * separately as `DictateUiState.imeViewVisible`.
 *
 * # Variants
 *
 *  - **[Hidden]** — no floating overlay rendered. Default initial
 *    state; reached via `User Close-Btn` (W2), pipeline-end with
 *    PIPELINE origin (W6), or `OnImeViewShown` while PIPELINE origin
 *    (W4).
 *  - **[Visible]** — the floating overlay is rendered. Carries an
 *    [origin] tag distinguishing user-stickiness from pipeline-
 *    transience:
 *     - [WidgetOrigin.USER] — user pressed the widget-toggle; the
 *       overlay survives IME-View show/hide cycles (W5: sticky).
 *     - [WidgetOrigin.PIPELINE] — auto-shown by `OnImeViewHidden` while
 *       recording / pipeline was in flight (W3); auto-closes on the
 *       next `OnImeViewShown` (W4) or pipeline-end (W6).
 *
 * # Why `sealed class` and not `enum`
 *
 * The [Visible] variant carries the [WidgetOrigin] data; an enum
 * would need a side-channel (a second field) and re-introduce the
 * "two fields, one axis" pitfall that motivated this refactor.
 */
sealed class WidgetState {
    /** No floating overlay rendered. */
    data object Hidden : WidgetState()

    /**
     * The floating overlay is rendered. [origin] determines sticky-vs-
     * transient lifecycle per the W1-W8 transition table.
     */
    data class Visible(val origin: WidgetOrigin) : WidgetState()
}

/**
 * Provenance of a [WidgetState.Visible] surface.
 *
 * Captures the **user intent vs system convenience** distinction that
 * the legacy Triangle-FSM lost via row-priority truth-table resolution.
 * A USER-origin widget persists across IME-View lifecycle events; a
 * PIPELINE-origin widget is auto-released on the next IME-View-show or
 * pipeline-end.
 */
enum class WidgetOrigin {
    /**
     * User pressed widget-toggle. Sticky semantics:
     *
     *  - survives `OnImeViewShown` (W5 — keyboard renders alongside)
     *  - survives `OnImeViewHidden` (W3 — already visible, no
     *    re-trigger)
     *  - is dismissed only by an explicit user action ([Visible] → [Hidden]
     *    via Close-Btn — W2) or a Trash-cascade.
     */
    USER,

    /**
     * Auto-shown by the system because the IME-View vanished while a
     * recording or pipeline was in flight. Transient semantics:
     *
     *  - is dismissed automatically on the next `OnImeViewShown`
     *    (W4) — the keyboard is back, the overlay is no longer
     *    needed
     *  - is dismissed automatically on `recording=Idle && pipeline=Idle`
     *    (W6) — nothing left to render
     */
    PIPELINE,
}

/**
 * Which UI surface closed the widget — the discriminator for the W2
 * `CloseWidget` reducer's pause decision (2026-05-22 user-req).
 *
 * The legacy `CloseWidget` paused the in-flight recording unconditionally.
 * The user wants the pause gated on *how* the widget was closed:
 *  - Close via the keyboard's edit-bar toggle → IME-View stays on
 *    screen, the user can keep dictating → keep recording running.
 *  - Close via the floating overlay's own X button → the user
 *    explicitly dismissed the Dictate surface → pause the recording.
 *
 * @see Action.WidgetAction.CloseWidget
 */
enum class WidgetCloseSource {
    /** Edit-bar Widget-Toggle-Btn (`edit_widget_toggle_btn`). No pause. */
    KEYBOARD_TOGGLE,

    /** Floating overlay's X button (`overlay_close_btn`). Pauses recording. */
    WIDGET_BUTTON,
}

/**
 * Layout-related Pref-mirror + `contentArea` switch. Owned by `LayoutModule`.
 *
 * `contentArea` was previously a top-level field; it now lives here so
 * the small-mode mutation can atomically set both `smallMode = true`
 * and `contentArea = MAIN_BUTTONS` (KSM-bug fix, Issue 1.1.5 / R.5).
 *
 * @see net.devemperor.dictate.core.ContentArea
 */
data class LayoutState(
    val contentArea: net.devemperor.dictate.core.ContentArea = net.devemperor.dictate.core.ContentArea.MAIN_BUTTONS,
    val singleRowMode: Boolean = false,
    val smallMode: Boolean = false,
    val animationsEnabled: Boolean = true,
)

/**
 * Overlay-Position + Permission + Suppress + Onboarding. Owned by `OverlayModule`.
 *
 * Position fields are normalised [0..1] relative to the screen dimension
 * for the given orientation (portrait vs landscape).
 *
 * @property suppressAutoOverlayUntilNextSession Issue 3.1.7 — set after
 *   `closeOverlay`-cascade (user closed WIDGET while in HOVER); prevents
 *   auto-reopen for the current recording session. Reset by
 *   `Action.OverlayAction.ResetSuppressBit` on `Idle → Preparing` boundary.
 * @property hasPermission Issue 3.1.3 — permission status mirrored from
 *   `OverlayPermissionObserver`; reducers don't poll the system.
 */
data class OverlayState(
    val positionPortraitX: Float = 1.0f,
    val positionPortraitY: Float = 0.1f,
    val positionLandscapeX: Float = 1.0f,
    val positionLandscapeY: Float = 0.1f,
    val userPrefersWidget: Boolean = false,
    val onboardingPending: Boolean = false,
    val suppressAutoOverlayUntilNextSession: Boolean = false,
    val hasPermission: Boolean = false,
)

/**
 * Audio subsystem state (focus + Bluetooth-SCO + vibration). Owned by
 * `AudioModule`.
 *
 * @property audioFocusEnabledPref user toggle (Pref-mirrored).
 * @property audioFocusGranted runtime system status — kept in state so
 *   reducers can react to focus-loss → pause cascades without hardware reads.
 */
data class AudioState(
    val audioFocusEnabledPref: Boolean = true,
    val audioFocusGranted: Boolean = false,
    val bluetoothSco: BluetoothScoPublicState = BluetoothScoPublicState(),
    val useBluetoothMic: Boolean = false,
    val vibrationEnabled: Boolean = true,
)

/**
 * Public snapshot of the BluetoothSco subsystem. The full state machine
 * lives inside the subsystem; only the user-observable fields are mirrored
 * here.
 */
data class BluetoothScoPublicState(
    val phase: ScoPhase = ScoPhase.Disconnected,
    val failureReason: String? = null,
)

/** BluetoothSco connection phase. */
enum class ScoPhase {
    Disconnected,
    Waiting,
    Connected,
    Failed,
}

/**
 * Resend-button visibility + cooldown + post-pipeline UI affordances.
 * Owned by `ResendModule`.
 *
 * @property lastAudioExists set by `ResendAction.MarkLastAudio` after the
 *   pipeline completes successfully and the audio file is still readable.
 * @property resendEnabled mirrored from `Pref.ResendButton`.
 * @property resendCooldown 500 ms window after a resend-click to prevent
 *   double-fire.
 * @property lastResultNeedsManualPaste set after IME-service-death recovery
 *   when the pipeline completed but no `InputConnection` was available to
 *   insert the result; the recovery path copied the result to the system
 *   clipboard and the IME header must hint "tap to paste". Cleared by
 *   `Action.ResendAction.ClearManualPasteFlag` after the user pastes
 *   (Issue 2.1.9 Option C; F-1 fix per
 *   `research/manual-paste-field-architecture.md`). Sibling to
 *   [lastAudioExists] — both are post-pipeline UI affordances surviving
 *   the pipeline-FSM's return to Idle.
 *
 *   **B3-VAL-W1 F-14 (data-shape):** the Boolean form drops N-1 of N
 *   pending paste sessions on recovery. The new
 *   [pendingPasteSessionIds] set is the canonical store; the Boolean
 *   stays as a derived "any pending" alias for IME consumers that
 *   haven't migrated to the per-session affordance (consumer wiring
 *   tracked for B5/B6, see Issue Index "F-14 IME consumer wiring").
 * @property pendingPasteSessionIds set of session-ids that have a
 *   COMPLETED result the user has not yet seen (manual-paste hint
 *   surface). Populated by `Action.ResendAction.NotifyManualPasteNeeded(sessionId)`.
 *   `ClearManualPasteFlag` clears the whole set; the per-session
 *   clear lands when the IME consumer learns the per-session
 *   affordance (B5/B6).
 */
data class ResendState(
    val lastAudioExists: Boolean = false,
    val resendEnabled: Boolean = false,
    val resendCooldown: Boolean = false,
    val lastResultNeedsManualPaste: Boolean = false,
    val pendingPasteSessionIds: Set<String> = emptySet(),
)

/**
 * Live-prompt chaining buffer. Owned by `LivePromptModule`.
 *
 * @property enabled user toggle for live-prompt mode.
 * @property pendingChain a follow-up prompt is queued and will run after the
 *   current pipeline finishes (chain-trigger from `PipelineDone` cascade).
 */
data class LivePromptState(
    val enabled: Boolean = false,
    val pendingChain: Boolean = false,
)

/**
 * Effective + override language for transcription. Owned by `LanguageModule`.
 *
 * @property effective the language that will be used for the next call
 *   (Pref-resolved at boot, may be `"system"`).
 * @property override Reprocess-Staging override — set per session, never
 *   persisted.
 */
data class LanguageState(
    val effective: String,
    val override: String? = null,
)

/**
 * Five product toggles. Owned by `FeatureToggleModule`.
 *
 * All five mirror their `Pref.*` counterparts via `PipelinePrefMirror`.
 */
data class FeatureToggles(
    val rewordingEnabled: Boolean = true,
    val autoFormattingEnabled: Boolean = false,
    val instantOutputEnabled: Boolean = true,
    val autoEnterEnabled: Boolean = false,
)

/**
 * Theming Pref-mirror. Owned by `ThemingModule`. All five fields mirror
 * their `Pref.*` counterparts.
 *
 * @property widgetOpacity floating-overlay card opacity in percent
 *   (20..100, 100 = opaque) — mirrors `Pref.WidgetOpacity` (F-118).
 */
data class ThemingState(
    val theme: String = "system",
    val accentColor: Int = -14700810,
    val overlayCharacters: String = "()-:!?,.",
    val outputSpeed: Int = 5,
    val widgetOpacity: Int = 100,
)

/**
 * Interruption sub-state — owned by `InterruptionModule` (2026-07-02,
 * F-036 implementation).
 *
 * @property lastInterruption the interruption event that paused the
 *   current recording, or `null` when no interruption-caused pause is
 *   live. **Lifecycle invariant:** set only when an
 *   [Action.InterruptionAction] arrives while the recording is
 *   `Active` (the reducer gates on it; the module's observer then
 *   cascades `PauseRecording` in the same dispatch pass); cleared via
 *   the self-cascaded `ClearInterruption` the moment the recording
 *   leaves `Paused`. Non-null therefore implies "the recording is
 *   paused *because of* this event" — the info-bar producer keys on
 *   exactly that.
 */
data class InterruptionState(
    val lastInterruption: InterruptionEvent? = null,
)

/**
 * One recorded interruption occurrence.
 *
 * @property reason typed classifier — see [InterruptionReason].
 * @property occurredAt wall-clock ms from `ReducerContext.now`
 *   (reducers never read the clock directly). Doubles as the
 *   info-bar item's `createdAt` sort key.
 */
data class InterruptionEvent(
    val reason: InterruptionReason,
    val occurredAt: Long,
)

/**
 * Why a recording was interruption-paused. Mirrors the two
 * [Action.InterruptionAction] producer leaves — see their KDoc for the
 * detection semantics (audio-focus classification, device-removal
 * classification).
 */
enum class InterruptionReason {
    /** Another app took audio focus (call, assistant, other recorder). */
    AUDIO_FOCUS_LOST,

    /** External input-capable device (wired/USB/BT headset) disconnected. */
    HEADSET_DISCONNECTED,
}

/**
 * One entry in [DictateUiState.pendingSessions] — a recorded-but-not-
 * dismissed session shown in the restart-button UI.
 *
 * @property sessionId UUID string (R.15 — strings throughout for cross-process
 *   safety, never long-typed IDs).
 * @property status one of [net.devemperor.dictate.database.entity.SessionStatus.RECORDED],
 *   [net.devemperor.dictate.database.entity.SessionStatus.RECORDING_INTERRUPTED],
 *   or [net.devemperor.dictate.database.entity.SessionStatus.COMPLETED] —
 *   the three terminal-ish states that surface to the user. `RECORDING`
 *   and `TRANSCRIBING` are rewritten by recovery before insertion (Spec 1 §6.3);
 *   `FAILED`/`CANCELLED` are terminal and never appear.
 *
 *   `RECORDING_INTERRUPTED` was added in recording-stack-completion
 *   §4.5.3 to let the keyboard Trash-button at Idle target the row
 *   for explicit user-driven discard (`DiscardInterruptedSession`).
 *   Note that consumers must filter on `status` — the [InfoBarSelector]
 *   producer-pipeline does this already (each producer's `.filter { …`
 *   clause picks exactly one status), so widening the list does not
 *   change existing info-bar behaviour.
 */
data class PendingSession(
    val sessionId: String,
    val status: net.devemperor.dictate.database.entity.SessionStatus,
    val transcribedText: String?,
    val createdAt: Long,
    /**
     * The `last_error_message` column from `SessionEntity`. The
     * Partial-Recovery info-bar producer (B4) inspects this for the
     * marker substring "partial:<seconds>" persisted by the pipeline
     * when `PipelineAudioResult.PartialRecovery` reaches the upload
     * stage (segments were unreadable, some audio was lost).
     */
    val lastErrorMessage: String? = null,
)
