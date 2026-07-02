package net.devemperor.dictate.state.infobar

import net.devemperor.dictate.R
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.EngagementHint
import net.devemperor.dictate.state.InterruptionReason
import net.devemperor.dictate.state.PipelineErrorHint
import net.devemperor.dictate.state.PipelineErrorKind
import net.devemperor.dictate.state.RecordingState

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
 *     transient item. Replaces the legacy overlay-permission
 *     onboarding surface (`OverlayPermissionInfobarRenderer`
 *     + `OverlayOnboardingObserver`).
 *   - **Pipeline-Errors** (2026-07-02, ADR-0006 completion) —
 *     transient network / quota / model / api-key / bad-request
 *     error surfaces driven by `state.infoHints.pipelineError`.
 *     Replaces the legacy imperative `showInfo(type)` error
 *     cases.
 *   - **Engagement hints** (2026-07-02, ADR-0006 completion) —
 *     Update / Rate / Donate nags driven by
 *     `state.infoHints.engagementHint`. Replaces the remaining three
 *     legacy imperative `showInfo(type)` cases.
 *   - **Pending-Insert / Recovery** (Block E) — producers driven by
 *     `pendingSessions` and the recording-recovery state.
 *   - **Interruption-paused recording** (2026-07-02, F-036) —
 *     pure-info item driven by `state.interruption.lastInterruption`;
 *     lifetime bound to the interruption-caused pause (module
 *     self-clear). Accepted shadowing (F-030 precedent): an older
 *     timestamped item (e.g. a pending-insert transcript) outranks a
 *     fresh interruption notice; nothing is lost — the hidden item
 *     resurfaces as `items.first()` once the shown one's source
 *     clears, and the pause itself is visible in the keyboard UI.
 *
 * **Priority note (consolidation research Gap 2, 2026-07-02):** items
 * for the *same* session share a `createdAt` — the stable sort plus
 * build order then puts the pending-insert item ahead of the
 * partial-recovery item, and the renderer shows only `items.first()`.
 * A partial-recovery warning is therefore effectively shadowed while
 * a transcript exists for that session (it can only surface alone
 * when `transcribedText == null`). This is the accepted current
 * behaviour — kept deliberately, documented here so nobody
 * re-diagnoses it as a sorting bug (refuted finding F-030).
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
        // surfaces an ERROR-style info-bar that estimates the number
        // of lost seconds.
        //
        // Dismissal (2026-07-02 KDoc fix, F-030 residue): the dismiss
        // routes through the same PendingSessionsAction.Dismiss the
        // pending-insert item uses — which removes the ENTIRE session
        // from state.pendingSessions and stamps `inserted_at` in the
        // DB. Both items of the session (pending-insert AND this
        // warning) leave the bar together; the "partial:<N>" marker in
        // lastErrorMessage is NOT cleared (it stays as the session's
        // persisted failure context).
        //
        // Sort-key: same createdAt as the pending-insert item. With
        // the stable sort + build order the pending-insert item ranks
        // first, so this warning is shadowed while a transcript exists
        // (see the class-KDoc "Priority note" — accepted behaviour,
        // not a bug).
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

        // ── Recovery-surfaced unfinished recording (2026-05-23) ─────────
        // PipelineRecovery Phase 5 surfaces both RECORDING_INTERRUPTED
        // (recording cut off mid-flight) and RECORDED (audio complete,
        // transcription never finished) sessions as
        // [RecordingState.Interrupted] in the keyboard — record button
        // continues, trash button discards (LayoutPredicates +
        // ActionResolvers). The user previously saw a separate
        // "pending-recording" info-bar with a single Dismiss button,
        // which doubled-surfaced the same thing and forced an
        // architecture coupling between the InfoBar and the record/trash
        // buttons.
        //
        // Now the info-bar adds an *explanatory pure-info text only* —
        // both `confirmAction` and `dismissAction` are null, so the
        // renderer hides both buttons. The text exists exactly as long
        // as `state.recording is Interrupted` holds; the moment a
        // keyboard action transitions the recording out (continuation →
        // Preparing/Active, discard → Idle), the source condition fails
        // and the item vanishes. No "DismissInfoBar" action exists, no
        // record/trash button has any InfoBar awareness — the keyboard
        // buttons mutate `state.recording`, this producer reads
        // `state.recording`, that is the entire coupling surface.
        //
        // `createdAt = 0L` pins the item at the top so it outranks
        // later transient items — the unfinished recording is the most
        // actionable thing in the UI until the user resolves it.
        //
        // The replaced `pending-recording` producer + `dictate_pending_
        // recording_msg` string are gone; RECORDED rows still live in
        // `pendingSessions` (loadPending unchanged) but no producer
        // emits anything for them in the InfoBar surface.
        val recording = state.recording
        if (recording is RecordingState.Interrupted) {
            add(
                InfoBarItem(
                    id = "recovery-unfinished:${recording.sessionId}",
                    createdAt = 0L,
                    message = InfoBarMessage(
                        textResId = R.string.dictate_recovery_unfinished_info,
                        style = InfoBarStyle.INFO,
                    ),
                    confirmAction = null,
                    dismissAction = null,
                )
            )
        }

        // ── Interruption-paused recording (2026-07-02, F-036) ───────────
        // The recording was auto-paused by InterruptionModule (another
        // app took audio focus, or the headset disconnected). Pure-info
        // item like the recovery-unfinished producer above: no buttons —
        // `InterruptionState.lastInterruption` is non-null exactly while
        // the interruption-caused pause is live (the module's
        // self-cascade clears it when the recording leaves Paused), so
        // the item's lifetime is bound to its natural source. Resume is
        // user-driven via the existing paused-state keyboard UI.
        state.interruption.lastInterruption?.let { event ->
            add(
                InfoBarItem(
                    id = "interruption:${event.reason.name.lowercase()}",
                    createdAt = event.occurredAt,
                    message = InfoBarMessage(
                        textResId = when (event.reason) {
                            InterruptionReason.AUDIO_FOCUS_LOST ->
                                R.string.dictate_interruption_audio_focus_msg
                            InterruptionReason.HEADSET_DISCONNECTED ->
                                R.string.dictate_interruption_headset_msg
                        },
                        style = InfoBarStyle.INFO,
                    ),
                    confirmAction = null,
                    dismissAction = null,
                )
            )
        }

        // ── Pipeline-Errors (2026-07-02, ADR-0006 completion) ───────────
        // Transient AI-pipeline errors surfaced via the
        // state.infoHints.pipelineError axis (set by the IME's
        // onPipelineError callback → InfoHintAction.PipelineErrorOccurred,
        // cleared by dismiss/confirm or InfoHintModule's cascade when a
        // new run starts). Replaces the legacy
        // imperative showInfo(errorInfoKey) routing — force-expand
        // and the prompts-mutex now apply to error bars by construction
        // (both key on this selector's output). `createdAt = occurredAt`
        // gives the error its authentic event timestamp.
        state.infoHints.pipelineError?.let { add(pipelineErrorItem(it)) }

        // ── Engagement hints: Update / Rate / Donate (2026-07-02) ───────
        // Driven by state.infoHints.engagementHint; the trigger
        // conditions (pref + usage-DB reads) are evaluated IME-side on
        // onStartInputView and dispatched as ShowEngagementHint —
        // mirroring the legacy showInfo("update"/"rate"/"donate")
        // trigger sites (consolidation research Gap 1 fallback).
        //
        // `createdAt = Long.MAX_VALUE`: pref-/usage-driven nags have no
        // event timestamp (ADR-0006 §"createdAt drift" — its suggested
        // `VERSION_BUILD_TIME` proxy does not exist in this project's
        // BuildConfig). MAX_VALUE is an equally stable proxy that
        // additionally encodes the intended priority: a nag always
        // yields to any real-event item (errors, pending inserts).
        state.infoHints.engagementHint?.let { add(engagementHintItem(it)) }
    }.sortedBy { it.createdAt }

    // ─── Info-hint producers (ADR-0006 completion) ─────────────────────

    /**
     * Build the ERROR-style item for a transient pipeline error.
     *
     * Confirm-button mapping (parity with the deleted
     * legacy imperative info-bar controller cases):
     *
     *  - [PipelineErrorKind.INVALID_API_KEY] / [PipelineErrorKind.MODEL_NOT_FOUND] /
     *    [PipelineErrorKind.BAD_REQUEST] → confirm opens the settings.
     *  - [PipelineErrorKind.QUOTA_EXCEEDED] → confirm opens the
     *    provider's billing page — only offered when the provider has
     *    one; the message carries the provider display name.
     *  - [PipelineErrorKind.INTERNET_ERROR] → dismiss-only.
     *
     * The confirm/dismiss actions mutate the natural source
     * (`InfoHintModule` clears `state.infoHints.pipelineError`), so
     * the no-resurrection guarantee holds; the Activity launches are
     * the IME-side side-channel keyed on the dispatched action.
     */
    private fun pipelineErrorItem(hint: PipelineErrorHint): InfoBarItem {
        val confirm = Action.InfoHintAction.ConfirmPipelineError(hint.kind, hint.providerKey)
        val message: InfoBarMessage
        val confirmAction: Action?
        when (hint.kind) {
            PipelineErrorKind.INVALID_API_KEY -> {
                message = InfoBarMessage(R.string.dictate_invalid_api_key_msg, style = InfoBarStyle.ERROR)
                confirmAction = confirm
            }
            PipelineErrorKind.MODEL_NOT_FOUND -> {
                message = InfoBarMessage(R.string.dictate_model_not_found_msg, style = InfoBarStyle.ERROR)
                confirmAction = confirm
            }
            PipelineErrorKind.BAD_REQUEST -> {
                message = InfoBarMessage(R.string.dictate_bad_request_msg, style = InfoBarStyle.ERROR)
                confirmAction = confirm
            }
            PipelineErrorKind.QUOTA_EXCEEDED -> {
                // Legacy parity: an unknown/null provider key renders a
                // generic "API" display name and offers no billing link.
                val provider = hint.providerKey?.let { AIProvider.fromPersistKey(it) }
                message = InfoBarMessage(
                    textResId = R.string.dictate_quota_exceeded_msg,
                    textArgs = listOf(provider?.displayName ?: "API"),
                    style = InfoBarStyle.ERROR,
                )
                confirmAction = if (provider?.billingUrl != null) confirm else null
            }
            PipelineErrorKind.INTERNET_ERROR -> {
                message = InfoBarMessage(R.string.dictate_internet_error_msg, style = InfoBarStyle.ERROR)
                confirmAction = null
            }
        }
        return InfoBarItem(
            id = "pipeline-error:${hint.kind.name.lowercase()}",
            createdAt = hint.occurredAt,
            message = message,
            confirmAction = confirmAction,
            dismissAction = Action.InfoHintAction.DismissPipelineError,
        )
    }

    /**
     * Build the INFO-style item for an Update / Rate / Donate nag.
     * Confirm and dismiss both clear the hint via `InfoHintModule`;
     * dismiss additionally persists the matching `Pref.*` flag so the
     * IME-side trigger stops re-firing (see [EngagementHint] KDoc for
     * the per-hint persistence table).
     */
    private fun engagementHintItem(hint: EngagementHint): InfoBarItem = InfoBarItem(
        id = "engagement-hint:${hint.name.lowercase()}",
        createdAt = Long.MAX_VALUE,
        message = InfoBarMessage(
            textResId = when (hint) {
                EngagementHint.UPDATE -> R.string.dictate_update_installed_msg
                EngagementHint.RATE -> R.string.dictate_rate_app_msg
                EngagementHint.DONATE -> R.string.dictate_donate_msg
            },
            style = InfoBarStyle.INFO,
        ),
        confirmAction = Action.InfoHintAction.ConfirmEngagementHint(hint),
        dismissAction = Action.InfoHintAction.DismissEngagementHint(hint),
    )

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
