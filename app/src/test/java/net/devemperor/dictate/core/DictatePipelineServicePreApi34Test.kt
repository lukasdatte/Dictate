package net.devemperor.dictate.core

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F-22: API-version-branch coverage — pre-API-34 startForegroundCompat path.
 *
 * Robolectric default `@Config(sdk = [34])` covers the explicit-type
 * `startForeground(id, notif, type)` overload. This class exercises the
 * pre-API-34 implicit overload `startForeground(id, notif)` (no FGS-type
 * argument) at SDK 33 so the API-version branch in [DictatePipelineService.startForegroundCompat]
 * has direct regression coverage. The pre-API-26 ensureNotificationChannel
 * early-return is omitted: project `minSdk = 26` makes it logically
 * unreachable on real devices and the defensive-coverage value is
 * marginal.
 *
 * Quality-Gate K-4 exception — same justification as
 * [DictatePipelineServiceTest]: assertions touch real ShadowService
 * state (`startForeground`) that requires Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DictatePipelineServicePreApi34Test {

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
            // Already destroyed by the test — no-op.
        }
    }

    @Test
    fun onStartCommand_usesImplicitStartForeground_onPreApi34() {
        // Channel is created in onCreate — required before startForeground
        // on API ≥ 26 (Spec 1 §11.1.4). Same precondition as default-SDK tests.
        controller.create()
        val service = controller.get()
        val shadow = shadowOf(service)
        // Channel must exist (API 33 still requires it).
        val nm = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(NotificationManager::class.java)
        assertNotNull(nm.getNotificationChannel(DictatePipelineService.CHANNEL_ID))

        // Act: drive onStartCommand the way Android does.
        val result = service.onStartCommand(Intent(), 0, 0)

        // The pre-API-34 path: implicit-type `startForeground(id, notif)`
        // overload. Robolectric's shadow service records the call regardless
        // of which signature was used; assert NOTIF_ID + the documented
        // return value to pin the behaviour.
        assertNotNull(
            "Pre-API-34 onStartCommand must call startForeground synchronously",
            shadow.lastForegroundNotification,
        )
        assertEquals(
            "startForeground must use the documented NOTIF_ID even on pre-API-34",
            DictatePipelineService.NOTIF_ID,
            shadow.lastForegroundNotificationId,
        )
        assertEquals(
            "Service must return START_NOT_STICKY on pre-API-34 too",
            Service.START_NOT_STICKY,
            result,
        )
    }
}
