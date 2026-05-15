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
 *   text (e.g. "Dictate (en)" — picks up the current language). Called
 *   lazily per render-tick.
 * @property formatStagingLabel mapper producing the
 *   `"Audio 0:23 · Send"` label for the reprocess-staging record button.
 *   Receives the staging audio duration in seconds.
 * @property formatPipelineLabel mapper producing the running-pipeline
 *   label, e.g. `"2/3 ↵  0:08"`. Receives `(completedSteps, totalSteps,
 *   autoEnterActive, elapsedMs)`.
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
    val dictateButtonText: () -> CharSequence,
    val formatStagingLabel: (audioDurationSeconds: Int) -> CharSequence,
    val formatPipelineLabel: (
        completedSteps: Int,
        totalSteps: Int,
        autoEnterActive: Boolean,
        elapsedMs: Long,
    ) -> CharSequence,
    val overlaySend: CharSequence = "Send",
)

/**
 * Record-button text in standard (non-SEND-MODE) keyboard layouts.
 *
 * | RecordingState  | Text                                  |
 * |-----------------|---------------------------------------|
 * | `Active`        | [LayoutStrings.send]                  |
 * | `Paused`        | [LayoutStrings.send]                  |
 * | `Preparing`     | [LayoutStrings.record]                |
 * | `Idle`          | [LayoutStrings.dictateButtonText] ()  |
 */
fun resolveRecordButtonText(state: DictateUiState, strings: LayoutStrings): CharSequence =
    when (state.recording) {
        is RecordingState.Active -> strings.send
        is RecordingState.Paused -> strings.send
        is RecordingState.Preparing -> strings.record
        RecordingState.Idle -> strings.dictateButtonText()
    }

/**
 * Record-button text while the pipeline is live (SEND_MODE).
 *
 * - `Preparing` → "Sending …"
 * - `Running` → "[counter]↵ [timer]" via [LayoutStrings.formatPipelineLabel]
 * - otherwise → "Record" (defensive — visibility hides this label)
 *
 * Note: the [PipelineUiState.Running] data class doesn't yet carry
 * `completedSteps`, `totalSteps`, `elapsedMs` — those fields will land in
 * the next chunk's pipeline-state extension. For now, the resolver
 * picks default `0`s; C14 wires the live values once the state shape
 * settles.
 */
fun resolveRecordButtonTextPipeline(state: DictateUiState, strings: LayoutStrings): CharSequence =
    when (val pipe = state.pipeline) {
        is PipelineUiState.Preparing -> strings.sending
        is PipelineUiState.Running ->
            strings.formatPipelineLabel(0, 0, pipe.autoEnterActive, 0L)
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
