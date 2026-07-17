@file:JvmName("LayoutTextResolvers")

package net.devemperor.dictate.state.layout

import android.content.Context
import net.devemperor.dictate.R
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.WidgetState
import net.devemperor.dictate.state.currentStepName
import java.util.Locale

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
 *   label, e.g. `"Transkribiert\n2/3 ↵  0:08"`. Receives
 *   `(stepName, completedSteps, totalSteps, autoEnterActive, elapsedMs)`.
 *
 *   **B-D-1 (dictate-pipeline-render-and-state-unification §5.1 +
 *   §9.1 OQ-1 Variante A):** the leading `stepName: String?` slot is
 *   the live `currentStepName` derived from
 *   `PipelineUiState.Running.stepHistory` (Q3 / §3.5). When non-null
 *   and non-empty the formatter renders the step name on a first line
 *   above the counter+timer line — same shape as QWERTZ's
 *   `"$counter$enterIndicator\n$timer"` mini-record-button. When
 *   `null` (between steps / immediately after `StartPipeline` before
 *   the first `StepStarted`) the formatter falls back to the
 *   single-line `"N/M ↵ M:SS"` legacy shape.
 *   §9.2 OQ-2 Variante A: the step name is forwarded 1:1 from the
 *   pipeline runner (`Action.PipelineAction.StepStarted.stepName`) —
 *   no i18n indirection.
 * @property formatPreparingLabel mapper producing the upload-window label,
 *   e.g. `"Sending …"` or `"Sending … ↵"` when the user pre-armed
 *   auto-enter during the Preparing-window. #AE-DEEP2: without this, a
 *   double-tap during the 500ms–2s upload window flipped
 *   `Preparing.autoEnterActive` correctly but produced no visual feedback
 *   — the label stayed plain "Sending …" until the Preparing→Running
 *   transition (often too late to confirm the tap registered).
 * @property overlaySend **Deprecated** — short literal for the standalone
 *   overlay SEND button. Unused since dictate-widget-integration §6.5
 *   Variante 2a merged the overlay RECORD+SEND into a single rich
 *   `overlay_record_btn` whose label is computed by
 *   [resolveOverlayRecordButtonText] using the same keyboard-surface
 *   labels ([send], [sending], [formatPipelineLabel],
 *   [formatPreparingLabel]). Field kept for backwards-compatible
 *   construction calls; safe to drop together with `R.string.overlay_send`
 *   once no external caller passes it explicitly.
 */
data class LayoutStrings(
    val record: String,
    val send: CharSequence,
    val sending: String,
    val dictateButtonText: (effectiveLanguage: String) -> CharSequence,
    val formatStagingLabel: (audioDurationSeconds: Int) -> CharSequence,
    val formatPipelineLabel: (
        stepName: String?,
        completedSteps: Int,
        totalSteps: Int,
        autoEnterActive: Boolean,
        elapsedMs: Long,
    ) -> CharSequence,
    val formatPreparingLabel: (autoEnterActive: Boolean) -> CharSequence,
    /**
     * Label for the overlay record-button when [DictateUiState.widget]
     * is `Visible` and a recording is `Active` — the button morphs into
     * a Pause-Toggle (B3.4 / plan §1.2). Backed by
     * `R.string.dictate_action_pause`.
     */
    val pauseLabel: CharSequence = "Pause",
    /**
     * Label for the overlay record-button when [DictateUiState.widget]
     * is `Visible` and a recording is `Paused` — the button morphs into
     * a Resume-Toggle (B3.4 / plan §1.2). Backed by
     * `R.string.dictate_action_resume`.
     */
    val resumeLabel: CharSequence = "Resume",
    /**
     * Badge appended to the record label while PC send-mode is active
     * (ADR-0019) — backed by `R.string.dictate_pc_badge`.
     *
     * A constant, not a pre-composed label: PC-mode flips at runtime but
     * [LayoutStrings] is built once, so a `"Aufnehmen PC"` baked in here
     * would freeze at whatever the mode was at construction. The *composition*
     * therefore happens in [resolveRecordButtonText], which sees state.
     */
    val pcBadge: String = "PC",
    @Deprecated(
        "Variante 2a (dictate-widget-integration §6.5) merged OVERLAY_SEND " +
            "into OVERLAY_RECORD; this field is no longer read by any " +
            "catalog slot. Remove once external callers stop passing it.",
    )
    val overlaySend: CharSequence = "Send",
) {
    companion object {
        /**
         * Build a [LayoutStrings] from a [Context]'s string resources —
         * the **single source of truth** for the keyboard's static
         * labels and the dynamic label formatters (render-latency-wave2
         * §A2).
         *
         * Both render hosts now share this factory:
         *  - `DictatePipelineService` builds the live catalog's strings
         *    from the service context (post-bind).
         *  - the IME builds a cheap **bootstrap** catalog from the themed
         *    view context to paint the idle keyboard **before** the
         *    service binder arrives (Problem A). Sharing this factory
         *    keeps the two catalogs' string logic byte-identical — there
         *    is no second place where a label could drift.
         *
         * Pure formatting lambdas (staging / pipeline / preparing labels)
         * carry no `Context` capture beyond the resolved base strings, so
         * the returned instance is safe to outlive the `Context` used to
         * build it (it holds only the already-resolved `CharSequence`s).
         */
        @JvmStatic
        fun from(context: Context): LayoutStrings = LayoutStrings(
            record = context.getString(R.string.dictate_record),
            send = context.getString(
                R.string.dictate_send,
                context.getString(R.string.dictate_record),
            ),
            sending = context.getString(R.string.dictate_sending),
            // Just the badge word. `resolveRecordButtonText` decides
            // whether to splice it in, because PC-mode flips at runtime
            // while this factory runs once — see LayoutStrings.pcBadge.
            pcBadge = context.getString(R.string.dictate_pc_badge),
            // F-15 — language-aware idle label. The `"system"` sentinel
            // (boot default before the pref resolves) renders the plain
            // "Record" label; any concrete language code is suffixed.
            dictateButtonText = { effectiveLanguage ->
                if (effectiveLanguage.isEmpty() || effectiveLanguage == "system") {
                    context.getString(R.string.dictate_record)
                } else {
                    "${context.getString(R.string.dictate_record)} ($effectiveLanguage)"
                }
            },
            formatStagingLabel = { audioDurationSeconds ->
                // Locale.US: technical format — keeps digits ASCII
                // regardless of device locale (B4-VAL F-5).
                val minutes = audioDurationSeconds / 60
                val seconds = audioDurationSeconds % 60
                String.format(Locale.US, "Audio %d:%02d · Send", minutes, seconds)
            },
            formatPipelineLabel = { stepName, completedSteps, totalSteps, autoEnterActive, elapsedMs ->
                // Locale.US: ASCII digits regardless of device locale
                // (B4-VAL F-5 / F-13). OQ-1 Variante A two-line layout.
                val seconds = (elapsedMs / 1000L).toInt()
                val mm = seconds / 60
                val ss = seconds % 60
                val arrow = if (autoEnterActive) " ↵" else ""
                val phase = stepName?.takeIf { it.isNotBlank() }
                if (phase != null) {
                    String.format(
                        Locale.US,
                        "%s\n%d/%d%s %d:%02d",
                        phase, completedSteps, totalSteps, arrow, mm, ss,
                    )
                } else {
                    String.format(
                        Locale.US,
                        "%d/%d%s %d:%02d",
                        completedSteps, totalSteps, arrow, mm, ss,
                    )
                }
            },
            formatPreparingLabel = { autoEnterActive ->
                // #AE-DEEP2 — Preparing carries an autoEnter-toggle too;
                // append the same ↵ marker the pipeline formatter uses.
                if (autoEnterActive) {
                    context.getString(R.string.dictate_sending) + " ↵"
                } else {
                    context.getString(R.string.dictate_sending)
                }
            },
            // B3.4 — Pause-Toggle labels, reusing the notification-action
            // strings so localisation stays in one place.
            pauseLabel = context.getString(R.string.dictate_action_pause),
            resumeLabel = context.getString(R.string.dictate_action_resume),
        )
    }
}

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
 *
 * **PC send-mode (ADR-0019):** while `features.windowsAutoSendActive` the
 * record word carries a `PC` badge — `"Aufnehmen PC (de)"` — so the user
 * knows the dictation is bound for the paired PC *before* speaking rather
 * than after the text fails to appear. The badge follows the record word
 * itself, not the trailing language hint, because that is the word it
 * qualifies.
 *
 * `Active`/`Paused` are deliberately left un-badged: they read "Senden",
 * not "Aufnehmen", and during a live recording the button text is replaced
 * wholesale by the amplitude visualizer anyway — that surface carries its
 * own PC badge next to the timer (see `AmplitudeVisualizerDrawable`).
 */
fun resolveRecordButtonText(state: DictateUiState, strings: LayoutStrings): CharSequence =
    when (state.recording) {
        is RecordingState.Active -> strings.send
        is RecordingState.Paused -> strings.send
        is RecordingState.Preparing -> strings.record.withPcBadge(state, strings)
        RecordingState.Idle -> strings.dictateButtonText(state.language.effective)
            .withPcBadge(state, strings)
        // Interrupted (2026-05-22): rendered "as if paused" — the frozen
        // timer overlay carries the elapsed seconds; the button label is
        // the plain record text since a tap continues recording.
        is RecordingState.Interrupted -> strings.record.withPcBadge(state, strings)
    }

/**
 * Insert [LayoutStrings.pcBadge] after the record word when PC send-mode is
 * active, leaving the label untouched otherwise.
 *
 * The receiver may already carry a trailing language hint
 * (`"Aufnehmen (de)"`), and the badge belongs to the verb rather than the
 * hint, so it is spliced in *before* a trailing `(…)` group when one is
 * present and appended otherwise.
 */
private fun CharSequence.withPcBadge(state: DictateUiState, strings: LayoutStrings): CharSequence {
    if (!state.features.windowsAutoSendActive) return this
    val label = toString()
    val hintStart = label.indexOf(" (")
    return if (hintStart >= 0) {
        label.substring(0, hintStart) + " " + strings.pcBadge + label.substring(hintStart)
    } else {
        "$label ${strings.pcBadge}"
    }
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
            // B-D-1 (dictate-pipeline-render-and-state-unification §5.1):
            // forward the live `currentStepName` derived from
            // `Running.stepHistory` so the button label carries the step
            // name on its own line above the N/M ↵ M:SS counter — both
            // backends (Keyboard via SEND_MODE selection, Widget via
            // `resolveOverlayRecordButtonText` composition) reuse this
            // single resolver, so the step name appears in both surfaces.
            strings.formatPipelineLabel(
                pipe.currentStepName,
                pipe.completedSteps,
                pipe.totalSteps,
                pipe.autoEnterActive,
                pipe.elapsedMs,
            )
        else -> strings.record
    }

/**
 * Record-button text for the **overlay-surface** record button (the
 * Variante-2a merged RECORD+SEND slot, dictate-widget-integration §6.5).
 *
 * Composition of the two keyboard-surface resolvers:
 *
 *  - When recording is `Active` / `Paused` → defer to
 *    [resolveRecordButtonText] (the Send label), **even while a pipeline
 *    run processes** — recording-wins precedence (2026-07, ADR-0009), so
 *    the caption matches the Stop&Send action a live recording drives.
 *  - Else when the pipeline is `Preparing` or `Running` → defer to
 *    [resolveRecordButtonTextPipeline] (shows "Sending …", `"N/M ↵ M:SS"`,
 *    same labels as the keyboard `SEND_MODE` layouts).
 *  - Otherwise → defer to [resolveRecordButtonText] (Idle / Preparing —
 *    same labels as the standard keyboard `RECORD` slot).
 *
 * **Why a separate resolver instead of inlining one of the two?** The
 * keyboard surface branches between `TWO_ROW` (uses
 * [resolveRecordButtonText]) and `TWO_ROW_SEND_MODE` (uses
 * [resolveRecordButtonTextPipeline]) at the LayoutMode-selector level —
 * `KeyboardLayoutManager.forKeyboard(state)` picks the mode. The
 * overlay surface has only one mode (`OVERLAY_5BUTTON`, shared between
 * WIDGET and HOVER), so the branching has to happen inside the resolver
 * instead.
 *
 * @see resolveRecordButtonText (keyboard-surface non-pipeline sibling)
 * @see resolveRecordButtonTextPipeline (keyboard-surface pipeline sibling)
 */
fun resolveOverlayRecordButtonText(state: DictateUiState, strings: LayoutStrings): CharSequence {
    // 2026-05-22 — overlay record-btn text mirrors the keyboard-surface
    // record-btn 1:1. The previous B3.4 "morph to Pause / Resume label
    // while widget is visible" rule is gone: the dedicated OVERLAY_PAUSE
    // slot now owns the pause UI, so the record-btn keeps its start/send
    // identity on both surfaces.
    //
    // 2026-07 — recording-wins precedence (matches resolveOverlayRecordAction,
    // parity with LayoutCatalog.forKeyboard): a live recording — including a
    // secondary recording started during a run — shows the Send label rather
    // than the pipeline progress label, so the caption tracks the Stop&Send
    // action instead of the (now-outranked) auto-enter toggle. Only when
    // recording is Idle/Preparing does a live pipeline surface its per-run
    // auto-enter-toggle label ("N/M ↵ M:SS").
    if (state.recording is RecordingState.Active ||
        state.recording is RecordingState.Paused
    ) {
        return resolveRecordButtonText(state, strings)
    }
    return when (state.pipeline) {
        is PipelineUiState.Preparing,
        is PipelineUiState.Running -> resolveRecordButtonTextPipeline(state, strings)
        else -> resolveRecordButtonText(state, strings)
    }
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
