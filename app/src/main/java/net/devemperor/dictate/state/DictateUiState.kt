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
 * **Axes (13 sub-state fields, single-owner-per-axis throughout):**
 *
 * | # | Field | Owner module | Notes |
 * |---|-------|--------------|-------|
 * | 1 | [recording] | RecordingModule | sealed FSM with audioFile/useBluetooth payload |
 * | 2 | [pipeline] | PipelineModule | sealed FSM with sessionId payload |
 * | 3 | [viewMode] | ViewModeModule | KEYBOARD / WIDGET / HOVER (Triangle-FSM, ADR-0005) |
 * | 4 | [layout] | LayoutModule | contentArea + 3 booleans (Pref-mirror) |
 * | 5 | [overlay] | OverlayModule | 4 floats (positions) + 4 booleans (perm / pref / suppress / onboarding) |
 * | 6 | [audio] | AudioModule | AudioFocus + BluetoothSco + vibration |
 * | 7 | [resend] | ResendModule | lastAudioExists + enabled + cooldown + manual-paste hint (IME-service-death recovery, F-1) |
 * | 8 | [livePrompt] | LivePromptModule | chain state |
 * | 9 | [language] | LanguageModule | effective + override |
 * | 10 | [features] | FeatureToggleModule | 5 user-toggles |
 * | 11 | [theming] | ThemingModule | theme + accent + overlay-chars + speed |
 * | 12 | [pendingSessions] | PendingSessionsModule | PersistentList, DB-subscriber-driven |
 * | 13 | [interruption] | InterruptionModule (Phase 2) | null in Phase 1 |
 *
 * @see net.devemperor.dictate.state.DictateModule
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Module inventory"
 * @see docs/architecture/state-architecture/state-and-actions.md §3
 */
data class DictateUiState(

    // ─── Hot-path FSMs (sealed classes, dedicated reducer modules) ───
    val recording: RecordingState,
    val pipeline: PipelineUiState,
    val viewMode: ViewMode,

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

    // ─── Phase 2 stub (default null = not modelled) ───
    val interruption: InterruptionState? = null,
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
            layout = LayoutState(),
            overlay = OverlayState(),
            audio = AudioState(),
            resend = ResendState(),
            livePrompt = LivePromptState(),
            language = LanguageState(effective = "system"),
            features = FeatureToggles(),
            theming = ThemingState(),
            pendingSessions = persistentListOf(),
            interruption = null,
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
        RecordingState.Idle -> null
    }

/**
 * Pipeline progress FSM. Owned by `PipelineModule`.
 *
 * Each non-idle state carries the `sessionId` (UUID string per R.15)
 * so callers can disambiguate concurrent submissions. ReprocessStaging
 * is a sub-state of the pipeline FSM, not a separate axis.
 */
sealed interface PipelineUiState {
    /** No pipeline running. */
    data object Idle : PipelineUiState

    /** Audio uploaded, waiting for the first `StepStarted`. */
    data class Preparing(val sessionId: String) : PipelineUiState

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
 * Theming Pref-mirror. Owned by `ThemingModule`. All four fields mirror
 * their `Pref.*` counterparts.
 */
data class ThemingState(
    val theme: String = "system",
    val accentColor: Int = -14700810,
    val overlayCharacters: String = "()-:!?,.",
    val outputSpeed: Int = 5,
)

/**
 * Phase-2 InterruptionState. Default `null` in Phase 1 — InterruptionModule
 * is a stub. Phase 2 will populate this from call-state + headset-plug +
 * screen-state listeners and cascade `RecordingAction.CancelRecording`.
 */
data class InterruptionState(
    val callIncoming: Boolean = false,
    val headsetPlugged: Boolean = false,
    val screenAwake: Boolean = true,
)

/**
 * One entry in [DictateUiState.pendingSessions] — a recorded-but-not-
 * dismissed session shown in the restart-button UI.
 *
 * @property sessionId UUID string (R.15 — strings throughout for cross-process
 *   safety, never long-typed IDs).
 * @property status terminal status — only [net.devemperor.dictate.database.entity.SessionStatus.RECORDED]
 *   or [net.devemperor.dictate.database.entity.SessionStatus.COMPLETED]
 *   reach this list. `RECORDING`/`TRANSCRIBING` are rewritten by recovery
 *   before insertion (Spec 1 §6.3); `FAILED`/`CANCELLED` are terminal and
 *   never appear.
 */
data class PendingSession(
    val sessionId: String,
    val status: net.devemperor.dictate.database.entity.SessionStatus,
    val transcribedText: String?,
    val createdAt: Long,
)
