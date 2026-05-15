package net.devemperor.dictate.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.devemperor.dictate.state.DictateUiState

/**
 * Java-friendly bridge that observes the `state.overlay.onboardingPending`
 * axis of the pipeline [StateFlow] and drives the in-IME
 * overlay-permission info-bar (Spec 3 §5.3, B5 F-2).
 *
 * # Why a dedicated bridge (not an IME-side `collect`)
 *
 * [DictateInputMethodService] is a Java `InputMethodService` — it has no
 * `lifecycleScope` (it is not a `LifecycleOwner`, unlike the Activities
 * served by [ActiveJobRegistryObserver]) and the production render path
 * is **service-side** (`DictatePipelineService` owns the single
 * `orchestrator.state.collect` that fans into the
 * `KeyboardLayoutManager`). The info-bar, however, is an IME-owned view
 * surface co-located with the existing `InfoBarController` (the IME
 * holds the inflated root + the `pipelineBinder`). This bridge gives the
 * Java IME a minimal, lifecycle-scoped subscription to the *single*
 * sub-axis it needs (`onboardingPending`) without widening
 * `ImeViewBackend`'s button-map-only contract (a documented deviation
 * from the Spec 3 §5.3 sketch — see ADR-0005 Decision-History
 * 2026-05-15).
 *
 * # Lifecycle
 *
 * [start] launches a collector on a private [Dispatchers.Main]
 * [CoroutineScope]; [stop] cancels it. The IME starts the observer once
 * it has both the inflated info-bar views and a bound `pipelineBinder`
 * (`onCreateInputView` / `onServiceConnected`), and stops it in
 * `onDestroyInputView` / `onDestroy`. The flow is
 * `distinctUntilChanged()` on the boolean so the callback fires only on
 * an actual pending↔cleared transition.
 *
 * @see net.devemperor.dictate.core.ActiveJobRegistryObserver — sibling
 *   Java-bridge pattern (Activity-scoped variant).
 */
class OverlayOnboardingObserver(
    private val state: StateFlow<DictateUiState>,
    private val onChanged: Listener,
) {

    private var scope: CoroutineScope? = null

    /**
     * Begin observing. Idempotent — a second call while already running
     * is a no-op (the existing collector keeps running).
     */
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            state
                .map { it.overlay.onboardingPending }
                .distinctUntilChanged()
                .collect { pending -> onChanged.onOnboardingPendingChanged(pending) }
        }
    }

    /**
     * Stop observing and release the collector scope. Idempotent —
     * calling on an already-stopped observer is safe.
     */
    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** Functional-interface-compatible listener so Java lambdas work. */
    fun interface Listener {
        fun onOnboardingPendingChanged(pending: Boolean)
    }
}
