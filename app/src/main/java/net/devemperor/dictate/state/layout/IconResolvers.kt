@file:JvmName("LayoutIconResolvers")

package net.devemperor.dictate.state.layout

import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState

/**
 * Shared `ButtonSlot.iconResolver` helpers consumed by the
 * [LayoutCatalog].
 *
 * Icons resolve to drawable-resource ids (`@DrawableRes Int`) so the
 * catalog stays Android-loose: only `R.drawable.*` references leak, no
 * `Drawable` / `Context` plumbing. The actual `ContextCompat.getDrawable`
 * call lives in `SlotRenderer.applySlotToView` (Spec 2 §5.1, C14).
 *
 * # F-4: shared `resolveAudioFocusIcon` (SSoT)
 *
 * The legacy `MainButtonsController.refreshAudioFocusIcon` (Spec 2 §9.2)
 * AND the `EditBarController` both mapped `audioFocusEnabledPref` to the
 * same icon pair. Centralising in [resolveAudioFocusIcon] eliminates the
 * drift class (`Gap 1` in Spec 2 §13.5.c).
 *
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §8.5
 */

/**
 * Pause-button icon based on `RecordingState`.
 *
 * - `Paused` → mic icon ("tap to resume")
 * - everything else → pause icon
 *
 * The legacy code maintained two `setForeground` call sites for the
 * pause-icon flip; here the single resolver replaces both.
 */
fun resolvePauseIcon(state: DictateUiState): Int = when (state.recording) {
    is RecordingState.Paused -> R.drawable.ic_baseline_mic_24
    else -> R.drawable.ic_baseline_pause_24
}

/**
 * Audio-focus-button icon based on the user-pref toggle.
 *
 * The icon depicts the **effect on other audio sources**, not the toggle
 * state — pressing the button **toggles** AudioFocus:
 *
 * - `true` (focus requested, other audio muted) → `volume_off`
 *   (other audio is muted; pressing the button unmutes / releases focus)
 * - `false` (no focus, other audio plays) → `volume_up`
 *   (other audio is audible; pressing the button mutes / acquires focus)
 *
 * This matches the legacy `MainButtonsController.refreshAudioFocusIcon`
 * semantics (B4-VAL F-3 / Spec 2 §13.5.c). Free function (not a slot
 * resolver wrapper) because `EditBarController` uses it too — F-4 SSoT
 * (Spec 2 §13.5.c / Gap 1).
 */
fun resolveAudioFocusIcon(enabled: Boolean): Int =
    if (enabled) R.drawable.ic_baseline_volume_off_24
    else R.drawable.ic_baseline_volume_up_24

/**
 * Slot-resolver convenience wrapping [resolveAudioFocusIcon] for the
 * AUDIO_FOCUS button slot — reads `state.audio.audioFocusEnabledPref`.
 */
fun resolveAudioFocusIconForSlot(state: DictateUiState): Int =
    resolveAudioFocusIcon(state.audio.audioFocusEnabledPref)

// ────────────────────────────────────────────────────────────────────────
// Record-button compound-drawable resolvers — vol2 Phase 1 (preparation).
//
// Phase 1 of the `2026-05-21 - dictate-render-cutover-completion-vol2`
// plan adds these helpers as Catalog-ready Single-Source-of-Truth
// resolvers for the `record_btn`'s left + right compound drawables.
//
// **Why two resolvers, not one?** The legacy
// `PipelineStepRowRenderer.refreshRecordButtonFromState` /
// `applyRecordButtonForRecording` write both compound-drawable slots
// (left + right) via `setCompoundDrawablesRelativeWithIntrinsicBounds`.
// `ButtonSlot.iconResolver` only models a single drawable
// (`MaterialButton.icon`, left position). The right compound-drawable
// has no Catalog hook yet — Phase 3 of the plan decides whether to
// add a `rightIconResolver` to `ButtonSlot` or to wire each right
// branch (folder / bluetooth / send / auto-enter-↵) as side-channel
// renderers analog to `RecordingAnimationController`. Until then both
// resolvers below are **defined but unwired** so the byte-equivalent
// values are testable.
//
// @see net.devemperor.dictate.state.render.PipelineStepRowRenderer
// @see docs/plans/2026-05-21 - dictate-render-cutover-completion-vol2/dictate-render-cutover-completion-vol2.md §4 Phase 1
// ────────────────────────────────────────────────────────────────────────

/**
 * Left compound-drawable for the `record_btn` slot, byte-equivalent to
 * what `PipelineStepRowRenderer.refreshRecordButtonFromState` /
 * `applyRecordButtonForRecording` write today on the left axis.
 *
 * | `pipeline`          | `recording`          | Left icon                    |
 * |---------------------|----------------------|------------------------------|
 * | `Idle`              | `Idle`               | `ic_baseline_mic_20`         |
 * | `Idle`              | `Active(*)`          | `ic_baseline_send_20`        |
 * | `Idle`              | `Paused(*)`          | `ic_baseline_send_20`        |
 * | `Idle`              | `Preparing(*)`       | `null` (Legacy no-op)        |
 * | `Preparing(...)`    | (any)                | `ic_baseline_send_20`        |
 * | `Running(...)`      | (any)                | `null` (left empty — Auto-Enter side-channel uses right slot) |
 * | `ReprocessStaging`  | (any)                | `ic_baseline_play_arrow_24`  |
 *
 * **Paused branch:** the legacy renderer makes **no mutation** on
 * `RecordingState.Paused` — the view holds whatever Active wrote last
 * (always `ic_baseline_send_20`). The orchestrator state's `Paused`
 * data class carries `useBluetooth` so byte-equivalence is structurally
 * preserved; here we return `send_20` unconditionally because the left
 * slot is `send` for both Active and Paused.
 *
 * **Phase-3-acceptance delta:** the Catalog currently has no
 * `iconResolver` on the four RECORD slots in `LayoutCatalog.kt`, so the
 * left axis is written **only** by the legacy renderer today (the
 * Catalog's `view.icon` write would race the 100 ms legacy tick — same
 * dual-writer bug this plan eliminates). Phase 3 wires this resolver
 * onto the slots in the same atomic commit that no-ops the legacy
 * writer.
 */
fun resolveRecordLeftIcon(state: DictateUiState): Int? = when (state.pipeline) {
    is PipelineUiState.Preparing -> R.drawable.ic_baseline_send_20
    is PipelineUiState.Running -> null
    is PipelineUiState.ReprocessStaging -> R.drawable.ic_baseline_play_arrow_24
    PipelineUiState.Idle -> when (state.recording) {
        RecordingState.Idle -> R.drawable.ic_baseline_mic_20
        is RecordingState.Active -> R.drawable.ic_baseline_send_20
        is RecordingState.Paused -> R.drawable.ic_baseline_send_20
        is RecordingState.Preparing -> null
    }
}

/**
 * Right compound-drawable for the `record_btn` slot, byte-equivalent to
 * what `PipelineStepRowRenderer.refreshRecordButtonFromState` /
 * `applyRecordButtonForRecording` write today on the right axis.
 *
 * | `pipeline`          | `recording`              | Right icon                    |
 * |---------------------|--------------------------|-------------------------------|
 * | `Idle`              | `Idle`                   | `ic_baseline_folder_open_20`  |
 * | `Idle`              | `Active(useBluetooth=t)` | `ic_baseline_bluetooth_20`    |
 * | `Idle`              | `Active(useBluetooth=f)` | `null`                        |
 * | `Idle`              | `Paused(useBluetooth=t)` | `ic_baseline_bluetooth_20`    |
 * | `Idle`              | `Paused(useBluetooth=f)` | `null`                        |
 * | `Idle`              | `Preparing(*)`           | `null` (Legacy no-op)         |
 * | `Preparing(...)`    | (any)                    | `null`                        |
 * | `Running(...)`      | (any)                    | `null` ✱                      |
 * | `ReprocessStaging`  | (any)                    | `ic_baseline_send_24`         |
 *
 * **Paused branch:** the legacy renderer makes no mutation on Paused —
 * the view holds the previous Active write (Bluetooth icon if the
 * Active was BT, null otherwise). Because the orchestrator's
 * `RecordingState.Paused` data class carries `useBluetooth`, this
 * resolver reproduces that residue statelessly with no loss.
 *
 * ✱ **Running branch:** the legacy renderer writes `null, null,
 * autoEnterRenderer.get(active), null` (left+right both null, right
 * gets the dynamic ↵ icon). The dynamic auto-enter drawable is **not**
 * a `@DrawableRes Int` (it's a `BitmapDrawable` with PorterDuff
 * knockout, see Q1 decision §7 in the plan), so it cannot be modelled
 * via this resolver. Phase 3 introduces the `AutoEnterRenderer`
 * side-channel for it; this resolver returns `null` for the right slot
 * in `Running` because the resource-id pipe carries nothing useful.
 *
 * @see resolveRecordLeftIcon for the left axis and the rationale.
 */
fun resolveRecordRightIcon(state: DictateUiState): Int? = when (state.pipeline) {
    is PipelineUiState.Preparing -> null
    is PipelineUiState.Running -> null
    is PipelineUiState.ReprocessStaging -> R.drawable.ic_baseline_send_24
    PipelineUiState.Idle -> when (val rec = state.recording) {
        RecordingState.Idle -> R.drawable.ic_baseline_folder_open_20
        is RecordingState.Active -> if (rec.useBluetooth) R.drawable.ic_baseline_bluetooth_20 else null
        is RecordingState.Paused -> if (rec.useBluetooth) R.drawable.ic_baseline_bluetooth_20 else null
        is RecordingState.Preparing -> null
    }
}
