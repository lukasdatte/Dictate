package net.devemperor.dictate.shared.sync

/**
 * A hand-written [CatalogSubscriberStore] for the engine tests — records what the engine wrote so a
 * test can assert the watermark advance, the fork-skip (via what [activeSubscriptions] hands out),
 * and the "copy left unchanged" of a verify failure. No mock library, per house style ([FakeTransport]).
 */
class FakeCatalogSubscriberStore(
    private val subscriptions: MutableMap<String, List<CatalogSubscriptionRef>> = mutableMapOf(),
) : CatalogSubscriberStore {

    data class SuccessCall(val at: Long, val rootHash: String?)

    val contacts = mutableListOf<Pair<String, Long>>()
    val successes = mutableListOf<SuccessCall>()
    val entityUpdates = mutableListOf<VerifiedEntityUpdate>()
    val credentialUpdates = mutableListOf<VerifiedCredentialUpdate>()
    val sourceRemoved = mutableListOf<Pair<String, Long>>()

    fun withActive(peerId: String, vararg refs: CatalogSubscriptionRef) = apply {
        subscriptions[peerId] = refs.toList()
    }

    override fun activeSubscriptions(peerId: String): List<CatalogSubscriptionRef> =
        subscriptions[peerId].orEmpty()

    override fun recordContact(peerId: String, at: Long) {
        contacts += peerId to at
    }

    override fun recordSuccess(peerId: String, at: Long, rootHash: String?) {
        successes += SuccessCall(at, rootHash)
    }

    override fun applyEntityUpdate(update: VerifiedEntityUpdate) {
        entityUpdates += update
    }

    override fun applyCredentialUpdate(update: VerifiedCredentialUpdate) {
        credentialUpdates += update
    }

    override fun markSourceRemoved(localEntityId: String, at: Long) {
        sourceRemoved += localEntityId to at
    }
}

/** A [NotificationPort] that just records the runs it was told to announce. */
class RecordingNotificationPort(override val available: Boolean = true) : NotificationPort {
    val fired = mutableListOf<SyncNotification>()
    override fun notify(notification: SyncNotification) {
        fired += notification
    }
}
