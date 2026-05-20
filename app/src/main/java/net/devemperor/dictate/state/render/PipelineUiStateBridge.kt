@file:JvmName("PipelineUiStateBridge")

package net.devemperor.dictate.state.render

import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.core.PipelineUiState as CorePipelineUiState
import net.devemperor.dictate.state.PipelineUiState as StatePipelineUiState

/**
 * Bidirectional mapping between the two parallel `PipelineUiState` sealed
 * classes that the codebase carries during the cutover:
 *
 * - **Legacy** `net.devemperor.dictate.core.PipelineUiState` — owned by
 *   [PipelineStepRowRenderer]. Drives the legacy 100 ms-tick writer on
 *   `record_btn.text`.
 * - **Orchestrator** `net.devemperor.dictate.state.PipelineUiState` — owned
 *   by `DictateUiStateStore` (`PipelineModule.reduce`). Drives the
 *   Catalog/SlotRenderer.
 *
 * # Why this bridge exists (Phase 2 of cutover-vol2)
 *
 * The bridge is a **transitional adapter** for Phase 2 of
 * `2026-05-21 - dictate-render-cutover-completion-vol2`. It lets the
 * IME-Java + the renderer reduce the cutover surface area by routing
 * pipeline-state reads through a single, typed accessor — without
 * having to switch every call site to the orchestrator type in one
 * commit. Phase 5 deletes the legacy sealed class, this bridge, and
 * the renderer's `state` property; nothing here is meant to survive
 * the cutover.
 *
 * # The asymmetry — lossy in both directions
 *
 * The two sealed classes carry **different field sets**. The bridge
 * documents every field that is not faithfully roundtrippable, so a
 * reader can tell at a glance which information is bridge-state vs.
 * source-of-truth state.
 *
 * **Orchestrator → Legacy:** the legacy `Running` carries
 * `currentStepName` and `hasFailure`. As of Phase 5.A the orchestrator
 * `Running` has both — `hasFailure` as a direct field, `currentStepName`
 * as the derived extension property
 * `Running.currentStepName: String?`. However the bridge **does not
 * propagate them** — it sets `currentStepName = ""` and
 * `hasFailure = false` regardless. That is intentional and harmless
 * today because `syncFromOrchestrator` is **not yet called from
 * production code**; Phase 5.B replaces both the bridge and the
 * legacy state with a direct StateFlow subscription
 * (`stepHistory`-diff renderer), at which point this lossy mapping
 * is removed entirely. The legacy `ReprocessStaging` carries
 * audio-duration, queue, language, model, isStarting — orchestrator
 * only has `(sessionId, transcript)`. The bridge defaults these to
 * neutral values; the IME hydrates them through their dedicated
 * callbacks (the same path that exists today).
 *
 * **Legacy → Orchestrator:** the orchestrator state carries `sessionId`
 * (on `Preparing` + `Running`) + `target` (on `Running`); the legacy
 * state carries neither. The bridge fills in `sessionId = ""` and
 * `target = InsertionTarget.INPUT_CONNECTION` as placeholder defaults.
 * Real values still flow via the dedicated `pipelineBinder.dispatch`
 * paths.
 *
 * # Roundtrip identity claim
 *
 * For any `state` value in either world, `to(then-back)` preserves the
 * **branch type** (`Idle` → `Idle`, `Preparing` → `Preparing`, etc.) and
 * every field that **both** sealed-class variants carry. The fields
 * that exist on only one side are reset to the documented defaults on
 * the roundtrip — see `PipelineUiStateBridgeTest` for the exhaustive
 * roundtrip matrix.
 *
 * @see net.devemperor.dictate.state.render.PipelineStepRowRenderer.syncFromOrchestrator
 * @see docs/plans/2026-05-21 - dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md §4 Phase 2
 */

/**
 * Project an orchestrator pipeline state into the legacy renderer's
 * sealed class. See the file-level KDoc for the documented losses
 * around `currentStepName`, `hasFailure`, and the `ReprocessStaging`
 * detail fields.
 */
fun StatePipelineUiState.toCoreLegacy(): CorePipelineUiState = when (this) {
    is StatePipelineUiState.Idle -> CorePipelineUiState.Idle
    is StatePipelineUiState.Preparing -> CorePipelineUiState.Preparing
    is StatePipelineUiState.Running -> CorePipelineUiState.Running(
        totalSteps = totalSteps,
        completedSteps = completedSteps,
        currentStepName = "",
        autoEnterActive = autoEnterActive,
        hasFailure = false,
    )
    is StatePipelineUiState.ReprocessStaging -> CorePipelineUiState.ReprocessStaging(
        targetSessionId = sessionId,
        audioDurationSeconds = 0L,
        editableQueue = emptyList(),
        selectedLanguage = null,
    )
}

/**
 * Project a legacy renderer pipeline state into the orchestrator
 * sealed class. See the file-level KDoc for the documented losses
 * around `sessionId` and `target`.
 */
fun CorePipelineUiState.toOrchestrator(): StatePipelineUiState = when (this) {
    is CorePipelineUiState.Idle -> StatePipelineUiState.Idle
    is CorePipelineUiState.Preparing -> StatePipelineUiState.Preparing(
        sessionId = "",
        autoEnterActive = false,
    )
    is CorePipelineUiState.Running -> StatePipelineUiState.Running(
        sessionId = "",
        target = InsertionTarget.INPUT_CONNECTION,
        autoEnterActive = autoEnterActive,
        completedSteps = completedSteps,
        totalSteps = totalSteps,
    )
    is CorePipelineUiState.ReprocessStaging -> StatePipelineUiState.ReprocessStaging(
        sessionId = targetSessionId,
        transcript = "",
    )
}
