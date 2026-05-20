@file:JvmName("LayoutTextResolvers")

package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState

/**
 * Shared `ButtonSlot.textResolver` helpers consumed by the
 * [LayoutCatalog].
 *
 * # Why a `LayoutStrings` indirection?
 *
 * The text resolvers depend on Android string resources (`R.string.dictate_send`
 * etc.) which require a `Context` — embedding that directly into the
 * catalog would force every JVM unit test to spin up Robolectric (K-4
 * violation). [LayoutStrings] inverts the dependency: the catalog
 * receives the resolved strings + the language-label provider at
 * construction-time and stays pure data afterwards.
 *
 * Production wiring builds a `LayoutStrings` from the IME service's
 * `Context.getString(...)` calls in C14. Unit tests construct one with
 * fixed literals.
 *
 * @see net.devemperor.dictate.state.layout.LayoutCatalog
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/2-keyboard-layout/2-keyboard-layout.reviewed.md §8.5
 */

/**
 * Pre-resolved Android string resources + dynamic label providers used by
 * the layout-mode text resolvers.
 *
 * **Why pre-resolved (and not raw `Context`)?** Decoupling from
 * `android.content.Context` is what lets the catalog and its consumers
 * run under plain JVM unit tests. The IME service builds an instance once
 * per `onCreateInputView` (Context lifecycle bound to View inflation)
 * and hands it to the renderer.
 *
 * @property record literal for `R.string.dictate_record` ("Record").
 * @property send literal for `R.string.dictate_send` formatted with the
 *   current language label — supplied as a complete `CharSequence` by the
 *   caller; the slot just consumes it.
 * @property sending literal for `R.string.dictate_sending` ("Sending …").
 * @property dictateButtonText label provider for the idle record-button
 *   text (e.g. "Dictate (en)"). **F-15 (Epic §4 Block A2):** receives
 *   the effective language code (`DictateUiState.language.effective`,
 *   owned by `LanguageModule`) so the label reflects the current
 *   language without the resolver reading a static string or any legacy
 *   controller. Read-only: this consumes `LanguageState`, it does not
 *   write it. **D-13 closed (Epic §4 Block C1):** the legacy
 *   effective-language writer is now deleted; `LanguageState.effective`
 *   is fed by the IME's payload-bearing `LanguageAction.RefreshFromPref`
 *   dispatch (resolved from prefs via
 *   `preferences.LanguageResolver`), so this read now reflects a live
 *   value instead of the boot `"system"` sentinel. Called lazily per
 *   render-tick with the live effective-language value.
 * @property formatStagingLabel mapper producing the
 *   `"Audio 0:23 · Send"` label for the reprocess-staging record button.
 *   Receives the staging audio duration in seconds.
 * @property formatPipelineLabel mapper producing the running-pipeline
 *   label, e.g. `"2/3 ↵  0:08"`. Receives `(completedSteps, totalSteps,
 *   autoEnterActive, elapsedMs)`.
 * @property formatPreparingLabel mapper producing the upload-window label,
 *   e.g. `"Sending …"` or `"Sending … ↵"` when the user pre-armed
 *   auto-enter during the Preparing-window. #AE-DEEP2: without this, a
 *   double-tap during the 500ms–2s upload window flipped
 *   `Preparing.autoEnterActive` correctly but produced no visual feedback
 *   — the label stayed plain "Sending …" until the Preparing→Running
 *   transition (often too late to confirm the tap registered).
 * @property overlaySend short literal for the overlay-surface SEND button
 *   (`R.string.overlay_send` — "Send"). Distinct from [send] because the
 *   keyboard-surface label is language-suffixed
 *   (`R.string.dictate_send` = "Send (en)") while the overlay surface is
 *   space-constrained and uses the icon for language hinting.
 */
data class LayoutStrings(
    val record: String,
    val send: CharSequence,
    val sending: String,
    val dictateButtonText: (effectiveLanguage: String) -> CharSequence,
    val formatStagingLabel: (audioDurationSeconds: Int) -> CharSequence,
    val formatPipelineLabel: (
        completedSteps: Int,
        totalSteps: Int,
        autoEnterActive: Boolean,
        elapsedMs: Long,
    ) -> CharSequence,
    val formatPreparingLabel: (autoEnterActive: Boolean) -> CharSequence,
    val overlaySend: CharSequence = "Send",
)

/**
 * Record-button text in standard (non-SEND-MODE) keyboard layouts.
 *
 * | RecordingState  | Text                                            |
 * |-----------------|-------------------------------------------------|
 * | `Active`        | [LayoutStrings.send]                            |
 * | `Paused`        | [LayoutStrings.send]                            |
 * | `Preparing`     | [LayoutStrings.record]                          |
 * | `Idle`          | [LayoutStrings.dictateButtonText] (effective)   |
 *
 * **F-15 (Epic §4 Block A2):** in the `Idle` branch the label is
 * resolved against `state.language.effective` (the `LanguageModule`
 * effective-language axis) so it differs across languages — e.g.
 * `"Dictate (en)"` vs `"Dictate (de)"`. Read-only consumption of
 * `LanguageState`; no legacy writer is introduced (D-13 scope).
 */
fun resolveRecordButtonText(state: DictateUiState, strings: LayoutStrings): CharSequence =
    when (state.recording) {
        is RecordingState.Active -> strings.send
        is RecordingState.Paused -> strings.send
        is RecordingState.Preparing -> strings.record
        RecordingState.Idle -> strings.dictateButtonText(state.language.effective)
    }

/**
 * Record-button text while the pipeline is live (SEND_MODE).
 *
 * - `Preparing` → "Sending …"
 * - `Running` → "[counter]↵ [timer]" via [LayoutStrings.formatPipelineLabel]
 * - otherwise → "Record" (defensive — visibility hides this label)
 *
 * **F-13 (2026-05-15):** the live `completedSteps` / `totalSteps` /
 * `elapsedMs` are now read off [PipelineUiState.Running] (Epic §4 Block
 * A1, AC-4). The earlier hard-coded `0, 0, …, 0L` placeholders are gone;
 * the reducer maintains these counters
 * ([net.devemperor.dictate.state.modules.PipelineModule]).
 */
fun resolveRecordButtonTextPipeline(state: DictateUiState, strings: LayoutStrings): CharSequence =
    when (val pipe = state.pipeline) {
        // #AE-DEEP2: read Preparing.autoEnterActive so a double-tap during
        // the upload window gets immediate visual confirmation. Previously
        // this arm returned a flag-blind `strings.sending`, which made the
        // tap feel like it had no effect.
        is PipelineUiState.Preparing -> strings.formatPreparingLabel(pipe.autoEnterActive)
        is PipelineUiState.Running ->
            strings.formatPipelineLabel(
                pipe.completedSteps,
                pipe.totalSteps,
                pipe.autoEnterActive,
                pipe.elapsedMs,
            )
        else -> strings.record
    }

/**
 * Record-button text in `KEYBOARD_REPROCESS_STAGING` — "Audio 0:23 · Send".
 *
 * The duration field will come from the `ReprocessStaging` sub-state once
 * Spec 1 §3 adds it; for now the resolver passes `0` and the formatter
 * decides how to render that (typically `"Audio 0:00 · Send"`).
 */
fun resolveRecordButtonTextStaging(state: DictateUiState, strings: LayoutStrings): CharSequence {
    // The cast is a defensive gate: if the state is not in ReprocessStaging the
    // visibility predicate above should have hidden the button — but a stale
    // render-tick could still hit this resolver, so we short-circuit to an
    // empty label instead of asserting (`error(...)` would crash the IME).
    state.pipeline as? PipelineUiState.ReprocessStaging ?: return ""
    return strings.formatStagingLabel(0)
}
