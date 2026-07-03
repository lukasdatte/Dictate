package net.devemperor.dictate.history

import android.os.Bundle

/**
 * Owns the set of expanded pipeline-step cards on the history detail screen
 * (R4 + R5 expand/collapse).
 *
 * # Why this lives outside the adapter and the throwaway step list
 *
 * The detail list is wholesale-rebuilt on every [ActiveJobRegistry] tick — the
 * Activity re-derives a fresh `List<PipelineStep>` and hands it to
 * `submitList`. If expansion were a boolean on the (throwaway) `PipelineStep`
 * objects it would reset on every reload (spec D2 rejected alternative). It is
 * instead keyed by the stable [PipelineStep.StepKey], so expansion survives
 * both registry-tick reloads (DiffUtil rebinds only changed rows) and process
 * death / rotation via [saveTo] / [restoreFrom].
 *
 * Ownership: the Activity creates one instance in `onCreate`, passes it to the
 * adapter, and persists it in `onSaveInstanceState` (R5 keystone).
 *
 * @see docs/research/2026-07-02 - history-ui-overhaul.md §3.3
 */
class StepExpansionState {

    private val expanded: MutableSet<String> = LinkedHashSet()

    /** Flip the expanded/collapsed state of [key]; returns the new expanded flag. */
    fun toggle(key: String): Boolean =
        if (expanded.remove(key)) false else { expanded.add(key); true }

    fun isExpanded(key: String): Boolean = expanded.contains(key)

    /** Write the expanded key set into [outState] under [BUNDLE_KEY]. */
    fun saveTo(outState: Bundle) {
        outState.putStringArrayList(BUNDLE_KEY, ArrayList(expanded))
    }

    /** Restore the expanded key set from [inState]; a null bundle is a no-op. */
    fun restoreFrom(inState: Bundle?) {
        val keys = inState?.getStringArrayList(BUNDLE_KEY) ?: return
        expanded.clear()
        expanded.addAll(keys)
    }

    companion object {
        private const val BUNDLE_KEY = "history_step_expansion_state"
    }
}
