package net.devemperor.dictate.peers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.devemperor.dictate.R
import net.devemperor.dictate.shared.sync.NotificationPort
import net.devemperor.dictate.shared.sync.SyncNotification

/**
 * The phone's implementation of the shared [NotificationPort] (peer-katalog.md §7.2): a system
 * notification when a background [net.devemperor.dictate.peers.CatalogSyncWorker] run changed a
 * locally-held copy.
 *
 * Follows the house `PipelineNotificationCoordinator` pattern — its own low-noise
 * [NotificationChannel] ("Peer sync", importance `DEFAULT`), built with `NotificationCompat.Builder`.
 * A tap opens the app (the read-only Peer Explorer settings page is E3's deep-link target, §8.3;
 * until it lands the launcher intent is the honest fallback).
 *
 * [available] reflects the runtime `POST_NOTIFICATIONS` grant (API 33+) via
 * `NotificationManagerCompat.areNotificationsEnabled()` — the same surface the onboarding flow
 * requests. When notifications are disabled [notify] is a silent no-op: a background sync must never
 * throw because the user declined the toast.
 */
class AndroidNotificationPort(private val context: Context) : NotificationPort {

    private val manager = NotificationManagerCompat.from(context)

    init {
        ensureChannel()
    }

    override val available: Boolean
        get() = manager.areNotificationsEnabled()

    override fun notify(notification: SyncNotification) {
        if (!available) return
        try {
            val built = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_mic_20)
                .setContentTitle(context.getString(R.string.dictate_catalog_sync_notif_title))
                .setContentText("${notification.peerName}: ${notification.summary}")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(launcherIntent())
                .build()
            // A stable id per peer so a fresh run replaces the peer's previous toast rather than stacking.
            manager.notify(NOTIFICATION_TAG, notification.peerName.hashCode(), built)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the areNotificationsEnabled() check and here — ignore.
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.dictate_catalog_sync_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun launcherIntent() =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            android.app.PendingIntent.getActivity(
                context,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }

    companion object {
        const val CHANNEL_ID = "catalog_sync"
        private const val NOTIFICATION_TAG = "catalog_sync"
    }
}
