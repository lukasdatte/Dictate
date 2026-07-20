package net.devemperor.dictate.companion.platform.fallback

import net.devemperor.dictate.shared.sync.NotificationPort
import net.devemperor.dictate.shared.sync.SyncNotification

/**
 * The notification port on a host with no usable system tray — a headless companion (`--headless`,
 * §9.3) or a Linux/macOS box where `SystemTray.isSupported()` is false (peer-katalog.md §7.1).
 *
 * Mirrors [NoopTextInserter]: it reports [available] = false and swallows every [notify], so the
 * sync engine runs and completes exactly the same, it just cannot surface a toast. Never throws —
 * a headless sync must not fail because there is nowhere to show a message.
 */
object NoopNotificationPort : NotificationPort {

    override val available: Boolean = false

    override fun notify(notification: SyncNotification) = Unit
}
