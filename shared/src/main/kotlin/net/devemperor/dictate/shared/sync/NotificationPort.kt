package net.devemperor.dictate.shared.sync

import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire

/**
 * "What to announce" separated from "how to announce it" (peer-katalog.md §7).
 *
 * Pure and platform-free — shared verbatim between the desktop companion and the Android app
 * (ADR-0015). It lives in `:shared` (NOT `companion/domain/port` as §7's prose sketches) precisely
 * because the shared [CatalogSyncEngine] is the caller: a port the engine invokes cannot live in
 * `:companion`, or `:shared` would depend on `:companion` and the module graph would cycle. The two
 * platform adapters implement THIS interface — `AwtNotificationPort`/`NoopNotificationPort` on the
 * companion, `AndroidNotificationPort` on the phone — which is exactly the "one port, two hows"
 * §7 asks for, just anchored where the layering allows.
 *
 * The engine fires [notify] once per run, and only when at least one local copy actually changed
 * (§6.1 step 5) — an idle sync is silent. Implementations must be best-effort and never throw into
 * the engine's background thread (a failed toast must not fail a sync); the `Noop` fallback is the
 * headless/unsupported answer, mirroring `TextInserter.available`.
 */
interface NotificationPort {

    /** false → this host cannot surface notifications (headless, no tray). Then [notify] is a no-op. */
    val available: Boolean

    /** Announce the changes of one completed sync run. Best-effort; must not throw. */
    fun notify(notification: SyncNotification)
}

/**
 * One sync run's worth of user-visible change, ready for a tray toast or a system notification.
 *
 * [summary] is a compact, platform-neutral one-liner the simplest adapter (`AwtNotificationPort`,
 * §7.1) hands straight to `TrayIcon.displayMessage`. A richer adapter can ignore it and format
 * [changes] itself. It is deliberately terse and language-neutral (counts, not sentences): `:shared`
 * carries no i18n, so a localizing UI formats from [changes] instead.
 */
data class SyncNotification(
    val peerName: String,
    val changes: List<CatalogChange>,
) {
    /** e.g. "2 updated, 1 removed" — a factual count, never empty (the engine only fires on change). */
    val summary: String
        get() {
            val updated = changes.count { it is CatalogChange.Updated }
            val removed = changes.count { it is CatalogChange.SourceRemoved }
            return listOfNotNull(
                updated.takeIf { it > 0 }?.let { "$it updated" },
                removed.takeIf { it > 0 }?.let { "$it removed" },
            ).joinToString(", ").ifEmpty { "no changes" }
        }
}

/**
 * A single local-copy change produced by a sync run — the unit both the notification and the E2E
 * assertions speak in.
 *
 * A closed set so a UI can render each case exhaustively (an [Updated] copy vs. one whose upstream
 * [SourceRemoved] it — the two look different and mean different things to the user, §6.4).
 */
sealed class CatalogChange {

    abstract val localEntityId: String
    abstract val kind: CatalogEntityKindWire

    /** A verified newer payload was written to the local copy (§6.2). [label] is the peer's index label, if any. */
    data class Updated(
        override val localEntityId: String,
        override val kind: CatalogEntityKindWire,
        val label: String?,
    ) : CatalogChange()

    /**
     * The upstream entity vanished from the index (or answered 404). The local copy is KEPT and its
     * subscription marked `SOURCE_REMOVED` — never auto-deleted, because deletion is destructive
     * and the copy is the receiver's now (§6.4).
     */
    data class SourceRemoved(
        override val localEntityId: String,
        override val kind: CatalogEntityKindWire,
    ) : CatalogChange()
}
