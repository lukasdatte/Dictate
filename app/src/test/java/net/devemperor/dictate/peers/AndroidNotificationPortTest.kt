package net.devemperor.dictate.peers

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.sync.CatalogChange
import net.devemperor.dictate.shared.sync.SyncNotification
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [AndroidNotificationPort]: the "Peer sync" channel is created on construction
 * (§7.2) and a [notify] with a real change does not throw. Notification delivery itself is exercised
 * by the two-peer E2E; here we pin the channel registration and the crash-freeness of the toast path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidNotificationPortTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun construction_registersTheCatalogSyncChannel() {
        AndroidNotificationPort(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(
            "the 'Peer sync' channel must exist after construction",
            manager.getNotificationChannel(AndroidNotificationPort.CHANNEL_ID),
        )
    }

    @Test
    fun notify_doesNotThrow_forARealChange() {
        val port = AndroidNotificationPort(context)

        // Must complete without throwing regardless of the POST_NOTIFICATIONS grant state.
        port.notify(
            SyncNotification(
                peerName = "Heim-PC",
                changes = listOf(CatalogChange.Updated("local-1", CatalogEntityKindWire.PROMPT, "Prompt")),
            ),
        )
    }
}
