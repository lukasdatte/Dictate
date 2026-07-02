package net.devemperor.dictate.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Transport-model round-trip tests for the queue-editor slot queue
 * (research doc "reprocess-queue-editor" §2.1): slots with and without an
 * `entityId` must reach the pipeline runner **in order and with their
 * content intact** — the pre-slot `queuedPromptIds: List<Int>` transport
 * silently dropped free-text prompts (the F-110 dead-end).
 *
 * Pure JVM (K-1: handwritten fakes via [JobExecutor.initializeForTest],
 * no mocking framework) — same harness as [JobExecutorTest].
 */
class PromptQueueTransportTest {

    private val mixedQueue = listOf(
        PromptQueueSlot.ofSavedPrompt(7),
        PromptQueueSlot.ofFreeText("Translate to English"),
        PromptQueueSlot.ofContent("Make it formal", 9)
    )

    @Before
    fun setUp() {
        ActiveJobRegistry.state.value.keys.toList().forEach { ActiveJobRegistry.unregister(it) }
        JobExecutor.resetForTest()
    }

    @After
    fun tearDown() {
        ActiveJobRegistry.state.value.keys.toList().forEach { ActiveJobRegistry.unregister(it) }
        JobExecutor.resetForTest()
    }

    // ── Slot invariants ───────────────────────────────────────────────────

    @Test
    fun `a slot without text AND without entityId is unconstructible`() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptQueueSlot(text = null, entityId = null)
        }
    }

    @Test
    fun `regenerate override must carry text - an ID-only slot is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            JobRequest.StepRegenerate(
                sessionId = "s1",
                totalSteps = 1,
                stepChainIndex = 0,
                promptOverride = PromptQueueSlot.ofSavedPrompt(3)
            )
        }
    }

    // ── JobRequest → PipelineConfig ───────────────────────────────────────

    @Test
    fun `toPipelineConfig preserves slot order and content`() {
        val request = JobRequest.TranscriptionPipeline(
            sessionId = "s1",
            totalSteps = 4,
            kind = JobRequest.TranscriptionKind.HISTORY_REPROCESS,
            audioFilePath = "/tmp/a.m4a",
            language = "de",
            modelOverride = null,
            queuedPromptSlots = mixedQueue,
            targetAppPackage = null,
            recordingsDir = java.io.File("/tmp"),
            reuseSessionId = "s1"
        )

        assertEquals(mixedQueue, request.toPipelineConfig().queuedPromptSlots)
    }

    // ── JobExecutor → PipelineRunner round-trip ───────────────────────────

    @Test
    fun `slot queue reaches the pipeline runner in order - free-text and entity slots intact`() {
        val runner = CapturingRunner()
        JobExecutor.initializeForTest(runner)

        val request = JobRequest.TranscriptionPipeline(
            sessionId = "s-transport",
            totalSteps = 4,
            kind = JobRequest.TranscriptionKind.HISTORY_REPROCESS,
            audioFilePath = "/tmp/a.m4a",
            language = null,
            modelOverride = null,
            queuedPromptSlots = mixedQueue,
            targetAppPackage = null,
            recordingsDir = java.io.File("/tmp"),
            reuseSessionId = "s-transport"
        )

        assertTrue(JobExecutor.start(/* context */ null, request))
        runner.awaitDone()

        val config = runner.config.get()!!
        assertEquals(mixedQueue, config.queuedPromptSlots)
        // Order matters (Gap-2: queue executes as a sequential chain) —
        // spot-check the shape mix survived, not just list equality.
        assertEquals(7, config.queuedPromptSlots[0].entityId)
        assertEquals(null, config.queuedPromptSlots[0].text)
        assertEquals("Translate to English", config.queuedPromptSlots[1].text)
        assertEquals(null, config.queuedPromptSlots[1].entityId)
        assertEquals("Make it formal", config.queuedPromptSlots[2].text)
        assertEquals(9, config.queuedPromptSlots[2].entityId)
    }

    /** Records the [PipelineOrchestrator.PipelineConfig] it was started with. */
    private class CapturingRunner : PipelineRunner {
        val config = AtomicReference<PipelineOrchestrator.PipelineConfig?>(null)
        private val done = CountDownLatch(1)

        override fun runTranscription(
            config: PipelineOrchestrator.PipelineConfig,
            reuseSessionId: String?,
            token: CancellationToken
        ) {
            this.config.set(config)
            done.countDown()
        }

        override fun resume(sessionId: String, token: CancellationToken) = Unit
        override fun regenerate(request: JobRequest.StepRegenerate, token: CancellationToken) = Unit
        override fun postProcess(request: JobRequest.PostProcess) = Unit

        fun awaitDone() {
            assertTrue(
                "pipeline runner was not reached within 2s",
                done.await(2, TimeUnit.SECONDS)
            )
        }
    }
}
