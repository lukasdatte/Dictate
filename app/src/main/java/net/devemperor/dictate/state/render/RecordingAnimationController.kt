package net.devemperor.dictate.state.render

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.DictateUtils
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.widget.RecordingAnimation
import java.util.Locale

/**
 * Drives the record-button visual recording indicator (BorderGlow +
 * Background-Pulse) from `state.recording`.
 *
 * # Visual model (2026-05-23 rework)
 *
 * Previously this controller drove a separate red [PulseLayout] painted
 * behind the record button — a strong, attention-grabbing overlay
 * (`0x44FF0000`) that looked dated. The pulse is replaced by a subtle
 * animated tint **on the button background itself**: the button breathes
 * between its full accent colour (peak) and a slightly darker variant
 * (`darkenColor(accent, 0.18)` — same `accentMedium` ramp the edit-bar
 * uses) while recording. Paused and interrupted recordings hold the
 * dimmed colour statically; Idle is the bright accent (the "ready"
 * state painted by [ImeViewBackend.applyTheme]).
 *
 * # Why a dedicated controller and not a slot resolver?
 *
 * Animations are stateful (start, pause, resume, cancel) — that
 * lifecycle cannot be expressed by the [net.devemperor.dictate.state.layout.ButtonSlot]
 * pure-resolver model. Mixing the animator into the catalog would
 * re-introduce a side-effect path inside a pure-data structure
 * (forbidden pattern (b)).
 *
 * Instead this controller observes [DictateUiState.recording] from the
 * backend's render-tick and forwards the **class transition**
 * (Idle → Active → Paused → Idle) into the [RecordingAnimation] strategy
 * interface plus its own [ValueAnimator]-driven background tint. A class
 * comparison (`prev::class == curr::class`) keeps the controller cheap.
 *
 * # Per-tick amplitude / timer hooks
 *
 * Amplitude and timer ticks live **outside** [DictateUiState] (per Spec
 * 2 §11.5 — pre-tick allocations would inflate StateFlow emission
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
 * @property recordButton the `MaterialButton` whose background is tinted
 *   by the breathing animation. `null` in test setups that don't render
 *   the button.
 * @property accentColorProvider lambda — yields the current accent
 *   colour at animator-construction time. Lambda (not constructor int)
 *   because the user can change the accent in settings while the
 *   service stays up; reading it lazily keeps the animator fresh
 *   without re-construction.
 * @property animationsEnabled lambda — `false` during reduced-motion /
 *   user preference suppresses the breathing animator (the button still
 *   gets the static accent/dimmed colour, just no interpolation).
 *
 * @see net.devemperor.dictate.widget.RecordingAnimation
 * @see net.devemperor.dictate.widget.BorderGlowAnimation
 * @see net.devemperor.dictate.state.render.ImeViewBackend.applyTheme
 */
class RecordingAnimationController(
    private val animation: RecordingAnimation,
    private val recordButton: MaterialButton?,
    private val accentColorProvider: () -> Int,
    private val animationsEnabled: () -> Boolean,
) {

    /**
     * Cached last-observed [RecordingState] value — drives the idempotency
     * check via class-comparison (`prev::class == curr::class`). `null`
     * until the first [onState] call.
     */
    private var lastRecordingState: RecordingState? = null

    /**
     * The breathing background animator. Reused across start/stop cycles
     * — constructed lazily on first start so the controller stays cheap
     * for setups without a button (e.g. overlay backend before the
     * widget is rendered).
     *
     * Duration / loop count chosen to feel "alive but unobtrusive":
     * 1500 ms one-way × infinite reverse — slightly slower than a
     * resting heartbeat (~1100 ms). The user reads it as "something is
     * happening" without it competing with the BorderGlow visualiser.
     */
    private var backgroundAnimator: ValueAnimator? = null

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
                stopBackgroundAnimator()
                applyBackground(accentColorProvider())
            }

            is RecordingState.Preparing -> {
                // No animation while the recorder warms up — the recorder
                // hardware can take 100-300 ms to acquire the mic, and an
                // animation during that window misleads the user about the
                // actual recording start. Animation begins once the state
                // transitions into Active.
            }

            is RecordingState.Active -> {
                if (animationsEnabled()) {
                    animation.start()
                    startBackgroundAnimator()
                } else {
                    // Reduced-motion: still mark the button visually as
                    // "active" via the brighter peak colour so the user
                    // sees something happen, just without interpolation.
                    applyBackground(accentColorProvider())
                }
            }

            is RecordingState.Paused -> {
                if (animationsEnabled()) {
                    animation.pause()
                }
                stopBackgroundAnimator()
                applyBackground(dimmed(accentColorProvider()))
            }

            is RecordingState.Interrupted -> {
                // Interrupted (2026-05-22) — a recovery-surfaced recording.
                // Unlike Paused (only ever reached *after* an Active interval
                // already `start()`-ed the animation), Interrupted is reached
                // COLD: the process that ran the Active interval is gone, so
                // this fresh controller never saw `start()`. A bare `pause()`
                // on a cold animation is a no-op that renders nothing —
                // `BorderGlowAnimation.pause()` only dims the background, and
                // `onTimerTick` is dropped by its `!isActive` guard, so
                // neither the visualizer nor the "0:08" timer ever appears.
                // To render the frozen-paused look we must first `start()` the
                // animation — that builds the `AmplitudeVisualizerDrawable`
                // which hosts the timer text — then `pause()` it, and seed
                // the timer straight from `Interrupted.elapsedMs`.
                if (animationsEnabled()) {
                    animation.start()
                    animation.pause()
                    onTimerTick(curr.elapsedMs)
                }
                stopBackgroundAnimator()
                applyBackground(dimmed(accentColorProvider()))
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
     * service when the user changes the accent in settings.
     *
     * The breathing background animator picks up the new accent on its
     * next start (via [accentColorProvider]); for the in-flight active
     * case we restart it so the colour change is visible immediately.
     */
    fun updateColor(accentColor: Int) {
        animation.updateColor(accentColor)
        if (lastRecordingState is RecordingState.Active) {
            stopBackgroundAnimator()
            startBackgroundAnimator()
        } else if (lastRecordingState != null) {
            // Refresh the static colour for non-active states too.
            val curr = lastRecordingState
            applyBackground(
                if (curr is RecordingState.Paused || curr is RecordingState.Interrupted)
                    dimmed(accentColor)
                else accentColor
            )
        }
    }

    /**
     * Reset internal cache. Called from [ImeViewBackend.detach] so a
     * re-attach (view-recreate / rotation) does NOT skip the first
     * recording-state apply on the next render-tick.
     */
    fun reset() {
        lastRecordingState = null
        stopBackgroundAnimator()
    }

    // ─── private animator helpers ─────────────────────────────────────

    private fun startBackgroundAnimator() {
        val button = recordButton ?: return
        stopBackgroundAnimator()
        val accent = accentColorProvider()
        val dim = dimmed(accent)
        backgroundAnimator = ValueAnimator.ofObject(ArgbEvaluator(), accent, dim).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val c = animator.animatedValue as? Int ?: return@addUpdateListener
                button.setBackgroundColor(c)
            }
            start()
        }
    }

    private fun stopBackgroundAnimator() {
        backgroundAnimator?.cancel()
        backgroundAnimator = null
    }

    private fun applyBackground(color: Int) {
        recordButton?.setBackgroundColor(color)
    }

    private fun dimmed(accent: Int): Int = DictateUtils.darkenColor(accent, 0.18f)
}
