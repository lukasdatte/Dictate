package net.devemperor.dictate.state.render

import android.view.View
import net.devemperor.dictate.core.ContentArea
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.imeCollapsedToStrip
import net.devemperor.dictate.state.isActiveOrPaused
import net.devemperor.dictate.state.layout.BackendType
import net.devemperor.dictate.state.layout.LayoutMode
import net.devemperor.dictate.state.layout.RenderBackend

/**
 * RenderBackend driving the prompt-area visibility (Spec 2 §4.1 / R.10).
 *
 * # Wiring status (post-CR-DEL — sole live owner)
 *
 * **Sole live owner of the prompt-area visibility axis.** Attached via
 * `KeyboardLayoutManager.attachBackend` (CR3), armed in CR4. Now that
 * `KeyboardStateManager` is **deleted** (CR-DEL completed the D-13
 * migration), this controller is the **only** writer of the
 * prompts/pipeline-progress/recording-controls visibility axis — there
 * is no parallel KSM drive left. The earlier "not yet attached / KSM
 * still owns it / D-13 follow-up" framing is historical.
 * `PromptVisibilityControllerTest` covers the contract.
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
 * | collapsedToStrip | smallMode | EMOJI_PICKER | active/staging/pipeline | rewordingEnabled | promptsCl |
 * |------------------|-----------|--------------|-------------------------|------------------|-----------|
 * | true             | —         | —            | —                       | —                | GONE      |
 * | false            | true      | —            | —                       | —                | GONE      |
 * | false            | false     | true         | —                       | —                | GONE      |
 * | false            | false     | false        | true                    | —                | VISIBLE   |
 * | false            | false     | false        | false                   | true             | VISIBLE   |
 * | false            | false     | false        | false                   | false            | GONE      |
 *
 * `collapsedToStrip` = [net.devemperor.dictate.state.imeCollapsedToStrip]
 * (user holds the floating widget open → the IME is a 2dp strip).
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
 * # CR3 staged-safety-net (render-path-cutover.md §6 RR-2)
 *
 * Attached in CR3 but [gate]d **dormant** until CR4: while the legacy
 * `KeyboardStateManager.applyPromptsVisibility` still drove this axis
 * (pre-CR-DEL), a real write here would have double-written (silent
 * flicker — RR-2). Dormant → report the intended write to the audit
 * ledger (the dormant-phase single-live-writer proof, Spec 2 §10), do
 * not touch the view. CR4 [arm]ed the gate in the same chunk it
 * removed the legacy drive; CR-DEL then deleted `KeyboardStateManager`
 * (this controller is now the sole owner — see "Wiring status" above).
 * `null` gate = legacy always-write (unit-test contract). Same pattern
 * as [ContentAreaController].
 *
 * @property views the four prompt-area containers; some are nullable
 *   because the IME service can run in configurations where the prompt
 *   row is omitted (legacy single-row variant). `null` references are
 *   treated as "view not present" — skip the visibility write.
 * @property gate the dormant/armed staged-safety-net switch (RR-2).
 *   `null` = always write (legacy contract / unit tests).
 *
 * @see net.devemperor.dictate.state.layout.RenderBackend
 * @see net.devemperor.dictate.state.render.ContentAreaController
 * @see net.devemperor.dictate.state.render.RenderGate
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §4.1
 */
class PromptVisibilityController(
    private val views: PromptVisibilityViews,
    private val gate: RenderGate? = null,
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

    override fun render(state: DictateUiState, @Suppress("UNUSED_PARAMETER") mode: LayoutMode) {
        val layout = state.layout
        val isActive = state.recording.isActiveOrPaused
        val pipeline = state.pipeline
        val isPipelineRunning = pipeline is PipelineUiState.Running
        val isPreparing = pipeline is PipelineUiState.Preparing
        val isStaging = pipeline is PipelineUiState.ReprocessStaging
        val rewordingEnabled = state.features.rewordingEnabled

        // 2026-05-22 — InfoBar mutex (the "enters in Block F" TODO from
        // InfoBarRenderer's KDoc that was never implemented). When the
        // state-derived info-bar has at least one item, `infobar_cl`
        // renders at the top of the keyboard view; without this mutex
        // `prompts_keyboard_cl` stayed VISIBLE underneath and (because
        // it is declared later in the XML → higher Z-order) painted
        // over the info-bar — the user could neither read nor tap it.
        // Hiding the prompts container while the info-bar is up gives
        // the info-bar the surface to itself. InfoBarSelector.select is
        // a pure (DictateUiState) -> List function, so calling it here
        // keeps PromptVisibilityController the single owner of
        // `prompts_keyboard_cl`'s visibility (no two-controller race).
        // 2026-07-02 (ADR-0006 completion) — pipeline errors + the
        // Update/Rate/Donate hints now flow through the same selector,
        // so this mutex covers them by construction.
        val infoBarActive = net.devemperor.dictate.state.infobar.InfoBarSelector
            .select(state).isNotEmpty()

        // Prompts container visibility — derived from the truth-table
        // in the class KDoc. The `else` branch maps to `rewordingEnabled`
        // so the prompt list stays visible in the rewording-only flow.
        val showPrompts = when {
            // Must be the FIRST arm: the "recording/pipeline is live" arm
            // below would otherwise force the 72dp pill row on screen next
            // to the 2dp strip — and dictating with the keyboard collapsed
            // is the widget's whole point, so that arm fires precisely when
            // the strip is up. (`3c47cba` collapsed the four containers
            // ContentAreaController owns and promised the prompt row in its
            // message, but never touched this file.)
            state.imeCollapsedToStrip -> false
            infoBarActive -> false
            layout.smallMode -> false
            layout.contentArea == ContentArea.EMOJI_PICKER -> false
            isActive || isPipelineRunning || isPreparing || isStaging -> true
            else -> rewordingEnabled
        }
        writeVisibility(views.promptsContainer, if (showPrompts) View.VISIBLE else View.GONE)

        // The progress-list replaces the recycler view *during* a
        // Running pipeline (NOT Preparing — upload phase keeps the
        // prompt list visible per Spec 2 §9.3). ReprocessStaging shows
        // the recycler (editable queue), so we explicitly exclude it.
        val showProgress = isPipelineRunning && !isStaging
        writeVisibility(views.promptsRecyclerView, if (showProgress) View.GONE else View.VISIBLE)
        writeVisibility(views.pipelineProgressView, if (showProgress) View.VISIBLE else View.GONE)

        // QWERTZ-side recording controls (small mini-pause + send) are
        // only on screen during active recording inside the QWERTZ
        // content-area — outside of that the controls live inside the
        // main button row already.
        val showQwertzRecControls =
            isActive && !showProgress && layout.contentArea == ContentArea.QWERTZ
        writeVisibility(
            views.qwertzRecordingControls,
            if (showQwertzRecControls) View.VISIBLE else View.GONE,
        )
    }

    /**
     * Route every visibility write through the [gate] (RR-2), skipping
     * `null` views (the "view not present" contract). Dormant → ledger
     * report only; armed/absent → real mutation. Mirrors
     * [ContentAreaController.writeVisibility].
     */
    private fun writeVisibility(view: View?, target: Int) {
        if (view == null) return
        if (gate == null) {
            view.visibility = target
            return
        }
        if (gate.shouldWrite(view.id, target)) {
            view.visibility = target
        }
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
