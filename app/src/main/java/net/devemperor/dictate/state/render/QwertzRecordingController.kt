package net.devemperor.dictate.state.render

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.core.PipelineUiState
import net.devemperor.dictate.core.formatElapsedCompact
import net.devemperor.dictate.widget.AmplitudeVisualizerDrawable
import net.devemperor.dictate.widget.computeVisualizerBarColor
import java.util.Locale

/**
 * Owns the **QWERTZ rec-button + prompts-visualizer** — the G9 BLEIBT
 * surface Spec 2 §9.4 keeps ( *"updateQwertzRecButton — bleibt, QWERTZ-
 * Bereich ist orthogonal, eigener Slot oder eigener Controller"* /
 * *"enterPipelineDisplay / updatePipelineTimer — bleibt"* ).
 *
 * # Why this class exists (CR-DEL / RR-3 / A3 option-a)
 *
 * The deleted `RecordingUiController` mixed two concerns: the
 * **recording-axis Main-button side-effects** (record/pause/resend
 * appearance, animation) and the **QWERTZ rec-button + amplitude
 * visualizer**. The Main-button side-effects are *dead on the bound
 * path* (the legacy `recordingStateController` is never started on the
 * new path — C5; the orchestrator's `RecordingModule` owns recording
 * end-to-end) and have been collapsed onto `RecordingAnimationController`
 * + the catalog resolvers + the `predResendVisible` predicate. The
 * QWERTZ rec-button + prompts-visualizer, however, were **still live**
 * (driven by the IME's pipeline callback + the QWERTZ layout-rebuild
 * callback, neither bound-guarded). Per the **binding A3 option-a**
 * disposition (extract the BLEIBT parts so the kill-list class fully
 * deletes and AC-RR-7 stays a clean zero-grep) this QWERTZ surface is
 * relocated here **byte-equivalent** from `RecordingUiController`.
 *
 * Imperatively driven (the IME calls it from the pipeline-UI callback +
 * the QWERTZ layout-rebuild callback) — exactly as the legacy controller
 * was. Not a `RenderBackend`.
 *
 * @see PipelineStepRowRenderer — the sibling G13 BLEIBT extraction.
 * @see RecordingAnimationController — the recording-axis collapse target.
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/reports/B5-theme-cr-render-cutover.md "Chunk C10-C3 (CR-DEL)"
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §9.4
 */
class QwertzRecordingController(
    private val context: Context,
    /**
     * Returns the QWERTZ bottom-row Rec button, or `null` when the QWERTZ
     * keyboard view is not currently inflated (the legacy
     * `qwertzRecButtonProvider` semantics, preserved verbatim).
     */
    private val qwertzRecButtonProvider: () -> MaterialButton?,
    /**
     * The prompts-bar recording-indicator button (the visualizer host),
     * or `null` when the prompts bar is not present.
     */
    private val promptRecButton: MaterialButton? = null,
    private val promptPauseButton: MaterialButton? = null,
    private val onPauseToggle: () -> Unit = {},
    private val onSend: () -> Unit = {},
) {

    private var promptsVisualizer: AmplitudeVisualizerDrawable? = null

    init {
        if (promptRecButton != null) {
            setupPromptsVisualizer()
        }
    }

    // ── Amplitude / Timer side-channel (QWERTZ part only) ──

    /**
     * Per-tick amplitude forwarding to the prompts-visualizer. The
     * record-button BorderGlow amplitude is owned by
     * [RecordingAnimationController.onAmplitude] (the recording-axis
     * collapse target) — this owns only the QWERTZ-side visualizer.
     */
    fun onAmplitude(level: Float) {
        promptsVisualizer?.pushAmplitude(level)
    }

    /**
     * Per-tick timer forwarding to the prompts-visualizer + the QWERTZ
     * rec button two-line display. Byte-equivalent to the deleted
     * `RecordingUiController.onTimerTick` QWERTZ half.
     */
    fun onTimerTick(elapsedMs: Long) {
        val timerText = String.format(
            Locale.getDefault(), "%02d:%02d",
            (elapsedMs / 60000).toInt(),
            ((elapsedMs / 1000) % 60).toInt(),
        )
        promptsVisualizer?.setTimerText(timerText)

        qwertzRecButtonProvider()?.let { btn ->
            btn.icon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_send_20)
            btn.iconGravity = MaterialButton.ICON_GRAVITY_TOP
            btn.text = timerText
        }
    }

    /** Updates the prompts-visualizer bar colour (e.g. after theme change). */
    fun updateAnimationColor(accentColor: Int) {
        promptsVisualizer?.updateBarColor(computeVisualizerBarColor(accentColor))
    }

    /**
     * Activates / resets the prompts-bar recording controls for the
     * Active recording state (visualizer + send + pause). Byte-equivalent
     * to the live half of the deleted `RecordingUiController.applyActiveState`
     * prompt-button block (the only part still relevant to the QWERTZ
     * prompts bar; the Main-button side-effects are collapsed elsewhere).
     */
    fun activatePromptRecordingControls() {
        promptRecButton?.let { btn ->
            btn.text = ""
            btn.icon = null
            btn.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            btn.foreground = promptsVisualizer
            btn.setOnClickListener { onSend() }
        }
        promptPauseButton?.let { btn ->
            btn.text = ""
            btn.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            btn.foreground = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24)
            btn.setOnClickListener { onPauseToggle() }
        }
    }

    /** Pauses the prompts-bar recording controls (frozen visualizer, mic icon). */
    fun pausePromptRecordingControls() {
        promptPauseButton?.foreground =
            AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24)
    }

    /** Resets the prompts-bar recording controls back to Idle. */
    fun resetPromptRecordingControls() {
        promptsVisualizer?.reset()
        promptRecButton?.foreground = null
        promptRecButton?.icon = null
        promptRecButton?.text = ""
        promptRecButton?.setOnClickListener(null)
        promptPauseButton?.foreground = null
        promptPauseButton?.setOnClickListener(null)
    }

    // ── Prompts Visualizer ──

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
            insetBottomPx = density * 4f,
        )
    }

    // ── QWERTZ Rec Button ──

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
                btn.paddingRight, btn.paddingBottom,
            )
        }
    }

    fun updateQwertzRecButton(isActive: Boolean) {
        val recButton = qwertzRecButtonProvider() ?: return
        if (isActive) {
            ensureQwertzOriginalsSaved(recButton)
            val density = recButton.resources.displayMetrics.density
            recButton.icon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_send_20)
            recButton.iconTint = ColorStateList.valueOf(Color.WHITE)
            recButton.iconGravity = MaterialButton.ICON_GRAVITY_TOP
            recButton.iconPadding = 0
            recButton.setPadding(0, (4 * density).toInt(), 0, (2 * density).toInt())
            recButton.setTextColor(Color.WHITE)
        } else {
            recButton.text = ""
            recButton.icon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24)
            recButton.iconGravity =
                qwertzRecOriginalIconGravity ?: MaterialButton.ICON_GRAVITY_TEXT_START
            qwertzRecOriginalIconPadding?.let { recButton.iconPadding = it }
            qwertzRecOriginalTextColors?.let { recButton.setTextColor(it) }
            qwertzRecOriginalIconTint?.let { recButton.iconTint = it }
            qwertzRecOriginalPadding?.let { p ->
                recButton.setPadding(p[0], p[1], p[2], p[3])
            }
        }
    }

    fun enterPipelineDisplay(state: PipelineUiState.Running) {
        val recButton = qwertzRecButtonProvider() ?: return
        ensureQwertzOriginalsSaved(recButton)
        recButton.icon = null
        recButton.setTextColor(if (state.hasFailure) 0xFFF44336.toInt() else Color.WHITE)
        val density = recButton.resources.displayMetrics.density
        recButton.setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        updatePipelineTimer(state, 0L)
    }

    fun updatePipelineTimer(state: PipelineUiState.Running, elapsedMs: Long) {
        val recButton = qwertzRecButtonProvider() ?: return
        val counter = "${state.completedSteps}/${state.totalSteps}"
        val enterIndicator = if (state.autoEnterActive) " ↵" else ""
        val timer = formatElapsedCompact(elapsedMs)
        recButton.text = "$counter$enterIndicator\n$timer"
        recButton.setTextColor(if (state.hasFailure) 0xFFF44336.toInt() else Color.WHITE)
    }
}
