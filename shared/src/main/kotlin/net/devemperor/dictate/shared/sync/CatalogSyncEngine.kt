package net.devemperor.dictate.shared.sync

import kotlinx.serialization.json.Json
import net.devemperor.dictate.shared.client.CatalogClient
import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.config.contentHashOfElement
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import net.devemperor.dictate.shared.protocol.CatalogEntry

/**
 * Pulls the changes a subscribed peer's catalog has that our local copies do not yet (peer-katalog.md §6).
 *
 * Pure and platform-free — shared verbatim between the desktop companion and the Android app
 * (ADR-0015), the sibling of [SyncClient] on the RECEIVE side (that one pushes history to the PC;
 * this one pulls shared config FROM a peer). Blocking; the caller owns the thread (the companion's
 * [CatalogSyncScheduler] tick, or the phone's `CatalogSyncWorker` coroutine) and drives one peer per
 * [sync] call. It never throws into that thread — every failure is an outcome (F33, §6.4).
 *
 * ## The two things that make it cheap and safe
 *
 * **Cheap:** one GET answers "did anything change at all?" — if the peer's `rootHash` equals the one
 * we stored last time, the run stops after that single call (AC6). Only a changed root hash triggers
 * per-entity work, and even then only the subscriptions whose `last_hash` moved are pulled.
 *
 * **Safe:** nothing unverified ever reaches the DB. Every pulled payload passes TWO hash checks
 * before [CatalogSubscriberStore.applyEntityUpdate] is even called (§6.3): the index-vs-payload check
 * catches a lying/broken provider (index says X, body is Y), and the payload-vs-recompute check
 * catches a canonicalization drift between the two sides' `:shared` (the receiver re-canonicalizes
 * the payload and re-hashes it). A failure leaves the copy untouched and is reported, never written.
 *
 * ## Fork protection is not this class's job
 *
 * A forked or `ONE_SHOT` copy is simply never in [CatalogSubscriberStore.activeSubscriptions] (AC8,
 * see that port's doc). The engine iterates what it is given; it contains no fork test.
 */
class CatalogSyncEngine(
    private val store: CatalogSubscriberStore,
    private val notifier: NotificationPort,
    private val clock: () -> Long,
    /** "No silent failures": a verify or fetch failure says so out loud. The hosts pass their logger. */
    private val log: (String) -> Unit = {},
) {

    /** A plain JSON parser for the payload-vs-recompute check — no discriminator, no defaults; just parse-then-recanonicalize. */
    private val payloadJson = Json

    /**
     * Run one sync against [peer] through [client] (already pointed at the peer's address with the
     * peer's pairing credentials). Returns how the run ended; performs all writes through [store] and
     * fires [notifier] once if anything changed.
     */
    fun sync(peer: CatalogPeer, client: CatalogClient): CatalogSyncOutcome {
        val now = clock()

        // 1. One GET — the index + rootHash.
        val index = when (val result = client.index()) {
            is DispatchResult.Success -> result.value
            is DispatchResult.Failure -> return onIndexFailure(peer, result.error, now)
        }

        // 2. Cheap no-op path: unchanged root hash → one GET, no writes, no notification (AC6).
        if (index.rootHash == peer.lastRootHash) {
            store.recordSuccess(peer.peerId, now, index.rootHash)
            return CatalogSyncOutcome.NoChange
        }

        // 3. Diff each active (SUBSCRIBE) subscription against the fresh index.
        val changes = mutableListOf<CatalogChange>()
        val failures = mutableListOf<VerifyFailure>()
        val byId = index.entries.associateBy { it.id }

        for (subscription in store.activeSubscriptions(peer.peerId)) {
            val entry = byId[subscription.sourceEntityId]
            when {
                entry == null -> {
                    // Upstream removed/unshared → keep the copy, mark it, announce it (§6.4).
                    store.markSourceRemoved(subscription.localEntityId, now)
                    changes += CatalogChange.SourceRemoved(subscription.localEntityId, subscription.kind)
                }
                entry.contentHash == subscription.lastHash -> Unit // unchanged, skip
                else -> pull(client, subscription, entry, now, changes, failures)
            }
        }

        // 4. Advance the watermark — but only the root hash on a CLEAN run. A partial run keeps the old
        //    root hash so the next run re-detects and retries the unresolved entities (§6.1 step 4 note).
        store.recordSuccess(peer.peerId, now, if (failures.isEmpty()) index.rootHash else null)

        // 5. One notification per run, only when something actually changed (§6.1 step 5).
        if (changes.isNotEmpty()) notifier.notify(SyncNotification(peer.displayName, changes))

        return when {
            failures.isNotEmpty() -> CatalogSyncOutcome.PartialVerifyFailure(failures, changes)
            changes.isNotEmpty() -> CatalogSyncOutcome.Updated(changes)
            else -> CatalogSyncOutcome.NoChange
        }
    }

    /** Pull one changed entity or credential, verify it, and hand it to the store — or record why not. */
    private fun pull(
        client: CatalogClient,
        subscription: CatalogSubscriptionRef,
        entry: CatalogEntry,
        now: Long,
        changes: MutableList<CatalogChange>,
        failures: MutableList<VerifyFailure>,
    ) {
        if (subscription.kind == CatalogEntityKindWire.CREDENTIAL) {
            pullCredential(client, subscription, entry, now, changes, failures)
        } else {
            pullEntity(client, subscription, entry, now, changes, failures)
        }
    }

    private fun pullEntity(
        client: CatalogClient,
        subscription: CatalogSubscriptionRef,
        entry: CatalogEntry,
        now: Long,
        changes: MutableList<CatalogChange>,
        failures: MutableList<VerifyFailure>,
    ) {
        val response = when (val result = client.entity(subscription.sourceEntityId)) {
            is DispatchResult.Success -> result.value
            is DispatchResult.Failure -> return onPullFailure(subscription, result.error, now, changes, failures)
        }

        // Verify 1 — index ↔ payload: the served hash must equal the one the index advertised.
        if (response.contentHash != entry.contentHash) {
            return recordVerifyFailure(subscription, "index/payload hash mismatch", failures)
        }
        // Verify 2 — payload ↔ recompute: re-canonicalize the payload and re-hash it. Catches a
        // canonicalization drift between the two sides' :shared, and any tampering in transit (§6.3).
        val recomputed = recompute(response.payload)
            ?: return recordVerifyFailure(subscription, "payload not canonicalizable", failures)
        if (recomputed != response.contentHash) {
            return recordVerifyFailure(subscription, "payload/recompute hash mismatch", failures)
        }

        store.applyEntityUpdate(
            VerifiedEntityUpdate(
                localEntityId = subscription.localEntityId,
                sourceEntityId = subscription.sourceEntityId,
                kind = subscription.kind,
                payload = response.payload,
                contentHash = response.contentHash,
                at = now,
            ),
        )
        changes += CatalogChange.Updated(subscription.localEntityId, subscription.kind, entry.label)
    }

    private fun pullCredential(
        client: CatalogClient,
        subscription: CatalogSubscriptionRef,
        entry: CatalogEntry,
        now: Long,
        changes: MutableList<CatalogChange>,
        failures: MutableList<VerifyFailure>,
    ) {
        val response = when (val result = client.credential(subscription.sourceEntityId)) {
            is DispatchResult.Success -> result.value
            is DispatchResult.Failure -> return onPullFailure(subscription, result.error, now, changes, failures)
        }

        // A credential carries no verifiable content hash — its at-rest fingerprint is computed over
        // the PROVIDER's encrypted blob, which the receiver cannot reproduce (§6.2). The plaintext is
        // trusted via TLS + pairing; the index entry's fingerprint becomes the local watermark.
        store.applyCredentialUpdate(
            VerifiedCredentialUpdate(
                localEntityId = subscription.localEntityId,
                sourceEntityId = subscription.sourceEntityId,
                provider = response.provider,
                label = response.label,
                secret = response.secret,
                contentHash = entry.contentHash,
                at = now,
            ),
        )
        changes += CatalogChange.Updated(subscription.localEntityId, subscription.kind, entry.label)
    }

    /** `EntityGone`/404 mid-pull is the same as an absent index entry: keep the copy, mark it (§6.4). */
    private fun onPullFailure(
        subscription: CatalogSubscriptionRef,
        error: DispatchError,
        now: Long,
        changes: MutableList<CatalogChange>,
        failures: MutableList<VerifyFailure>,
    ) {
        if (error is DispatchError.EntityGone) {
            store.markSourceRemoved(subscription.localEntityId, now)
            changes += CatalogChange.SourceRemoved(subscription.localEntityId, subscription.kind)
        } else {
            // A transient fetch failure (peer went away mid-run, a 5xx): keep the old copy, report it,
            // and — via the empty root hash in recordSuccess — let the next run retry (§6.4).
            recordVerifyFailure(subscription, "fetch failed: ${error::class.java.simpleName}", failures)
        }
    }

    private fun recordVerifyFailure(
        subscription: CatalogSubscriptionRef,
        reason: String,
        failures: MutableList<VerifyFailure>,
    ) {
        log("catalog-sync: ${subscription.localEntityId} from ${subscription.sourceEntityId} — $reason; copy left unchanged")
        failures += VerifyFailure(subscription.localEntityId, subscription.sourceEntityId, subscription.kind, reason)
    }

    private fun onIndexFailure(peer: CatalogPeer, error: DispatchError, now: Long): CatalogSyncOutcome =
        when (error) {
            // The peer has no catalog route at all — an older companion (AC1). Record the attempt so
            // the UI can say "update that peer"; do NOT touch last_success_at.
            is DispatchError.EndpointMissing -> {
                store.recordContact(peer.peerId, now)
                CatalogSyncOutcome.EndpointMissing
            }
            // Unreachable, or any other non-endpoint failure (auth, protocol, 5xx): we could not sync,
            // so the peer shows as stale — last_contact_at moves, last_success_at does not, and there
            // is no error spam (§6.4, F33). Staleness is DERIVED from last_success_at, not a run state,
            // which is why the spec's separate `Stale` outcome collapses into this one.
            else -> {
                log("catalog-sync: ${peer.peerId} index failed: ${error::class.java.simpleName}")
                store.recordContact(peer.peerId, now)
                CatalogSyncOutcome.PeerUnreachable
            }
        }

    /** Re-canonicalize a payload and hash it; null if it is not parseable JSON (a verify failure). */
    private fun recompute(payload: String): String? =
        try {
            contentHashOfElement(payloadJson.parseToJsonElement(payload))
        } catch (_: Exception) {
            null
        }
}

/** The peer this run targets, plus the one bit of its stored state the run needs: the last root hash. */
data class CatalogPeer(
    val peerId: String,
    val displayName: String,
    /** The `rootHash` of the last successful run, or null if never synced — the no-op detector (§6.1). */
    val lastRootHash: String?,
)

/** One subscription that could not be brought current this run — the copy was left untouched (§6.3). */
data class VerifyFailure(
    val localEntityId: String,
    val sourceEntityId: String,
    val kind: CatalogEntityKindWire,
    val reason: String,
)

/**
 * How one peer's sync run ended (peer-katalog.md §6.1).
 *
 * Not the generic `DispatchResult`: like [SyncOutcome], several arms are *successes of different
 * completeness* — a run that pulled two entities and failed to verify a third did real work and must
 * be tellable from one that did nothing.
 */
sealed class CatalogSyncOutcome {

    /** Nothing to do: the root hash matched (one GET), or it moved but none of OUR copies were affected. */
    object NoChange : CatalogSyncOutcome()

    /** Every changed copy was verified and written. [changes] is non-empty. */
    data class Updated(val changes: List<CatalogChange>) : CatalogSyncOutcome()

    /**
     * At least one copy could not be brought current ([failures]) — a hash mismatch or a mid-run
     * fetch failure — while [changes] (possibly empty) did land. The failed copies were left
     * untouched and the peer's root hash was NOT advanced, so the next run retries them (§6.3).
     */
    data class PartialVerifyFailure(
        val failures: List<VerifyFailure>,
        val changes: List<CatalogChange>,
    ) : CatalogSyncOutcome()

    /** The index GET failed on connectivity (or auth/protocol): the peer is stale, not errored (§6.4, AC9). */
    object PeerUnreachable : CatalogSyncOutcome()

    /** The peer answered a bare 404: it serves no catalog route — an older companion (AC1, §6.1). */
    object EndpointMissing : CatalogSyncOutcome()
}
