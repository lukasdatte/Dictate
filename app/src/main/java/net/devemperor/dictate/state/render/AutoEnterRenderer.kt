package net.devemperor.dictate.state.render

import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import net.devemperor.dictate.core.AutoEnterIconRenderer
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.layout.resolveRecordLeftIcon
import net.devemperor.dictate.state.layout.resolveRecordRightIcon

/**
 * Side-channel renderer for the `record_btn` compound-drawables.
 *
 * # Why a side-channel and not catalog `iconResolver`?
 *
 * Three structural reasons force a side-channel design:
 *
 *  1. `ButtonSlot.iconResolver` returns a single `@DrawableRes Int?` and
 *     is wired through `SlotRenderer.applySlotToView` onto
 *     `MaterialButton.icon` (left compound only). The auto-enter ↵
 *     indicator in [PipelineUiState.Running] is a dynamic
 *     `BitmapDrawable` (PorterDuff knockout, density-baked via
 *     [AutoEnterIconRenderer]) — it does not fit the `@DrawableRes Int`
 *     contract.
 *  2. The record-button needs **two** compound-drawables (left + right
 *     simultaneously), which the single-`iconResolver` model cannot
 *     express. Extending [net.devemperor.dictate.state.layout.ButtonSlot]
 *     with a `rightIconResolver` would proliferate the field on every
 *     slot in every layout-mode for one consumer.
 *  3. Mixing `view.icon` (MaterialButton-managed) with raw
 *     `setCompoundDrawablesRelativeWithIntrinsicBounds` calls on the
 *     same view confuses MaterialButton's internal icon-state tracking
 *     — the writes silently fight each other.
 *
 * # The Q1-decision implementation (cutover-vol2 §7 Q1)
 *
 * The plan §7 Q1 selected option (b) Side-Channel `AutoEnterRenderer`,
 * analog to [RecordingAnimationController]. This implementation scopes
 * the channel to **all** record-btn compound drawables (left + right),
 * making it the single writer on that axis. Left is driven by
 * [resolveRecordLeftIcon] (mic / send / play / null). Right is split:
 *
 *  - In [PipelineUiState.Running] → the dynamic AutoEnter ↵ via
 *    [AutoEnterIconRenderer.get] (the only place the dynamic drawable
 *    is ever applied).
 *  - Otherwise → [resolveRecordRightIcon] (folder / bluetooth / send-staging /
 *    null).
 *
 * # Why no [setTextColor] handling here?
 *
 * The `hasFailure`-driven red color flip is a separate side-channel
 * (`RecordButtonColorController`), introduced in Phase 5.A of the
 * cutover-vol2 plan together with the `Running.hasFailure` state
 * migration. Until that lands the red color is transiently absent; see
 * the plan's R-C risk row.
 *
 * # Lifecycle
 *
 * One instance per [net.devemperor.dictate.state.render.ImeViewBackend]
 * `attach()` interval; `reset()` on `detach()` so the next attach
 * re-applies (the cache key is otherwise stale across view-recreates).
 * The underlying [AutoEnterIconRenderer] is context-scoped — recreate
 * the wrapper if the host context (theme/density) changes.
 *
 * # Idempotency
 *
 * [onState] is a no-op when the resolved cache key has not changed —
 * matches the [RecordingAnimationController] discipline (B4-VAL F-14).
 *
 * @see RecordingAnimationController — sibling side-channel pattern for
 *   the recording-axis animation/timer/amplitude triplet.
 * @see docs/plans/2026-05-21 - dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md §4 Phase 3 + §7 Q1
 */
class AutoEnterRenderer @JvmOverloads constructor(
    private val recordButton: MaterialButton,
    rendererFactory: () -> AutoEnterIconRenderer = { AutoEnterIconRenderer(recordButton.context) },
) {

    private val iconRenderer = rendererFactory()

    /**
     * Cached last-applied compound-drawable key. Triple of:
     *
     *  - `leftRes` — `@DrawableRes` id or `null`.
     *  - `rightRes` — `@DrawableRes` id or `null` (irrelevant on
     *    Running; the dynamic drawable is keyed via [autoEnterActive]).
     *  - `autoEnterActive` — only meaningful in the `Running` branch;
     *    forced to `false` elsewhere so toggling the pref while not in
     *    a pipeline doesn't trigger spurious renders.
     */
    private data class AppliedKey(
        val leftRes: Int?,
        val rightRes: Int?,
        val running: Boolean,
        val autoEnterActive: Boolean,
    )

    private var lastApplied: AppliedKey? = null

    /**
     * Idempotent reactive entry point. Called by
     * [ImeViewBackend.render] after `applySlotToView` runs for every
     * slot, before the recording-animation forward.
     */
    fun onState(state: DictateUiState) {
        val leftRes = resolveRecordLeftIcon(state)
        val rightRes = resolveRecordRightIcon(state)
        // 2026-07-11 double-arrow fix — recording-wins precedence
        // (ADR-0009). The dynamic ↵ belongs to the button's PIPELINE role
        // only. While any recording is in flight (incl. a secondary
        // recording started during a run, and its Preparing start-window)
        // the layout/text/action axes all render the RECORDING role
        // (LayoutCatalog.forKeyboard: recordingLive outranks
        // isPipelineLive) — keying `running` on `pipeline is Running`
        // alone painted the ↵ bitmap onto the Send-role button: the
        // user-reported "doubled partial symbol behind the record symbol"
        // at secondary-recording start.
        val running = state.pipeline is PipelineUiState.Running &&
            state.recording is net.devemperor.dictate.state.RecordingState.Idle
        val autoEnter = running &&
            (state.pipeline as? PipelineUiState.Running)?.autoEnterActive == true
        val key = AppliedKey(leftRes, rightRes, running, autoEnter)
        if (lastApplied == key) return

        val ctx = recordButton.context
        val leftDrawable = leftRes?.let { ContextCompat.getDrawable(ctx, it) }
        val rightDrawable = if (running) {
            // The dynamic AutoEnter ↵ BitmapDrawable lives here and
            // nowhere else (Q1 SoT for the right slot during Running).
            iconRenderer.get(autoEnter)
        } else {
            rightRes?.let { ContextCompat.getDrawable(ctx, it) }
        }

        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            leftDrawable, null, rightDrawable, null,
        )
        lastApplied = key
        // Cache hygiene — drop the dynamic drawable cache whenever the
        // pipeline is not Running so a Theme / density change picked
        // up between pipeline runs gets a freshly-rendered icon.
        if (!running) iconRenderer.invalidate()
    }

    /**
     * Drop the idempotency cache + invalidate the icon-renderer cache.
     * Call from [ImeViewBackend.detach] so the next `onState` after a
     * re-attach (view recreate / rotation) re-applies unconditionally.
     */
    fun reset() {
        lastApplied = null
        iconRenderer.invalidate()
    }
}
