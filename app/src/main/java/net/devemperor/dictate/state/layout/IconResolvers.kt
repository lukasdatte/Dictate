@file:JvmName("LayoutIconResolvers")

package net.devemperor.dictate.state.layout

import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
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
 * - `true` (focus requested) → volume_up
 * - `false` → volume_off
 *
 * Free function (not a slot resolver wrapper) because the `EditBarController`
 * uses it too — F-4 SSoT (Spec 2 §13.5.c / Gap 1).
 */
fun resolveAudioFocusIcon(enabled: Boolean): Int =
    if (enabled) R.drawable.ic_baseline_volume_up_24
    else R.drawable.ic_baseline_volume_off_24

/**
 * Slot-resolver convenience wrapping [resolveAudioFocusIcon] for the
 * AUDIO_FOCUS button slot — reads `state.audio.audioFocusEnabledPref`.
 */
fun resolveAudioFocusIconForSlot(state: DictateUiState): Int =
    resolveAudioFocusIcon(state.audio.audioFocusEnabledPref)
