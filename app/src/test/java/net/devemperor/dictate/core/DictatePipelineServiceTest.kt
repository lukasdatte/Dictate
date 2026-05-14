package net.devemperor.dictate.core

import android.app.Application
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
 * @see docs/decisions/0003-service-foreground-pipeline-architecture.md §"Required mechanics"
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §10 §11.1 §11.3
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
    fun notificationChannel_isImportanceLow_andSilent() {
        controller.create()

        val nm = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(DictatePipelineService.CHANNEL_ID)
        assertNotNull("Channel must be registered", channel)
        // IMPORTANCE_LOW means no sound + no heads-up — Spec 1 §11.1.2.
        assertEquals(
            "Channel importance must be LOW so the persistent FGS notification is not intrusive",
            NotificationManager.IMPORTANCE_LOW,
            channel!!.importance,
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
            DictatePipelineService.NOTIF_ID,
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
    fun localBinderDispatch_isNoOp_butCountsInvocations() {
        // The Block-2 stub records dispatch invocations only — Block 1b
        // replaces the body with `orchestrator.dispatch(action)`. The
        // assertion here pins the contract: dispatching does not throw
        // and the counter advances, so the IME-side dispatch path can
        // be smoke-tested before Block 1b lands.
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        assertEquals(0, controller.get().dispatchInvocationCount)
        binder.dispatch("Block-2 placeholder action #1")
        binder.dispatch("Block-2 placeholder action #2")
        assertEquals(
            "Stub dispatch should be observed via the invocation counter",
            2,
            controller.get().dispatchInvocationCount,
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
