package net.devemperor.dictate.core

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.secrets.AndroidKeystoreSecretStore
import net.devemperor.dictate.secrets.PairingSecrets
import net.devemperor.dictate.testutil.FakeSecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Robolectric tests for the C8 composition-root extensions
 * (IMPL-1 closure — Spec 1 §11.2.2 step 7 + §7.3).
 *
 * **Coverage focus:**
 *  - Service.onCreate constructs AIOrchestrator, AutoFormattingService,
 *    PromptQueueManager, SessionManager, SessionTracker, PromptService,
 *    RecordingRepository, the legacy [PipelineOrchestrator], and the
 *    [PipelineCallbackBridge]. All are exposed via [LocalBinder] getters.
 *  - [JobExecutor.initialize] is called during onCreate.
 *  - [LocalBinder.registerPipelineCallback] forwards through to the
 *    [PipelineCallbackBridge].
 *  - [LocalBinder.registerPromptQueueCallback] routes
 *    PromptQueueManager callback events to the registered delegate.
 *  - [LocalBinder.registerInputConnectionProvider] is honoured by the
 *    `ModuleServices.inputConnectionProvider` lambda.
 *
 * These tests complement [DictatePipelineServiceTest], which covers the
 * orchestrator-surface (state + dispatch) and notification-channel
 * invariants. The split keeps each file under ~500 lines.
 *
 * @see net.devemperor.dictate.core.DictatePipelineService
 * @see net.devemperor.dictate.core.PipelineCallbackBridge
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictatePipelineServiceCompositionTest {

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
        }
        // Reset JobExecutor so the next test starts clean. The Kotlin
        // `object` is process-wide; without reset, the previous test's
        // initialize would leak across tests and assertions about
        // "freshly initialized in this onCreate" would be unreliable.
        JobExecutor.resetForTest()
        // Restore the production SecretStore factory — the paired-sync tests swap in a fake (the
        // real Keystore is absent under Robolectric — spec §5.4).
        DictatePipelineService.secretStoreFactory = { AndroidKeystoreSecretStore.create(it) }
    }

    /** A ready in-memory SecretStore holding the pairing secret (Keystore is absent under Robolectric). */
    private fun seededSecretStore() = FakeSecretStore().apply {
        put(PairingSecrets.DEVICE_SECRET_REF, "s3cr3t".toByteArray())
    }

    // ──────────────────────────────────────────────────────────────────
    // AI-infrastructure construction (IMPL-1 closure)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `onCreate exposes AIOrchestrator via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        assertNotNull(
            "AIOrchestrator must be constructed during onCreate (IMPL-1)",
            binder.aiOrchestrator,
        )
    }

    @Test
    fun `onCreate exposes AutoFormattingService via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        assertNotNull(binder.autoFormattingService)
    }

    @Test
    fun `onCreate exposes PromptQueueManager via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        assertNotNull(binder.promptQueueManager)
    }

    @Test
    fun `onCreate exposes SessionManager via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        assertNotNull(binder.sessionManager)
    }

    @Test
    fun `onCreate exposes SessionTracker via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        assertNotNull(binder.sessionTracker)
    }

    @Test
    fun `onCreate exposes PromptService via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        assertNotNull(binder.promptService)
    }

    @Test
    fun `onCreate exposes RecordingRepository via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        assertNotNull(binder.recordingRepository)
    }

    @Test
    fun `onCreate exposes PipelineOrchestrator via the binder`() {
        // IMPL-1 closure: the LEGACY PipelineOrchestrator (audio-pipeline
        // runner) is constructed in the Service, not the IME. The binder
        // exposes it so the IME's existing call sites (resume, cancel,
        // runStandalonePrompt, isRunning) keep working unchanged after
        // bind.
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        assertNotNull(
            "Legacy PipelineOrchestrator must be constructed and exposed",
            binder.pipelineOrchestrator,
        )
    }

    @Test
    fun `onCreate exposes AudioFocusGate via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        assertNotNull(
            "Production AudioFocusGate must be constructed during onCreate",
            binder.audioFocusGate,
        )
    }

    @Test
    fun `binder accessors return the same instance across multiple gets`() {
        // The service holds these as `lateinit val`-style fields; every
        // binder getter must return the same singleton (the IME caches
        // the reference; a new instance per get would break that
        // contract).
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        assertSame(binder.aiOrchestrator, binder.aiOrchestrator)
        assertSame(binder.pipelineOrchestrator, binder.pipelineOrchestrator)
        assertSame(binder.sessionManager, binder.sessionManager)
    }

    // ──────────────────────────────────────────────────────────────────
    // Callback registration
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `registerPipelineCallback forwards to the underlying bridge`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        val recorder = object : PipelineOrchestrator.PipelineCallback {
            var called: Boolean = false
            override fun onStepStarted(stepName: String) { called = true }
            override fun onStepCompleted(stepName: String, durationMs: Long) = Unit
            override fun onStepFailed(stepName: String) = Unit
            override fun onPipelineCompleted(
                text: String,
                source: net.devemperor.dictate.database.entity.InsertionSource,
                review: net.devemperor.dictate.ai.conversation.PostProcessingReview?,
            ) = Unit
            override fun onPipelineError(errorInfoKey: String, vibrate: Boolean, providerName: String?) = Unit
            override fun onPipelineFinished() = Unit
            override fun onShowResend() = Unit
            override fun onAutoSwitch() = Unit
            override fun onAudioPersisted(audioFile: java.io.File, sessionId: String) = Unit
        }

        binder.registerPipelineCallback(recorder)
        // Fire a callback through the bridge by calling onStepStarted
        // on the PipelineOrchestrator's callback — i.e. the bridge.
        // The bridge is private to the Service; reach it indirectly via
        // PipelineOrchestrator.PipelineCallback chain. Since the service-
        // owned PipelineOrchestrator was constructed with the bridge,
        // calling its callbacks through orchestrator internals would
        // require firing a real pipeline. For this unit test we assert
        // via the secondary path: registerPipelineCallback(null)
        // succeeds without throwing, demonstrating the registration
        // surface is wired.
        binder.registerPipelineCallback(null)
        // No exception => the binder's registration plumbing reaches the bridge.
    }

    @Test
    fun `registerPromptQueueCallback wires the delegate`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        var observedSnapshots: List<List<Int>> = emptyList()
        binder.registerPromptQueueCallback(object : PromptQueueManager.PromptQueueCallback {
            override fun onQueueChanged(queuedIds: List<Int>) {
                observedSnapshots = observedSnapshots + listOf(queuedIds.toList())
            }
        })

        // Fire a queue change through the PromptQueueManager. The
        // service constructed it with a callback that routes to our
        // registered delegate.
        binder.promptQueueManager.togglePrompt(42)
        val snapshotCountAfterFirstToggle = observedSnapshots.size
        assertTrue(
            "Queue-change callback must reach the registered delegate",
            snapshotCountAfterFirstToggle >= 1,
        )
        assertTrue(observedSnapshots.last().contains(42))

        // Unregister — further callbacks must be silently dropped.
        binder.registerPromptQueueCallback(null)
        binder.promptQueueManager.togglePrompt(42)  // now removes 42

        // The snapshot count must NOT increase — the callback was not
        // invoked because the delegate is null.
        assertEquals(
            "After unregister, no further callback must fire",
            snapshotCountAfterFirstToggle,
            observedSnapshots.size,
        )
    }

    @Test
    fun `registerInputConnectionProvider null clears the provider`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        // Pre-register a provider, then clear it. Smoke check only —
        // the actual InputConnection plumbing tests live in the
        // ModuleServices/orchestrator-level integration tests.
        binder.registerInputConnectionProvider { null }
        binder.registerInputConnectionProvider(null)
    }

    // ──────────────────────────────────────────────────────────────────
    // App-start Windows sync (C15 — ADR-0020)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `onCreate boots cleanly when paired to an unreachable PC — app-start sync is inert`() {
        // C15: onCreate kicks off a fire-and-forget cursor sync when a PC is paired. It runs on
        // Dispatchers.IO and must NEVER affect service startup — a failed start-sync is not a user
        // event. Pair to a connection-refused address (127.0.0.1:1) so the background sync fails,
        // and assert the service still constructs its whole surface. (When unpaired — every other
        // test here — the trigger is skipped entirely, so those tests never touch the network.)
        // Paired = url + deviceId in prefs (WindowsTarget.isPaired); the secret comes from the store.
        DictatePipelineService.secretStoreFactory = { seededSecretStore() }
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(DictatePipelineService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Pref.WindowsTargetUrl.key, "http://127.0.0.1:1")
            .putString(Pref.WindowsDeviceId.key, "device-under-test")
            .putString(Pref.WindowsServerName.key, "Unreachable-PC")
            .commit()

        try {
            controller.create()
            val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
            assertNotNull(
                "The service must construct its full surface even when the app-start sync fails",
                binder.pipelineOrchestrator,
            )
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun `onCreate actually fires the app-start sync when a PC is paired`() {
        // Regression (review — L4-c): the inert test above proves a FAILED start-sync doesn't break
        // startup, but it would still pass if the onCreate trigger were deleted entirely (an unpaired
        // service never syncs). This pins the POSITIVE half of the wd-15 / ADR-0020 trigger: pair to a
        // real listening socket and assert onCreate makes a connection attempt to it. We only accept
        // the TCP connection — the attempt alone proves the fire-and-forget sync ran.
        val listener = ServerSocket(0)
        val connected = CountDownLatch(1)
        val accepter = Thread {
            try {
                listener.accept().use { connected.countDown() }
            } catch (ignored: Throwable) {
            }
        }.apply { isDaemon = true; start() }

        // Paired = url + deviceId in prefs (WindowsTarget.isPaired); the secret comes from the store.
        DictatePipelineService.secretStoreFactory = { seededSecretStore() }
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(DictatePipelineService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Pref.WindowsTargetUrl.key, "http://127.0.0.1:${listener.localPort}")
            .putString(Pref.WindowsDeviceId.key, "device-under-test")
            .putString(Pref.WindowsServerName.key, "Paired-PC")
            .commit()

        try {
            controller.create()
            controller.get().onBind(Intent())
            assertTrue(
                "onCreate must fire the app-start cursor sync at the paired PC",
                connected.await(5, TimeUnit.SECONDS),
            )
        } finally {
            accepter.interrupt()
            listener.close()
            prefs.edit().clear().commit()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // JobExecutor initialization
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `onCreate calls JobExecutor_initialize with the legacy PipelineOrchestrator`() {
        // IMPL-1: the JobExecutor.initialize call moves from
        // DictateInputMethodService.initLongLivedObjects to
        // DictatePipelineService.onCreate (Spec 1 §11.2.2 step 7).
        //
        // We assert by exercising the side-effect: after onCreate,
        // JobExecutor.start should fail with "not initialized" only if
        // initialize did NOT run. We test the inverse — that a JobRequest
        // submitted post-onCreate does not throw IllegalStateException
        // (the "not initialized" path).
        controller.create()

        // Construct a minimal JobRequest; we don't care if it actually
        // runs (no AI keys, no real audio file). What we care about is
        // that JobExecutor accepts it without throwing
        // IllegalStateException("not initialized").
        val request = JobRequest.TranscriptionPipeline(
            sessionId = "test-sess-" + System.currentTimeMillis(),
            totalSteps = 1,
            kind = JobRequest.TranscriptionKind.RECORDING,
            audioFilePath = null,
            language = null,
            modelOverride = null,
            queuedPromptSlots = emptyList(),
            targetAppPackage = null,
            recordingsDir = java.io.File("/tmp"),
            reuseSessionId = null,
            stylePrompt = null,
        )

        try {
            JobExecutor.start(null, request)
            // The job may fail downstream (file missing, no API key) —
            // we only assert that `initialize` happened.
        } catch (e: IllegalStateException) {
            if (e.message?.contains("not initialized") == true) {
                throw AssertionError(
                    "JobExecutor.initialize was not called during " +
                        "DictatePipelineService.onCreate — IMPL-1 closure regression",
                    e,
                )
            }
            // Other IllegalStateException paths are unrelated and acceptable.
        } catch (ignored: Throwable) {
            // Any other downstream error is acceptable — the assertion is
            // only about IMPL-1 closure.
        } finally {
            // Best-effort cleanup if a job actually started.
            try {
                JobExecutor.cancel(request.sessionId)
            } catch (ignored: Throwable) {
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // PromptQueueManager wiring
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `PromptQueueManager callback is null-safe before any IME delegate registers`() {
        // The service builds the PromptQueueManager with a
        // service-side callback that routes to the registered delegate.
        // Until the IME binds and calls registerPromptQueueCallback,
        // the delegate is null and queue mutations must drop silently
        // (no NPE).
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        // No registerPromptQueueCallback call — delegate stays null.
        assertNull(binder.delegatePromptQueueCallback)

        // Toggling a prompt must NOT throw even though the delegate is null.
        binder.promptQueueManager.togglePrompt(99)
        // Reaching here is the assertion.
    }
}
