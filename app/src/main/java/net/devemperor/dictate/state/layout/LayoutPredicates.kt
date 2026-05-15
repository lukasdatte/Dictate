@file:JvmName("LayoutPredicates")

package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.isActiveOrPaused

/**
 * Shared `ButtonSlot.visibilityPredicate` helpers consumed by the
 * [LayoutCatalog].
 *
 * # Why central predicates?
 *
 * The legacy code re-derived the same boolean ("is the resend button
 * visible?") from a different combination of inputs in five call sites
 * (Spec 2 §1.1 bug #3b). Concentrating the truth-table here eliminates the
 * drift class: a new pipeline-state, a new resend-toggle, or a new sealed
 * member adds **one** branch here and every consuming slot picks it up
 * automatically.
 *
 * # `predResendVisible` and the cooldown rule (Spec 2 §8.5)
 *
 * [isResendVisible] does **NOT** include `state.resend.resendCooldown` —
 * the cooldown is enforced via [ButtonSlot.enabledResolver] (`{ !state.resend.resendCooldown }`)
 * plus an `alphaResolver` that fades to `0.4f`. Mixing the cooldown into
 * the visibility predicate is forbidden pattern (j), Spec 2 §4.0.1.5 —
 * it reactivates bug #3b (resend-btn disappears mid-cooldown when the
 * user toggles Two-Row ↔ Single-Row).
 *
 * # JVM-pure
 *
 * Predicates take only the immutable [DictateUiState] snapshot, no
 * Android references — they unit-test under JVM with handwritten
 * `DictateUiState` instances (K-1/K-4).
 *
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §8.5
 */

/**
 * Returns `true` when the resend button should be visible to the user.
 *
 * Truth table (all four inputs must hold simultaneously):
 *
 * | Input                              | Required value                  |
 * |------------------------------------|---------------------------------|
 * | `state.resend.lastAudioExists`     | `true`                          |
 * | `state.resend.resendEnabled`       | `true` (`Pref.ResendButton`)    |
 * | `state.recording`                  | [RecordingState.Idle]           |
 * | `state.pipeline`                   | [PipelineUiState.Idle]          |
 *
 * `state.resend.resendCooldown` is **NOT** read here — see file KDoc.
 */
fun isResendVisible(state: DictateUiState): Boolean =
    state.resend.lastAudioExists &&
        state.resend.resendEnabled &&
        state.recording is RecordingState.Idle &&
        state.pipeline is PipelineUiState.Idle

/**
 * Trash button visibility in standard (non-SEND-MODE) keyboard layouts.
 *
 * Visible when recording is `Active`/`Paused`, OR the user is editing the
 * reprocess queue (ReprocessStaging) — both cases want a "cancel"
 * affordance.
 *
 * **NOT consumed in `KEYBOARD_*_SEND_MODE`** — those layouts hardcode
 * `{ false }` to structurally prevent bug #3a (trash button covering the
 * send button during pipeline). See Spec 2 §8.3 architecture note.
 */
fun isTrashVisible(state: DictateUiState): Boolean =
    state.recording.isActiveOrPaused ||
        state.pipeline is PipelineUiState.ReprocessStaging

/**
 * Pause button visibility in standard (non-SEND-MODE) keyboard layouts.
 *
 * Same truth-table as [isTrashVisible] — both buttons co-appear /
 * co-disappear in the recording/staging affordance group. They are kept
 * as separate functions for callsite-readability (`predPauseVisible` vs
 * `predTrashVisible`) and so that a future divergence (e.g. pause hidden
 * in Paused-but-trash-still-shown) is a one-function edit.
 *
 * **NOT consumed in `KEYBOARD_*_SEND_MODE`** — hardcoded `{ false }`
 * there for the same bug #3a reason.
 */
fun isPauseVisible(state: DictateUiState): Boolean =
    state.recording.isActiveOrPaused ||
        state.pipeline is PipelineUiState.ReprocessStaging

/**
 * Widget-toggle visibility — predicate-form retained as a stand-alone helper.
 *
 * # Status (B4-VAL F-18)
 *
 * The catalog's KEYBOARD_TWO_ROW / KEYBOARD_SINGLE_ROW slots use
 * `visibilityPredicate = { true }` because gating happens one layer up:
 * `LayoutCatalog.forKeyboard(state)` is only entered when
 * `state.viewMode == ViewMode.KEYBOARD`. SEND_MODE + REPROCESS_STAGING
 * slots hardcode `{ false }` (Spec 2 §8.3 Phase-B S-6 — user mustn't
 * tear down the running pipeline mid-flight).
 *
 * This function is kept as a reusable helper for code that needs to ask
 * the question outside the catalog flow — e.g. unit tests asserting the
 * truth-table, or future container-side decisions that don't go through
 * the catalog at all. Direct catalog use is no longer required.
 */
fun isWidgetToggleVisible(state: DictateUiState): Boolean =
    state.viewMode == net.devemperor.dictate.state.ViewMode.KEYBOARD
