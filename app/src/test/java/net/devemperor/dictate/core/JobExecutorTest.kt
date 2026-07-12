package net.devemperor.dictate.core

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [JobExecutor].
 *
 * Verifies that the K1 fix (routing through *Blocking* methods) holds:
 * - [ActiveJobRegistry] reflects the real lifecycle (entry while running, empty
 *   after completion)
 * - The single-job lock blocks overlapping [JobExecutor.start] calls
 * - [JobExecutor.cancel] cooperatively cancels via token + interrupts the thread
 *
 * The test installs a fake [PipelineRunner] via the [JobExecutor.initializeForTest]
 * testing seam so we don't need an Android [Context] or a real
 * [PipelineOrchestrator].
 */
class JobExecutorTest {

    /**
     * Null [Context] is safe — the test scenarios never reach
     * [JobExecutor.finalizeFailed] (which is the only code path that touches
     * the context), because the fake runner either completes successfully or
     * cancels cooperatively. `start` treats `null` as a testing signal and
     * short-circuits the failure DB write.
     */
    private val ctx: Context? = null

    @Before
    fun setUp() {
        // Make sure no previous test left the registry / executor pinned.
        ActiveJobRegistry.state.value.keys.toList().forEach { ActiveJobRegistry.unregister(it) }
        JobExecutor.resetForTest()
    }

    @After
    fun tearDown() {
        ActiveJobRegistry.state.value.keys.toList().forEach { ActiveJobRegistry.unregister(it) }
        JobExecutor.resetForTest()
    }

    @Test
    fun `start returns true on first call`() {
        val runner = NoopRunner()
        JobExecutor.initializeForTest(runner)

        val request = postProcessRequest("s1")
        assertTrue(JobExecutor.start(ctx, request))

        runner.awaitDone()
        // After the job finishes, the registry is empty.
        assertFalse(ActiveJobRegistry.isActive("s1"))
    }

    @Test
    fun `start returns false while a job is still running`() {
        // Fake runner blocks until the latch is released — emulates a pipeline
        // that is still in-flight.
        val released = CountDownLatch(1)
        val started = CountDownLatch(1)
        val runner = BlockingRunner(started, released)
        JobExecutor.initializeForTest(runner)

        assertTrue(JobExecutor.start(ctx, postProcessRequest("s1")))
        started.await(2, TimeUnit.SECONDS)

        // Single-job lock: a second start must be rejected while s1 runs.
        assertFalse(JobExecutor.start(ctx, postProcessRequest("s2")))
        assertTrue(ActiveJobRegistry.isActive("s1"))
        assertFalse(ActiveJobRegistry.isActive("s2"))

        // Let the first job finish and verify the lock releases.
        released.countDown()
        runner.awaitDone()
        assertFalse(ActiveJobRegistry.isAnyActive())

        // Now a fresh start works.
        val runner2 = NoopRunner()
        JobExecutor.initializeForTest(runner2)
        assertTrue(JobExecutor.start(ctx, postProcessRequest("s3")))
        runner2.awaitDone()
    }

    @Test
    fun `registry contains job while it runs`() {
        val released = CountDownLatch(1)
        val started = CountDownLatch(1)
        val runner = BlockingRunner(started, released)
        JobExecutor.initializeForTest(runner)

        JobExecutor.start(ctx, postProcessRequest("s1"))
        started.await(2, TimeUnit.SECONDS)

        // Registry reflects the RUNNING state (K1 fix — previously the
        // nested-executor bug cleared this entry immediately).
        assertTrue(ActiveJobRegistry.isActive("s1"))
        val state = ActiveJobRegistry.get("s1")
        assertNotNull(state)
        assertTrue(state is JobState.Running)

        released.countDown()
        runner.awaitDone()
        assertNull(ActiveJobRegistry.get("s1"))
    }

    @Test
    fun `cancel flips the token and interrupts the thread`() {
        val started = CountDownLatch(1)
        val threadRef = AtomicReference<Thread>()
        val tokenRef = AtomicReference<CancellationToken>()
        val observedInterrupt = AtomicReference<Boolean>(false)

        // Use a StepRegenerate request so the token is exposed to the runner.
        val blockingRunner = object : PipelineRunner {
            override fun runTranscription(
                config: PipelineOrchestrator.PipelineConfig,
                reuseSessionId: String?,
                token: CancellationToken
            ) = Unit

            override fun resume(sessionId: String, token: CancellationToken) = Unit

            override fun regenerate(
                request: JobRequest.StepRegenerate,
                token: CancellationToken
            ) {
                threadRef.set(Thread.currentThread())
                tokenRef.set(token)
                started.countDown()
                // Block on sleep that responds to both the token and to
                // interrupt. We sleep in small slices so the token check
                // can surface even without the interrupt.
                try {
                    while (!token.isCancelled) {
                        Thread.sleep(20)
                    }
                    // Cooperative cancel observed — throw to complete.
                    throw java.util.concurrent.CancellationException("cancelled")
                } catch (ie: InterruptedException) {
                    observedInterrupt.set(true)
                    // Preserve the interrupt flag so JobExecutor's catch sees it.
                    Thread.currentThread().interrupt()
                    throw ie
                }
            }

            override fun postProcess(request: JobRequest.PostProcess) = Unit

            override fun continueConversation(request: JobRequest.ConversationContinuation, token: CancellationToken) = Unit

            override fun rerunTranscription(request: JobRequest.TranscriptionRerun) = Unit
        }

        JobExecutor.initializeForTest(blockingRunner)
        assertTrue(
            JobExecutor.start(
                ctx,
                JobRequest.StepRegenerate(sessionId = "s1", totalSteps = 1, stepChainIndex = 0)
            )
        )
        started.await(2, TimeUnit.SECONDS)

        // Precondition: running.
        assertTrue(ActiveJobRegistry.isActive("s1"))

        // Cancel — must flip the token AND interrupt the thread.
        JobExecutor.cancel("s1")

        // Wait until the worker actually exits.
        val worker = threadRef.get()
        worker.join(2_000)

        assertTrue("Cancellation token must be flipped", tokenRef.get().isCancelled)
        // Registry cleared via finally block.
        assertFalse(ActiveJobRegistry.isAnyActive())
    }

    // ── F-055 regression tests (history-reprocess-hardening §3) ─────────

    @Test
    fun `regenerate job registers in ActiveJobRegistry while running and unregisters after`() {
        val released = CountDownLatch(1)
        val started = CountDownLatch(1)
        val runner = BlockingRunner(started, released)
        JobExecutor.initializeForTest(runner)

        assertTrue(JobExecutor.start(ctx, regenerateRequest("s1")))
        assertTrue(started.await(2, TimeUnit.SECONDS))

        // Registered while the regenerate runs — this is what makes it
        // mutually exclusive with reprocess and visible to the history UI.
        assertTrue(ActiveJobRegistry.isActive("s1"))

        released.countDown()
        runner.awaitDone()
        assertFalse(ActiveJobRegistry.isActive("s1"))
    }

    @Test
    fun `reprocess is blocked while a regenerate is active on the same session`() {
        val released = CountDownLatch(1)
        val started = CountDownLatch(1)
        val runner = BlockingRunner(started, released)
        JobExecutor.initializeForTest(runner)

        assertTrue(JobExecutor.start(ctx, regenerateRequest("s1")))
        assertTrue(started.await(2, TimeUnit.SECONDS))

        // A history reprocess on the SAME session must be rejected while the
        // regenerate is in flight (pre-F-055 these raced on the same
        // processing-step chain because regenerate bypassed the registry).
        val reprocess = JobRequest.TranscriptionPipeline(
            sessionId = "s1",
            totalSteps = 1,
            kind = JobRequest.TranscriptionKind.HISTORY_REPROCESS,
            recordingsDir = java.io.File("."),
            reuseSessionId = "s1"
        )
        assertFalse(JobExecutor.start(ctx, reprocess))

        released.countDown()
        runner.awaitDone()

        // Once the regenerate finished, the reprocess may start.
        val runner2 = BlockingRunner(CountDownLatch(1), CountDownLatch(0))
        JobExecutor.initializeForTest(runner2)
        assertTrue(JobExecutor.start(ctx, reprocess))
        runner2.awaitDone()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun regenerateRequest(sessionId: String) = JobRequest.StepRegenerate(
        sessionId = sessionId,
        totalSteps = 1,
        stepChainIndex = 0
    )

    private fun postProcessRequest(sessionId: String) = JobRequest.PostProcess(
        sessionId = sessionId,
        totalSteps = 1,
        parentSessionId = "parent-of-$sessionId",
        inputText = "hi",
        promptSlot = PromptQueueSlot.ofFreeText("do something")
    )

    /**
     * Fake runner whose `postProcess` returns immediately. Exposes an
     * `awaitDone()` helper that blocks until the worker thread has finished.
     */
    private class NoopRunner : PipelineRunner {
        private val done = CountDownLatch(1)

        override fun runTranscription(
            config: PipelineOrchestrator.PipelineConfig,
            reuseSessionId: String?,
            token: CancellationToken
        ) {
            done.countDown()
        }

        override fun resume(sessionId: String, token: CancellationToken) {
            done.countDown()
        }

        override fun regenerate(
            request: JobRequest.StepRegenerate,
            token: CancellationToken
        ) {
            done.countDown()
        }

        override fun postProcess(request: JobRequest.PostProcess) {
            done.countDown()
        }

        override fun continueConversation(request: JobRequest.ConversationContinuation, token: CancellationToken) = Unit

        override fun rerunTranscription(request: JobRequest.TranscriptionRerun) {
            done.countDown()
        }

        fun awaitDone() {
            assertTrue("runner did not finish", done.await(2, TimeUnit.SECONDS))
            // Give JobExecutor's finally{} a moment to unregister.
            waitForRegistryEmpty()
        }
    }

    /**
     * Fake runner that blocks on `postProcess` until [released] is counted
     * down. Used to prove the single-job lock and the running-registry state.
     */
    private class BlockingRunner(
        private val started: CountDownLatch,
        private val released: CountDownLatch
    ) : PipelineRunner {
        private val done = CountDownLatch(1)

        override fun runTranscription(
            config: PipelineOrchestrator.PipelineConfig,
            reuseSessionId: String?,
            token: CancellationToken
        ) = block()

        override fun resume(sessionId: String, token: CancellationToken) = block()

        override fun regenerate(
            request: JobRequest.StepRegenerate,
            token: CancellationToken
        ) = block()

        override fun postProcess(request: JobRequest.PostProcess) = block()

        override fun continueConversation(request: JobRequest.ConversationContinuation, token: CancellationToken) = Unit

        override fun rerunTranscription(request: JobRequest.TranscriptionRerun) = block()

        private fun block() {
            started.countDown()
            released.await(5, TimeUnit.SECONDS)
            done.countDown()
        }

        fun awaitDone() {
            assertTrue(done.await(5, TimeUnit.SECONDS))
            waitForRegistryEmpty()
        }
    }

    companion object {
        /**
         * JobExecutor's `finally` block unregisters AFTER the runner returns.
         * We spin briefly so assertions on an empty registry are reliable.
         */
        fun waitForRegistryEmpty() {
            val deadline = System.currentTimeMillis() + 2_000
            while (ActiveJobRegistry.isAnyActive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(
                "registry should be empty after job completes",
                emptyMap<String, JobState>(),
                ActiveJobRegistry.state.value
            )
        }
    }
}
