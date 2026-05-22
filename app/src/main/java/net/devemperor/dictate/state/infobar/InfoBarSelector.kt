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
        //
        // B4 (ADR-0008) — Text-preview: the info-bar message now carries
        // the first ~60 chars of the transcribed text so the user can
        // judge what they're about to paste. Uses `dictate_pending_insert_msg_preview`
        // (with %1$s placeholder) when transcribedText is non-empty;
        // falls back to the legacy generic message when the text is
        // null or empty (defensive — should not happen for COMPLETED
        // status but the filter above only guarantees non-null).
        state.pendingSessions
            .filter { it.status == SessionStatus.COMPLETED && it.transcribedText != null }
            .forEach { session ->
                val text = session.transcribedText
                val preview = text?.trim()?.takeIf { it.isNotEmpty() }
                val message = if (preview != null) {
                    InfoBarMessage(
                        textResId = R.string.dictate_pending_insert_msg_preview,
                        textArgs = listOf(buildPreview(preview)),
                        style = InfoBarStyle.ACTION,
                    )
                } else {
                    // Fallback for the (theoretically impossible)
                    // COMPLETED-with-empty-text edge case.
                    InfoBarMessage(
                        textResId = R.string.dictate_pending_insert_msg,
                        style = InfoBarStyle.ACTION,
                    )
                }
                add(
                    InfoBarItem(
                        id = "pending-insert:${session.sessionId}",
                        createdAt = session.createdAt,
                        message = message,
                        confirmAction = Action.PendingSessionsAction.AcceptAndInsert(session.sessionId),
                        dismissAction = Action.PendingSessionsAction.Dismiss(session.sessionId),
                    )
                )
            }

        // ── Partial-Recovery warning (B4 / ADR-0008 §"Partial Recovery") ──
        // The pipeline persists a marker substring "partial:<seconds>"
        // into SessionEntity.lastErrorMessage when a multi-segment
        // upload had to skip an unreadable segment. The producer
        // surfaces a one-shot ERROR-style info-bar that estimates the
        // number of lost seconds; dismissing it clears the marker by
        // routing through the same PendingSessionsAction.Dismiss that
        // the pending-insert path uses (the session row stays as
        // COMPLETED; only the info-bar item leaves the list).
        //
        // Sort-key: same createdAt as the pending-insert item, so when
        // both fire for the same session they cluster together. The
        // partial-warning item has a different id so the renderer
        // shows them as two stacked entries.
        state.pendingSessions
            .filter { it.status == SessionStatus.COMPLETED }
            .forEach { session ->
                val lostSeconds = extractPartialRecoverySeconds(session.lastErrorMessage)
                if (lostSeconds != null) {
                    add(
                        InfoBarItem(
                            id = "partial-recovery:${session.sessionId}",
                            createdAt = session.createdAt,
                            message = InfoBarMessage(
                                textResId = R.string.dictate_recovery_partial_msg,
                                textArgs = listOf(lostSeconds),
                                style = InfoBarStyle.ERROR,
                            ),
                            confirmAction = null,
                            dismissAction = Action.PendingSessionsAction.Dismiss(session.sessionId),
                        )
                    )
                }
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

        // ── Recording-Interrupted (2026-05-22) ──────────────────────────
        // Session in RECORDING_INTERRUPTED status — recording was cut
        // off by an FGS kill (Android 14+ microphone-FGS restriction at
        // keyboard switch / process background). The audio segments are
        // still on disk. The user can resume by tapping Record (the
        // ContinuationLookup will pick this session up if its freshness
        // is OK) or discard via the InfoBar's dismiss action.
        //
        // dismissAction = DiscardInterruptedSession (atomic discard via
        // RecordingModule reducer — stops MediaRecorder if any, deletes
        // audio segments, marks DB row as FAILED).
        state.pendingSessions
            .filter { it.status == SessionStatus.RECORDING_INTERRUPTED }
            .forEach { session ->
                add(
                    InfoBarItem(
                        id = "recording-interrupted:${session.sessionId}",
                        createdAt = session.createdAt,
                        message = InfoBarMessage(
                            textResId = R.string.dictate_recording_interrupted_msg,
                            style = InfoBarStyle.ACTION,
                        ),
                        confirmAction = null,
                        dismissAction = Action.RecordingAction.DiscardInterruptedSession(session.sessionId),
                    )
                )
            }
    }.sortedBy { it.createdAt }

    // ─── B4 helpers ────────────────────────────────────────────────────

    /**
     * Truncate [text] to the first ~60 trimmed characters with an
     * ellipsis when truncated. Used by the pending-insert info-bar so
     * the user sees *what* they're about to paste (ADR-0008
     * §"Pending-Insert text preview", plan §4 B4).
     *
     * The 60-char limit matches the user-stated preference
     * ("Dieser beginnt mit den folgenden Buchstaben: …"); slightly
     * shorter or longer is fine — the info-bar layout has the room
     * for one line of body text, and the ellipsis signals truncation
     * unambiguously. Newlines inside the text are replaced by a
     * single space so the preview stays on a single visual line.
     */
    internal fun buildPreview(text: String): String {
        val flattened = text.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= PREVIEW_LIMIT) {
            flattened
        } else {
            flattened.substring(0, PREVIEW_LIMIT).trimEnd() + "…"
        }
    }

    /**
     * Parse the "partial:<seconds>" marker substring out of a
     * `SessionEntity.lastErrorMessage` value (B4 / ADR-0008
     * §"Partial Recovery"). Returns the seconds count or `null` when
     * the marker is absent or malformed. Tolerant of leading /
     * trailing context so the pipeline can append the marker to an
     * existing error message without losing parseability:
     *
     *     "partial:7"            → 7
     *     "partial:7s"           → 7   (s suffix tolerated)
     *     "concat warning - partial:12 segments=3" → 12
     *     null / "" / "ok"        → null
     */
    internal fun extractPartialRecoverySeconds(lastErrorMessage: String?): Int? {
        if (lastErrorMessage.isNullOrEmpty()) return null
        val match = PARTIAL_MARKER_REGEX.find(lastErrorMessage) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private const val PREVIEW_LIMIT = 60
    private val PARTIAL_MARKER_REGEX = Regex("""partial:(\d+)""")
}
