package net.devemperor.dictate.state.render

import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.R
import net.devemperor.dictate.core.ElapsedTimer
import net.devemperor.dictate.core.formatElapsedCompact
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.StepRowItem
import net.devemperor.dictate.state.StepStatus

/**
 * Reactive consumer for the pipeline **step-row** progress UI.
 *
 * # The Phase-5.B re-architecture
 *
 * Pre-cutover (and through Phases 1-5.A) this class owned its own
 * `core.PipelineUiState` sealed class, accepted imperative mutator
 * calls (`preparePipeline`, `startPipeline`, `addRunningStep`,
 * `completeStep`, `failStep`, `stopPipeline`, …) from the IME service,
 * and ran a 100 ms `ElapsedTimer` driving a parallel
 * `record_btn` writer. Phase 3 stopped the record-btn writes
 * (`refreshRecordButtonFromState` → no-op + `AutoEnterRenderer`
 * side-channel); Phase 5.A migrated the FSM-of-step rows into
 * [PipelineUiState.Running.stepHistory] inside the orchestrator
 * state. **This Phase 5.B reduction** completes the cutover:
 *
 *  - The legacy `state`-property and the entire `PipelineUiStateReader`
 *    / `PipelineUiCallback` surface are gone.
 *  - The imperative mutator API (`preparePipeline`, `startPipeline`,
 *    `addRunningStep`, `completeStep`, `failStep`, `stopPipeline`,
 *    `toggleAutoEnter`, `enterReprocessStaging`, `cancelReprocessStaging`,
 *    `updateReprocessQueue`, `updateReprocessLanguage`) is gone.
 *  - The renderer becomes a **pure View-side consumer** of
 *    [DictateUiState] — driven from [ImeViewBackend.render] via
 *    [onState], just like [AutoEnterRenderer] and
 *    [RecordButtonColorController].
 *
 * # What the renderer still owns
 *
 * Step-row inflate + per-row ElapsedTimer remain View-side per Spec 1
 * §9.2 (an explicit "BLEIBT" disposition). The only difference is the
 * **trigger**: the reducer (`PipelineModule.StepStarted` / `…Completed`
 * / `…Failed`) appends / finalises [StepRowItem] entries on
 * [PipelineUiState.Running.stepHistory], and this renderer diffs the
 * list against its inflated view children on every state emit.
 *
 * # Idempotency
 *
 * [onState] is a no-op when the resolved `(sessionId, stepHistory)`
 * snapshot matches the last applied one — same discipline as
 * [AutoEnterRenderer] / [RecordButtonColorController] /
 * [RecordingAnimationController].
 *
 * # Lifecycle
 *
 * One instance per [ImeViewBackend] attach interval. [reset] on
 * detach clears the row cache + stops the active per-step timer so a
 * re-attach (rotation / view-recreate) re-inflates from a fresh
 * baseline.
 *
 * @see AutoEnterRenderer — sibling side-channel for record_btn compound drawables.
 * @see RecordButtonColorController — sibling side-channel for record_btn setTextColor.
 * @see RecordingAnimationController — sibling side-channel for the recording-axis animation.
 * @see docs/plans/2026-05-21 - dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md §4 Phase 5.B
 */
class PipelineStepRowRenderer(
    private val views: PipelineViews,
) {

    data class PipelineViews(
        val pipelineStepsContainer: LinearLayout,
        val pipelineScrollView: ScrollView,
        /**
         * Kept on the data class for binary-compat with the IME-Java
         * caller; the renderer no longer writes to it. The
         * [AutoEnterRenderer] + [RecordButtonColorController] side-channels
         * are the sole writers on the `record_btn` axes after Phase 5.B.
         */
        @Suppress("unused")
        val recordButton: MaterialButton,
        val layoutInflater: LayoutInflater,
        val mainHandler: Handler,
    )

    private data class StepRowBinding(
        val root: View,
        val iconTv: TextView,
        val pb: ProgressBar,
        val nameTv: TextView,
        val durationTv: TextView,
    )

    private val stepRows = mutableListOf<StepRowBinding>()

    /**
     * Per-RUNNING-row live timer — driven by an [ElapsedTimer] anchored
     * at the row's `startedAtMs`. Stopped when the row finalises to
     * COMPLETED / FAILED, restarted when a new RUNNING row is appended.
     */
    private var activeTimer: ElapsedTimer? = null

    /**
     * Cache key for the idempotency short-circuit. Two snapshots with
     * the same `(sessionId, stepHistory-content)` produce identical
     * views; skipping the diff avoids tear-down of running per-row
     * animations.
     */
    private data class AppliedKey(val sessionId: String?, val stepHistory: List<StepRowItem>)

    private var lastApplied: AppliedKey? = null

    /**
     * Idempotent reactive entry point. Called from
     * [ImeViewBackend.render] after the slot-renderer fan-out and the
     * record-button side-channels.
     */
    fun onState(state: DictateUiState) {
        val pipe = state.pipeline
        val key = when (pipe) {
            is PipelineUiState.Running -> AppliedKey(pipe.sessionId, pipe.stepHistory)
            else -> AppliedKey(sessionId = null, stepHistory = emptyList())
        }
        if (key == lastApplied) return

        when (pipe) {
            is PipelineUiState.Running -> applyRunning(pipe)
            else -> applyNonRunning()
        }
        lastApplied = key
    }

    /**
     * Drop the cache + stop the timer + wipe inflated rows. Call from
     * [ImeViewBackend.detach] so a re-attach re-inflates from scratch.
     */
    fun reset() {
        activeTimer?.stop()
        activeTimer = null
        stepRows.clear()
        views.pipelineStepsContainer.removeAllViews()
        lastApplied = null
    }

    /**
     * Stop the per-row timer without touching the cache or the inflated
     * row views. Used by the IME service in
     * `cleanupOldControllers` when the View tree gets recreated but the
     * renderer wants to leave UI in its current state (the next
     * `reset()` from `detach()` will clear it properly).
     */
    fun stopActiveTimer() {
        activeTimer?.stop()
        activeTimer = null
    }

    // ── Branch handlers ─────────────────────────────────────────────────

    private fun applyRunning(running: PipelineUiState.Running) {
        // First emit of a new session — wipe whatever rows the previous
        // session left behind (rotation, cancel + retry, etc.).
        // 2026-07-02 (ADR-0006 completion) — the legacy hard-hide of the
        // deleted legacy error-bar container on new-session start is gone;
        // state-driven info bar clears reactively via InfoHintModule's
        // pipeline-start cascade.
        if (lastApplied?.sessionId != running.sessionId) {
            views.pipelineStepsContainer.removeAllViews()
            stepRows.clear()
            activeTimer?.stop()
            activeTimer = null
        }
        diffStepHistory(running.stepHistory)
    }

    private fun applyNonRunning() {
        // Idle / Preparing / ReprocessStaging — wipe the rows from the
        // previous Running. ReprocessStaging shows its own UI (the
        // editable queue + language chip); it does not own pipeline
        // step rows.
        if (stepRows.isNotEmpty()) {
            views.pipelineStepsContainer.removeAllViews()
            stepRows.clear()
        }
        activeTimer?.stop()
        activeTimer = null
    }

    private fun diffStepHistory(target: List<StepRowItem>) {
        // 1) Inflate any new rows beyond the current view count.
        while (stepRows.size < target.size) {
            val item = target[stepRows.size]
            inflateRow(item)
        }
        // 2) Re-apply the visual state to every existing row (idempotent
        //    re-write of icon/progressbar/duration text, even if it
        //    matches the previous render — cheap, no animation tear-down).
        for (i in target.indices) {
            applyRowState(stepRows[i], target[i])
        }
        // 3) Wire the per-row live timer to the last RUNNING row, if any.
        val lastRunningIdx = target.indexOfLast { it.status == StepStatus.RUNNING }
        if (lastRunningIdx >= 0) {
            startActiveTimerFor(stepRows[lastRunningIdx])
        } else {
            activeTimer?.stop()
            activeTimer = null
        }
        // 4) Auto-scroll to the latest row.
        views.pipelineScrollView.post { views.pipelineScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun inflateRow(item: StepRowItem) {
        val row = views.layoutInflater.inflate(
            R.layout.item_pipeline_step_row,
            views.pipelineStepsContainer,
            false,
        )
        val binding = StepRowBinding(
            root = row,
            iconTv = row.findViewById(R.id.pipeline_step_icon_tv),
            pb = row.findViewById(R.id.pipeline_step_pb),
            nameTv = row.findViewById(R.id.pipeline_step_name_tv),
            durationTv = row.findViewById(R.id.pipeline_step_duration_tv),
        )
        binding.nameTv.text = item.stepName
        views.pipelineStepsContainer.addView(row)
        stepRows.add(binding)
        applyRowState(binding, item)
    }

    private fun applyRowState(binding: StepRowBinding, item: StepRowItem) {
        binding.nameTv.text = item.stepName
        when (item.status) {
            StepStatus.RUNNING -> {
                binding.iconTv.visibility = View.GONE
                binding.pb.visibility = View.VISIBLE
                binding.durationTv.visibility = View.VISIBLE
                binding.durationTv.text = formatElapsedCompact(0)
            }
            StepStatus.COMPLETED -> {
                binding.pb.visibility = View.GONE
                binding.iconTv.visibility = View.VISIBLE
                binding.iconTv.text = "✓"
                binding.iconTv.setTextColor(0xFF4CAF50.toInt())  // Material Green 500
                binding.durationTv.visibility = View.VISIBLE
                binding.durationTv.text = formatElapsedCompact(item.durationMs)
            }
            StepStatus.FAILED -> {
                binding.pb.visibility = View.GONE
                binding.iconTv.visibility = View.VISIBLE
                binding.iconTv.text = "✕"
                binding.iconTv.setTextColor(0xFFF44336.toInt())  // Material Red 500
                binding.durationTv.visibility = View.VISIBLE
                binding.durationTv.text = formatElapsedCompact(item.durationMs)
            }
        }
    }

    private fun startActiveTimerFor(targetBinding: StepRowBinding) {
        activeTimer?.stop()
        activeTimer = ElapsedTimer.start(views.mainHandler) { ms ->
            targetBinding.durationTv.text = formatElapsedCompact(ms)
        }
    }
}
