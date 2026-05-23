@file:JvmName("KeyboardVisibilityPredicates")

package net.devemperor.dictate.core

import android.view.View
import net.devemperor.dictate.state.PipelineUiState

/**
 * Single source of truth for the resend-button visibility predicate.
 *
 * Block 1a (Quick-Wins in today's code, no module-architecture yet).
 *
 * # Why this file exists
 *
 * Before this refactor, six call sites mutated `resendButton.visibility`
 * imperatively — each with a subtly different gating expression:
 *
 * - (legacy) the recording-UI controller idle branch — VISIBLE iff last
 *   audio exists (the lambda gating Pref.ResendButton lived in the IME
 *   service). That controller was retired in CR-DEL; the resend
 *   visibility is now the RESEND-slot `predResendVisible` predicate.
 * - (legacy) the recording-UI controller active branch — unconditional
 *   GONE while recording is active.
 * - `DictateInputMethodService.onStartInputView` (Idle branch) — VISIBLE iff
 *   audio exists AND `Pref.ResendButton` is on.
 * - `DictateInputMethodService.runTranscriptionViaOrchestrator` —
 *   unconditional GONE at pipeline start.
 * - `DictateInputMethodService.onShowResend` — unconditional VISIBLE at
 *   pipeline completion.
 *
 * Each site re-derived the answer from a different combination of inputs.
 * Adding a new input (recording-paused vs idle, ReprocessStaging, etc.)
 * meant editing five branches and getting all of them right.
 *
 * [isResendVisible] consolidates the rule into a pure function so all
 * five "compute the answer from current state" sites read the same
 * expression. The unconditional VISIBLE in `onShowResend` is kept as an
 * explicit call site for now because the pipeline-state has not yet
 * transitioned back to Idle when the callback fires — see the call site
 * for the timing note. Block 5 (LayoutCatalog) collapses the 4-arg
 * signature into the single-state-arg form `(DictateUiState) -> Boolean`
 * per Spec 2 §3.2; the truth-table body — same 4 axes ANDed in same
 * order — is preserved.
 *
 * # Future shape
 *
 * In Block 5 the function body is identical; only the inputs swap from
 * scattered sources (cache-dir File-check, SharedPreferences,
 * RecordingStateController.state, the legacy pipeline-UI state) to
 * sub-state reads off the global `DictateUiState`. The signature is
 * intentionally written to mirror that future shape so the migration is a
 * rename, not a rewrite.
 *
 * Working title in the plan: `predResendVisible`. The implementation
 * settled on the codebase `isXxx` convention for booleans.
 *
 * @see `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` §9.4
 * @see `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md` §4 Block 1a
 */

/**
 * Returns `true` when the resend button should be visible to the user.
 *
 * Truth table (all four inputs must hold simultaneously):
 *
 * | Input                | Required value                  |
 * |----------------------|---------------------------------|
 * | [lastAudioFileExists]| `true` (cache contains last m4a)|
 * | [resendEnabled]      | `true` (`Pref.ResendButton`)    |
 * | [recordingState]     | [RecordingState.Idle]           |
 * | [pipelineState]      | [PipelineUiState.Idle]          |
 *
 * Recording-Active/Paused/Preparing or any non-Idle pipeline-state
 * (Preparing / Running / ReprocessStaging) returns `false` — the resend
 * button must not compete with the live recording or pipeline UI.
 *
 * Pure function: identical inputs always yield identical output. Safe to
 * call from any thread; no Android dependency in the predicate body
 * itself (the [View.VISIBLE] / [View.GONE] translation lives in
 * [resolveResendVisibility]).
 *
 * @see net.devemperor.dictate.state.layout.isResendVisible — the
 *   single-state-argument B4 replacement (Spec 2 §3.2).
 */
@Deprecated(
    "Use net.devemperor.dictate.state.layout.isResendVisible(state) — the " +
        "legacy four-arg form is scheduled for removal once the remaining " +
        "DictateInputMethodService consumers migrate (D-13 / B7 follow-up).",
    level = DeprecationLevel.WARNING,
)
fun isResendVisible(
    lastAudioFileExists: Boolean,
    resendEnabled: Boolean,
    recordingState: RecordingState,
    pipelineState: PipelineUiState
): Boolean =
    lastAudioFileExists &&
        resendEnabled &&
        recordingState is RecordingState.Idle &&
        pipelineState is PipelineUiState.Idle

/**
 * View-level helper: translates [isResendVisible] into [View.VISIBLE] /
 * [View.GONE]. Call sites that previously did
 * `resendButton.visibility = if (...) VISIBLE else GONE` collapse into
 * `resendButton.visibility = resolveResendVisibility(...)`.
 */
@Suppress("DEPRECATION") // legacy four-arg form — see isResendVisible KDoc.
fun resolveResendVisibility(
    lastAudioFileExists: Boolean,
    resendEnabled: Boolean,
    recordingState: RecordingState,
    pipelineState: PipelineUiState
): Int =
    if (isResendVisible(lastAudioFileExists, resendEnabled, recordingState, pipelineState)) {
        View.VISIBLE
    } else {
        View.GONE
    }
