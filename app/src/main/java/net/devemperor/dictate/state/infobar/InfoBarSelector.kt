package net.devemperor.dictate.state.infobar

import net.devemperor.dictate.R
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.state.Action
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
 *   Each branch below extracts items from one logical source.
 *
 *   - **Overlay-Permission-Onboarding** (Block D) — pinned to top
 *     with `createdAt = 0L` so the explainer outranks any later
 *     transient item. Replaces the legacy
 *     `overlay_permission_infobar` surface + `OverlayPermissionInfobarRenderer`
 *     + `OverlayOnboardingObserver`.
 *   - **Pipeline-Errors** (planned Block D.2) — transient network /
 *     quota / model / api-key error surfaces. Replaces the nine
 *     `InfoBarController.showInfo(type)` cases.
 *   - **Pending-Insert / Pending-Recording / Recovery / API-Key**
 *     (Block E) — new producers driven by `pendingSessions`,
 *     recovery acknowledgements, and pref-mirror flags.
 *
 * @see InfoBarItem
 * @see InfoBarMessage
 * @see docs/decisions/0006-ui-info-bar-state-derived-items.md
 */
object InfoBarSelector {

    /**
     * Compute the current info-bar items from [state]. The list is
     * sorted ascending by `createdAt`; an empty list means the
     * info-bar surface is hidden.
     */
    fun select(state: DictateUiState): List<InfoBarItem> = buildList<InfoBarItem> {
        // ── Overlay-Permission-Onboarding (ADR-0005 §5.4 + ADR-0006) ──
        // The user toggled the widget without SYSTEM_ALERT_WINDOW
        // permission; the explainer surfaces with two actions:
        //   - Confirm → RequestOverlayPermission (opens Settings)
        //   - Dismiss → DismissOverlayOnboarding (persists "Later")
        // `createdAt = 0L` pins this item at the top so a later
        // transient error never visually covers the explainer.
        if (state.overlay.onboardingPending) {
            add(
                InfoBarItem(
                    id = "overlay-permission:onboarding",
                    createdAt = 0L,
                    message = InfoBarMessage(
                        textResId = R.string.overlay_perm_explainer,
                        style = InfoBarStyle.INFO,
                    ),
                    confirmAction = Action.OverlayAction.RequestOverlayPermission,
                    dismissAction = Action.OverlayAction.DismissOverlayOnboarding,
                )
            )
        }

        // ── Pending-Insert (ADR-0006 §"Cross-Module Producer pattern") ──
        // Session whose pipeline COMPLETED but whose result text was
        // never surfaced to a live `InputConnection`. Confirm =
        // AcceptAndInsert → IME service side-channel calls commitText;
        // Dismiss = plain Dismiss → session leaves the list with
        // `inserted_at` stamped, the result text is forfeited.
        state.pendingSessions
            .filter { it.status == SessionStatus.COMPLETED && it.transcribedText != null }
            .forEach { session ->
                add(
                    InfoBarItem(
                        id = "pending-insert:${session.sessionId}",
                        createdAt = session.createdAt,
                        message = InfoBarMessage(
                            textResId = R.string.dictate_pending_insert_msg,
                            style = InfoBarStyle.ACTION,
                        ),
                        confirmAction = Action.PendingSessionsAction.AcceptAndInsert(session.sessionId),
                        dismissAction = Action.PendingSessionsAction.Dismiss(session.sessionId),
                    )
                )
            }

        // ── Pending-Recording (ADR-0006, MVP) ───────────────────────────
        // Session in RECORDED status (recording finished, pipeline not
        // started yet). MVP-cut: single Dismiss-button ("Verwerfen")
        // removes the session from the list and stamps inserted_at.
        // 3-button affordance (Fortsetzen / Senden / Verwerfen) requires
        // a layout that supports a third button and additional Pipeline
        // actions — scheduled for a follow-up commit. The MVP keeps the
        // user from being stuck with a stale RECORDED entry.
        state.pendingSessions
            .filter { it.status == SessionStatus.RECORDED }
            .forEach { session ->
                add(
                    InfoBarItem(
                        id = "pending-recording:${session.sessionId}",
                        createdAt = session.createdAt,
                        message = InfoBarMessage(
                            textResId = R.string.dictate_pending_recording_msg,
                            style = InfoBarStyle.ACTION,
                        ),
                        confirmAction = null,
                        dismissAction = Action.PendingSessionsAction.Dismiss(session.sessionId),
                    )
                )
            }
    }.sortedBy { it.createdAt }
}
