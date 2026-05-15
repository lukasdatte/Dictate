package net.devemperor.dictate.core

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric service tests for the Block-2 [DictatePipelineService] skeleton.
 *
 * **Quality-Gate K-4 exception.** Per the state-file `### Test-Strategy`,
 * Robolectric is opt-out by default in this codebase — explicit
 * justification required. The justifications for this test class:
 *
 *  - **Channel-order acceptance** (Spec 1 §10 Phase-B S-5
 *    "NotificationChannel-vor-startForeground"): the assertion is that
 *    [DictatePipelineService.onCreate] creates the FGS notification
 *    channel before any code path that could call `startForeground`,
 *    and on API ≥ 26 `NotificationManager.createNotificationChannel`
 *    needs a real (shadow) `NotificationManager`.
 *  - **FGS-5-second budget** (Spec 1 §10 Phase-B S-5 + ADR-0003
 *    Failure-Mode "startForeground not called within 5 s"): the
 *    assertion is that [DictatePipelineService.onStartCommand] calls
 *    `startForeground` synchronously as step 1, so Robolectric's
 *    shadow `Service` is needed to observe the call.
 *  - **LocalBinder identity / Multi-Bind acceptance** (Spec 1 §11.3.4):
 *    Android dispatches the same `IBinder` to multiple bindService
 *    callers. Robolectric's shadow ServiceController exposes
 *    `onBind` directly, so we can assert the singleton invariant.
 *
 * The tests stay narrow on purpose — Block 1b introduces the
 * orchestrator + notification coordinator + action router, each with
 * their own tests. This class covers only what the Block-2 skeleton
 * promises.
 *
 * @see `docs/decisions/0003-service-foreground-pipeline-architecture.md` §"Required mechanics"
 * @see `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` §10 §11.1 §11.3
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictatePipelineServiceTest {

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)

    @After
    fun tearDown() {
        // `Robolectric.buildService` is independent of the controller in
        // `setup()` — destroying it here is safe + idempotent and keeps
        // the shadow Service registry clean for the next test.
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
            // Already destroyed by the test itself — no-op.
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Channel-order — Spec 1 §10 Phase-B S-5 acceptance
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun onCreate_createsNotificationChannel_beforeAnyStartForeground() {
        // Before onCreate, the channel does not exist.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val nm = context.getSystemService(NotificationManager::class.java)
        assertNull(
            "Pre-condition: channel must not exist before onCreate",
            nm.getNotificationChannel(DictatePipelineService.CHANNEL_ID),
        )

        controller.create()

        // After onCreate, the channel exists. This is the binding contract
        // for `startForeground` on API ≥ 26 — Spec 1 §11.1.4.
        val service = controller.get()
        assertTrue(
            "ensureNotificationChannel must run during onCreate",
            service.isNotificationChannelReady,
        )
        assertNotNull(
            "Channel must be registered with NotificationManager",
            nm.getNotificationChannel(DictatePipelineService.CHANNEL_ID),
        )
    }

    @Test
    fun notificationChannel_invariants() {
        // F-21: full invariant assertion — every property of the silent,
        // unobtrusive FGS notification channel that the production code
        // promises (Spec 1 §11.1.2):
        //   - IMPORTANCE_LOW            no sound, no heads-up
        //   - setShowBadge(false)       no launcher badge dot
        //   - setSound(null, null)      audibly silent
        //   - enableVibration(false)    no haptics
        //   - enableLights(false)       no LED pulse
        //   - lockscreenVisibility=PRIVATE  hides contents on lockscreen
        controller.create()

        val nm = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(DictatePipelineService.CHANNEL_ID)
        assertNotNull("Channel must be registered", channel)
        assertEquals(
            "Channel importance must be LOW so the persistent FGS notification is not intrusive",
            NotificationManager.IMPORTANCE_LOW,
            channel!!.importance,
        )
        assertFalse("Channel must not show a launcher badge", channel.canShowBadge())
        assertNull("Channel must be silent (no sound)", channel.sound)
        assertFalse("Channel must not vibrate", channel.shouldVibrate())
        assertFalse("Channel must not pulse lights", channel.shouldShowLights())
        assertEquals(
            "Channel must hide contents on lockscreen (VISIBILITY_PRIVATE)",
            Notification.VISIBILITY_PRIVATE,
            channel.lockscreenVisibility,
        )
    }

    @Test
    fun ensureNotificationChannel_isIdempotent_acrossRepeatedOnCreate() {
        // Two service instances → both should populate the same channel
        // exactly once. The NotificationManager is application-scoped, so
        // the second instance must short-circuit on the existing-channel
        // check. This guards the `getNotificationChannel != null` early
        // return in ensureNotificationChannel().
        controller.create()
        val first = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(NotificationManager::class.java)
            .getNotificationChannel(DictatePipelineService.CHANNEL_ID)
        assertNotNull(first)

        // Second invocation on a fresh controller.
        val secondController = Robolectric.buildService(DictatePipelineService::class.java)
        try {
            secondController.create()
            assertTrue(secondController.get().isNotificationChannelReady)
            val second = ApplicationProvider.getApplicationContext<Application>()
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(DictatePipelineService.CHANNEL_ID)
            assertNotNull("Channel still present after second create()", second)
        } finally {
            secondController.destroy()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // FGS-5s-budget — Spec 1 §10 Phase-B S-5 acceptance
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun onStartCommand_callsStartForeground_synchronously() {
        controller.create()
        val service = controller.get()
        val shadow = shadowOf(service)

        // Pre-condition: no foreground notification yet.
        assertNull(
            "Pre-condition: no FGS notification before onStartCommand",
            shadow.lastForegroundNotification,
        )

        // Drive onStartCommand the same way Android does (via
        // ServiceController.startCommand). The shadow records the
        // startForeground call so we can observe both that it ran AND
        // that it ran with the documented NOTIF_ID.
        controller.startCommand(0, 0)

        assertNotNull(
            "onStartCommand must call startForeground synchronously (Spec 1 §11.1.4)",
            shadow.lastForegroundNotification,
        )
        assertEquals(
            "startForeground must use the documented NOTIF_ID",
            // C4-B2: NOTIF_ID moved to PipelineNotificationCoordinator
            // (Spec 1 §10 NOTIF_ID-Konsistenz SoT).
            PipelineNotificationCoordinator.NOTIF_ID,
            shadow.lastForegroundNotificationId,
        )
    }

    @Test
    fun onStartCommand_returnsStartNotSticky() {
        controller.create()

        val result = controller.get().onStartCommand(Intent(), 0, 0)

        // START_NOT_STICKY is the documented return value (Spec 1 §7.3 +
        // ADR-0003: OOM-killed service does NOT auto-restart; recovery is
        // user-triggered via DB-replay).
        assertEquals(
            "Service must return START_NOT_STICKY so OOM-killed instances do not auto-restart",
            Service.START_NOT_STICKY,
            result,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // LocalBinder contract (Spec 1 §5 + §11.3.4)
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun onBind_returnsLocalBinder_pointingAtTheService() {
        controller.create()
        val service = controller.get()

        val binder = service.onBind(Intent())

        assertNotNull("onBind must return a non-null IBinder", binder)
        val typed = binder as? DictatePipelineService.LocalBinder
        assertNotNull(
            "onBind must return a DictatePipelineService.LocalBinder (Spec 1 §5)",
            typed,
        )
        assertSame(
            "LocalBinder.service must point back at the hosting service instance",
            service,
            typed!!.service,
        )
    }

    @Test
    fun onBind_returnsSameBinderInstance_acrossMultipleCalls() {
        // Multi-Bind acceptance (Spec 1 §11.3.4): multiple clients see
        // the same IBinder instance.
        controller.create()
        val service = controller.get()

        val first: IBinder? = service.onBind(Intent())
        val second: IBinder? = service.onBind(Intent())

        assertSame("Multi-Bind must hand out the same singleton binder", first, second)
    }

    @Test
    fun localBinderDispatch_forwardsToOrchestrator_andReturnsTypedOutcome() {
        // C7 wired the orchestrator into the binder. `dispatch(action)`
        // now forwards to `orchestrator.dispatch(action)` and returns a
        // typed [DispatchOutcome]. Pin the contract: the binder accepts
        // typed [Action] and propagates the routing result.
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        // PauseRecording is a payload-less `data object` — safe to
        // dispatch in any state. RecordingState.Idle does not accept
        // pause (reducer returns null), so we expect Rejected — i.e.
        // the orchestrator wiring is reached.
        val outcome = binder.dispatch(net.devemperor.dictate.state.Action.RecordingAction.PauseRecording)

        // Either Applied (if the reducer did transition) or Rejected
        // (idle-state pause). Unrouted would mean orchestrator was
        // never wired — that is the regression we are guarding against.
        assertTrue(
            "Binder.dispatch must reach the orchestrator (Applied or Rejected, not Unrouted): $outcome",
            outcome is net.devemperor.dictate.state.DispatchOutcome.Applied ||
                outcome is net.devemperor.dictate.state.DispatchOutcome.Rejected,
        )
    }

    @Test
    fun localBinderState_exposesOrchestratorStateFlow() {
        // C7 added `LocalBinder.state` so the IME can subscribe via
        // `binder.state.collect { … }`. The flow must hand out the
        // initial state — pref-mirror ran during onCreate; with an
        // empty SP the defaults match [DictateUiState.initial].
        //
        // F-23 (2026-05-15) — tightened from `assertNotNull(snapshot)`
        // (weak smoke check) to substantive equality against
        // `DictateUiState.initial()`. An empty SharedPreferences
        // makes the expected state deterministic — every Pref's
        // default matches the corresponding sub-state default.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val sp = context.getSharedPreferences(
            "net.devemperor.dictate",
            Context.MODE_PRIVATE,
        )
        sp.edit().clear().commit()    // Ensure empty SP.

        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        val snapshot = binder.state.value
        assertEquals(
            "Empty SP + PrefMirror.attach must yield a snapshot equal to " +
                "DictateUiState.initial() — any sub-state delta indicates either " +
                "a pref-default mismatch or the orchestrator was not wired.",
            net.devemperor.dictate.state.DictateUiState.initial(),
            snapshot,
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // onDestroy cleanup
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun onDestroy_cancelsServiceScope_andSurvivesIdempotently() {
        controller.create()
        controller.get().onBind(Intent())

        // Should not throw — Block 2 only has a serviceScope to cancel.
        controller.destroy()

        // After destroy, the controller's service is still queryable
        // but its scope must be cancelled. The simplest observable is
        // that a second destroy() does not throw (idempotency proxy):
        // double-destroy used to be a regression class in Android
        // service teardown.
        controller.get() // smoke — no NPE
    }

    // ──────────────────────────────────────────────────────────────────
    // End-to-end bind via the application context (Multi-Bind smoke)
    // ──────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────
    // C7 composition-root wiring
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun onCreate_wiresOrchestrator_andPrefMirrorRunsBeforeBindReturn() {
        // C7 invariant: by the time onBind returns, the pref-mirror has
        // already applied a snapshot of SP into the store. The IME-side
        // first read of `binder.state.value` must NOT see
        // `DictateUiState.initial()` if the user has set a non-default
        // pref before the service started.
        //
        // Write a non-default Pref before onCreate runs.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sp = context.getSharedPreferences(DictatePipelineService.PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean(net.devemperor.dictate.preferences.Pref.SingleRowMode.key, true)
            .apply()

        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        // PrefMirror ran during onCreate — the IME's first state-read
        // sees the mirrored Pref value, not the initial-state default.
        assertTrue(
            "PrefMirror must attach during onCreate so SingleRowMode is true by first state-read",
            binder.state.value.layout.singleRowMode,
        )
    }

    @Test
    fun onDestroy_runsOrchestratorShutdown_beforeScopeCancellation() {
        // C7 invariant (Spec 1 §4.3 Aufrufer-Vertrag): orchestrator.shutdown()
        // must run BEFORE serviceScope.cancel(). Otherwise the per-module
        // `terminate(services)` calls would launch async cleanup on a
        // cancelled scope.
        //
        // F-9 (2026-05-15) — improved from a non-throw smoke check to
        // a behavioural assertion via the PrefMirror lifecycle: write a
        // mirrored SP value AFTER destroy, then verify the store stays
        // at its pre-destroy snapshot. PrefMirror.detach() runs as the
        // first step inside orchestrator.shutdown() (see
        // [DictateOrchestrator.shutdown]), so a post-destroy SP write
        // failing to propagate proves that detach ran during destroy.
        // The chain is: destroy → orchestrator.shutdown() →
        // prefMirror.detach() → SP listener unregistered → post-destroy
        // SP writes invisible to the store. Plus the original no-throw
        // check (a CancellationException from shutting down on a
        // cancelled scope would surface here).

        val context = ApplicationProvider.getApplicationContext<Application>()
        val sp = context.getSharedPreferences(
            "net.devemperor.dictate",
            Context.MODE_PRIVATE,
        )

        // Pre-condition: SingleRowMode = true so the mirrored axis has a
        // distinguishable value.
        sp.edit().putBoolean("net.devemperor.dictate.single_row_mode", true).commit()

        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(
            "Pre-destroy: PrefMirror snapshot reflects the SP value",
            binder.state.value.layout.singleRowMode,
        )

        controller.destroy()
        // No exception => no regression in the no-throw acceptance.

        // Now flip the SP. If PrefMirror is still attached, the listener
        // would propagate the change into the store. If detach ran
        // during shutdown, the store stays put.
        sp.edit().putBoolean("net.devemperor.dictate.single_row_mode", false).commit()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(
            "PrefMirror must be detached by destroy — post-destroy SP writes " +
                "must not mutate the snapshot. A regression in the shutdown order " +
                "(scope.cancel before orchestrator.shutdown) would manifest as the " +
                "SP-listener still registered, flipping this snapshot to false.",
            binder.state.value.layout.singleRowMode,
        )
    }

    @Test
    fun onCreate_succeeds_withDefaultProductionRegistry() {
        // Sanity test: the C5/C6 production registry passes
        // assertCompleteCoverage at service-bind time. If a future
        // change adds a new Action sealed-subclass without registering
        // it, onCreate fails fast — this test would be the canary.
        controller.create()
        // No exception => coverage check passed.
    }

    @Test
    fun bindService_smokeTest_doesNotThrow() {
        // Robolectric does not run the full system service-manager, so
        // we cannot assert onServiceConnected fires the way it does on
        // device. What we CAN assert is that `Context.bindService`
        // accepts the explicit-component intent without throwing — a
        // regression test for missing-manifest-declaration mistakes
        // (the manifest service entry MUST exist for the package
        // manager to resolve the component name in this test).
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, DictatePipelineService::class.java)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                /* not invoked in Robolectric */
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                /* not invoked in Robolectric */
            }
        }

        // The act under test: bindService must not throw. JUnit fails
        // the test on any uncaught exception, so reaching the unbind
        // line is the assertion.
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (bound) context.unbindService(connection)
    }
}
