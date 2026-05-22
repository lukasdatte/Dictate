package net.devemperor.dictate.state.render

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.widget.PulseLayout
import net.devemperor.dictate.widget.RecordingAnimation
import java.util.Locale

/**
 * Drives the record-button visual recording indicator
 * (BorderGlow + PulseLayout) from `state.recording`.
 *
 * # Why a dedicated controller and not a slot resolver?
 *
 * Animations are stateful (they start, pause, resume, cancel) — that
 * lifecycle cannot be expressed by the [net.devemperor.dictate.state.layout.ButtonSlot]
 * pure-resolver model. Mixing the animator into the catalog would
 * re-introduce a side-effect path inside a pure-data structure
 * (forbidden pattern (b)).
 *
 * Instead this controller observes [DictateUiState.recording] from the
 * backend's render-tick and forwards the **class transition**
 * (Idle → Active → Paused → Idle) into the
 * [RecordingAnimation] strategy interface. A class comparison
 * (`prev::class == curr::class`) keeps the controller cheap — no work
 * happens when the recording state hasn't actually changed.
 *
 * # Per-tick amplitude / timer hooks
 *
 * Amplitude and timer ticks live **outside** [DictateUiState] (per
 * Spec 2 §11.5 — pre-tick allocations would inflate StateFlow emission
 * cost). They come in via [onAmplitude] and [onTimerTick] from the
 * service's amplitude/timer side-channels. The backend forwards them
 * without going through the orchestrator.
 *
 * # Threading
 *
 * Called from the UI thread (the render-tick is dispatched on Main).
 * No internal synchronisation needed.
 *
 * @property animation the [RecordingAnimation] strategy
 *   (production: `BorderGlowAdapter` wrapping `BorderGlowAnimation`).
 * @property pulseLayout the `PulseLayout` wrapper around `record_btn`.
 * @property animationsEnabled lambda — `false` during reduced-motion /
 *   user preference suppresses every animation start.
 *
 * @see net.devemperor.dictate.widget.RecordingAnimation
 * @see net.devemperor.dictate.widget.BorderGlowAnimation
 * @see net.devemperor.dictate.widget.PulseLayout
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §11.5
 */
class RecordingAnimationController(
    private val animation: RecordingAnimation,
    private val pulseLayout: PulseLayout?,
    private val animationsEnabled: () -> Boolean,
) {

    /**
     * Cached last-observed [RecordingState] value — drives the idempotency
     * check via class-comparison (`prev::class == curr::class`). `null`
     * until the first [onState] call.
     *
     * Caching the value (not the Java class) matches Spec 2 §11.5 verbatim
     * and avoids the misleading "value-equality" mismatch the previous
     * `Class<out RecordingState>?` cache implied — `::class` already
     * ignores constructor arguments, so two `Active` states with different
     * `audioFile` are correctly seen as the same class transition (B4-VAL
     * F-14 / Spec 2 §11.5).
     */
    private var lastRecordingState: RecordingState? = null

    /**
     * Idempotent reactive entry point. Called by [ImeViewBackend.render]
     * after applying slot properties. Only mutates animations when the
     * `RecordingState`-sealed-class branch actually changes.
     */
    fun onState(state: DictateUiState) {
        val curr = state.recording
        if (lastRecordingState?.let { prev -> prev::class == curr::class } == true) return

        when (curr) {
            is RecordingState.Idle -> {
                animation.cancel()
                pulseLayout?.stopPulse()
            }

            is RecordingState.Preparing -> {
                // No animation while the recorder warms up — the recorder
                // hardware can take 100-300 ms to acquire the mic, and a
                // pulse during that window misleads the user about the
                // actual recording start. Animation begins once the state
                // transitions into Active.
            }

            is RecordingState.Active -> {
                if (animationsEnabled()) {
                    animation.start()
                    pulseLayout?.startPulse()
                }
            }

            is RecordingState.Paused,
            is RecordingState.Interrupted -> {
                // Interrupted (2026-05-22) renders "as if briefly
                // paused" — the same frozen-pulse look as Paused.
                if (animationsEnabled()) {
                    animation.pause()
                    pulseLayout?.pausePulse()
                }
            }
        }
        lastRecordingState = curr
    }

    /**
     * Per-tick amplitude forwarding. Side-channel — does NOT come from
     * [DictateUiState] (Spec 2 §11.5).
     */
    fun onAmplitude(level: Float) = animation.onAmplitude(level)

    /**
     * Per-tick timer forwarding. Side-channel — formats `MM:SS` before
     * passing through. Same rationale as [onAmplitude].
     */
    fun onTimerTick(elapsedMs: Long) {
        val minutes = (elapsedMs / 60_000L).toInt()
        val seconds = ((elapsedMs / 1_000L) % 60L).toInt()
        val text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
        animation.onTimerTick(text)
    }

    /**
     * Re-paint the animation for a new accent colour. Called by the
     * service when the user changes the accent in settings (Spec 2
     * §11.5 — forwards into the underlying [RecordingAnimation]).
     */
    fun updateColor(accentColor: Int) = animation.updateColor(accentColor)

    /**
     * Reset internal cache. Called from [ImeViewBackend.detach] so a
     * re-attach (view-recreate / rotation) does NOT skip the first
     * recording-state apply on the next render-tick.
     */
    fun reset() {
        lastRecordingState = null
    }
}
