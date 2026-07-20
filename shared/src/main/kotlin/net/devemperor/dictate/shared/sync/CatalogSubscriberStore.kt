package net.devemperor.dictate.shared.sync

import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire

/**
 * The subscriber's local mirror, seen by the [CatalogSyncEngine] as a set of narrow, verb-shaped
 * operations (peer-katalog.md §6).
 *
 * A port, not a concrete DB, for the same reason [net.devemperor.dictate.shared.sync.SyncSource]
 * is one: the engine is pure and platform-free (ADR-0015), so both hosts implement this over their
 * own store — the companion's SQLDelight `peers`/`subscriptions`/entity tables
 * (`SqlDelightCatalogSubscriberStore`) and the phone's Room copy (`AndroidCatalogSubscriberStore`)
 * — and the two-peer E2E drives a real one, while the unit tests drive a fake.
 *
 * ## Fork protection is a query property, not a check (AC8)
 *
 * [activeSubscriptions] returns ONLY `mode = 'SUBSCRIBE'` rows. A forked copy has had its
 * subscription row DELETED (and its entity `subscription_mode` set to `LOCAL`) in one transaction
 * (§5.3), and a `ONE_SHOT` copy never had a SUBSCRIBE row — so neither is ever handed to the engine,
 * and no run can overwrite them. The engine does not test for forks; it simply never sees them.
 *
 * ## Watermark advance is the store's, atomicity too
 *
 * A pull's two writes — the entity payload AND `subscription.last_hash`/`last_checked_at` — MUST
 * land together ([applyEntityUpdate]/[applyCredentialUpdate] are each one transaction), so a crash
 * between them can never leave a copy whose watermark says "current" over stale bytes. A credential's
 * plaintext goes to the SecretStore and NEVER to a column; only its fingerprint hash is the watermark
 * (F12, §6.2) — which is why credential application is its own method, not an entity write.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/peer-katalog.md §6
 * @see docs/decisions/0034-peer-catalog.md
 */
interface CatalogSubscriberStore {

    /**
     * The active (`SUBSCRIBE`) subscriptions of [peerId] — the copies this run may touch. `ONE_SHOT`
     * and forked copies are excluded by construction (see class doc); the engine iterates exactly
     * what this returns.
     */
    fun activeSubscriptions(peerId: String): List<CatalogSubscriptionRef>

    /**
     * Record a reached-but-unsuccessful attempt: `last_contact_at = at`, `last_success_at` untouched.
     * This is what drives the staleness display without any error spam (§6.4, F33) — a peer that is
     * merely unreachable is stale, not failed.
     */
    fun recordContact(peerId: String, at: Long)

    /**
     * Record a successful contact: `last_contact_at = at`, `last_success_at = at`, and — only when
     * [rootHash] is non-null — advance `last_root_hash = rootHash`.
     *
     * [rootHash] is null when the run reached the peer but did NOT fully resolve (a per-entity fetch
     * or verify failed): the peer is not stale (it answered), yet the root hash must NOT advance, or
     * the cheap no-op path (§6.1 step 2) would skip the unresolved change forever. A clean run passes
     * the index root hash; a partial run passes null and the next run re-detects and retries.
     */
    fun recordSuccess(peerId: String, at: Long, rootHash: String?)

    /**
     * Apply a verified non-credential payload to its local copy AND advance the subscription
     * watermark, in ONE transaction (§6.2): the copy's fields are re-parsed from [VerifiedEntityUpdate.payload]
     * by the platform adapter (which owns the row⇄DTO mapping), its `content_hash` set to
     * [VerifiedEntityUpdate.contentHash], and `subscription.last_hash`/`last_checked_at` updated.
     *
     * Called ONLY after both hash checks passed — the store never re-verifies, it trusts the engine.
     */
    fun applyEntityUpdate(update: VerifiedEntityUpdate)

    /**
     * Apply a verified credential: put the plaintext into the SecretStore, set the entity row's
     * `provider`/`label`/`content_hash` (the fingerprint watermark, never the secret), and advance
     * the subscription watermark — in ONE transaction (§6.2, F12).
     */
    fun applyCredentialUpdate(update: VerifiedCredentialUpdate)

    /**
     * The upstream entity is gone (absent from the index, or a 404 on fetch): mark the subscription
     * `SOURCE_REMOVED` at [at] and KEEP the local copy. Never deletes — deletion is destructive and
     * the copy belongs to the receiver now (§6.4).
     */
    fun markSourceRemoved(localEntityId: String, at: Long)
}

/**
 * One active subscription as the engine needs it: which local copy, which upstream id and kind, and
 * the last content hash it was synced to ([lastHash]) — the diff basis against the peer's index.
 */
data class CatalogSubscriptionRef(
    val localEntityId: String,
    val sourceEntityId: String,
    val kind: CatalogEntityKindWire,
    val lastHash: String,
)

/** A non-credential pull that passed both verify checks, ready for the store to persist (§6.2, §6.3). */
data class VerifiedEntityUpdate(
    val localEntityId: String,
    val sourceEntityId: String,
    val kind: CatalogEntityKindWire,
    /** The canonical payload the store re-parses into the copy's fields. Its hash is [contentHash]. */
    val payload: String,
    val contentHash: String,
    val at: Long,
)

/**
 * A credential pull ready for the store to persist (§6.2). [secret] is plaintext in transit only —
 * the store puts it straight into the SecretStore and never into a column; [contentHash] is the
 * provider's at-rest fingerprint from the index entry, and becomes the subscription watermark.
 */
data class VerifiedCredentialUpdate(
    val localEntityId: String,
    val sourceEntityId: String,
    val provider: String,
    val label: String,
    val secret: String,
    val contentHash: String,
    val at: Long,
)
