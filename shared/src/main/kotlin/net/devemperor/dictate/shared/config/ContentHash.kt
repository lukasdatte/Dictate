package net.devemperor.dictate.shared.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

/**
 * The content hash of a config value: `sha256` of its [CanonicalJson] bytes, lowercase hex.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). Because [CanonicalJson] strips the envelope fields (§4.2), two entities
 * with the same payload but a different `id`/`visibility`/`sourceRef` produce the SAME hash —
 * the property Block E's fork-dedup and drift-detection rely on (F27).
 *
 * This is a denormalised cache: `ConfigRepository` (C2) recomputes it on every write path
 * (create/edit/import/migration) and never trusts an incoming value (§5.3,
 * docs/DATABASE-PATTERNS.md "Denormalized Cache Columns").
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §5.2, §5.3
 */
fun <T> contentHash(value: T, serializer: KSerializer<T>): String =
    sha256Hex(CanonicalJson.canonicalBytes(value, serializer))

/**
 * The content hash of an already-parsed payload [element] — the same digest as [contentHash], but
 * taken over the RAW file bytes (via [CanonicalJson.canonicalString]) rather than a typed round-trip.
 * A newer writer's superset payload therefore verifies: an unknown additive field survives the parse
 * and is folded into the hash exactly as the writer computed it (forward-compat, §5.4), while a
 * tampered payload value still produces a different hash and is rejected.
 */
fun contentHashOfElement(element: JsonElement): String =
    sha256Hex(CanonicalJson.canonicalString(element).toByteArray(Charsets.UTF_8))

/**
 * SHA-256 → lowercase hex. `and 0xFF` is mandatory: a Kotlin Byte is signed, so a byte >= 0x80 would
 * sign-extend to a negative Int and format as "ffffff80" without the mask. A SHA-256 digest routinely
 * has such bytes, so the mask is load-bearing, not cosmetic (guarded by ContentHashTest).
 */
private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
