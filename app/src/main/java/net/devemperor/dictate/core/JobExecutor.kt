package net.devemperor.dictate.core

import android.content.Context
import android.util.Log
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionStatus
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide executor for all pipeline operations.
 *
 * Lifecycle: Kotlin object (singleton). Lazy-initialised on first access.
 * No explicit shutdown — dies with the app process.
 *
 * Responsibilities:
 * - Holds the [ExecutorService] for background pipeline work.
 * - Provides a single entry point ([start]) for every kind of pipeline job.
 * - Creates a [CancellationToken] per job for cooperative cancellation.
 * - Updates [ActiveJobRegistry] throughout the lifecycle.
 * - Finalizes session state in DB on completion/failure/cancel.
 *
 * `initialize(orchestrator)` MUST be called once — from
 * `DictateInputMethodService.onCreate()` — so that both the IME service and
 * `HistoryDetailActivity` can start jobs without owning their own orchestrator
 * instance (Finding SEC-10-2).
 */
object JobExecutor {

    private const val TAG = "JobExecutor"

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /** The cancellation token for the currently active job, or null if idle. */
    @Volatile
    private var activeToken: CancellationToken? = null

    /** The thread running the current job — used for last-resort interrupt. */
    @Volatile
    private var activeThread: Thread? = null

    /**
     * Finding SEC-10-2: The orchestrator is held internally so that both the
     * IME service and HistoryDetailActivity can start jobs without owning their
     * own orchestrator instance.
     *
     * Stored as [PipelineRunner] — the minimal contract JobExecutor needs —
     * so unit tests can swap in a fake without constructing a full
     * [PipelineOrchestrator]. Production wiring hands in the real orchestrator
     * which implements [PipelineRunner] by delegating to its `*Blocking` APIs.
     */
    @Volatile
    private var orchestrator: PipelineRunner? = null

    fun initialize(orchestrator: PipelineOrchestrator) {
        this.orchestrator = PipelineOrchestratorRunner(orchestrator)
    }

    /** Testing seam — installs a [PipelineRunner] directly. */
    @JvmStatic
    internal fun initializeForTest(runner: PipelineRunner) {
        this.orchestrator = runner
    }

    /**
     * Testing seam — clears state between tests.
     *
     * R-7 3rd axis (B5-VAL F-6, after the B2 `ActiveJobRegistry` and
     * B3 `DurationHealingScheduler` resetForTest seams). The
     * [executor] is a **process-global single-thread** FIFO worker. A
     * previous same-fork test's still-finishing job [Runnable] (its
     * `finally` at the end of [start] still draining —
     * `activeToken`/`activeThread`/registry cleanup) keeps the worker
     * busy; the next test's `submit` then queues *behind* it →
     * `PipelineRunnerSubsystemAdapterTest` "blocking runner did not
     * start" within its 2 s await (testRelease-only — release
     * co-locates forks more aggressively, the same R-7
     * timing-amplification family). `ActiveJobRegistry.waitForRegistry-
     * Empty()` waits on the registry, not on this executor's work
     * queue, so the registry can be empty while the worker thread is
     * still inside a predecessor's `finally`.
     *
     * Fix: submit a no-op sentinel and block until it runs. Because the
     * single worker is FIFO, the sentinel can only run *after* every
     * previously-submitted job [Runnable] — including its `finally` —
     * has completed. This drains the queue deterministically without
     * making [executor] mutable (rejected the recreate-executor
     * alternative — a `val`→`var` production-mutability footgun for a
     * test-only concern). The `@After` already calls `resetForTest()`
     * first, so no test edit is needed.
     */
    @JvmStatic
    internal fun resetForTest() {
        // Drain the FIFO worker: the sentinel runs only after any
        // in-flight job Runnable (incl. its finally) completes.
        val drained = java.util.concurrent.CountDownLatch(1)
        executor.submit { drained.countDown() }
        check(drained.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            "JobExecutor.resetForTest: executor quiescence drain timed out " +
                "(5s) — a previous job Runnable did not finish"
        }
        this.orchestrator = null
        this.activeToken = null
        this.activeThread = null
    }

    /**
     * Starts a new job. Returns false if another job is already active.
     *
     * `context` is captured for the failure path where we update the session
     * row to FAILED via Room. Nullable so unit tests can drive the lifecycle
     * without pulling in an Android [Context] (the tests never hit the
     * failure path).
     */
    fun start(context: Context?, request: JobRequest): Boolean {
        val orchestrator = this.orchestrator
            ?: throw IllegalStateException(
                "JobExecutor not initialized — call initialize() first"
            )

        val initial = JobState.Running(
            sessionId = request.sessionId,
            currentStepIndex = 0,
            totalSteps = request.totalSteps,
            currentStepName = "",
            startedAt = System.currentTimeMillis()
        )

        if (!ActiveJobRegistry.register(request.sessionId, initial)) {
            Log.w(TAG, "Cannot start job — another job is already active")
            return false
        }

        val token = CancellationToken()
        activeToken = token

        executor.submit {
            activeThread = Thread.currentThread()
            try {
                // K1 Fix: Call the *Blocking* pipeline methods so the job
                // actually runs on THIS executor thread. Previously we called
                // the legacy async wrappers, which submitted work onto the
                // orchestrator's own executor — and returned immediately.
                // That made the registry's `unregister` fire before the real
                // pipeline finished (badges flicker, single-job lock broken,
                // `JobExecutor.cancel()` hitting the wrong thread).
                //
                // Finding CA-4: Sealed-class dispatch — no force-unwraps needed.
                when (request) {
                    is JobRequest.TranscriptionPipeline -> orchestrator.runTranscription(
                        request.toPipelineConfig(),
                        /* reuseSessionId = */ request.reuseSessionId,
                        /* token = */ token
                    )
                    is JobRequest.Resume -> orchestrator.resume(
                        request.sessionId,
                        token
                    )
                    is JobRequest.StepRegenerate -> orchestrator.regenerate(
                        request,
                        token
                    )
                    is JobRequest.PostProcess -> orchestrator.postProcess(request)
                    is JobRequest.TranscriptionRerun -> orchestrator.rerunTranscription(request)
                    is JobRequest.ConversationContinuation ->
                        orchestrator.continueConversation(request, token)
                }
                // Orchestrator writes terminal COMPLETED itself (via
                // sessionManager.finalizeCompleted). Nothing to do here.
            } catch (e: java.util.concurrent.CancellationException) {
                // Finding CA-1 + SEC-5-1: CancellationException means the
                // cooperative token was triggered. The orchestrator already
                // wrote CANCELLED via sessionManager.finalizeCancelled; don't
                // overwrite it here.
                if (token.isCancelled) {
                    Log.i(TAG, "Job cancelled: ${request.sessionId}")
                } else {
                    finalizeFailed(context, request.sessionId, e)
                }
            } catch (e: InterruptedException) {
                // Thread.interrupt() fallback — treat as cancel.
                Log.i(TAG, "Job interrupted (cancel fallback): ${request.sessionId}")
            } catch (e: Exception) {
                // Finding CA-1: Check if this is a race with user cancel.
                if (token.isCancelled) {
                    Log.i(TAG, "Job failed after cancel — ignoring: ${request.sessionId}", e)
                } else {
                    Log.e(TAG, "Job failed: ${request.sessionId}", e)
                    finalizeFailed(context, request.sessionId, e)
                }
            } finally {
                activeToken = null
                activeThread = null
                ActiveJobRegistry.unregister(request.sessionId)
            }
        }
        return true
    }

    /**
     * Cancels the currently active job via cooperative token + last-resort
     * interrupt.
     *
     * 1. Set the CancellationToken flag — the orchestrator checks this at
     *    defined checkpoints (before each step, after each API call).
     * 2. Send Thread.interrupt() as fallback — catches blocking OkHttp calls
     *    that don't check the token.
     *
     * The orchestrator's finalize-on-cancel path writes the terminal CANCELLED
     * status to the DB. The registry is cleaned up in the executor's finally
     * block.
     */
    @Suppress("UNUSED_PARAMETER")
    fun cancel(sessionId: String) {
        activeToken?.cancel()
        activeThread?.interrupt()
    }

    private fun finalizeFailed(context: Context?, sessionId: String, error: Throwable) {
        // Unit tests pass a mock / null context and never reach this path, so
        // defensively short-circuit when no context is available. Production
        // paths always have a real context from the IME service / activity.
        if (context == null) {
            Log.w(TAG, "finalizeFailed skipped (no context) for $sessionId", error)
            return
        }
        val dao = DictateDatabase.getInstance(context).sessionDao()
        val (errorType, errorMessage) = classifyError(error)
        dao.updateStatus(sessionId, SessionStatus.FAILED.name)
        dao.updateError(sessionId, errorType, errorMessage)
    }

    private fun classifyError(error: Throwable): Pair<String, String> = when (error) {
        is AIProviderException -> {
            // Defence-in-depth: CANCELLED must NEVER be persisted as last_error_type
            // (violates the sessions.last_error_type CHECK constraint — cancellation
            // is expressed via status=CANCELLED, not as an error row). If a provider
            // raises CANCELLED but the JobExecutor-level token is not flipped (rare
            // race), downgrade to UNKNOWN here so the DB write stays valid.
            val type = if (error.errorType == AIProviderException.ErrorType.CANCELLED) {
                AIProviderException.ErrorType.UNKNOWN
            } else {
                error.errorType
            }
            type.name to (error.message ?: "unknown")
        }
        else -> AIProviderException.ErrorType.UNKNOWN.name to (
            error.message ?: error.javaClass.simpleName
        )
    }
}

/**
 * Unified request descriptor for [JobExecutor.start].
 *
 * Finding CA-4: Modeled as a sealed class with type-safe variants per JobKind.
 * This eliminates force-unwraps (stepChainIndex!!, postProcessInputText!!) and
 * makes it impossible to construct an invalid request (e.g., a STEP_REGENERATE
 * without a stepChainIndex).
 */
sealed class JobRequest {
    abstract val sessionId: String
    abstract val totalSteps: Int

    /** Initial recording pipeline or full reprocess (re-transcribe + all steps). */
    data class TranscriptionPipeline @JvmOverloads constructor(
        override val sessionId: String,
        override val totalSteps: Int,
        val kind: TranscriptionKind,
        val audioFilePath: String? = null,
        val language: String? = null,
        val modelOverride: String? = null,
        /**
         * Prompt queue as content-capable slots (queue-editor transport
         * model, research doc "reprocess-queue-editor" §2.1). ID-only
         * slots preserve the legacy entity-ID semantics; text-carrying
         * slots let free-text prompts and since-deleted saved prompts
         * survive the trip. See [PromptQueueSlot] for the three shapes.
         *
         * Nullability is load-bearing (verification defect 1):
         *  - `null`  = UNSET — no explicit queue travelled with the request;
         *    the pipeline falls back to the live auto-apply queue at run
         *    time (legacy keyboard semantics; see
         *    [PromptQueueSlot.fromIdsOrUnset]).
         *  - empty   = EXPLICITLY NONE — e.g. the reprocess queue editor
         *    was emptied; the pipeline runs zero prompts and must NOT read
         *    the live keyboard queue.
         * Chosen over gating the fallback on [origin] because the intent
         * lives in the queue itself, not in session provenance — and the
         * F-001 staged-queue fix simply switches its seam from
         * `fromIdsOrUnset` to an explicit list without another transport
         * change.
         */
        val queuedPromptSlots: List<PromptQueueSlot>? = null,
        val targetAppPackage: String? = null,
        val recordingsDir: java.io.File,
        /** null = brand-new session; non-null = reprocess an existing session. */
        val reuseSessionId: String? = null,
        val stylePrompt: String? = null,
        val origin: net.devemperor.dictate.database.entity.SessionOrigin =
            net.devemperor.dictate.database.entity.SessionOrigin.KEYBOARD,
        /**
         * W3: Propagates `PipelineConfig.livePrompt` for the initial
         * recording flow. When true, queued prompts are suppressed so the
         * IME can chain a live-prompt follow-up via `runStandalonePrompt`.
         */
        val livePrompt: Boolean = false,
        /** W3: Propagates `PipelineConfig.autoSwitchKeyboard`. */
        val autoSwitchKeyboard: Boolean = false,
        /** W3: Propagates `PipelineConfig.showResendButton`. */
        val showResendButton: Boolean = false
    ) : JobRequest() {
        /**
         * W6: `toPipelineConfig()` is only defined on [TranscriptionPipeline] —
         * the other variants ([Resume], [StepRegenerate], [PostProcess]) operate
         * on existing sessions and don't need a [PipelineOrchestrator.PipelineConfig].
         *
         * (Deviates from plan Phase 4.2 for SOLID/ISP reasons: lifting this to an
         * abstract method on [JobRequest] with empty defaults on the other variants
         * would force them to carry fields they don't use, violating the Interface
         * Segregation Principle.)
         */
        fun toPipelineConfig() = PipelineOrchestrator.PipelineConfig(
            audioFile = audioFilePath?.let { java.io.File(it) },
            language = language,
            stylePrompt = stylePrompt,
            livePrompt = livePrompt,
            autoSwitchKeyboard = autoSwitchKeyboard,
            showResendButton = showResendButton,
            recordingsDir = recordingsDir,
            targetAppPackage = targetAppPackage,
            origin = origin,
            // W2: `modelOverride` is forwarded into PipelineConfig but the AI
            // runner layer (AIOrchestrator.transcribe / .complete) does NOT yet
            // accept a per-call override — runner-layer signature extension is
            // scheduled for Chunk 3. For now this value is deliberately ignored
            // inside the orchestrator. The call-site already passes the correct
            // value so Chunk 3 only needs to touch the runner APIs, not the
            // job-request surface. See PipelineOrchestrator.PipelineConfig.modelOverride.
            // TODO(Chunk 3): wire `modelOverride` through AIOrchestrator.
            modelOverride = modelOverride,
            queuedPromptSlots = queuedPromptSlots,
            // W3: for a brand-new session (reuseSessionId == null) the
            // orchestrator must persist under THIS sessionId, because
            // JobExecutor has already registered this ID in ActiveJobRegistry
            // before the pipeline runs. For reprocess/resume we pass null
            // because persistNewSession isn't called on that path.
            preAllocatedSessionId = if (reuseSessionId == null) sessionId else null
        )
    }

    enum class TranscriptionKind { RECORDING, REPROCESS_STAGING, HISTORY_REPROCESS }

    /** Short-press resend — continue from failure point. */
    data class Resume(
        override val sessionId: String,
        override val totalSteps: Int
    ) : JobRequest()

    /**
     * Regenerate a single processing step (history "Regenerate" / "Other
     * prompt" — F-055: routed through [JobExecutor] so the operation survives
     * the Activity, registers in [ActiveJobRegistry] and is mutually exclusive
     * with reprocess).
     *
     * Without an override, the job layer re-derives the prompt from the
     * persisted step (`promptUsed`) — see
     * [PipelineOrchestrator.regenerateStepBlocking]. The override is a
     * [PromptQueueSlot] — the same "(text, optional entityId)" queue-slot
     * type the reprocess queue editor transports — so free-text prompts and
     * since-deleted saved prompts both work. An override slot must carry
     * text (an ID-only slot has nothing to override *with*; the init guard
     * keeps that unconstructible).
     */
    data class StepRegenerate @JvmOverloads constructor(
        override val sessionId: String,
        override val totalSteps: Int,
        val stepChainIndex: Int,
        val promptOverride: PromptQueueSlot? = null
    ) : JobRequest() {
        init {
            require(promptOverride == null || promptOverride.text != null) {
                "StepRegenerate.promptOverride must carry prompt text — " +
                    "an ID-only slot cannot serve as an override"
            }
        }
    }

    /**
     * Post-processing completion on a NEW session (history "Post-process" —
     * F-055/F-111). [sessionId] is caller-pre-allocated (like
     * [TranscriptionPipeline]'s `preAllocatedSessionId`) so [JobExecutor] can
     * register it in [ActiveJobRegistry] before the job runs; the session ROW
     * is created inside the job body (F-111 — a chooser dismissed before this
     * request is built must leave no orphan row, and a rejected `start` must
     * not either).
     *
     * The prompt travels as a [PromptQueueSlot] — the one queue-slot type of
     * the transport model (same as [StepRegenerate.promptOverride] and
     * [TranscriptionPipeline.queuedPromptSlots]) — instead of a raw
     * `(promptText, promptId)` pair. Must carry text: an ID-only slot has no
     * content to apply (same init-guard pattern as [StepRegenerate]).
     */
    data class PostProcess(
        override val sessionId: String,
        override val totalSteps: Int,
        /** Session whose step output is being post-processed (parent link). */
        val parentSessionId: String,
        val inputText: String,
        val promptSlot: PromptQueueSlot
    ) : JobRequest() {
        init {
            require(promptSlot.text != null) {
                "PostProcess.promptSlot must carry prompt text — " +
                    "an ID-only slot has no content to apply"
            }
        }
    }

    /**
     * Re-transcribes a session's stored audio as a **new transcription
     * version** (R6). The re-run reads the session's audio through the same
     * multi-segment merge the initial pipeline uses, transcribes it with the
     * session's persisted language, and persists the result via
     * [SessionManager.addTranscriptionVersion] — so the DB-present-but-UI-dead
     * transcription version chain (`TranscriptionEntity.version`/`is_current`)
     * finally gets a consumer (D4: no schema change, a re-run version is
     * indistinguishable from a reprocess version).
     *
     * Deliberately does **NOT** touch the processing chain (D3). Re-running a
     * transcription version leaves any downstream completion steps' snapshotted
     * `input_text` untouched; the resulting staleness is surfaced in the UI
     * (transcription card's warning line), and re-running downstream stays the
     * reprocess buttons' job. This keeps R6 non-destructive and cheap, mirrors
     * the step-version semantics, and avoids surprise multi-step re-billing.
     *
     * Model / keyterms come from the *current* prefs (not persisted per
     * session), so a re-run may legitimately differ from v1 for reasons other
     * than audio — accepted as inherent to re-running (spec Information Gap 2).
     *
     * @see docs/research/2026-07-02 - history-ui-overhaul.md §3.4, D3, D4
     */
    data class TranscriptionRerun(
        override val sessionId: String,
        override val totalSteps: Int = 1
    ) : JobRequest()

    /**
     * Appends a dictated review-refinement follow-up turn to an existing
     * conversation (ADR-0013). Runs on the serialized run-queue (ADR-0009) like
     * a regenerate — off the main pipeline FSM — and surfaces its result via the
     * non-terminal `onReviewTurnCompleted` callback (never the guarded terminal
     * one). [followUpText] is the transcript of the refinement recording (S2).
     */
    data class ConversationContinuation(
        override val sessionId: String,
        val followUpText: String,
        override val totalSteps: Int = 1
    ) : JobRequest()
}

/**
 * Minimal abstraction over the pipeline implementation, so [JobExecutor] can
 * be unit-tested with a fake runner (see JobExecutorTest). Only the
 * synchronous (`*Blocking`) entry points are exposed — these are what
 * JobExecutor actually calls from its executor thread.
 */
interface PipelineRunner {
    fun runTranscription(
        config: PipelineOrchestrator.PipelineConfig,
        reuseSessionId: String?,
        token: CancellationToken
    )

    fun resume(sessionId: String, token: CancellationToken)

    // regenerate/postProcess take the sealed request objects so future fields
    // (e.g. the queue-editor's queue slots) extend the transport without
    // re-touching every PipelineRunner implementation/fake.
    fun regenerate(request: JobRequest.StepRegenerate, token: CancellationToken)

    fun postProcess(request: JobRequest.PostProcess)

    // R6: takes the sealed request object (same extension-friendly seam as
    // regenerate/postProcess) — a re-run needs only the session id today, but
    // routing the request keeps future fields off every implementation/fake.
    fun rerunTranscription(request: JobRequest.TranscriptionRerun)

    // ADR-0013: dictated review-refinement follow-up turn. Same
    // request-object seam as regenerate/postProcess.
    fun continueConversation(request: JobRequest.ConversationContinuation, token: CancellationToken)
}

/** Production [PipelineRunner] that delegates to a real [PipelineOrchestrator]. */
class PipelineOrchestratorRunner(
    private val orchestrator: PipelineOrchestrator
) : PipelineRunner {
    override fun runTranscription(
        config: PipelineOrchestrator.PipelineConfig,
        reuseSessionId: String?,
        token: CancellationToken
    ) = orchestrator.runTranscriptionPipelineBlocking(config, reuseSessionId, token)

    override fun resume(sessionId: String, token: CancellationToken) =
        orchestrator.resumePipelineBlocking(sessionId, token)

    override fun regenerate(request: JobRequest.StepRegenerate, token: CancellationToken) =
        orchestrator.regenerateStepBlocking(
            request.sessionId,
            request.stepChainIndex,
            token,
            request.promptOverride?.text,
            request.promptOverride?.entityId
        )

    override fun postProcess(request: JobRequest.PostProcess) =
        orchestrator.runPostProcessingBlocking(
            request.sessionId,
            request.parentSessionId,
            request.inputText,
            // Non-null by the PostProcess init guard (text-bearing slot).
            requireNotNull(request.promptSlot.text),
            request.promptSlot.entityId
        )

    override fun rerunTranscription(request: JobRequest.TranscriptionRerun) =
        orchestrator.rerunTranscriptionBlocking(request.sessionId)

    override fun continueConversation(
        request: JobRequest.ConversationContinuation,
        token: CancellationToken
    ) = orchestrator.continueConversationBlocking(request.sessionId, request.followUpText, token)
}
