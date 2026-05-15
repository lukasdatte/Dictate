package net.devemperor.dictate.core

/**
 * Narrow read/observe surface of [KeyboardUiController] for the
 * ReprocessStaging language-override carrier.
 *
 * Pulled out as a separate interface so consumers do not import the
 * concrete UI class — that would force callers to know about
 * [android.view.View] / [Handler] / Material widgets, inverting the
 * intended dependency direction.
 *
 * The legacy effective-language controller that originally drove this
 * surface was removed in D-13 (Epic §4 Block C1); the permanent language
 * SoT is now [net.devemperor.dictate.preferences.LanguageResolver] and
 * the override axis is `LanguageState.override`. This interface remains
 * the carrier for the still-legacy
 * [PipelineUiState.ReprocessStaging.selectedLanguage] until
 * `KeyboardUiController` itself is retired (Epic §4 Block C3).
 *
 *  - [state] — read the current pipeline UI state (Idle, Running,
 *    ReprocessStaging, Preparing) to decide whether a write is permanent
 *    or transient.
 *  - [updateReprocessLanguage] — temporary, transcript-only override
 *    during `ReprocessStaging`.
 *  - [addCallback] / [removeCallback] — observe state changes so the
 *    "effective language" derived value can be re-evaluated and
 *    dispatched.
 *
 * Quality-Gate W-4 (DIP) and K-2 (multi-callback support).
 */
interface PipelineUiStateReader {
    /** Current pipeline UI state, mirrors `KeyboardUiController.state`. */
    val state: PipelineUiState

    /**
     * Update the transient language override carried inside
     * [PipelineUiState.ReprocessStaging]. No-op when the state is not
     * `ReprocessStaging` (the implementation guards against that).
     */
    fun updateReprocessLanguage(code: String)

    /** Register [callback] for pipeline UI state changes and timer ticks. */
    fun addCallback(callback: PipelineUiCallback)

    /** Deregister a previously [addCallback]'d callback. Safe if not present. */
    fun removeCallback(callback: PipelineUiCallback)
}
