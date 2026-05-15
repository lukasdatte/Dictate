package net.devemperor.dictate.state.render

import android.view.View
import net.devemperor.dictate.core.ContentArea
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.isActiveOrPaused
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * RenderBackend driving the prompt-area visibility (Spec 2 §4.1 / R.10).
 *
 * The prompts container sits **above** the main buttons and shows one
 * of three faces depending on state:
 *
 *  - **List of rewording prompts** — when rewording is enabled and
 *    nothing is recording / processing.
 *  - **Pipeline progress** (replacing the list) — while a pipeline is
 *    `Running` (NOT `Preparing` — uploads keep the prompt list).
 *  - **Hidden** — when small-mode is on, an EMOJI_PICKER is up, or
 *    rewording is off AND no recording/pipeline activity.
 *
 * # Visibility truth-table (Spec 2 §9.3 / Spec 2 §4.1 R.10 prompt
 * sub-axis carve-out)
 *
 * | smallMode | EMOJI_PICKER | active/staging/pipeline | rewordingEnabled | promptsCl |
 * |-----------|--------------|-------------------------|------------------|-----------|
 * | true      | —            | —                       | —                | GONE      |
 * | false     | true         | —                       | —                | GONE      |
 * | false     | false        | true                    | —                | VISIBLE   |
 * | false     | false        | false                   | true             | VISIBLE   |
 * | false     | false        | false                   | false            | GONE      |
 *
 * `pipelineProgress` replaces the `promptsRv` when
 * `state.pipeline is Running && !isReprocessStaging` (the staging
 * pipeline shows the editable queue, not the progress list).
 *
 * # Why a separate backend (not slot resolvers)?
 *
 * Same rationale as [ContentAreaController]: the prompt-area concerns
 * are container-level decisions on `state.layout.smallMode` +
 * `state.layout.contentArea` + the cross-axis FSMs — too coarse for
 * the per-button [net.devemperor.dictate.state.layout.ButtonSlot] model.
 *
 * # `backendType = null` — consume every mode
 *
 * Like [ContentAreaController], the prompt-area lives outside the
 * layout-mode partition. The manager fans every render-tick out
 * regardless of which surface owns the active mode.
 *
 * @property views the four prompt-area containers; some are nullable
 *   because the IME service can run in configurations where the prompt
 *   row is omitted (legacy single-row variant). `null` references are
 *   treated as "view not present" — skip the visibility write.
 *
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see net.devemperor.dictate.state.render.ContentAreaController
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §4.1
 */
class PromptVisibilityController(
    private val views: PromptVisibilityViews,
) : RenderBackend {

    override val backendType: BackendType? = null

    @Suppress("unused")
    private var onAction: ((Action) -> Unit)? = null

    override fun attach(onAction: (Action) -> Unit) {
        this.onAction = onAction
    }

    override fun detach() {
        onAction = null
    }

    override fun render(state: DictateUiState, mode: LayoutMode) {
        @Suppress("UNUSED_VARIABLE") val _unused = mode

        val layout = state.layout
        val isActive = state.recording.isActiveOrPaused
        val pipeline = state.pipeline
        val isPipelineRunning = pipeline is PipelineUiState.Running
        val isPreparing = pipeline is PipelineUiState.Preparing
        val isStaging = pipeline is PipelineUiState.ReprocessStaging
        val rewordingEnabled = state.features.rewordingEnabled

        // Prompts container visibility — derived from the truth-table
        // in the class KDoc. The `else` branch maps to `rewordingEnabled`
        // so the prompt list stays visible in the rewording-only flow.
        val showPrompts = when {
            layout.smallMode -> false
            layout.contentArea == ContentArea.EMOJI_PICKER -> false
            isActive || isPipelineRunning || isPreparing || isStaging -> true
            else -> rewordingEnabled
        }
        views.promptsContainer?.visibility = if (showPrompts) View.VISIBLE else View.GONE

        // The progress-list replaces the recycler view *during* a
        // Running pipeline (NOT Preparing — upload phase keeps the
        // prompt list visible per Spec 2 §9.3). ReprocessStaging shows
        // the recycler (editable queue), so we explicitly exclude it.
        val showProgress = isPipelineRunning && !isStaging
        views.promptsRecyclerView?.visibility = if (showProgress) View.GONE else View.VISIBLE
        views.pipelineProgressView?.visibility = if (showProgress) View.VISIBLE else View.GONE

        // QWERTZ-side recording controls (small mini-pause + send) are
        // only on screen during active recording inside the QWERTZ
        // content-area — outside of that the controls live inside the
        // main button row already.
        val showQwertzRecControls =
            isActive && !showProgress && layout.contentArea == ContentArea.QWERTZ
        views.qwertzRecordingControls?.visibility =
            if (showQwertzRecControls) View.VISIBLE else View.GONE
    }
}

/**
 * View-holder for [PromptVisibilityController].
 *
 * The four members are nullable because the IME layout may omit
 * individual prompt-area widgets in legacy configurations (the
 * service's `onCreateInputView` decides at inflate-time which IDs
 * resolve). Passing `null` is the controller's "view not present"
 * signal and skips the matching visibility write.
 */
data class PromptVisibilityViews(
    val promptsContainer: View?,
    val promptsRecyclerView: View?,
    val pipelineProgressView: View?,
    val qwertzRecordingControls: View?,
)
