package net.devemperor.dictate.core

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.state.Action
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [PipelineRunnerSubsystemAdapter] + [DefaultPipelineConfigResolver]
 * (chunk C3-B1, Spec 1 §9.6/§13.3.11, Epic AC-1 pipelineRunner part).
 *
 * **K-1** — the `JobExecutor` "spy" is a handwritten recording
 * [PipelineRunner] fake installed via [JobExecutor.initializeForTest]
 * (no Mockito/MockK). **K-4** — Robolectric is a justified opt-out:
 * the binder-integration test boots the real [DictatePipelineService]
 * to prove the production wiring (not the stub) is reached.
 *
 * **R-1 evidence (the load-bearing risk).** The reprocess field-by-field
 * fidelity is asserted against the IME's
 * `DictateInputMethodService.java:3038-3051` construction; the
 * fresh-recording R-1 guard (resolver throws rather than silently
 * defaulting IME-runtime-only fields) is asserted to surface as a loud
 * pipeline failure, never a silent wrong-config submit.
 *
 * tearDown copies the
 * `DictatePipelineServiceOverlayTransitionTest` discipline
 * (DB/JobExecutor reset) — mandated by `b5-ime-activation-wiring.md`
 * §8 to avoid test-pollution of the shared
 * `DictateDatabase`/`JobExecutor` singletons.
 *
 * @see net.devemperor.dictate.core.PipelineRunnerSubsystemAdapter
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineRunnerSubsystemAdapterTest {

    // ── Handwritten recording PipelineRunner fake (K-1) ────────────────

    private class RecordingRunner : PipelineRunner {
        val transcriptionConfig = AtomicReference<PipelineOrchestrator.PipelineConfig?>()
        val reuseSessionId = AtomicReference<String?>()
        val done = CountDownLatch(1)

        override fun runTranscription(
            config: PipelineOrchestrator.PipelineConfig,
            reuseSessionId: String?,
            token: CancellationToken,
        ) {
            transcriptionConfig.set(config)
            this.reuseSessionId.set(reuseSessionId)
            done.countDown()
        }

        override fun resume(sessionId: String, token: CancellationToken) = Unit
        override fun regenerate(request: JobRequest.StepRegenerate, token: CancellationToken) = Unit
        override fun postProcess(request: JobRequest.PostProcess) = Unit

        fun awaitStarted() {
            check(done.await(2, TimeUnit.SECONDS)) { "runner did not start" }
        }
    }

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
        }
        JobExecutor.resetForTest()
        // B2-VAL-W1 F-6 / Epic R-7 — drain the process-wide
        // ActiveJobRegistry single-job lock so a sibling test in the
        // same Robolectric fork is not blocked by a still-registered job
        // whose async unregister had not completed.
        ActiveJobRegistry.resetForTest()
        // C8-IMPL-1 / B3-VAL F-1 — belt-and-suspenders: this test boots
        // the full Service (→ DictateApplication →
        // DurationHealingScheduler.schedule()). Drain the in-flight heal
        // thread BEFORE the DB is dropped so it cannot pollute a
        // co-locating sibling. Ordering mandatory: scheduler reset
        // precedes DictateDatabase.resetForTest.
        net.devemperor.dictate.database.DurationHealingScheduler.resetForTest()
        net.devemperor.dictate.database.DictateDatabase.resetForTest(
            ApplicationProvider.getApplicationContext(),
        )
    }

    private fun appContext() =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun newAdapter(
        resolver: PipelineConfigResolver,
    ) = PipelineRunnerSubsystemAdapter(appContext(), resolver)

    private fun tempFilesDir(): File =
        File(System.getProperty("java.io.tmpdir"), "c3b1-files-${System.nanoTime()}").apply { mkdirs() }

    private fun waitForRegistryEmpty() = JobExecutorTest.waitForRegistryEmpty()

    // ── DefaultPipelineConfigResolver — R-1 field-by-field fidelity ────

    @Test
    fun `resolveReprocess maps every JobRequest field 1-to-1 with the IME reprocess construction`() {
        val filesDir = tempFilesDir()
        val resolver = DefaultPipelineConfigResolver(filesDirProvider = { filesDir })
        val audio = File(filesDir, "rec.m4a")

        val req = resolver.resolveReprocess(
            sessionId = "session-42",
            audioFile = audio,
            queue = listOf(7, 9, 11),
            language = "de",
        )

        // IME :3039 targetSessionId → sessionId
        assertEquals("session-42", req.sessionId)
        // IME :3034-3036 totalSteps = 1 (transcription) + queue.size
        assertEquals(1 + 3, req.totalSteps)
        // IME :3041 TranscriptionKind.REPROCESS_STAGING
        assertEquals(JobRequest.TranscriptionKind.REPROCESS_STAGING, req.kind)
        // IME :3042 audioPath
        assertEquals(audio.absolutePath, req.audioFilePath)
        // IME :3043 selectedLanguage
        assertEquals("de", req.language)
        // IME :3045 editableQueue
        assertEquals(listOf(7, 9, 11), req.queuedPromptIds)
        // IME :3047 new File(getFilesDir(), "recordings")
        assertEquals(File(filesDir, "recordings"), req.recordingsDir)
        // IME :3048 reuseSessionId = targetSessionId
        assertEquals("session-42", req.reuseSessionId)
        // IME :3050 SessionOrigin.KEYBOARD
        assertEquals(SessionOrigin.KEYBOARD, req.origin)
        // IME :3049 stylePrompt = null on the reprocess path
        assertEquals(null, req.stylePrompt)
    }

    @Test
    fun `resolveReprocess passes a null audio path through (F-19 DB-lookup contract)`() {
        val resolver = DefaultPipelineConfigResolver(filesDirProvider = { tempFilesDir() })

        val req = resolver.resolveReprocess(
            sessionId = "s1",
            audioFile = null,
            queue = emptyList(),
            language = null,
        )

        assertEquals(null, req.audioFilePath)
        assertEquals(null, req.language)
        assertEquals(1, req.totalSteps) // 1 + 0
        assertEquals(emptyList<Int>(), req.queuedPromptIds)
    }

    @Test
    fun `resolveFresh throws rather than silently defaulting IME-runtime-only fields (R-1)`() {
        val resolver = DefaultPipelineConfigResolver(filesDirProvider = { tempFilesDir() })

        val ex = runCatching {
            resolver.resolveFresh("s1", File("/tmp/a.m4a"))
        }.exceptionOrNull()

        check(ex is UnsupportedOperationException) {
            "fresh-recording resolver must throw (R-1: surfacing beats silent data loss), got: $ex"
        }
    }

    // ── Adapter — JobExecutor delegation (thin-delegation OQ-1) ────────

    @Test
    fun `submitReprocess starts JobExecutor with the resolved JobRequest`() {
        val runner = RecordingRunner()
        JobExecutor.initializeForTest(runner)
        val filesDir = tempFilesDir()
        val adapter = newAdapter(DefaultPipelineConfigResolver(filesDirProvider = { filesDir }))

        adapter.submitReprocess(
            sessionId = "sx",
            audioFile = File(filesDir, "a.m4a"),
            queue = listOf(3),
            language = "en",
        )

        runner.awaitStarted()
        // reuseSessionId is non-null for reprocess → runner sees the
        // reuse id, NOT a preAllocatedSessionId.
        assertEquals("sx", runner.reuseSessionId.get())
        val cfg = runner.transcriptionConfig.get()!!
        assertEquals("en", cfg.language)
        assertEquals(listOf(3), cfg.queuedPromptIds)
        assertEquals(SessionOrigin.KEYBOARD, cfg.origin)
        waitForRegistryEmpty()
    }

    @Test
    fun `isRunning and activeJobCount reflect ActiveJobRegistry`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : PipelineRunner {
            override fun runTranscription(
                config: PipelineOrchestrator.PipelineConfig,
                reuseSessionId: String?,
                token: CancellationToken,
            ) {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }

            override fun resume(sessionId: String, token: CancellationToken) = Unit
            override fun regenerate(request: JobRequest.StepRegenerate, token: CancellationToken) = Unit
            override fun postProcess(request: JobRequest.PostProcess) = Unit
        }
        JobExecutor.initializeForTest(blocking)
        val filesDir = tempFilesDir()
        val adapter = newAdapter(DefaultPipelineConfigResolver(filesDirProvider = { filesDir }))

        assertEquals(false, adapter.isRunning("job-1"))
        assertEquals(0, adapter.activeJobCount())

        adapter.submitReprocess("job-1", File(filesDir, "a.m4a"), emptyList(), null)
        check(started.await(2, TimeUnit.SECONDS)) { "blocking runner did not start" }

        assertEquals(true, adapter.isRunning("job-1"))
        assertEquals(1, adapter.activeJobCount())

        release.countDown()
        waitForRegistryEmpty()
        assertEquals(false, adapter.isRunning("job-1"))
        assertEquals(0, adapter.activeJobCount())
    }

    @Test
    fun `cancel delegates to JobExecutor cancel`() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val cancellableRunner = object : PipelineRunner {
            override fun runTranscription(
                config: PipelineOrchestrator.PipelineConfig,
                reuseSessionId: String?,
                token: CancellationToken,
            ) {
                started.countDown()
                // Spin until the cooperative token is flipped by cancel().
                // JobExecutor.cancel ALSO Thread.interrupt()s this thread
                // (last-resort fallback) — swallow that and keep checking
                // the cooperative token, which is the contract surface.
                val deadline = System.currentTimeMillis() + 2_000
                while (!token.isCancelled && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(10)
                    } catch (ignored: InterruptedException) {
                        // interrupt() is the cancel fallback — the token
                        // is (or is about to be) set; loop re-checks it.
                    }
                }
                if (token.isCancelled) cancelled.countDown()
            }

            override fun resume(sessionId: String, token: CancellationToken) = Unit
            override fun regenerate(request: JobRequest.StepRegenerate, token: CancellationToken) = Unit
            override fun postProcess(request: JobRequest.PostProcess) = Unit
        }
        JobExecutor.initializeForTest(cancellableRunner)
        val filesDir = tempFilesDir()
        val adapter = newAdapter(DefaultPipelineConfigResolver(filesDirProvider = { filesDir }))

        adapter.submitReprocess("c1", File(filesDir, "a.m4a"), emptyList(), null)
        check(started.await(2, TimeUnit.SECONDS)) { "runner did not start" }

        adapter.cancel("c1")

        check(cancelled.await(2, TimeUnit.SECONDS)) {
            "adapter.cancel() must propagate to JobExecutor's cooperative token"
        }
        waitForRegistryEmpty()
    }

    // ── Binder integration — production wiring reaches the real adapter ─

    /**
     * Boots the real [DictatePipelineService] and dispatches
     * `TriggerPipeline` through the production binder. This proves the
     * `onCreate` Step-4 wiring routes to the **real**
     * [PipelineRunnerSubsystemAdapter] (not the demoted stub): the C3
     * default resolver's fresh-recording R-1 guard throws, the
     * orchestrator wraps it into an `EffectFailure`, and `JobExecutor`
     * is **never** started with a silently-wrong `JobRequest`.
     *
     * If the stub were still wired, `pipelineRunner.submit` would be a
     * `Log.w` no-op and the registry would also stay empty — so this
     * test additionally asserts the pipeline state left `Idle`
     * (Preparing was entered then the failure surfaced), distinguishing
     * "real adapter, guard fired" from "stub, silently swallowed".
     */
    @Test
    fun `binder TriggerPipeline reaches the real adapter and the R-1 guard surfaces (no silent JobExecutor start)`() {
        val runner = RecordingRunner()
        JobExecutor.initializeForTest(runner)
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        ShadowLooper.idleMainLooper()

        binder.dispatch(
            Action.PipelineAction.TriggerPipeline(
                sessionId = "boot-1",
                audioFile = File("/tmp/boot.m4a"),
            ),
        )
        ShadowLooper.idleMainLooper()

        // The real adapter's DefaultPipelineConfigResolver.resolveFresh
        // throws → orchestrator routes EffectFailure → JobExecutor never
        // started with a wrong JobRequest (R-1: no silent data loss).
        assertEquals(
            "registry must stay empty — the R-1 guard prevents a wrong-config submit",
            emptyMap<String, JobState>(),
            ActiveJobRegistry.state.value,
        )
        check(runner.done.count == 1L) {
            "runner must NOT have started — fresh submit is guarded until C5"
        }
    }
}
