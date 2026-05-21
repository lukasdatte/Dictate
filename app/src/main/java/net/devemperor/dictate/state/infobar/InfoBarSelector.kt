package net.devemperor.dictate.state.infobar

import net.devemperor.dictate.state.DictateUiState

/**
 * Pure function `(DictateUiState) -> List<InfoBarItem>` that derives
 * the live info-bar surface from the global state (ADR-0006).
 *
 * **Contract:**
 *
 *  - **Pure.** No side-effects, no IO, no clock reads. Producers that
 *    need timestamps embed them in the state itself (via mirror writers
 *    or recovery hydration); the selector reads them as is.
 *  - **Deterministic per state.** Two calls with the same input
 *    produce equal output (including item order). Required by the
 *    renderer's `distinctUntilChanged` collector — drift in this
 *    contract would cause unnecessary re-renders.
 *  - **Sorted ascending by `createdAt`.** Oldest items first per the
 *    user's "Entstehungszeitpunkt"-rule (ADR-0006 §"Dismiss = natural-
 *    source mutation"). Renderer shows the top item (= oldest) until
 *    its source is resolved.
 *
 * **Producer integration model (ADR-0006 §"Cross-Module Producer pattern"):**
 *
 *   "Producers" are not classes; they are reads against the state.
 *   Each branch below extracts items from one logical source. Block D
 *   adds the nine legacy `InfoBarController` cases plus
 *   `overlay_permission_infobar`; Block E adds Pending-Insert,
 *   Pending-Recording, Recovery-Acknowledge, and API-Key-missing.
 *
 *   This skeleton-body keeps the contract live during the rollout —
 *   downstream renderer + tests can subscribe to the selector without
 *   waiting for the first producer.
 *
 * @see InfoBarItem
 * @see InfoBarMessage
 * @see docs/decisions/0006-ui-info-bar-state-derived-items.md
 */
object InfoBarSelector {

    /**
     * Compute the current info-bar items from [state]. Returns an
     * empty list while no producer has surfaced a trigger.
     *
     * **Implementation roadmap:**
     *
     *  - **Block D** (legacy migration): nine SharedPreferences-flag
     *    -driven items (update / rate / donate / timeout / invalid-api-
     *    key / quota-exceeded / model-not-found / bad-request /
     *    internet-error) + the overlay-permission onboarding hint.
     *  - **Block E** (new producers): pending-insert (COMPLETED +
     *    inserted_at IS NULL), pending-recording (RECORDED), recovery-
     *    acknowledge (after `PipelineRecovery` ran), api-key-missing
     *    (no provider key configured).
     */
    fun select(state: DictateUiState): List<InfoBarItem> = buildList<InfoBarItem> {
        // Empty during Block C — producers populate in Block D + E.
        // The function intentionally stays a pure pass-through: the
        // renderer and its tests can subscribe and verify the empty-
        // case behaviour before any item-producing logic lands.
    }.sortedBy { it.createdAt }
}
