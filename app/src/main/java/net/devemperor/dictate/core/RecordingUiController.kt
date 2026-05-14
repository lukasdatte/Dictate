package net.devemperor.dictate.core

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import android.content.res.ColorStateList
import net.devemperor.dictate.R
import net.devemperor.dictate.widget.AmplitudeVisualizerDrawable
import net.devemperor.dictate.widget.RecordingAnimation
import net.devemperor.dictate.widget.computeVisualizerBarColor
import java.util.Locale

/**
 * Handles all recording-related UI updates.
 *
 * Implements [RecordingStateController.Callback] for the UI-relevant methods
 * (state changes, amplitude, timer). Lifecycle events (completed, error, keepAwake)
 * are handled by the Service via a composite callback wrapper.
 *
 * Extracted from DictateInputMethodService to separate recording UI from
 * recording coordination logic.
 */
class RecordingUiController(
    private val recordButton: MaterialButton,
    private val pauseButton: MaterialButton,
    private val resendButton: MaterialButton,
    private val recordingAnimation: RecordingAnimation,
    private val stateManager: KeyboardStateManager,
    private val context: Context,
    private val getDictateButtonText: () -> String,
    private val isAnimationEnabled: () -> Boolean,
    /**
     * Returns `true` iff the cached "last recording" audio file is still
     * present. Block-1a split from the former combined
     * `getLastAudioFileExists` lambda — the resend-pref axis now travels
     * via [getResendEnabled] so [isResendVisible] sees the two inputs
     * independently. See [KeyboardVisibilityPredicates] for the rationale.
     */
    private val getLastAudioFileExists: () -> Boolean,
    /**
     * Returns the current `Pref.ResendButton` value. Split from
     * [getLastAudioFileExists] in Block 1a (Quick-Wins) so the
     * [isResendVisible] helper receives each axis as a separate input
     * — the future LayoutCatalog predicate (Spec 2 §3.2) has the same
     * shape.
     */
    private val getResendEnabled: () -> Boolean,
    /**
     * Returns the live pipeline-axis state. The recording-axis-driven Idle
     * transitions ([applyIdleState] / [applyActiveState]) need the
     * current pipeline state so [resolveResendVisibility] can return GONE
     * whenever the pipeline is still owning the UI (Preparing / Running /
     * ReprocessStaging) — e.g. on view-recreate `restoreUiState`,
     * language-flip, or cancel-recording paths that bypass
     * `onRecordingCompleted`. The default supplies `PipelineUiState.Idle`
     * so tests that don't care about the pipeline axis still compile.
     * Mirrors the existing pattern in [DictateInputMethodService] (e.g.
     * the `isReprocessStaging` provider).
     */
    private val pipelineStateProvider: () -> PipelineUiState = { PipelineUiState.Idle },
    /**
     * Block-1a Quick-Win (Spec 1 §11.2.2 step 2): the central
     * record-button-appearance resolver lives in
     * [KeyboardUiController.applyRecordButtonForRecording]. The
     * `RecordingUiController` no longer mutates `recordButton.text /
     * isEnabled / compound drawables` itself — it forwards the new
     * recording state via this callback so the resolver can combine the
     * recording axis with the pipeline axis it already owns. The
     * previously-race-fragile ordering between Idle/Active branches and
     * the Preparing/Running branches in `KeyboardUiController` collapses
     * into a single owner.
     */
    private val onRecordingStateChangedForRecordButton: (RecordingState) -> Unit,
    private val qwertzRecButtonProvider: () -> MaterialButton? = { null },
    private val promptRecButton: MaterialButton? = null,
    private val promptPauseButton: MaterialButton? = null,
    private val onPauseToggle: () -> Unit = {},
    private val onSend: () -> Unit = {}
) : RecordingStateController.Callback {

    private var promptsVisualizer: AmplitudeVisualizerDrawable? = null

    init {
        recordingAnimation.prepare(recordButton)
        if (promptRecButton != null) {
            setupPromptsVisualizer()
        }
    }

    /**
     * Ordering contract (load-bearing — Block 5 must preserve until
     * LayoutCatalog collapses both axes into one subscriber):
     *  1. record-button resolver (axis-aware appearance) —
     *     [onRecordingStateChangedForRecordButton] delegates to
     *     [KeyboardUiController.applyRecordButtonForRecording] BEFORE the
     *     auxiliary view work, so the resolver paints the record button
     *     first and any later mutations only adjust peripheral state.
     *  2. recording-axis side-effects (pauseButton, animation, prompt
     *     buttons, resend) — `when (newState)` dispatches into
     *     [applyIdleState] / [applyPreparingState] / [applyActiveState] /
     *     [applyPausedState].
     *  3. QWERTZ rec-button mirror — [updateQwertzRecButton] reflects the
     *     active-or-paused recording state on the dedicated QWERTZ key.
     *  4. [KeyboardStateManager.refresh] — rebuilds visibilities; runs
     *     LAST so all internal mutations above are visible to KSM in the
     *     same Main-thread task (eliminates the §9.5 race).
     *
     * The single-owner invariant (recording-axis combined with pipeline-
     * axis through the central resolver) collapses the previous Idle vs.
     * Preparing/Running ordering races into a deterministic sequence.
     */
    override fun onStateChanged(oldState: RecordingState, newState: RecordingState) {
        // Step 1: central record-button resolver (Spec 1 §11.2.2 step 2).
        onRecordingStateChangedForRecordButton(newState)
        // Step 2: recording-axis side-effects.
        when (newState) {
            is RecordingState.Idle -> applyIdleState()
            is RecordingState.Preparing -> applyPreparingState()
            is RecordingState.Active -> applyActiveState(newState.useBluetooth)
            is RecordingState.Paused -> applyPausedState()
        }
        // Step 3: QWERTZ rec-button mirror.
        updateQwertzRecButton(newState.isRecordingOrPaused)
        // Step 4: rebuild visibilities — MUST run last (ordering invariant).
        stateManager.refresh()
    }

    override fun onAmplitudeUpdate(level: Float) {
        recordingAnimation.onAmplitude(level)
        promptsVisualizer?.pushAmplitude(level)
    }

    override fun onTimerTick(elapsedMs: Long) {
        val timerText = String.format(
            Locale.getDefault(), "%02d:%02d",
            (elapsedMs / 60000).toInt(),
            ((elapsedMs / 1000) % 60).toInt()
        )
        recordingAnimation.onTimerTick(timerText)
        promptsVisualizer?.setTimerText(timerText)

        // QWERTZ rec button: two-line (send arrow icon on top, timer below)
        qwertzRecButtonProvider()?.let { btn ->
            btn.icon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_send_20)
            btn.iconGravity = MaterialButton.ICON_GRAVITY_TOP
            btn.text = timerText
        }
    }

    /** Updates animation color (e.g. after theme change). */
    fun updateAnimationColor(accentColor: Int) {
        recordingAnimation.updateColor(accentColor)
        promptsVisualizer?.updateBarColor(computeVisualizerBarColor(accentColor))
    }

    // ── Prompts Visualizer ──

    /**
     * Creates an adaptive [AmplitudeVisualizerDrawable] for the prompts recording
     * indicator button. The visualizer gracefully degrades when the button is small
     * (hides send icon and timer automatically).
     */
    private fun setupPromptsVisualizer() {
        val btn = promptRecButton ?: return
        val density = btn.resources.displayMetrics.density
        val accentColor = context.getColor(R.color.dictate_blue)

        promptsVisualizer = AmplitudeVisualizerDrawable(
            sendIcon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_send_20),
            barColor = computeVisualizerBarColor(accentColor),
            barCountMode = AmplitudeVisualizerDrawable.BarCountMode.Adaptive(minBars = 3),
            textColor = Color.WHITE,
            textSizePx = density * 12f,
            insetTopPx = density * 4f,
            insetBottomPx = density * 4f
        )
    }

    // ── State Application ──

    private fun applyIdleState() {
        // Block-1a Quick-Win: recordButton text / isEnabled / compound
        // drawables are owned by KeyboardUiController.applyRecordButtonFor
        // Recording (called from onStateChanged before this branch) — no
        // longer mutated here. See class header + §11.2.2 step 2.
        pauseButton.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24)

        if (isAnimationEnabled()) {
            recordingAnimation.cancel()
        }

        // Reset prompts rec button and click handlers
        promptsVisualizer?.reset()
        promptRecButton?.foreground = null
        promptRecButton?.icon = null
        promptRecButton?.text = ""
        promptRecButton?.setOnClickListener(null)
        promptPauseButton?.foreground = null
        promptPauseButton?.setOnClickListener(null)

        // Block-1a Quick-Win: single source of truth for resend visibility.
        // Recording is Idle here (we just entered this branch). The pipeline
        // axis is owned by KeyboardUiController; read it live via the
        // injected provider so non-stop pathways (view-recreate
        // restoreUiState, language-flip, cancel-recording bypassing
        // onRecordingCompleted) cannot expose a stale "Idle pipeline"
        // literal and let the resend button compete with a Sending… frame.
        resendButton.visibility = resolveResendVisibility(
            lastAudioFileExists = getLastAudioFileExists(),
            resendEnabled = getResendEnabled(),
            recordingState = RecordingState.Idle,
            pipelineState = pipelineStateProvider()
        )
    }

    private fun applyPreparingState() {
        // recordButton.isEnabled handled by KeyboardUiController's central
        // resolver (Block-1a Quick-Win, §11.2.2 step 2).
    }

    private fun applyActiveState(useBluetooth: Boolean) {
        // recordButton text / isEnabled / compound drawables: see Idle branch
        // — owned by KeyboardUiController.applyRecordButtonForRecording.

        // Recording is Active here, so the predicate evaluates to false
        // regardless of audio-file or pref state. Going through the helper
        // keeps every resend-visibility call site on the same expression.
        // The pipeline axis is read live via the injected provider so the
        // call site stays consistent with [applyIdleState] (same SoT).
        resendButton.visibility = resolveResendVisibility(
            lastAudioFileExists = getLastAudioFileExists(),
            resendEnabled = getResendEnabled(),
            recordingState = RecordingState.Active(useBluetooth = useBluetooth),
            pipelineState = pipelineStateProvider()
        )
        pauseButton.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24)

        if (isAnimationEnabled()) {
            recordingAnimation.start()
        }

        // Activate prompts rec button — amplitude visualizer with send arrow + bars + timer
        promptRecButton?.let { btn ->
            btn.text = ""
            btn.icon = null
            btn.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            btn.foreground = promptsVisualizer
            btn.setOnClickListener { onSend() }
        }

        // Setup pause button (prompts bar): shows pause icon, tap toggles pause/resume
        promptPauseButton?.let { btn ->
            btn.text = ""
            btn.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            btn.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24)
            btn.setOnClickListener { onPauseToggle() }
        }

        // Recording controls visibility is handled by KeyboardStateManager.refresh()
        // which is called via onStateChanged -> stateManager.refresh()
    }

    private fun applyPausedState() {
        pauseButton.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24)

        if (isAnimationEnabled()) {
            recordingAnimation.pause()
        }

        // Prompts: rec button keeps visualizer (frozen), still sends on tap
        // Pause button shows mic/resume icon
        promptPauseButton?.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24)
    }

    // ── QWERTZ Rec Button ──

    /**
     * Updates the QWERTZ bottom-row Rec button icon to reflect recording state.
     * Mic icon when idle, stop icon when recording/paused.
     */
    private var qwertzRecOriginalIconPadding: Int? = null
    private var qwertzRecOriginalTextColors: ColorStateList? = null
    private var qwertzRecOriginalIconTint: ColorStateList? = null
    private var qwertzRecOriginalPadding: IntArray? = null
    private var qwertzRecOriginalIconGravity: Int? = null

    private fun ensureQwertzOriginalsSaved(btn: MaterialButton) {
        if (qwertzRecOriginalIconPadding == null) {
            qwertzRecOriginalIconPadding = btn.iconPadding
            qwertzRecOriginalTextColors = btn.textColors
            qwertzRecOriginalIconTint = btn.iconTint
            qwertzRecOriginalIconGravity = btn.iconGravity
            qwertzRecOriginalPadding = intArrayOf(
                btn.paddingLeft, btn.paddingTop,
                btn.paddingRight, btn.paddingBottom
            )
        }
    }

    fun updateQwertzRecButton(isActive: Boolean) {
        val recButton = qwertzRecButtonProvider() ?: return
        if (isActive) {
            ensureQwertzOriginalsSaved(recButton)
            // Show send arrow + timer (set by onTimerTick), white text + icon, tight spacing
            val density = recButton.resources.displayMetrics.density
            recButton.icon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_send_20)
            recButton.iconTint = ColorStateList.valueOf(Color.WHITE)
            recButton.iconGravity = MaterialButton.ICON_GRAVITY_TOP
            recButton.iconPadding = 0
            recButton.setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt()) // Push icon down
            recButton.setTextColor(Color.WHITE)
        } else {
            recButton.text = ""
            recButton.icon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24)
            recButton.iconGravity = qwertzRecOriginalIconGravity ?: MaterialButton.ICON_GRAVITY_TEXT_START
            qwertzRecOriginalIconPadding?.let { recButton.iconPadding = it }
            qwertzRecOriginalTextColors?.let { recButton.setTextColor(it) }
            qwertzRecOriginalIconTint?.let { recButton.iconTint = it }
            qwertzRecOriginalPadding?.let { p ->
                recButton.setPadding(p[0], p[1], p[2], p[3])
            }
        }
    }

    /**
     * One-shot setup for pipeline display mode on the QWERTZ rec button.
     * Sets icon/color/padding once; subsequent per-tick updates only touch text
     * (see [updatePipelineTimer]). Should only be called on Idle→Running (or layout-rebuild)
     * transitions — calling it per tick would cause redundant [setPadding] re-layout triggers.
     */
    fun enterPipelineDisplay(state: PipelineUiState.Running) {
        val recButton = qwertzRecButtonProvider() ?: return
        ensureQwertzOriginalsSaved(recButton)
        recButton.icon = null
        recButton.setTextColor(if (state.hasFailure) 0xFFF44336.toInt() else Color.WHITE)
        val density = recButton.resources.displayMetrics.density
        recButton.setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        // Initial text (elapsed 0) so the button is not blank between enter and first tick.
        updatePipelineTimer(state, 0L)
    }

    /**
     * Per-tick update — only touches text, no layout-triggering property changes.
     * Also keeps the text color in sync with [PipelineUiState.Running.hasFailure], which may
     * flip mid-pipeline.
     */
    fun updatePipelineTimer(state: PipelineUiState.Running, elapsedMs: Long) {
        val recButton = qwertzRecButtonProvider() ?: return
        val counter = "${state.completedSteps}/${state.totalSteps}"
        val enterIndicator = if (state.autoEnterActive) " \u21B5" else ""
        val timer = formatElapsedCompact(elapsedMs)
        recButton.text = "$counter$enterIndicator\n$timer"
        recButton.setTextColor(if (state.hasFailure) 0xFFF44336.toInt() else Color.WHITE)
    }
}
