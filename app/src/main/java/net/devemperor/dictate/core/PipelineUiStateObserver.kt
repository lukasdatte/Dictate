package net.devemperor.dictate.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState

/**
 * Java-friendly bridge that observes `state.pipeline` and drives the
 * IME-side side-effects that the legacy
 * `PipelineStepRowRenderer.PipelineUiCallback` previously delivered (Phase
 * 5.B of `2026-05-21 - dictate-render-cutover-completion-vol2`).
 *
 * # Why a dedicated bridge
 *
 * After Phase 5.B the `PipelineStepRowRenderer` is a reactive consumer of
 * `DictateUiState` driven by `ImeViewBackend.render` — it no longer carries
 * an imperative callback registry. The remaining IME-side responsibilities
 * that were piggybacking on that callback (QWERTZ rec-button updates,
 * prompts-queue order sync, language-chip refresh, chip-enabled toggle)
 * still need to fire on `state.pipeline` transitions; this observer is the
 * single Service-side subscription that delivers them.
 *
 * Mirrors [InfoBarRenderer] structurally: a private
 * [Dispatchers.Main] [CoroutineScope] that collects a
 * `distinctUntilChanged()` slice of the orchestrator [StateFlow] and
 * forwards transitions to a Java-friendly [Listener].
 *
 * @see InfoBarRenderer — sibling Java-bridge pattern.
 * @see net.devemperor.dictate.state.render.PipelineStepRowRenderer — the
 *   reactive renderer that supersedes the legacy callback mechanic.
 */
class PipelineUiStateObserver(
    private val state: StateFlow<DictateUiState>,
    private val onChanged: Listener,
) {

    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            state
                .map { it.pipeline }
                .distinctUntilChanged()
                .collect { pipeline -> onChanged.onPipelineUiStateChanged(pipeline) }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** Functional-interface-compatible listener so Java lambdas work. */
    fun interface Listener {
        fun onPipelineUiStateChanged(pipeline: PipelineUiState)
    }
}
