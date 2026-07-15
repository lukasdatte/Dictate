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
    /**
     * Yields the PC-send-mode colour (ADR-0019) — used in place of the accent
     * for the whole button while `features.windowsAutoSendActive`, so "this
     * dictation is bound for the PC" is legible before the user speaks.
     *
     * Only the *colour* comes from a lambda (it needs resources); whether
     * PC-mode is on is read from state in [onState], where it belongs.
     *
     * Defaults to [accentColorProvider], i.e. "no PC colour supplied → PC-mode
     * looks like any other recording". Callers that do not render the PC
     * signal (and tests that do not care) then need no fake colour.
     */
    private val pcModeColorProvider: () -> Int = accentColorProvider,
    /**
     * The badge drawn next to the recording timer while PC-mode is active
     * (`"PC"`). Empty default keeps callers that do not render the signal —
     * and tests — unaffected.
     */
    private val pcModeBadge: String = "",
) {

    /**
     * Cached last-observed [RecordingState] value — drives the idempotency
     * check via class-comparison (`prev::class == curr::class`). `null`
     * until the first [onState] call.
     */
    private var lastRecordingState: RecordingState? = null

    /**
     * Cached `features.windowsAutoSendActive`, so [paletteColor] can answer
     * outside [onState] (the animator helpers and [updateColor] have no state
     * to read).
     */
    private var lastPcMode: Boolean = false

    /**
     * Last colour actually painted. Comparing the *resolved* colour — rather
     * than the PC flag and the accent separately — means one check covers
     * both reasons the palette can move (PC-mode flip, accent change).
     */
    private var lastPalette: Int? = null

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
        val pcMode = state.features.windowsAutoSendActive
        if (pcMode != lastPcMode) {
            // Push before the branch check below can return early: while a
            // recording is live the button text is gone and this badge is the
            // only PC marker on screen, so it must track a mid-recording flip.
            animation.onBadge(if (pcMode) pcModeBadge else "")
        }
        lastPcMode = pcMode
        val palette = paletteColor()
        val sameBranch = lastRecordingState?.let { prev -> prev::class == curr::class } == true

        if (sameBranch) {
            // The recording branch is unchanged, so the animation lifecycle
            // must not be touched — re-running `Active` here would call
            // `animation.start()` on an already-started visualizer, which
            // would capture the visualizer itself as `previousForeground`.
            // But the palette CAN change without a branch change (the user
            // flips PC-mode while idle), and the old early-return swallowed
            // exactly that: the button kept its accent until the next
            // recording transition. Repaint, then leave.
            if (palette != lastPalette) {
                lastPalette = palette
                repaint()
            }
            return
        }
        lastPalette = palette

        when (curr) {
            is RecordingState.Idle -> {
                animation.cancel()
                stopBackgroundAnimator()
                applyBackground(palette)
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
                    applyBackground(palette)
                }
            }

            is RecordingState.Paused -> {
                if (animationsEnabled()) {
                    animation.pause()
                }
                stopBackgroundAnimator()
                applyBackground(dimmed(palette))
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
                applyBackground(dimmed(palette))
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
     * Re-paint for a new accent colour. Called by the service when the user
     * changes the accent in settings.
     *
     * The parameter is honoured only when PC-mode is off: PC-mode replaces the
     * accent wholesale, so an accent change must not repaint the purple away.
     * [paletteColor] arbitrates; the argument is kept because callers already
     * hold the new accent and passing it keeps this an explicit push rather
     * than an implicit "something might have changed" ping.
     */
    fun updateColor(accentColor: Int) {
        lastPalette = if (lastPcMode) pcModeColorProvider() else accentColor
        repaint()
    }

    /**
     * Paint [lastPalette] onto the button in whatever way the current
     * recording branch calls for, **without touching the animation
     * lifecycle**. Shared by [updateColor] and the palette-only path in
     * [onState].
     */
    private fun repaint() {
        val palette = lastPalette ?: paletteColor()
        animation.updateColor(palette)
        val curr = lastRecordingState
        if (curr is RecordingState.Active && animationsEnabled()) {
            // Re-seed the interpolator's endpoints; the visualizer keeps
            // running (this restarts the ValueAnimator, not the animation).
            stopBackgroundAnimator()
            startBackgroundAnimator()
        } else {
            // Static colour for non-active states. Also covers the
            // pre-first-tick case (`curr == null`) — without this the
            // button would keep whatever colour `applyTheme` left
            // behind and the new colour would not become visible until
            // the next state-class transition.
            applyBackground(
                if (curr is RecordingState.Paused || curr is RecordingState.Interrupted) {
                    dimmed(palette)
                } else {
                    palette
                },
            )
        }
    }

    /**
     * The colour the button is painted in right now: PC-send-mode replaces the
     * accent entirely rather than tinting it, because the accent is
     * user-configurable and a blend would be invisible for anyone who already
     * picked purple.
     */
    private fun paletteColor(): Int =
        if (lastPcMode) pcModeColorProvider() else accentColorProvider()

    /**
     * Reset internal cache. Called from [ImeViewBackend.detach] so a
     * re-attach (view-recreate / rotation) does NOT skip the first
     * recording-state apply on the next render-tick.
     */
    fun reset() {
        lastRecordingState = null
        // Drop the palette too: a re-attach must repaint from scratch for the
        // same reason it must re-apply the recording state — the fresh view
        // carries whatever colour `applyTheme` left on it, not ours.
        lastPalette = null
        stopBackgroundAnimator()
    }

    // ─── private animator helpers ─────────────────────────────────────

    private fun startBackgroundAnimator() {
        val button = recordButton ?: return
        stopBackgroundAnimator()
        val peak = paletteColor()
        val dim = dimmed(peak)
        backgroundAnimator = ValueAnimator.ofObject(ArgbEvaluator(), peak, dim).apply {
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
