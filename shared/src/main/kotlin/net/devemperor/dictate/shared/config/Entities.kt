package net.devemperor.dictate.shared.config

import kotlinx.serialization.Serializable

/**
 * The five shareable config entities plus their common envelope value types.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). These are NOT Room entities: Room cannot annotate `:shared`
 * classes and the envelope/payload split does not map onto `@Entity`. `:app` keeps its own
 * Room rows plus a thin mapper (`ConfigEntityMapper`, C2) — the same pattern as
 * `SessionEntityMapper`.
 *
 * ## Envelope vs. payload
 *
 * Every entity carries six **envelope** fields flat on the class (`id`, `contentHash`,
 * `updatedAt`, `visibility`, `sourceRef`, `subscriptionMode`) followed by its **payload**.
 * The envelope is metadata that differs between two copies of the same content; the payload
 * is what the `contentHash` is computed over. `CanonicalJson` strips the envelope by the fixed
 * [ENVELOPE_FIELDS] name list before hashing (§4.2) — a flat class, not an `Envelope{payload}`
 * wrapper, so every consumer (Room mapper, UI) reads it without an extra nesting level (§13 D1).
 *
 * ## No key value ever
 *
 * [ApiCredentialEntity] carries only a [ApiCredentialEntity.keyFingerprint], never the key. The
 * clear-text key lives ONLY in the SecretStore (Block B), addressed by the entity `id` (F12). A
 * reviewer who finds an `apiKey`/`secret` field here has found an F12 violation.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §4.1-4.7
 */

/**
 * Provenance of a fetched copy (F14). null for locally created entities.
 *
 * Envelope value type — carried inside the (excluded) `sourceRef` field, so it never affects the
 * `contentHash`. [originalContentHash] is the basis of Block E's "has the upstream changed?"
 * comparison.
 */
@Serializable
data class SourceRef(
    val peerId: String,
    val originalId: String,
    /** `contentHash` of the original at the moment it was taken over. */
    val originalContentHash: String,
)

// ── Entities ──────────────────────────────────────────────────────────────────────────────

@Serializable
data class ProviderConfigEntity(
    // ── Envelope ──
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload ──
    val providerType: ProviderType,
    /** LOCAL = direct vendor API; GATEWAY reserved (F31), not selectable in v1. */
    val kind: ProviderKind = ProviderKind.LOCAL,
    val label: String,
    /** null → `providerType`'s default base URL; only meaningful for CUSTOM/GATEWAY. */
    val baseUrl: String? = null,
    /** uuid of an [ApiCredentialEntity], or null (e.g. a local custom endpoint with no key). */
    val credentialRef: String? = null,
)

@Serializable
data class ApiCredentialEntity(
    // ── Envelope ──
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload ──
    val providerType: ProviderType,
    val label: String,
    /**
     * Fingerprint of the key: `sha256(key)`-hex, first 16 chars. A key change therefore changes
     * the `contentHash` WITHOUT the key ever appearing in the payload/index (F12). The clear-text
     * key lives only in the SecretStore under `SecretRef(id)`. There is deliberately no field for
     * the key value.
     */
    val keyFingerprint: String,
)

@Serializable
data class ModelRefEntity(
    // ── Envelope ──
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload ──
    val providerRef: String,          // uuid of a ProviderConfigEntity
    val modelId: String,              // e.g. "gpt-4o-mini"
    val function: ModelFunction,      // TRANSCRIPTION | COMPLETION
    val label: String? = null,
    /**
     * Parameter defaults as canonical string values (sorted), e.g.
     * `{"temperature":"0.7","max_tokens":"4096"}`. Strings, not numbers, on purpose — it avoids
     * IEEE-754 canonicalisation (§5.1). Interpreted via `ParameterRegistry` (Block A).
     * Transcription-specific: `{"keyterms":"<parsed-json>"}` for ElevenLabs.
     */
    val parameterDefaults: Map<String, String> = emptyMap(),
)

@Serializable
data class PromptV3Entity(
    // ── Envelope ──
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload ──
    val name: String,
    val text: String,
    val requiresSelection: Boolean = false,
    val autoApply: Boolean = false,
)
// NOTE: no pill `type`. The shared prompt entity models only the shareable post-processing
// prompt. Android's `prompts.type` (PROMPT/TEXT, ADR-0024) stays a Room-only column; TEXT pills
// are literal snippets, not shareable AI prompts, and are never exported as a PromptV3Entity
// (§13 D3). A v3 import creates an Android `prompts` row with `type = PROMPT`.

@Serializable
data class ProfilePromptRef(
    val promptRef: String,   // uuid → PromptV3Entity (or Android prompts.uuid, §7.3)
    val autoApply: Boolean = false,
)

@Serializable
data class ProfileEntity(
    // ── Envelope ──
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload (F17) ──
    val name: String,
    val transcriptionModelRef: String? = null,  // uuid → ModelRefEntity (function=TRANSCRIPTION)
    val completionModelRef: String? = null,      // uuid → ModelRefEntity (function=COMPLETION)
    /** Ordered post-processing prompts (order is significant for the hash — §5.1 arrays in-order). */
    val orderedPrompts: List<ProfilePromptRef> = emptyList(),
    val stylePromptMode: PromptSelectionMode = PromptSelectionMode.PREDEFINED,
    val stylePromptCustomText: String = "",
    val systemPromptMode: PromptSelectionMode = PromptSelectionMode.PREDEFINED,
    val systemPromptCustomText: String = "",
    val ambiguityMode: AmbiguityModeValue = AmbiguityModeValue.ALWAYS_INSERT,
    /** Completion parameter overrides on top of the ModelRef defaults. */
    val parameterOverrides: Map<String, String> = emptyMap(),
)
// NOTE: `is_active` is NOT a profile field — it is not shareable content and would pollute the
// hash. The active profile is a device-local pointer `Pref.ActiveProfileId` (§4.7, §13 D4).
