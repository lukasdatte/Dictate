package net.devemperor.dictate.companion.platform.windows

import net.devemperor.dictate.shared.sync.NotificationPort
import net.devemperor.dictate.shared.sync.SyncNotification
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * Surfaces a sync run as a native tray balloon via AWT `SystemTray` (peer-katalog.md §7.1, Spec-D6).
 *
 * ## Why AWT and not the Compose `Tray`
 *
 * The Compose-Desktop `Tray` (`Main.kt`) has NO notification API — there is no
 * `Tray.displayMessage`. The smallest thing that actually shows a balloon is
 * `java.awt.TrayIcon.displayMessage(...)`, so this port reaches for AWT directly. Despite the
 * package name it is NOT Windows-only: any OS whose desktop has a supported tray (many Linux DEs)
 * gets balloons too; where the tray is unsupported it degrades to [available] = false and a silent
 * [notify], and the caller wires [net.devemperor.dictate.companion.platform.fallback.NoopNotificationPort]
 * instead.
 *
 * ## The single-tray-slot rule (§15 Gap 3)
 *
 * A second `TrayIcon` next to the Compose one would fight for the same slot and flicker. So this
 * port does NOT create its own icon — it REUSES whatever icon is already registered
 * (`SystemTray.trayIcons.firstOrNull()`, normally the Compose one) and only calls `displayMessage`
 * on it. If no icon is registered yet (nothing to attach to) it reports [available] = false. The
 * eventual "one AWT `TrayIcon` as the single source of truth, Compose `Tray` removed" consolidation
 * is a separate E3/`Main.kt` spike; this port is deliberately additive and non-breaking until then.
 */
class AwtNotificationPort(
    private val trayIconProvider: () -> TrayIcon? = { defaultTrayIcon() },
) : NotificationPort {

    override val available: Boolean
        get() = trayIconProvider() != null

    override fun notify(notification: SyncNotification) {
        val icon = trayIconProvider() ?: return
        try {
            icon.displayMessage(
                "Dictate — ${notification.peerName}",
                notification.summary,
                TrayIcon.MessageType.INFO,
            )
        } catch (_: Exception) {
            // A best-effort toast must never take down the background sync — swallow and move on.
        }
    }

    private companion object {
        fun defaultTrayIcon(): TrayIcon? =
            if (SystemTray.isSupported()) SystemTray.getSystemTray().trayIcons.firstOrNull() else null
    }
}
