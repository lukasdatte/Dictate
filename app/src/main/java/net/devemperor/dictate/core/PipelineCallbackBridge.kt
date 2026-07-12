package net.devemperor.dictate.core

import android.util.Log
import net.devemperor.dictate.ai.conversation.PostProcessingReview
import net.devemperor.dictate.database.entity.InsertionSource
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Indirection between the long-lived [PipelineOrchestrator] (constructed in
 * [DictatePipelineService.onCreate], lives for the whole process) and the
 * shorter-lived IME-side [PipelineOrchestrator.PipelineCallback] implementation
 * (lives only while the IME is bound and has a real UI).
 *
 * **C8 — IMPL-1 closure (Spec 1 §11.2.2 Block-2 sub-step 7 + §7.3):** the
 * JobExecutor-Init move from [DictateInputMethodService.initLongLivedObjects] to
 * [DictatePipelineService.onCreate] requires constructing [PipelineOrchestrator]
 * in the service. [PipelineOrchestrator]'s constructor takes a
 * [PipelineOrchestrator.PipelineCallback] — historically the IME itself (it
 * mutates Views during pipeline progress). The service has no Views; the IME
 * still owns the user-visible feedback.
 *
 * This bridge solves the lifecycle mismatch:
 *
 *  - The **service** owns one [PipelineCallbackBridge] for its lifetime and passes
 *    `this` to [PipelineOrchestrator]'s constructor at `onCreate`.
 *  - The **IME** registers its real callback implementation via
 *    [setDelegate] when it binds (`onServiceConnected`) and clears it via
 *    `setDelegate(null)` on unbind.
 *  - **Non-terminal calls during gaps** (`onStepStarted`, `onStepCompleted`,
 *    `onStepFailed`, `onPipelineFinished`, `onShowResend`, `onAutoSwitch`,
 *    `onAudioPersisted`) with no delegate set are logged at WARN and
 *    discarded — pure UI-progress feedback the pipeline can lose safely.
 *
 * **Terminal callbacks are the exception (ADR-0011).** `onPipelineCompleted`
 * and `onPipelineError` are the two *terminal* callbacks. Dropping them left
 * the in-memory `state.pipeline` FSM stuck in `Running` forever while the DB
 * row was already `COMPLETED` (fresh boot → external trigger → keyboard never
 * opened). To close that gap, once the service wires the late-bound
 * [setHeadlessTerminalSink]:
 *
 *  - **Delegate present** → the terminal call is delivered to the delegate,
 *    guarded by [PipelineTerminalDispatchGuard] so exactly one terminal
 *    dispatch happens per session (the delegate then commits text +
 *    dispatches `PipelineDone`).
 *  - **No delegate** → the bridge invokes the **headless sink**, which
 *    dispatches `PipelineDone(committed=false)` / `PipelineFailed` into the
 *    state orchestrator itself. `committed=false` keeps text-commit
 *    IME-exclusive: the transcript surfaces as a "Tap to paste" pending part
 *    rather than being inserted headlessly.
 *
 * The guard makes the three terminal producers (delegate-delivery, headless
 * fallback, and the ADR-0011 Decision-2 bind-reconciliation) mutually
 * exclusive per session. When the sink is NOT yet wired (early boot, or unit
 * tests that don't need it), terminal callbacks fall back to plain legacy
 * delegate delivery / drop — no behavioural change.
 *
 * **Threading:** [PipelineOrchestrator] invokes callbacks from its executor
 * thread. The delegate-AtomicReference makes the visibility safe (the IME sets
 * the delegate on the main thread; the pipeline reads it from its executor).
 * The delegate (when present) is responsible for posting UI work back to the
 * main looper.
 *
 * @see net.devemperor.dictate.core.PipelineOrchestrator.PipelineCallback
 * @see net.devemperor.dictate.core.DictatePipelineService
 * @see net.devemperor.dictate.core.PipelineTerminalDispatchGuard
 * @see docs/decisions/0011-pipeline-headless-completion-fallback.md
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §11.2.2 §7.3
 *
 * @param terminalGuard the process-wide once-guard. Injected so the service
 *   can share the SAME instance with the ADR-0011 Decision-2
 *   bind-reconciliation, keeping all three terminal producers mutually
 *   exclusive per session.
 */
class PipelineCallbackBridge(
    private val terminalGuard: PipelineTerminalDispatchGuard = PipelineTerminalDispatchGuard(),
) : PipelineOrchestrator.PipelineCallback {

    private val delegate = AtomicReference<PipelineOrchestrator.PipelineCallback?>(null)

    // ── Late-bound headless terminal fallback (ADR-0011) ─────────────
    // Wired by the service AFTER the state orchestrator is constructed
    // (the sinks call `orchestrator.emitAction`, which does not exist at
    // bridge-construction time in onCreate). `@Volatile` so the pipeline
    // executor thread sees the fields the moment the main thread wires
    // them; a single wiring call installs all three atomically enough for
    // this use (the provider is only meaningful together with the sinks).
    @Volatile
    private var currentSessionIdProvider: (() -> String?)? = null

    @Volatile
    private var headlessCompletionSink: ((sessionId: String, text: String) -> Unit)? = null

    @Volatile
    private var headlessFailureSink: ((sessionId: String, reason: String) -> Unit)? = null

    /**
     * Install the headless terminal fallback. Called once by the service
     * after the state orchestrator exists.
     *
     * @param currentSessionIdProvider resolves the sessionId of the
     *   in-flight pipeline from `store.snapshot.pipeline` (Running /
     *   Preparing / ReprocessStaging carry it; Idle → `null`). Returning
     *   `null` means the session is unresolvable → terminal callback is
     *   dropped and the guard is left untouched.
     * @param onCompleted headless completion sink — dispatches
     *   `PipelineDone(sessionId, text, committed=false)`. Invoked on the
     *   pipeline executor thread; the service resolves the authoritative
     *   text (never the raw `final_output_text` column) inside it.
     * @param onFailed headless failure sink — dispatches
     *   `PipelineFailed(sessionId, reason)`.
     */
    fun setHeadlessTerminalSink(
        currentSessionIdProvider: () -> String?,
        onCompleted: (sessionId: String, text: String) -> Unit,
        onFailed: (sessionId: String, reason: String) -> Unit,
    ) {
        this.currentSessionIdProvider = currentSessionIdProvider
        this.headlessCompletionSink = onCompleted
        this.headlessFailureSink = onFailed
    }

    /**
     * Atomically set or clear the IME-side delegate. `null` means "no IME
     * bound right now" — any callback firing while null logs at WARN and
     * returns without effect.
     */
    fun setDelegate(callback: PipelineOrchestrator.PipelineCallback?) {
        delegate.set(callback)
    }

    /** Test-visible — peek the current delegate. */
    fun currentDelegate(): PipelineOrchestrator.PipelineCallback? = delegate.get()

    private inline fun dispatch(method: String, block: (PipelineOrchestrator.PipelineCallback) -> Unit) {
        val cb = delegate.get()
        if (cb == null) {
            // Pipeline runs ahead while no UI is attached. DB-state still
            // updates per the Persistence-First contract (Spec 1 §6).
            Log.w(TAG, "$method dropped — no IME callback delegate registered")
            return
        }
        invokeSafely(method, cb, block)
    }

    private inline fun invokeSafely(
        method: String,
        cb: PipelineOrchestrator.PipelineCallback,
        block: (PipelineOrchestrator.PipelineCallback) -> Unit,
    ) {
        try {
            block(cb)
        } catch (e: Exception) {
            // A misbehaving IME-side callback must NOT abort the
            // pipeline thread. B3-VAL-W1 F-21: catch Exception, NOT
            // Throwable — JVM Errors (OOM, StackOverflow, LinkageError)
            // must propagate so Crashlytics sees them.
            Log.w(TAG, "$method delegate threw — swallowed to keep pipeline alive", e)
        }
    }

    override fun onStepStarted(stepName: String) =
        dispatch("onStepStarted") { it.onStepStarted(stepName) }

    override fun onStepCompleted(stepName: String, durationMs: Long) =
        dispatch("onStepCompleted") { it.onStepCompleted(stepName, durationMs) }

    override fun onStepFailed(stepName: String) =
        dispatch("onStepFailed") { it.onStepFailed(stepName) }

    // Non-terminal (ADR-0013): forward to the delegate, drop when unbound —
    // a review continuation only makes sense with a visible, bound IME.
    override fun onReviewTurnCompleted(
        sessionId: String,
        output: String,
        message: String?,
        needsClarification: Boolean
    ) = dispatch("onReviewTurnCompleted") {
        it.onReviewTurnCompleted(sessionId, output, message, needsClarification)
    }

    override fun onPipelineCompleted(text: String, source: InsertionSource, review: PostProcessingReview?) {
        val provider = currentSessionIdProvider
        if (provider == null) {
            // Headless fallback not wired yet (early boot / legacy tests):
            // preserve the original delegate-or-drop behaviour verbatim.
            dispatch("onPipelineCompleted") { it.onPipelineCompleted(text, source, review) }
            return
        }
        val sid = provider.invoke()
        if (sid == null) {
            // No in-flight session to key the guard on — dropping is the
            // only safe choice (an unguarded delivery could double-commit
            // if a reconciliation also runs). Guard left untouched.
            Log.w(TAG, "onPipelineCompleted dropped — no resolvable sessionId")
            return
        }
        val cb = delegate.get()
        if (cb != null) {
            // Delegate present: deliver ONCE. `tryConsume` failing means a
            // terminal dispatch already happened for this session (headless
            // fallback or reconciliation) — delivering again would
            // double-commit the transcript, so skip.
            if (terminalGuard.tryConsume(sid)) {
                invokeSafely("onPipelineCompleted", cb) { it.onPipelineCompleted(text, source, review) }
            } else {
                Log.w(TAG, "onPipelineCompleted skipped — session $sid already terminally dispatched")
            }
        } else {
            // No delegate: dispatch the headless completion (committed=false).
            if (terminalGuard.tryConsume(sid)) {
                headlessCompletionSink?.invoke(sid, text)
                    ?: Log.w(TAG, "onPipelineCompleted dropped — headless sink not installed")
            } else {
                Log.w(TAG, "onPipelineCompleted headless dropped — session $sid already terminally dispatched")
            }
        }
    }

    override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) {
        val provider = currentSessionIdProvider
        if (provider == null) {
            dispatch("onPipelineError") { it.onPipelineError(errorInfoKey, vibrate, providerName) }
            return
        }
        val sid = provider.invoke()
        if (sid == null) {
            Log.w(TAG, "onPipelineError dropped — no resolvable sessionId")
            return
        }
        val cb = delegate.get()
        if (cb != null) {
            if (terminalGuard.tryConsume(sid)) {
                invokeSafely("onPipelineError", cb) {
                    it.onPipelineError(errorInfoKey, vibrate, providerName)
                }
            } else {
                Log.w(TAG, "onPipelineError skipped — session $sid already terminally dispatched")
            }
        } else {
            // Headless failure — errorInfoKey is the failure reason carried
            // into PipelineFailed(sid, reason). A session either completes
            // or fails; the shared guard enforces exactly one of the two.
            if (terminalGuard.tryConsume(sid)) {
                headlessFailureSink?.invoke(sid, errorInfoKey)
                    ?: Log.w(TAG, "onPipelineError dropped — headless sink not installed")
            } else {
                Log.w(TAG, "onPipelineError headless dropped — session $sid already terminally dispatched")
            }
        }
    }

    override fun onPipelineFinished() =
        dispatch("onPipelineFinished") { it.onPipelineFinished() }

    override fun onShowResend() =
        dispatch("onShowResend") { it.onShowResend() }

    override fun onAutoSwitch() =
        dispatch("onAutoSwitch") { it.onAutoSwitch() }

    override fun onAudioPersisted(audioFile: File, sessionId: String) =
        dispatch("onAudioPersisted") { it.onAudioPersisted(audioFile, sessionId) }

    private companion object {
        const val TAG: String = "PipelineCallbackBridge"
    }
}
