package net.devemperor.dictate.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.devemperor.dictate.state.DictateUiState

/**
 * Drives the `edit_numbers_btn` rotation reactively from
 * `state.layout.smallMode` (2026-05-22 — recording-stack-completion
 * follow-up to the SmallMode auto-exit fix).
 *
 * # Why this exists
 *
 * Pre-fix, `editNumbersButton.rotation` was only re-painted from two
 * imperative call sites:
 *
 *  1. `onStartInputView` (initial set, `animate=false`).
 *  2. `onSmallModeToggled` (the edit-bar click, `animate=true`).
 *
 * Once `Action.LayoutAction.SetContentArea` started auto-exiting
 * small-mode (e.g. user taps Emoji while small-mode is on — the reducer
 * atomically drops small-mode + sets the area), the state-driven
 * `state.layout.smallMode` transition no longer routed through
 * `onSmallModeToggled`. The view stayed rotated to 180° while the
 * authoritative state had flipped back to `smallMode = false` — the
 * familiar "imperative paint drifts away from the SoT" bug.
 *
 * Mirror of [EditBarAudioFocusObserver] — single-axis subscription on
 * a sub-state field with `distinctUntilChanged`, forwarding the new
 * value to a JVM-test-friendly [Listener] callback. The IME wires the
 * listener to call `editNumbersAnimator.animateSmallModeToggle(true)`
 * so the rotation animates whenever the state changes — regardless of
 * who triggered it (user click / auto-exit / external Pref-write
 * through PipelinePrefMirror).
 *
 * # First-emit handling
 *
 * `StateFlow.collect` synchronously emits the current value on
 * subscribe. The initial small-mode paint is owned by
 * `onStartInputView`'s `animateSmallModeToggle(false)`. If we forwarded
 * the first emit, the observer would animate-tween from the static
 * initial 0° (or 180°) to the same value — visually a no-op but
 * triggering the 200 ms animator. To avoid that latency on the first
 * IME-bind we `.drop(1)` so the observer only reacts to *changes*. The
 * `distinctUntilChanged` upstream collapses no-op re-emissions; the
 * `drop(1)` collapses the initial emit.
 *
 * # Lifecycle
 *
 * [start] launches a collector on a private `Dispatchers.Main` scope;
 * [stop] cancels it. Idempotent — a second [start] while already
 * running is a no-op; [stop] on a stopped observer is safe.
 *
 * @see net.devemperor.dictate.core.EditBarAudioFocusObserver — sibling
 *   single-axis observer pattern.
 * @see net.devemperor.dictate.core.EditNumbersAnimator
 */
class EditNumbersSmallModeObserver @JvmOverloads constructor(
    private val state: StateFlow<DictateUiState>,
    private val onChanged: Listener,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {

    private var scope: CoroutineScope? = null

    /** Begin observing. Idempotent. */
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + mainDispatcher)
        scope = s
        s.launch {
            state
                .map { it.layout.smallMode }
                .distinctUntilChanged()
                .drop(1)
                .collect { onChanged.onSmallModeChanged(it) }
        }
    }

    /** Stop observing. Idempotent. */
    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** Functional-interface for Java-side lambda binding. */
    fun interface Listener {
        fun onSmallModeChanged(smallMode: Boolean)
    }
}
