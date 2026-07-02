@file:JvmName("LayoutActionResolvers")

package net.devemperor.dictate.state.layout

import android.util.Log
import net.devemperor.dictate.R
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.ModuleServices
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ViewMode
import net.devemperor.dictate.state.WidgetCloseSource
import net.devemperor.dictate.state.WidgetState
import java.util.UUID

/**
 * Shared `ButtonSlot.actionResolver` helpers consumed by the
 * [LayoutCatalog].
 *
 * # The nullable-return contract (R.3)
 *
 * Resolvers return `Action?`. `null` means "click is structurally
 * meaningless in the current state" — the click handler short-circuits
 * via `?.let { onAction(it) }` so no orchestrator dispatch happens and no
 * `Unrouted`-log fires (Spec 2 §3.2 KDoc, §6 wireStaticHandlers).
 *
 * **Why a separate file?** Each resolver carries non-trivial dispatch
 * logic — record-btn alone has three live branches (Idle / Active /
 * Paused). Keeping them as named top-level functions plays into Kotlin
 * method references (`actionResolver = ::resolveRecordAction`) and gives
 * the unit-test layer typed seams to assert on (Spec 2 §14.2).
 *
 * # IOException handling for `resolveRecordAction`
 *
 * `services.audioFileFactory.allocate()` can throw `IOException`
 * (mkdirs/storage failure). The resolver translates that into a
 * user-visible toast via `services.toastSink.show(...)`, logs a warning,
 * and returns `null` — the click ends as a silent no-op. The reducer
 * never sees the failure (R.2 Pure-Reducer invariant). See Spec 1
 * §4.11.10 / F1.
 *
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see net.devemperor.dictate.state.layout.ButtonSlot.actionResolver
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §8.5
 */

private const val TAG: String = "LayoutResolver"

/**
 * Mint a fresh session UUID for a new recording (F-10, Epic §4 Block A2).
 *
 * Both keyboard-surface and overlay-surface record resolvers start a
 * recording and must supply a `sessionId` for the FSM to carry through
 * to the pipeline trigger. Centralised here so the two call-sites stay
 * identical (R.15 — UUID strings throughout, matching the IME's
 * `UUID.randomUUID().toString()` in `DictateInputMethodService` and
 * `PipelineOrchestrator`). B3 will replace the *origin* (the IME's
 * pre-allocated `JobExecutor.register()` id flows in instead) without
 * touching the resolver shape.
 */
private fun newSessionId(): String = UUID.randomUUID().toString()

/**
 * Record-button click resolver in standard (non-SEND-MODE) layouts.
 *
 * | RecordingState | Action returned                                      |
 * |----------------|------------------------------------------------------|
 * | `Idle`         | `StartRecording(target, allocatedFile, sessionId)`   |
 * | `Active`       | `StopRecordingAndSend`                               |
 * | `Paused`       | `StopRecordingAndSend`                               |
 * | `Preparing`    | `null` (click is a no-op while the recorder warms up)|
 *
 * **sessionId source (F-10, Epic §4 Block A2).** The resolver mints a
 * fresh UUID and threads it into
 * [Action.RecordingAction.StartRecording]; the RecordingModule FSM
 * carries it through `Preparing → Active → Paused` and the
 * `StopRecordingAndSend` reducer arm reads it back off the live state.
 * The earlier empty-string-payload sentinel on `StopRecordingAndSend`
 * is gone — the FSM is the single source of the id. When B3
 * flips the IME recording trigger to dispatch, the IME's pre-allocated
 * `JobExecutor.register()` UUID is routed in here instead of a fresh
 * mint (the seam is the same; only the id origin moves).
 *
 * **IOException side-channel.** `audioFileFactory.allocate()` may fail;
 * the resolver fires a toast on `services.toastSink` and returns `null`.
 */
fun resolveRecordAction(state: DictateUiState, services: ModuleServices): Action? {
    return when (state.recording) {
        RecordingState.Idle -> resolveStartRecordingFromIdle(services)

        is RecordingState.Active -> Action.RecordingAction.StopRecordingAndSend
        is RecordingState.Paused -> Action.RecordingAction.StopRecordingAndSend
        is RecordingState.Preparing -> null

        // Recovery-surfaced interrupted recording (2026-05-22). A
        // Record-tap continues it — the same continuation path as the
        // Idle branch above. ContinuationLookup re-resolves the segment
        // list + codec and allocates the next segment; it finds this
        // very session (the freshest RECORDING_INTERRUPTED row). When
        // the session is no longer continuable (segments gone /
        // freshness elapsed between surfacing and the tap) the lookup
        // returns null and the tap is a no-op — the user can discard
        // via the trash button instead.
        is RecordingState.Interrupted -> {
            services.continuationLookup.lookup()?.let { continuation ->
                Action.RecordingAction.StartRecordingContinuation(
                    target = InsertionTarget.INPUT_CONNECTION,
                    audioFile = continuation.nextSegmentFile,
                    sessionId = continuation.sessionId,
                    codecParams = continuation.codecParams,
                )
            }
        }
    }
}

/**
 * Shared "arm a fresh recording from Idle" body, extracted so the primary
 * [resolveRecordAction] and the secondary [resolveSecondaryRecordAction]
 * produce byte-identical start-recording actions (no duplicated
 * allocation / UUID-mint / continuation-lookup logic).
 *
 * B2 / ADR-0008 §"Auto-Continuation": we first ask the ContinuationLookup
 * whether the most recent session is a fresh RECORDING_INTERRUPTED row
 * eligible for continuation. The composite (RecordingContinuationLookup)
 * does the DB lookup, segment-list probe, MediaExtractor codec read, and
 * allocateNext — see its KDoc for the eligibility chain. A non-null
 * result has already mutated repository state (next segment is reserved
 * on disk + appended to audio_file_paths). Returning the
 * StartRecordingContinuation action skips the fresh allocate + UUID mint
 * below — both would be wasteful and the fresh allocate would orphan a
 * file the user could see in cleanup logs.
 *
 * Block A4 (recording-stack-completion) — Initial-File-Cutover: the
 * sessionId is minted BEFORE allocate so the AudioFileRepository can hand
 * back `sess_{sid}_seg1.m4a`, unifying the naming convention (initial
 * file + every rolling segment share the `sess_{sid}_seg*` prefix that
 * `segments(sid)` scans for). Without this the initial file was named
 * `rec_{ts}_{uuid8}.m4a` and therefore invisible to the multi-segment
 * muxer at upload time — the "only the latest audio chunk reached the
 * AI" bug observed on-device on 2026-05-22.
 *
 * **IOException side-channel.** `audioFileRepository.allocateFirst()` may
 * fail (mkdirs/storage); the helper fires a toast on `services.toastSink`
 * and returns `null` (R.3 silent-no-op; the reducer never sees the IO
 * failure — Pure-Reducer invariant).
 */
private fun resolveStartRecordingFromIdle(services: ModuleServices): Action? {
    val continuation = services.continuationLookup.lookup()
    if (continuation != null) {
        return Action.RecordingAction.StartRecordingContinuation(
            target = InsertionTarget.INPUT_CONNECTION,
            audioFile = continuation.nextSegmentFile,
            sessionId = continuation.sessionId,
            codecParams = continuation.codecParams,
        )
    }
    val sessionId = newSessionId()
    val file = try {
        services.audioFileRepository.allocateFirst(sessionId)
    } catch (e: java.io.IOException) {
        // B4-VAL F-4: toast via @StringRes overload so the user-visible
        // message goes through Android's i18n machinery (Spec 2 §8.5).
        services.toastSink.show(R.string.dictate_storage_full)
        Log.w(TAG, "audioFileRepository.allocateFirst failed", e)
        return null
    }
    return Action.RecordingAction.StartRecording(
        target = InsertionTarget.INPUT_CONNECTION,
        audioFile = file,
        sessionId = sessionId,
    )
}

/**
 * Secondary record-button click resolver — the mic button offered in the
 * SEND_MODE layouts while a pipeline run processes (ADR-0009).
 *
 * A tap starts a **new** recording that will queue behind the active
 * pipeline run. The single-MediaRecorder gate is the recording-`Idle`
 * check here: a secondary recording is only possible while no recording
 * is in flight (`recording is Idle`), so any non-Idle state yields `null`.
 * Pipeline-live is implied by the slot's placement in the SEND_MODE modes
 * (the slot only renders there); the slot's `visibilityPredicate` also
 * carries the recording-Idle check as belt-and-braces.
 *
 * The arm delegates to the shared [resolveStartRecordingFromIdle] body,
 * so the produced action is identical to what [resolveRecordAction]
 * returns from its Idle arm.
 *
 * @see resolveRecordAction (primary record body — same Idle arm)
 * @see docs/research/2026-07-02 - concurrent-recording-deferred-insertion.md §3.4
 * @see docs/decisions/0009-pipeline-run-queue-serialized-concurrency.md
 */
fun resolveSecondaryRecordAction(state: DictateUiState, services: ModuleServices): Action? =
    if (state.recording is RecordingState.Idle) resolveStartRecordingFromIdle(services) else null

/**
 * Record-button **long-press** resolver (behaviour group G2,
 * render-path-cutover.md §3 / §7 A1).
 *
 * Thin state→Action mapping symmetric with [resolveRecordAction]: it
 * emits [Action.RecordingAction.OnRecordLongPress] whenever a recording
 * session is in flight ([RecordingState.Active] / [RecordingState.Paused])
 * and `null` otherwise. The **2-mode body** (Idle → Settings+file-picker
 * vs Active/Paused → autoSwitch+stop) is resolved in
 * [net.devemperor.dictate.state.RecordingModule]'s reducer from
 * `state.recording` — see [Action.RecordingAction.OnRecordLongPress]
 * KDoc for the full A1 rationale (the Idle Activity-launch is an
 * IME-side concern wired in CR4; this resolver returns `null` for Idle
 * so no pointless action reaches the orchestrator, R.3).
 *
 * **Why not just always emit `OnRecordLongPress` and let the reducer
 * decide?** The R.3 nullable-resolver contract pushes structurally
 * meaningless interactions out *before* dispatch (no
 * `DispatchOutcome.Rejected` log-spam). Idle + Preparing long-press
 * produce no FSM transition, so the resolver short-circuits them here —
 * the reducer's `null` is the second defence layer, not the first.
 *
 * Dormant until CR4 (the new long-press listener is not the live one
 * for RECORD until the legacy `MainButtonsController` drive is removed —
 * RR-1 no-double-wire).
 */
fun resolveRecordLongPressAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    when (state.recording) {
        is RecordingState.Active,
        is RecordingState.Paused -> Action.RecordingAction.OnRecordLongPress
        RecordingState.Idle,
        is RecordingState.Preparing,
        // Interrupted: long-press has no meaningful action — discard
        // goes through the trash button, continue through a normal tap.
        is RecordingState.Interrupted -> null
    }

/**
 * Record-button click resolver while the pipeline is live (SEND_MODE).
 *
 * While the pipeline is `Running` the record button acts as an
 * auto-enter-toggle (the visual "↵" decoration on the button text).
 * In any other pipeline state (including `Preparing`) the click is
 * structurally meaningless and the resolver returns `null`.
 */
fun resolveRecordActionPipeline(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    when (state.pipeline) {
        // Per-run auto-enter toggle — distinct from
        // FeatureToggleAction.ToggleAutoEnter (which would flip the
        // global Pref.AutoEnter). The in-pipeline toggle must NOT
        // mutate the global pref; it only flips the per-run
        // autoEnterActive flag for this one run. See
        // Action.PipelineAction.ToggleRunningAutoEnter.
        //
        // #AE-DEEP2: also dispatch during Preparing — the second
        // SEND-tap typically lands in the 500ms–2s upload window
        // before the runner emits StartPipeline (Preparing → Running).
        // Pre-fix the resolver returned null for Preparing, so taps in
        // that window were silently swallowed.
        is PipelineUiState.Preparing -> Action.PipelineAction.ToggleRunningAutoEnter
        is PipelineUiState.Running -> Action.PipelineAction.ToggleRunningAutoEnter
        else -> null
    }

/**
 * Trash-button click resolver.
 *
 * | Sub-state                                                  | Action                                                       |
 * |-----------------------------------------------------------|--------------------------------------------------------------|
 * | `pipeline is ReprocessStaging`                            | `CancelReprocessStaging(sessionId)`                          |
 * | `Idle + Idle + interrupted-session in pendingSessions`     | `DiscardInterruptedSession(sessionId)` (§4.5.3)             |
 * | `recording is Idle && pipeline is Idle`                   | `null` (visibility predicate hides it)                       |
 * | otherwise (recording active/paused, or pipeline Preparing)| `CancelRecording`                                            |
 *
 * The `DiscardInterruptedSession` branch picks the **first**
 * RECORDING_INTERRUPTED entry from `pendingSessions` (recording-stack-
 * completion §4.5.3). In practice there is at most one such entry
 * because `findLatestUnfinishedRecording` is used for the auto-
 * continuation path (a multi-RECORDING_INTERRUPTED race would already
 * have been collapsed by `PipelineRecovery` to a single active
 * candidate), but the resolver guards via `firstOrNull` defensively.
 */
fun resolveTrashAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? {
    val pipe = state.pipeline
    val rec = state.recording
    return when {
        pipe is PipelineUiState.ReprocessStaging ->
            Action.PipelineAction.CancelReprocessStaging(pipe.sessionId)
        // Recovery-surfaced interrupted recording (2026-05-22): the
        // trash button discards it (delete segments + mark row FAILED)
        // and returns the FSM to Idle.
        rec is RecordingState.Interrupted ->
            Action.RecordingAction.DiscardInterruptedSession(rec.sessionId)
        state.recording is RecordingState.Idle && state.pipeline is PipelineUiState.Idle -> {
            // Idle + Idle: surface DiscardInterruptedSession if a
            // RECORDING_INTERRUPTED row is present; otherwise the
            // button is hidden by the predicate so this branch only
            // fires when the user actually saw the button.
            val interrupted = state.pendingSessions.firstOrNull {
                it.status ==
                    net.devemperor.dictate.database.entity.SessionStatus.RECORDING_INTERRUPTED
            }
            interrupted?.let {
                Action.RecordingAction.DiscardInterruptedSession(it.sessionId)
            }
        }
        else -> Action.RecordingAction.CancelRecording
    }
}

/**
 * Pause-button click resolver.
 *
 * Toggles between `PauseRecording` and `ResumeRecording`; `null` outside
 * the Active/Paused sub-states (the visibility predicate is supposed to
 * hide the button there, but the second layer of defence keeps the
 * orchestrator-dispatch path clean per R.3).
 */
fun resolvePauseAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    when (state.recording) {
        is RecordingState.Paused -> Action.RecordingAction.ResumeRecording
        is RecordingState.Active -> Action.RecordingAction.PauseRecording
        else -> null
    }

/**
 * Resolver for the SendStaging click in `KEYBOARD_REPROCESS_STAGING`.
 *
 * Reads the active `ReprocessStaging.sessionId` from the state and emits
 * [Action.PipelineAction.SendStaging]; `null` when the pipeline isn't in
 * staging (defensive — the visibility predicate excludes this case).
 */
fun resolveSendStagingAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    (state.pipeline as? PipelineUiState.ReprocessStaging)
        ?.let { Action.PipelineAction.SendStaging(it.sessionId) }

/**
 * Resolver for the trash-button click in `KEYBOARD_REPROCESS_STAGING` —
 * cancels the staging session by id.
 */
fun resolveCancelStagingAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? =
    (state.pipeline as? PipelineUiState.ReprocessStaging)
        ?.let { Action.PipelineAction.CancelReprocessStaging(it.sessionId) }

/**
 * OVERLAY_RECORD click resolver — Variante 2a merged RECORD+SEND slot
 * (dictate-widget-integration §6.5, §8.2 Chunk 2.2).
 *
 * The user-requirement (2026-05-21) is that the overlay record-button is
 * "exakt der gleiche Button" as the keyboard `record_btn`. That means
 * one single slot drives all four recording sub-states **and** both
 * live pipeline sub-states:
 *
 * | viewMode | recording   | pipeline             | Action returned                          |
 * |----------|-------------|----------------------|------------------------------------------|
 * | WIDGET   | Idle        | Idle                 | `StartRecording(...)`                    |
 * | WIDGET   | Active      | Idle                 | `StopRecordingAndSend`                   |
 * | WIDGET   | Paused      | Idle                 | `StopRecordingAndSend`                   |
 * | WIDGET   | Preparing   | Idle                 | `null` (recorder warming up)             |
 * | WIDGET   | any         | Preparing / Running  | `ToggleRunningAutoEnter` (auto-enter ↵)  |
 * | HOVER    | any         | any                  | `null` (no InputConnection target)       |
 *
 * **HOVER gate.** User-Requirement §2 verbatim: "Senden darf nicht
 * möglich sein, während gerade kein Tastaturinput verfügbar ist". HOVER
 * is the only ViewMode where the IME-View is hidden and
 * `getCurrentInputConnection()` returns `null`; structurally any
 * SEND-class action would commit text into nothing. The
 * `enabledResolver` already disables the button visually
 * ([resolveOverlayRecordEnabled]); this defensive `null`-return is the
 * second layer per R.3 (a Race-Window click that slips through during a
 * ViewMode transition is also a no-op).
 *
 * **Reuse of the keyboard-surface bodies.** Both branches delegate to
 * the existing keyboard-surface resolvers:
 *
 *  - non-pipeline branch → [resolveRecordAction] (the SAME body —
 *    `audioFileFactory.allocate()`, fresh UUID, IOException → toast).
 *  - pipeline branch → [resolveRecordActionPipeline] (the SAME body —
 *    `ToggleRunningAutoEnter` for Preparing/Running, `null` else).
 *
 * Side-effect parity is therefore guaranteed by composition — no
 * separate IOException-handling, no separate UUID-mint.
 *
 * @see resolveRecordAction (keyboard non-pipeline body)
 * @see resolveRecordActionPipeline (keyboard pipeline body)
 * @see resolveOverlayRecordEnabled (matching enabled-state predicate)
 * @see docs/plans/2026-05-21 - dictate-widget-integration/dictate-widget-integration.md §8.2 Chunk 2.2
 */
fun resolveOverlayRecordAction(state: DictateUiState, services: ModuleServices): Action? {
    // 2026-05-22 — overlay record-btn spec (post-Widget-Pause refactor):
    //   • IME visible: Klick = Send (StopRecordingAndSend) — same as the
    //     keyboard record-btn. The dedicated OVERLAY_PAUSE slot handles
    //     pause/resume.
    //   • IME hidden: Klick = disabled (return null). Senden ohne
    //     InputConnection ist verboten; der Nutzer muss explizit den
    //     Pause-Btn rechts verwenden. The OVERLAY_PAUSE slot stays
    //     enabled in this branch so the user can still pause.
    //   • Idle (no recording): Klick = StartRecording — allowed regardless
    //     of IME visibility (this is the whole point of the widget —
    //     starting recording without unfolding the IME).
    //   • Pipeline preparing/running: auto-enter toggle (unchanged).
    //
    // Supersedes the B3.4 "Send-button morphs into Pause-Toggle while
    // widget is visible" rule — the separate OVERLAY_PAUSE slot now
    // owns the pause UI, and the record-btn's role is the same on both
    // surfaces (start when Idle, send when IME-visible-and-Active,
    // disabled when IME-hidden-and-Active).

    // While the pipeline is live, the button is a per-run auto-enter
    // toggle (symmetric to the keyboard SEND_MODE record button).
    if (state.pipeline is PipelineUiState.Preparing ||
        state.pipeline is PipelineUiState.Running
    ) {
        return resolveRecordActionPipeline(state, services)
    }

    // Active/Paused: Send only when the IME is visible (transcript needs
    // an InputConnection target). When the IME is hidden, return null —
    // the OVERLAY_PAUSE slot is the pause surface in that mode.
    if (state.recording is RecordingState.Active ||
        state.recording is RecordingState.Paused
    ) {
        if (!state.imeViewVisible) {
            return null
        }
        // IME visible → same Stop & Send semantics as the keyboard
        // surface (resolveRecordAction handles the Active|Paused →
        // StopRecordingAndSend dispatch).
        return resolveRecordAction(state, services)
    }

    // Idle / Preparing: same Start semantics as the keyboard surface —
    // delegate so IOException handling + UUID minting + the B2
    // ContinuationLookup branch stay byte-identical (R.3 /
    // single-source-of-side-effect).
    return resolveRecordAction(state, services)
}

/**
 * `enabledResolver` for the OVERLAY_RECORD slot (Variante 2a, §8.2
 * Chunk 2.3).
 *
 * Symmetric to [resolveOverlayRecordAction]: the button is enabled iff
 * the resolver would return a non-null action. Centralised so the
 * `enabledResolver` / `alphaResolver` / `actionResolver` slot fields
 * cannot drift apart — and so HOVER-disabled is **one** branch in
 * **one** function (the user requirement).
 *
 * | viewMode | recording               | pipeline             | enabled |
 * |----------|-------------------------|----------------------|---------|
 * | HOVER    | any                     | any                  | `false` |
 * | KEYBOARD | any                     | any                  | `false` |
 * | WIDGET   | Preparing               | Idle                 | `false` |
 * | WIDGET   | any other recording     | Preparing / Running  | `true`  |
 * | WIDGET   | Idle / Active / Paused  | Idle                 | `true`  |
 *
 * Note that the *visibility* predicate stays simple (`true`) — the
 * button is always present in the overlay layout; the `enabled` /
 * `alpha` axes carry the WIDGET vs HOVER distinction. This matches the
 * keyboard surface's `record_btn`, which is also always-visible.
 */
fun resolveOverlayRecordEnabled(state: DictateUiState): Boolean {
    // 2026-05-22 — overlay record-btn enabled-table (matches
    // resolveOverlayRecordAction, see its KDoc):
    //
    // | recording               | pipeline             | imeVisible | enabled |
    // |-------------------------|----------------------|------------|---------|
    // | any                     | Preparing / Running  | any        | true    |
    // | Active / Paused         | Idle                 | true       | true    |
    // | Active / Paused         | Idle                 | false      | false   |
    // | Idle                    | Idle                 | any        | true    |
    // | Preparing               | any                  | any        | false   |
    //
    // The IME-hidden + Active/Paused branch is the new "Senden ohne
    // InputConnection ist verboten" rule — the dedicated OVERLAY_PAUSE
    // slot remains enabled there so the user can still pause.
    if (state.pipeline is PipelineUiState.Preparing ||
        state.pipeline is PipelineUiState.Running
    ) {
        return true
    }
    if (state.recording is RecordingState.Preparing) {
        return false
    }
    if ((state.recording is RecordingState.Active ||
            state.recording is RecordingState.Paused) &&
        !state.imeViewVisible
    ) {
        return false
    }
    return true
}

/**
 * OVERLAY_CLOSE click resolver — differential behaviour per ViewMode
 * (Spec 3 §6 + §3.1).
 *
 * | viewMode  | Action emitted                                        |
 * |-----------|-------------------------------------------------------|
 * | WIDGET    | `Action.ViewModeAction.ToggleViewModeWidget`          |
 * | HOVER     | `Action.ViewModeAction.CloseOverlay`                  |
 * | KEYBOARD  | `null` (button is hidden by visibility predicate)     |
 *
 * The HOVER → KEYBOARD transition fans a cancel-cascade through
 * [net.devemperor.dictate.state.OverlayModule.onCrossModuleStateChange];
 * the resolver here only emits the **trigger** action.
 */
fun resolveOverlayCloseAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action? {
    // 2026-05-23 sticky-widget refactor — `state.widget` is the sole
    // source of truth for the overlay's visibility. Pre-refactor this
    // resolver discriminated on `state.viewMode` (WIDGET vs HOVER vs
    // KEYBOARD) and routed the HOVER case to `CloseOverlay`, which
    // ran a cancel-recording cascade. That coupled overlay-close with
    // recording-cancel and depended on the (sometimes stale) legacy
    // `viewMode` axis: the X-click could land in the KEYBOARD arm
    // (resolver returns null → click is a no-op) when the widget had
    // been auto-shown via PIPELINE origin and `viewMode` had since
    // moved on. Post-refactor the X button has one job: close the
    // widget. W2's `WIDGET_BUTTON` source still pauses the recording
    // if Active (user-intent: "I'm done dictating"), but recording-
    // cancel and viewMode bookkeeping flow through the existing
    // observers — no special-case branch here.
    return if (state.widget is WidgetState.Visible) {
        Action.WidgetAction.CloseWidget(WidgetCloseSource.WIDGET_BUTTON)
    } else {
        null
    }
}

/**
 * WIDGET_TOGGLE click resolver — permission-aware (Spec 3 §8 /
 * ADR-0005 §"Required mechanics" #3, B5 repair-wave F-2).
 *
 * | `state.overlay.hasPermission` | Action emitted                              |
 * |-------------------------------|---------------------------------------------|
 * | `true`                        | `Action.ViewModeAction.ToggleViewModeWidget`|
 * | `false`                       | `Action.OverlayAction.ShowOverlayOnboarding`|
 *
 * `hasPermission` is the **mirrored axis** kept fresh by
 * `OverlayPermissionObserver` (the IME calls `refresh()` in
 * `onStartInputView`, B5 F-3) — this resolver reads *state*, never
 * `Settings.canDrawOverlays`, so it stays R.2-pure.
 *
 * Defence-in-depth: even when permission is present and this resolver
 * emits `ToggleViewModeWidget`, `ViewModeModule.reduce`'s
 * `!hasPermission ⇒ null` guard still refuses a widget switch if the
 * axis is stale (ADR-0005 §8 "the reducer still refuses"). The two
 * checks are intentionally redundant.
 */
fun resolveWidgetToggleAction(
    state: DictateUiState,
    @Suppress("UNUSED_PARAMETER") services: ModuleServices,
): Action =
    if (state.overlay.hasPermission) {
        Action.ViewModeAction.ToggleViewModeWidget
    } else {
        Action.OverlayAction.ShowOverlayOnboarding
    }
