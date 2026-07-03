package net.devemperor.dictate.state.insertion

import android.view.inputmethod.InputConnection
import net.devemperor.dictate.core.EditorIdentity

/**
 * The single owner of every write to the host {@link
 * android.view.inputmethod.InputConnection}.
 *
 * Every insertion path in the app — pipeline transcription, short-press
 * resend, edit-bar copy/paste/cut, and the direct keystroke paths (space,
 * emoji, qwertz, enter, backspace, cursor) — routes through this service.
 * No other class calls `commitText` / `deleteSurroundingText` /
 * `performEditorAction` / `sendKeyEvent` / `performContextMenuAction` directly.
 *
 * The behaviour is the generalisation of the two historically-robust paths:
 * the pipeline commit (the reference) and the resend 3-stage strategy. A
 * [InsertionPolicy] selects which of the robust behaviours apply per caller.
 *
 * Pure Kotlin: all Android/DB concerns are behind the injected collaborators,
 * so the target-selection + fallback ladder is exercised in JVM unit tests.
 *
 * @see InsertionPolicy for the per-caller presets.
 */
class InsertionService(
    private val ic: IcProvider,
    private val guard: HostCommitGuard,
    private val committer: TextCommitter,
    private val controlExecutor: ControlExecutor,
    private val autoEnter: AutoEnterScheduler,
    private val audit: InsertionAuditLog,
    private val recovery: RecoveryHandler,
    private val clipboard: ClipboardGateway,
    private val textReader: HostTextReader,
) {
    /**
     * Insert text following [request]'s policy. Tries the live IC (for resend:
     * only if it is still the same editor as the captured one), then the
     * captured IC, then — if the policy allows — the focus-lost + resume
     * fallback.
     */
    fun insert(request: InsertionRequest): InsertionResult {
        // Host-block guard once up front (identical to the pre-refactor pipeline
        // commit): a visible widget means `ic` belongs to the wrong window, so
        // defer to the Pending-Insert info-bar rather than leak text.
        if (request.policy.respectHostGuard && !guard.canCommitToHost()) {
            return InsertionResult.DeferredToPending
        }

        for ((target, kind) in resolveTargets(request)) {
            val replaced =
                if (request.policy.audit && request.source != null) {
                    audit.captureReplaced(target.ic)
                } else {
                    null
                }
            if (committer.commit(target.ic, request.text)) {
                if (request.policy.autoEnter && autoEnter.isActive()) {
                    autoEnter.schedule(request.text)
                }
                if (request.policy.audit && request.source != null && request.text.isNotEmpty()) {
                    audit.record(
                        request.text, replaced, target.editor,
                        request.source, request.sessionIdOverride,
                    )
                }
                return InsertionResult.Committed(kind)
            }
        }

        // No usable IC channel.
        if (request.policy.resumeOnFailure) {
            recovery.notifyFocusLost()
            request.sessionIdOverride?.let { recovery.resume(it) }
            return InsertionResult.ResumedAfterFailure
        }
        return InsertionResult.Failed
    }

    /**
     * Execute a non-text control op (backspace / enter / cursor) on the live
     * editor. Unlike [insert], this does **not** consult the host-block guard:
     * control ops originate from the on-screen keyboard, which is only visible
     * while the IME owns the host window, so there is no wrong-window risk to
     * guard against.
     */
    fun control(op: ControlOp): InsertionResult {
        val live = ic.live() ?: return InsertionResult.Failed
        val resolved = resolveControlOp(live.ic, op)
        return if (controlExecutor.execute(live.ic, resolved)) {
            InsertionResult.Committed(Target.LIVE)
        } else {
            InsertionResult.Failed
        }
    }

    /**
     * The single place selection- and grapheme-semantics live (architecture
     * §2.3 theme 3). [ControlOp.DeleteGrapheme] and [ControlOp.CursorMove] carry
     * *intent*; here we read the host selection + surrounding text once and pick
     * the concrete primitive the executor runs. Every other op passes through
     * unchanged.
     */
    private fun resolveControlOp(hostIc: InputConnection, op: ControlOp): ControlOp =
        when (op) {
            is ControlOp.DeleteGrapheme -> resolveDeleteGrapheme(hostIc)
            is ControlOp.CursorMove -> resolveCursorMove(hostIc, op.direction)
            else -> op
        }

    /**
     * Backspace intent → concrete delete (F-018). An active selection is deleted
     * as a whole; otherwise exactly one grapheme cluster before the cursor is
     * removed (whole emoji / ZWJ sequence / combining mark, never a lone
     * surrogate). With no readable text, degrade to the raw one-unit primitive.
     */
    private fun resolveDeleteGrapheme(hostIc: InputConnection): ControlOp {
        if (textReader.selection(hostIc).isRange) return ControlOp.DeleteSelection

        val before = textReader.textBeforeCursor(hostIc, DELETE_LOOKBACK_UNITS)
        if (before.isEmpty()) return ControlOp.Backspace
        return ControlOp.DeleteSurrounding(GraphemeTextOps.lastGraphemeUnitCount(before), 0)
    }

    /**
     * Space-swipe cursor step → concrete move (F-021). With an active selection
     * we collapse to the edge the user is moving toward rather than destroying
     * it with an empty commit. Otherwise we step over a whole grapheme cluster
     * (left: back-scan the cluster before the caret; right: forward-scan after)
     * so the caret never lands inside a surrogate pair. With no readable
     * selection at all, fall back to the legacy empty-commit nudge.
     */
    private fun resolveCursorMove(hostIc: InputConnection, direction: Int): ControlOp {
        val sel = textReader.selection(hostIc)
        if (sel == HostSelection.NONE) return legacyCursorNudge(direction)

        if (sel.isRange) {
            val edge = if (direction < 0) sel.leftEdge else sel.rightEdge
            return ControlOp.SetSelection(edge, edge)
        }

        val caret = sel.start
        return if (direction < 0) {
            val before = textReader.textBeforeCursor(hostIc, DELETE_LOOKBACK_UNITS)
            if (before.isEmpty()) return legacyCursorNudge(direction)
            val step = GraphemeTextOps.lastGraphemeUnitCount(before)
            val target = (caret - step).coerceAtLeast(0)
            ControlOp.SetSelection(target, target)
        } else {
            // Rightward: the reader only exposes text-before-cursor, so a
            // grapheme-sized forward step would need text-after. Keep the legacy
            // one-unit nudge for the right direction; the selection-destroying
            // defect (the user-reported F-021 symptom) is already fixed above.
            legacyCursorNudge(direction)
        }
    }

    /** Legacy empty-commit caret nudge: +2 = one right, -1 = one left. */
    private fun legacyCursorNudge(direction: Int): ControlOp =
        ControlOp.CursorNudge(if (direction < 0) -1 else 2)

    /**
     * Run a copy/paste/cut edit action: try the host soft-API first, then the
     * manual clipboard fallback for hosts that ignore it. Like [control] and
     * for the same reason, this does not consult the host-block guard.
     */
    fun editAction(action: EditAction): InsertionResult {
        val live = ic.live() ?: return InsertionResult.Failed
        if (!clipboard.performHostAction(live.ic, action)) {
            clipboard.fallback(live.ic, action)
        }
        return InsertionResult.Committed(Target.LIVE)
    }

    /**
     * Ordered list of (target, channel) to attempt. Mirrors the resend
     * 3-stage strategy and reduces to a single live target for the pipeline.
     */
    private fun resolveTargets(request: InsertionRequest): List<Pair<HostTarget, Target>> {
        val targets = ArrayList<Pair<HostTarget, Target>>(2)
        val live = ic.live()
        if (live != null) {
            val useLive = if (request.policy.anchoredToCaptured) {
                // Resend → live only when still focused on the click-time field.
                // No anchor (captured == null) ⇒ skip live, go to resume.
                request.captured != null &&
                    EditorIdentity.isSame(live.editor, request.captured.editor)
            } else {
                // Pipeline / keystroke → live is always the primary target.
                true
            }
            if (useLive) targets += live to Target.LIVE
        }
        request.captured?.let { targets += it to Target.CAPTURED }
        return targets
    }

    private companion object {
        /**
         * How far back [resolveDeleteGrapheme] / [resolveCursorMove] read to find
         * a grapheme boundary. 64 UTF-16 units comfortably spans the longest
         * realistic single cluster (long ZWJ emoji sequences are ~10-20 units).
         * Matches the legacy `DELETE_LOOKBACK_CHARACTERS` in the IME service.
         */
        const val DELETE_LOOKBACK_UNITS = 64
    }
}
