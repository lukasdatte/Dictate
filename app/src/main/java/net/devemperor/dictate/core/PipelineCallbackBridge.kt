package net.devemperor.dictate.core

import android.util.Log
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
 *  - **Calls during gaps** (no delegate set, e.g. process boot before the user
 *    opens the keyboard, or after the IME unbinds) are logged at WARN and
 *    discarded — the pipeline still runs to DB-completion (per Spec 1 §11.6
 *    OOM-Death-Recovery model) but the UI feedback drops on the floor. This is
 *    the documented Phase-1 behaviour; the post-extraction recovery wiring (SF-4
 *    in B3 issue index) will make the missing-callback case observable in the
 *    UI on next bind via DB-replay.
 *
 * **Threading:** [PipelineOrchestrator] invokes callbacks from its executor
 * thread. The delegate-AtomicReference makes the visibility safe (the IME sets
 * the delegate on the main thread; the pipeline reads it from its executor).
 * The delegate (when present) is responsible for posting UI work back to the
 * main looper.
 *
 * @see net.devemperor.dictate.core.PipelineOrchestrator.PipelineCallback
 * @see net.devemperor.dictate.core.DictatePipelineService
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §11.2.2 §7.3
 */
class PipelineCallbackBridge : PipelineOrchestrator.PipelineCallback {

    private val delegate = AtomicReference<PipelineOrchestrator.PipelineCallback?>(null)

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

    override fun onPipelineCompleted(text: String, source: InsertionSource) =
        dispatch("onPipelineCompleted") { it.onPipelineCompleted(text, source) }

    override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) =
        dispatch("onPipelineError") { it.onPipelineError(errorInfoKey, vibrate, providerName) }

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
