package net.devemperor.dictate.core

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Outcome of one stage of the 3-stage resend insertion strategy.
 *
 * Internal enum used by [ResendInsertStrategy] so the implementation reads
 * top-down without nested early-returns.
 */
enum class ResendInsertStage {
    /** Stage 1 — committed via the live [InputConnection]. */
    LIVE,

    /** Stage 2 — committed via the captured [InputConnection]. */
    CAPTURED,

    /** Stage 3 — both IC channels failed, fallback resume invoked. */
    FALLBACK,
}

/**
 * Pure logic behind {@code DictateInputMethodService.insertOrFallback}.
 *
 * Extracting the strategy into a stateless helper keeps the IME service
 * thin and lets us exercise all three stages in JVM unit tests with
 * hand-rolled fakes (no Mockito, no Robolectric — see project CLAUDE.md).
 *
 * The Service composes this with its [InputConnection]/[EditorInfo]
 * accessors, the existing {@code commitTextToInputConnection} method, the
 * focus-lost {@code Toast} and the resume-job launcher. None of those
 * collaborators is constructed here — the strategy receives them as
 * function references.
 */
object ResendInsertStrategy {

    /**
     * Functional adapter for committing text on a specific
     * [InputConnection]/[EditorInfo] pair.
     *
     * The Service implements this via the parametrised
     * {@code commitTextToInputConnection(ic, editor, text, source, sessionId, enableAutoEnter)}
     * overload, passing {@code enableAutoEnter = false} for both Stage 1
     * and Stage 2 (resend is a recovery insert, never a new transcription;
     * see Service KDoc for details). Returning {@code false} signals the
     * caller that the IC is unusable and the next stage should be tried.
     */
    fun interface Committer {
        fun commit(ic: InputConnection, editor: EditorInfo?, text: String, sessionId: String): Boolean
    }

    /** Lambda invoked when both IC channels fail (Stage 3). */
    fun interface ResumeStarter {
        fun start(sessionId: String)
    }

    /** Lambda invoked when both IC channels fail — surfaces the focus-lost toast. */
    fun interface FocusLostNotifier {
        fun show()
    }

    /**
     * Run the 3-stage insertion strategy.
     *
     * @param liveIc          live [InputConnection] (typically
     *                        {@code service.getCurrentInputConnection()}).
     *                        {@code null} skips Stage 1.
     * @param liveEditor      live {@link EditorInfo}, or {@code null}.
     * @param capturedIc      [InputConnection] captured at click time.
     *                        {@code null} skips Stage 2.
     * @param capturedEditor  [EditorInfo] paired with {@code capturedIc}.
     * @param output          text to insert (non-null, may be empty —
     *                        empty short-circuits via the committer).
     * @param sessionId       session id to bind the audit log to.
     * @param committer       commit-side-effect callable; returns
     *                        {@code true} on success.
     * @param notifyFocusLost callback for the user-visible focus-lost toast.
     * @param resumeStarter   callback launching the resume job as last
     *                        resort.
     * @return which stage actually committed the text (or
     *         [ResendInsertStage.FALLBACK] if Stages 1-2 failed and the
     *         resume path ran).
     */
    fun execute(
        liveIc: InputConnection?,
        liveEditor: EditorInfo?,
        capturedIc: InputConnection?,
        capturedEditor: EditorInfo?,
        output: String,
        sessionId: String,
        committer: Committer,
        notifyFocusLost: FocusLostNotifier,
        resumeStarter: ResumeStarter,
    ): ResendInsertStage {
        // Stage 1 — prefer live IC if the user is still in the same field.
        if (liveIc != null && EditorIdentity.isSame(liveEditor, capturedEditor)) {
            if (committer.commit(liveIc, liveEditor, output, sessionId)) {
                return ResendInsertStage.LIVE
            }
            // Live IC failed despite same-editor → try captured IC anyway.
        }

        // Stage 2 — captured IC. Android does not synchronously mark IC
        // objects invalid on focus change, so the captured handle often
        // still accepts writes for the original target field.
        if (capturedIc != null) {
            if (committer.commit(capturedIc, capturedEditor, output, sessionId)) {
                return ResendInsertStage.CAPTURED
            }
        }

        // Stage 3 — both IC channels are unusable. Surface the situation
        // and resume the pipeline so the user can re-route the output.
        notifyFocusLost.show()
        resumeStarter.start(sessionId)
        return ResendInsertStage.FALLBACK
    }
}
