package net.devemperor.dictate.state.insertion

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
        return if (controlExecutor.execute(live.ic, op)) {
            InsertionResult.Committed(Target.LIVE)
        } else {
            InsertionResult.Failed
        }
    }

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
}
