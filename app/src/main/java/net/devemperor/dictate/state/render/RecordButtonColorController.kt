package net.devemperor.dictate.state.render

import android.graphics.Color
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState

/**
 * Phase 5.A of `2026-05-21 - dictate-render-cutover-completion-vol2` —
 * single side-channel writer for the `record_btn` `setTextColor` axis.
 *
 * # Why a side-channel?
 *
 * The Catalog `ButtonSlot` resolver model has no `colorResolver` slot
 * (`SlotRenderer.applySlotToView` writes text / icon / visibility /
 * enabled / alpha — no text colour). Adding a `colorResolver` to
 * `ButtonSlot` would proliferate the field on every slot for one
 * consumer. A side-channel matches the [AutoEnterRenderer] pattern
 * established by §7 Q1 of the plan and keeps the resolver model pure.
 *
 * # When the red colour flips on
 *
 * The controller paints the button text red iff
 * `state.pipeline is PipelineUiState.Running && Running.hasFailure`.
 * Anywhere else (Idle / Preparing / ReprocessStaging, or
 * `Running(hasFailure=false)`) it restores white.
 *
 * `hasFailure` becomes `true` when a [Action.PipelineAction.StepFailed]
 * arm fires on `Running`; the pipeline keeps running (Q6 decision) and
 * the red colour persists until the pipeline ends (`Running → Idle`
 * via `PipelineDone` / `PipelineFailed` / `CancelPipeline`).
 *
 * # Idempotency
 *
 * Skips the `setTextColor` write when the resolved `failure` flag
 * matches the cache. Matches the [AutoEnterRenderer] /
 * [RecordingAnimationController] discipline.
 *
 * # Lifecycle
 *
 * Instantiated once per [ImeViewBackend] attach interval, reset on
 * detach so the next attach re-applies the correct colour
 * unconditionally.
 *
 * @see AutoEnterRenderer — sibling side-channel for record_btn
 *   compound drawables.
 * @see RecordingAnimationController — sibling side-channel for the
 *   recording-axis animation/timer/amplitude triplet.
 */
class RecordButtonColorController(
    private val recordButton: MaterialButton,
    private val failureColor: Int = 0xFFF44336.toInt(),  // Material Red 500
    private val defaultColor: Int = Color.WHITE,
) {

    private var lastFailure: Boolean? = null

    /**
     * Idempotent reactive entry point. Called from
     * [ImeViewBackend.render] after the compound-drawable side-channel
     * runs.
     */
    fun onState(state: DictateUiState) {
        val failure = (state.pipeline as? PipelineUiState.Running)?.hasFailure == true
        if (failure == lastFailure) return
        recordButton.setTextColor(if (failure) failureColor else defaultColor)
        lastFailure = failure
    }

    /**
     * Drop the idempotency cache so the next [onState] applies
     * unconditionally. Call from [ImeViewBackend.detach].
     */
    fun reset() {
        lastFailure = null
    }
}
