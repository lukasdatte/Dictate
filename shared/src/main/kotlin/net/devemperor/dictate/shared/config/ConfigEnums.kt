package net.devemperor.dictate.shared.config

import kotlinx.serialization.Serializable

/**
 * The finite-set vocabularies of the shared config model — wire-side mirrors of the app's
 * behaviour-carrying domain enums.
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). `:shared` sits BELOW `:shared-ai`, so it cannot reference the
 * domain originals (`AIProvider`, `AmbiguityMode`, `AIFunction`, `PromptMode`). It
 * therefore defines its own wire enums here; a parity test in `:app` (which sees both
 * modules) pins value-equality so the two can never drift and silently break the
 * `contentHash` (Plan §3 D5.a, spec §4.8 / §13 D6). This is the same wire-vs-domain
 * doctrine as `SessionOriginWire` ↔ `SessionOrigin` (ADR-0016).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §4.8, §13 D6
 */

/** Mirror of `AIProvider` (6 values, parity-tested in `:app`). */
@Serializable
enum class ProviderType { OPENAI, GROQ, ANTHROPIC, ELEVENLABS, OPENROUTER, CUSTOM }

/**
 * How a [ProviderConfigEntity] reaches its vendor.
 *
 * `GATEWAY` is **reserved** (F31): the enum value exists so the wire format is forward-stable,
 * but v1 must not let a user create a GATEWAY provider — `ConfigValidations.providerConfig`
 * actively rejects `kind = GATEWAY` until Block E ships the gateway path.
 */
@Serializable
enum class ProviderKind { LOCAL, GATEWAY }

/** Mirror of `AIFunction` (parity-tested in `:app`). */
@Serializable
enum class ModelFunction { TRANSCRIPTION, COMPLETION }

/** Mirror of `AmbiguityMode.persistKey` (ADR-0013; parity-tested in `:app`). */
@Serializable
enum class AmbiguityModeValue { ALWAYS_INSERT, AUTO, ALWAYS_REVIEW }

/** Mirror of `PromptMode` (int values 0/1/2; parity-tested in `:app`). */
@Serializable
enum class PromptSelectionMode { NONE, PREDEFINED, CUSTOM }

/** Sharing scope of a config entity. Envelope metadata — excluded from the `contentHash` (§4.2). */
@Serializable
enum class Visibility { PRIVATE, SHARED }

/**
 * Subscription state of a fetched copy (F14). In Block C every entity is [LOCAL];
 * `SUBSCRIBE`/`ONE_SHOT` semantics are Block E. Envelope metadata — excluded from the
 * `contentHash` (§4.2).
 */
@Serializable
enum class SubscriptionMode { LOCAL, SUBSCRIBE, ONE_SHOT }
