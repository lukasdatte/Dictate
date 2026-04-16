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

    /** Testing seam — clears state between tests. */
    @JvmStatic
    internal fun resetForTest() {
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
                        request.sessionId,
                        request.stepChainIndex,
                        token
                    )
                    is JobRequest.PostProcess -> orchestrator.postProcess(
                        request.sessionId,
                        request.inputText,
                        request.promptText,
                        request.promptId
                    )
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
        val queuedPromptIds: List<Int> = emptyList(),
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
            queuedPromptIds = queuedPromptIds,
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

    /** Regenerate a single processing step. */
    data class StepRegenerate(
        override val sessionId: String,
        override val totalSteps: Int,
        val stepChainIndex: Int
    ) : JobRequest()

    /** Post-processing chain. */
    data class PostProcess(
        override val sessionId: String,
        override val totalSteps: Int,
        val inputText: String,
        val promptText: String,
        val promptId: Int? = null
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

    fun regenerate(sessionId: String, stepChainIndex: Int, token: CancellationToken)

    fun postProcess(sessionId: String, inputText: String, promptText: String, promptId: Int?)
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

    override fun regenerate(sessionId: String, stepChainIndex: Int, token: CancellationToken) =
        orchestrator.regenerateStepBlocking(sessionId, stepChainIndex, token)

    override fun postProcess(sessionId: String, inputText: String, promptText: String, promptId: Int?) =
        orchestrator.runPostProcessingBlocking(sessionId, inputText, promptText, promptId)
}
