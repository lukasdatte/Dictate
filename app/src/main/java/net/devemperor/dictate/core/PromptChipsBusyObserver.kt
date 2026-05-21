package net.devemperor.dictate.core

import kotlinx.coroutines.CoroutineDispatcher
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
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.isActiveOrPaused

/**
 * Drives the **prompt-chips disable predicate** reactively from the
 * orchestrator-authoritative `state.recording` AND `state.pipeline`
 * axes (dictate-pipeline-render-and-state-unification §5.7 / AC-E).
 *
 * # Why this exists
 *
 * The legacy IME-side `updatePromptButtonsEnabledState()` read
 * `recordingStateController.getState()` and recomputed the
 * `disableNonSelectionPrompts` flag from imperative callbacks. After
 * the render-cutover-vol2 cutover the orchestrator's
 * [RecordingHardwareAdapter] owns the MediaRecorder directly, the
 * legacy `RecordingStateController` is no longer started on the new
 * path, and its `state` stays permanently `Idle` — so the predicate
 * was always `false` and every chip was tappable during Recording /
 * Pipeline (the B-E regression).
 *
 * This observer fixes the seam by:
 *
 *  1. Mapping `DictateUiState` to a single `busy: Boolean` derived
 *     from BOTH the recording-axis (`Active | Paused | Preparing`)
 *     AND the pipeline-axis (`Preparing | Running`). Either axis
 *     being busy disables the non-selection chips.
 *  2. `distinctUntilChanged` on the derived boolean so the IME only
 *     re-renders when the actual disable-bit flips (not on every
 *     `elapsedMs` tick).
 *  3. Forwarding the value to a [Listener] callback — the IME's
 *     `updatePromptButtonsEnabledState()` then writes the bit into
 *     the [PromptsKeyboardAdapter] via `setDisableNonSelectionPrompts`.
 *
 * Sibling Java-bridge pattern to [EditBarAudioFocusObserver] and
 * [PipelineUiStateObserver]: a thin observer with a single derived
 * axis, JVM-testable because no Android-View dependency leaks.
 *
 * # Lifecycle
 *
 * [start] launches a collector on a private `Dispatchers.Main` scope;
 * [stop] cancels it. The IME starts the observer in
 * `onCreateInputView` (after the prompts adapter exists) and stops it
 * in `onDestroyInputView`. Idempotent — a second [start] while
 * already running is a no-op; [stop] on an already-stopped observer
 * is safe.
 *
 * @see net.devemperor.dictate.core.EditBarAudioFocusObserver — sibling
 *   single-axis observer pattern.
 * @see docs/plans/2026-05-21 - dictate-pipeline-render-and-state-unification/dictate-pipeline-render-and-state-unification.md §5.7
 */
class PromptChipsBusyObserver @JvmOverloads constructor(
    private val state: StateFlow<DictateUiState>,
    private val onChanged: Listener,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {

    private var scope: CoroutineScope? = null

    /**
     * Begin observing. Idempotent — a second call while already running
     * keeps the existing collector.
     */
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope = s
        s.launch {
            state
                .map { isBusy(it) }
                .distinctUntilChanged()
                .collect { busy -> onChanged.onPromptChipsBusyChanged(busy) }
        }
    }

    /** Stop observing. Idempotent. */
    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** Functional-interface-compatible listener so Java lambdas work. */
    fun interface Listener {
        fun onPromptChipsBusyChanged(busy: Boolean)
    }

    private companion object {
        /**
         * The derived "busy" predicate — `true` when any pipeline phase
         * or recording phase that should disable the non-selection chips
         * is in flight.
         *
         * Pulled out as a private helper so the test suite can pin the
         * exact axis combination (AC-E + AC-P-1).
         */
        fun isBusy(s: DictateUiState): Boolean {
            val recordingBusy = s.recording.isActiveOrPaused ||
                s.recording is RecordingState.Preparing
            val pipelineBusy = s.pipeline is PipelineUiState.Preparing ||
                s.pipeline is PipelineUiState.Running
            return recordingBusy || pipelineBusy
        }
    }
}
