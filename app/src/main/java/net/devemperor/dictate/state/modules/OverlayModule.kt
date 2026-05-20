// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import java.io.File
import kotlin.reflect.KClass

/**
 * Owns the [OverlayState] axis — position floats, permission flag,
 * `userPrefersWidget` (transient widget choice), `onboardingPending`
 * flag, and the `suppressAutoOverlayUntilNextSession` bit (the
 * "user-closed-the-HOVER-overlay" memory).
 *
 * Side-effects route through [ModuleServices.sharedPrefs] (position +
 * onboarding persistence). The OverlayBackend's WindowManager mutation
 * is reactive — the backend collects [DictateUiState.viewMode] /
 * [DictateUiState.overlay] and renders accordingly; OverlayModule does
 * **not** call WindowManager directly (SRP: render is the
 * KeyboardLayoutManager's job).
 *
 * **Cross-module cascades (Coupling-Matrix §15.1.x rows "Overlay" +
 * "ViewMode" → Overlay observer):**
 *
 * - On `KEYBOARD → WIDGET` (T1): emit
 *   [Action.OverlayAction.SetUserPrefersWidget]`(true)` so the
 *   widget-pref is sticky until the user explicitly closes (Spec 3
 *   §7.3 T1).
 * - On `WIDGET → KEYBOARD` (T2): emit
 *   [Action.OverlayAction.SetUserPrefersWidget]`(false)` so the next
 *   IME-hide event correctly falls to HOVER (Spec 3 §7.3 T2).
 * - On `HOVER → KEYBOARD`: emit
 *   [Action.OverlayAction.SuppressAutoOverlayUntilNextSession] +
 *   (if recording active) [Action.RecordingAction.CancelRecording] /
 *   (else if pipeline running) [Action.PipelineAction.CancelPipeline]
 *   (Spec 3 §6.2 + §4.8 closeOverlay-cascade).
 * - On `prev.viewMode != WIDGET && next.viewMode == WIDGET &&
 *   onboardingPending` (user granted the permission and reached
 *   WIDGET, Spec 3 §5.4): emit
 *   [Action.OverlayAction.MarkOverlayOnboardingShown] so the stale
 *   explainer info-bar is cleared.
 * - On `prev.hasPermission == true && next.hasPermission == false`
 *   (permission-loss at runtime, Spec 3 §3.1.3 + §9): emit
 *   [Action.ViewModeAction.SetViewMode]`(KEYBOARD)` so the overlay
 *   immediately falls back to in-IME rendering, **plus**
 *   [Action.OverlayAction.RequestOverlayPermissionNotification] (F-4)
 *   so the FGS notification surfaces the revoke reason (the user may
 *   be in another app — no in-IME info-bar reachable, Spec 3 §9 O7).
 *
 * **No [reduceFailure] override** (Phase-C C-5 design decision): every
 * Overlay effect is either an idempotent pref-write or a UI-trigger.
 * `PersistOverlayPosition` failure means the persistent mirror lags
 * one tick behind — the next `PrefMirror.sync` cycle catches up.
 * `DeleteAudioFile` failure is harmless. `OpenOverlayPermissionSettings`
 * failure would be visible to the user (settings page doesn't open),
 * and the user would manually try again. See Spec 3 §4.8
 * "EffectFailure-Konvention".
 *
 * @see net.devemperor.dictate.state.OverlayState
 * @see net.devemperor.dictate.state.Action.OverlayAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §4.8
 */
object OverlayModule : DictateModule<OverlayState, Action.OverlayAction, OverlayModule.Effect> {

    override val id: ModuleId = ModuleId.Overlay
    override val actionClass: KClass<Action.OverlayAction> = Action.OverlayAction::class

    override fun read(global: DictateUiState): OverlayState = global.overlay
    override fun write(global: DictateUiState, sub: OverlayState): DictateUiState =
        global.copy(overlay = sub)

    override fun initialState(): OverlayState = OverlayState()

    sealed interface Effect : SideEffect {
        data class PersistOverlayPosition(
            val portrait: Boolean,
            val x: Float,
            val y: Float,
        ) : Effect

        data object MarkOnboardingShown : Effect
        data object MarkOnboardingPermanentlyDismissed : Effect

        /** Best-effort delete (Issue 3.1.7 audio-file cleanup post-CloseOverlay). */
        data class DeleteAudioFile(val file: File) : Effect

        data object OpenOverlayPermissionSettings : Effect

        /**
         * Permission-free notification fallback (Spec 3 §4.8 + §9 —
         * deliberate O7 architecture decision). Emitted by the
         * runtime-permission-loss cascade alongside
         * [Action.ViewModeAction.SetViewMode]`(KEYBOARD)`: when the
         * user revokes `SYSTEM_ALERT_WINDOW` while WIDGET/HOVER is
         * live, the overlay silently falls back to in-IME rendering —
         * the FGS notification is the only surface that can tell the
         * user *why* (they may be in another app, no info-bar
         * reachable). `runEffect` routes this to
         * [ModuleServices.notificationCoordinator].
         */
        data object NotifyOverlayPermissionRequired : Effect
    }

    override fun reduce(
        state: OverlayState,
        action: Action.OverlayAction,
        ctx: ReducerContext,
    ): TransitionResult<OverlayState, Effect>? = when (action) {

        is Action.OverlayAction.UpdateOverlayPosition -> {
            val nextSub = if (action.portrait) {
                state.copy(positionPortraitX = action.x, positionPortraitY = action.y)
            } else {
                state.copy(positionLandscapeX = action.x, positionLandscapeY = action.y)
            }
            TransitionResult(
                nextState = nextSub,
                sideEffects = listOf(
                    Effect.PersistOverlayPosition(action.portrait, action.x, action.y),
                ),
            )
        }

        Action.OverlayAction.ShowOverlayOnboarding -> TransitionResult(
            // Spec 3 §5.4 — surface the in-IME info-bar. No effect: the
            // explainer bar is rendered off `onboardingPending` by the
            // IME service; the Settings-launch is a *separate* step
            // (the info-bar's Grant button dispatches
            // RequestOverlayPermission). Idempotent — re-dispatch on an
            // already-pending state is suppressed by the StateFlow
            // distinct contract.
            nextState = state.copy(onboardingPending = true),
            sideEffects = emptyList(),
        )

        Action.OverlayAction.MarkOverlayOnboardingShown -> TransitionResult(
            nextState = state.copy(onboardingPending = false),
            sideEffects = listOf(Effect.MarkOnboardingShown),
        )

        Action.OverlayAction.DismissOverlayOnboarding -> TransitionResult(
            nextState = state.copy(onboardingPending = false),
            sideEffects = listOf(Effect.MarkOnboardingPermanentlyDismissed),
        )

        Action.OverlayAction.SuppressAutoOverlayUntilNextSession -> TransitionResult(
            // Idempotent — even if already true, still emit a same-state
            // TransitionResult. StateFlow distinct-Vertrag (Phase-B S-9)
            // suppresses the subscriber re-emit for unchanged data-class
            // values, so this is cheap.
            nextState = state.copy(suppressAutoOverlayUntilNextSession = true),
            sideEffects = emptyList(),
        )

        Action.OverlayAction.ResetSuppressBit -> TransitionResult(
            // Idempotent reset — see Spec 3 §4.8.
            nextState = state.copy(suppressAutoOverlayUntilNextSession = false),
            sideEffects = emptyList(),
        )

        is Action.OverlayAction.SetUserPrefersWidget ->
            if (action.prefers != state.userPrefersWidget) {
                TransitionResult(
                    nextState = state.copy(userPrefersWidget = action.prefers),
                    sideEffects = emptyList(),
                )
            } else null

        is Action.OverlayAction.OnOverlayPermissionChanged ->
            if (action.granted != state.hasPermission) {
                TransitionResult(
                    nextState = state.copy(hasPermission = action.granted),
                    sideEffects = emptyList(),
                )
            } else null

        Action.OverlayAction.RequestOverlayPermission -> TransitionResult(
            // Post-cutover hotfix #HINT — clear `onboardingPending` on
            // "Grant" so the explainer bar disappears even if the user
            // never returns via the WIDGET-toggle path (the only other
            // self-clearing seam, see §3 + the WIDGET→HOVER transition
            // observer below). Without this clear, the explainer
            // stayed VISIBLE permanently and the IME's inline
            // `setVisibility(GONE)` hack at the click site was the
            // only thing hiding it — a true single-source-of-truth
            // violation. The hack is removed in the same wave.
            // Distinct from DismissOverlayOnboarding: that path also
            // writes a persistent "Later" pref; Grant doesn't.
            nextState = state.copy(onboardingPending = false),
            sideEffects = listOf(Effect.OpenOverlayPermissionSettings),
        )

        Action.OverlayAction.RequestOverlayPermissionNotification -> TransitionResult(
            // F-4 — no state change; the FGS notification is a pure
            // side-effect surface (Spec 3 §9, O7).
            nextState = state,
            sideEffects = listOf(Effect.NotifyOverlayPermissionRequired),
        )
    }

    override fun runEffect(effect: Effect, services: ModuleServices): Unit = when (effect) {
        is Effect.PersistOverlayPosition -> {
            // SharedPreferences are the canonical persistence mirror. The
            // C7 PrefMirror watches for SP changes and pushes them back
            // into the store, so this Effect is a belt-and-suspenders
            // write that survives a service restart before PrefMirror's
            // first sync.
            //
            // F-4 (2026-05-15) — typed [Pref] entries
            // (`Pref.OverlayPositionPortraitX/Y`, landscape) replace the
            // earlier raw-string accesses per the project convention
            // (`CLAUDE.md`: "Preferences are always accessed through
            // `DictatePrefs.kt` sealed class").
            val editor = services.sharedPrefs.edit()
            if (effect.portrait) {
                editor.put(Pref.OverlayPositionPortraitX, effect.x)
                editor.put(Pref.OverlayPositionPortraitY, effect.y)
            } else {
                editor.put(Pref.OverlayPositionLandscapeX, effect.x)
                editor.put(Pref.OverlayPositionLandscapeY, effect.y)
            }
            editor.apply()
        }
        Effect.MarkOnboardingShown -> {
            // F-4 — typed Pref entry.
            services.sharedPrefs.edit()
                .put(Pref.OverlayOnboardingShown, true)
                .apply()
        }
        Effect.MarkOnboardingPermanentlyDismissed -> {
            // F-4 — typed Pref entry.
            services.sharedPrefs.edit()
                .put(Pref.OverlayOnboardingDismissed, true)
                .apply()
        }
        is Effect.DeleteAudioFile -> {
            effect.file.delete()
            Unit
        }
        Effect.OpenOverlayPermissionSettings -> {
            // Structural placeholder — NOT a Phase-1 TODO. The Settings
            // launch is owned by the IME-side info-bar Grant handler
            // (Spec 3 §5.2/§5.3): `ModuleServices` carries no Android
            // `Context`/activity-launcher, so a reducer-side
            // `startActivity` is impossible without a new subsystem.
            // The IME *is* a Context and launches
            // `ACTION_MANAGE_OVERLAY_PERMISSION` with
            // `FLAG_ACTIVITY_NEW_TASK` directly from the info-bar click
            // handler. This Effect is kept (still emitted by
            // RequestOverlayPermission) as the seam for a future
            // activity-launcher subsystem; the *effective* launch is
            // the IME-side handler. See ADR-0005 Decision-History
            // 2026-05-15 (B5 repair-wave, F-1/F-2/F-3).
            Unit
        }
        Effect.NotifyOverlayPermissionRequired -> {
            // Spec 3 §9 permission-free fallback. The user revoked
            // SYSTEM_ALERT_WINDOW at runtime; the overlay collapsed to
            // in-IME rendering (the cascade also emitted
            // SetViewMode(KEYBOARD)). Surface the reason via the FGS
            // notification — the only channel reachable when the user
            // is in another app and no in-IME info-bar can render.
            services.notificationCoordinator.show(
                NotificationStatus.OverlayPermissionRequired,
            )
        }
    }

    /**
     * Cross-module observer per Spec 3 §4.8 + §7.3 T1 / T2 + §6.2:
     *
     *  - KEYBOARD → WIDGET ⇒ SetUserPrefersWidget(true)
     *  - WIDGET → KEYBOARD ⇒ SetUserPrefersWidget(false)
     *  - (prev≠WIDGET) → WIDGET while onboardingPending ⇒
     *    MarkOverlayOnboardingShown (Spec 3 §5.4 auto-cleanup)
     *  - HOVER → KEYBOARD ⇒ SuppressAutoOverlay + Cancel cascade
     *  - permission-loss ⇒ SetViewMode(KEYBOARD) +
     *    RequestOverlayPermissionNotification (Spec 3 §9 O7 fallback)
     *
     * **C-3-Disambiguation (HOVER→KEYBOARD cancel cascade, F-7
     * 2026-05-15):** both Recording AND Pipeline are cancelled
     * additively if both are in-flight (rare: HOVER closed during the
     * brief Send-cascade window — Recording stopping while Pipeline
     * already preparing). The orchestrator dispatches the list
     * serially at `depth+1` with re-snapshotting, so each cancellation
     * sees the previous one's effect. Spec 3 C-3 priority
     * "Recording > Pipeline" is preserved by **list order**
     * (Recording first), so a single-in-flight case still emits only
     * one cancel — but the both-in-flight case no longer leaves the
     * pipeline running and producing a transcript the user opted out
     * of (earlier `if/else` priority skipped the pipeline cancel).
     */
    override fun onCrossModuleStateChange(
        prev: DictateUiState,
        next: DictateUiState,
    ): List<Action> {
        val cascade = mutableListOf<Action>()

        // ─── T1: KEYBOARD → WIDGET ──────────────────────────────────────
        if (prev.viewMode == ViewMode.KEYBOARD && next.viewMode == ViewMode.WIDGET) {
            cascade += Action.OverlayAction.SetUserPrefersWidget(prefers = true)
        }

        // ─── Onboarding auto-cleanup (Spec 3 §5.4) ──────────────────────
        // Once the user successfully reaches WIDGET while the explainer
        // bar is still pending (they granted the permission and toggled
        // again), clear `onboardingPending` so a stale info-bar does not
        // linger. `prev != WIDGET` (Spec 3 §5.4 form) also covers a
        // hypothetical HOVER→WIDGET-with-pending; onboardingPending can
        // only be set in KEYBOARD so either predicate is correct.
        if (prev.viewMode != ViewMode.WIDGET &&
            next.viewMode == ViewMode.WIDGET &&
            next.overlay.onboardingPending
        ) {
            cascade += Action.OverlayAction.MarkOverlayOnboardingShown
        }

        // ─── T2: WIDGET → KEYBOARD ──────────────────────────────────────
        if (prev.viewMode == ViewMode.WIDGET && next.viewMode == ViewMode.KEYBOARD) {
            cascade += Action.OverlayAction.SetUserPrefersWidget(prefers = false)
        }

        // ─── HOVER → KEYBOARD (CloseOverlay-cascade, Spec 3 §6.2 + §4.8) ─
        if (prev.viewMode == ViewMode.HOVER && next.viewMode == ViewMode.KEYBOARD) {
            cascade += Action.OverlayAction.SuppressAutoOverlayUntilNextSession
            // F-7 — additive list (was `when { … }` priority-chain).
            // Both Recording and Pipeline can be cancelled in the same
            // pass when both are in-flight. Recording-first is the C-3
            // priority preserved by list order.
            if (next.recording.isActiveOrPaused || next.recording is RecordingState.Preparing) {
                cascade += Action.RecordingAction.CancelRecording
            }
            if (next.pipeline !is PipelineUiState.Idle) {
                cascade += Action.PipelineAction.CancelPipeline(sessionId = null)
            }
        }

        // ─── Permission revoked at runtime (Issue 3.1.3 / Spec 3 §9) ────
        if (prev.overlay.hasPermission &&
            !next.overlay.hasPermission &&
            next.viewMode != ViewMode.KEYBOARD
        ) {
            cascade += Action.ViewModeAction.SetViewMode(ViewMode.KEYBOARD)
            // F-4 — permission-free notification fallback (Spec 3 §4.8
            // + §9, O7). The overlay just collapsed to in-IME render;
            // the user may be in another app where no info-bar can
            // appear, so the FGS notification is the only "why" surface.
            cascade += Action.OverlayAction.RequestOverlayPermissionNotification
        }

        return cascade
    }
}
