package net.devemperor.dictate.state.render.overlay

import net.devemperor.dictate.state.Action

/**
 * Keeps `state.overlay.hasPermission` in sync with the live
 * system-permission status — the **only** live source of the boolean
 * after Issue 3.1.3 (Spec 3 §5.0).
 *
 * # Why this exists
 *
 * The Reducer-Purity invariant (R.2, ADR-0001 §3) forbids
 * [android.provider.Settings.canDrawOverlays] reads from
 * `OverlayModule.reduce`. Without an external syncer the
 * `state.overlay.hasPermission` axis would diverge from reality every
 * time the user toggles the permission in System Settings. This
 * observer's `init`/`refresh` calls are the **single live read** of
 * the system permission — every other consumer (reducer, render
 * backend) reads the mirrored axis.
 *
 * # Lifecycle (Spec 3 §5.0)
 *
 *  - **`init`** — called once from
 *    [net.devemperor.dictate.core.DictatePipelineService.onCreate] so
 *    the axis is correct from the first state-emit (the boot default
 *    is `false` per `DictateUiState.initial()`).
 *  - **`refresh`** — called from the IME's `onCreateInputView` /
 *    `onStartInputView` so the axis catches a user that just came
 *    back from the System-Settings deep link. This is the
 *    lifecycle-trigger model — explicitly **not polling** because
 *    Android lacks a system broadcast for overlay-permission changes
 *    (Spec 3 §5.0 "Warum kein Live-Polling?").
 *
 * # Idempotency
 *
 * Both `init` and `refresh` always dispatch — the [net.devemperor.dictate.state.OverlayModule]
 * reducer arm for [Action.OverlayAction.OnOverlayPermissionChanged]
 * filters by equality (`if (action.granted != state.hasPermission)`),
 * so a no-op dispatch produces `DispatchOutcome.Rejected("reducer-null")`
 * and never reaches cross-module cascade. That's the safe default.
 *
 * # DIP — dispatch sink is a function
 *
 * The observer takes a `(Action) -> Unit` sink instead of a
 * `DictateOrchestrator` reference so JVM unit tests can wire a
 * recording lambda without instantiating the orchestrator. Production
 * wires `orchestrator::dispatch`.
 *
 * @property gate live system-permission check — the production wire is
 *   [DefaultOverlayPermissionGate] (Settings.canDrawOverlays-backed);
 *   tests substitute a fake.
 * @property dispatch action sink — typically
 *   `orchestrator::dispatch` in production, a recording lambda in
 *   tests.
 *
 * @see DefaultOverlayPermissionGate
 * @see net.devemperor.dictate.state.OverlayModule
 * @see net.devemperor.dictate.state.Action.OverlayAction.OnOverlayPermissionChanged
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/3-floating-overlay/3-floating-overlay.reviewed.md §5.0
 */
class OverlayPermissionObserver(
    private val gate: OverlayPermissionGate,
    private val dispatch: (Action) -> Unit,
) {

    /**
     * One-shot dispatch of the current permission status. Called from
     * `DictatePipelineService.onCreate` so the state axis is correct
     * before any subscriber collects the first emission.
     */
    fun init() {
        dispatchCurrent()
    }

    /**
     * Re-read the system permission and dispatch the result. Called
     * from the IME's `onCreateInputView` / `onStartInputView`
     * lifecycle hooks so the user-returns-from-Settings path catches
     * the new value.
     *
     * The dispatch is unconditional; the reducer dedups via
     * equality-check (see class KDoc — "Idempotency").
     */
    fun refresh() {
        dispatchCurrent()
    }

    private fun dispatchCurrent() {
        val granted = gate.hasOverlayPermission()
        dispatch(
            Action.OverlayAction.OnOverlayPermissionChanged(granted = granted),
        )
    }
}
