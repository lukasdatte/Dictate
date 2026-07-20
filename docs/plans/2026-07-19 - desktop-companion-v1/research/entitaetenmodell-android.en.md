---
date: 2026-07-19
author: Lukas + Claude (planning session, groundwork agent)
status: Spec — programmer-ready
context: Implementer-ready specification for Block C of the desktop-companion-v1 plan — configuration entity model (ProviderConfig/ModelRef/ApiCredential/Prompt/Profile) in :shared, canonical v3 codec + contentHash, Room migration v11→v12, Prefs→entity migration, ProfileResolver and Android settings UI rework.
related-plan: ../desktop-companion-v1.md (resides in ~/.claude/plans/desktop-companion-v1.md until archival)
related-adrs: 0012, 0013, 0016, 0024; plan-scoped adr-config-entity-model, adr-secret-store, adr-shared-ai-module
---

# Block C — Configuration Entity Model + Android Rework

This spec is the binding implementation blueprint for **Block C** of the
Desktop Companion plan: it migrates the AI configuration that is today stored as loose
SharedPreferences strings (provider, models, keys, prompts, parameters) into a
shareable, versioned **entity model** in `:shared`, defines the
**canonical serialization** (which is at the same time the `contentHash` basis and the
v3 file format), the **Room migration v11→v12**, the **Prefs→entity migration** with
backup rollback, the **ProfileResolver** (which serves the AiConfig port from Block A)
and the **settings UI rework** onto the entity model.

It is exclusively Block-C-focused. Block A (`:shared-ai` extraction, ports),
Block B (SecretStore), Block D (desktop host), Block E (peer catalog) are referenced
only at their seams, never co-specified. Where a seam is still open, it is captured in
§14 Information Gaps.

## Table of Contents

- [Glossary](#glossary)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Current-State Configuration Inventory](#3-current-state-configuration-inventory)
- [§4 Architecture Specification — Entities (`:shared`)](#4-architecture-specification--entities-shared)
- [§5 Canonical Serialization + contentHash + v3 Format](#5-canonical-serialization--contenthash--v3-format)
- [§6 Directory Layout](#6-directory-layout)
- [§7 Room Persistence v11→v12 (`:app`)](#7-room-persistence-v11v12-app)
- [§8 Prefs→Entities Migration](#8-prefsentities-migration)
- [§9 ProfileResolver (AiConfig port)](#9-profileresolver-aiconfig-port)
- [§10 Settings UI Rework (C3)](#10-settings-ui-rework-c3)
- [§11 Migration Plan (Chunk Split)](#11-migration-plan-chunk-split)
- [§12 Testing Approach](#12-testing-approach)
- [§13 Decision Log](#13-decision-log)
- [§14 Information Gaps](#14-information-gaps)
- [§15 References](#15-references)

## Glossary

**Entities & Model**

- **ConfigEntity** — umbrella term for the five shareable configuration entities
  (`ProviderConfig`, `ApiCredential`, `ModelRef`, `Prompt`, `Profile`). Each carries
  a common **Envelope** (§4.1) and a **Payload** (§4.2).
- **Envelope** — the non-content metadata frame of every entity:
  `id`, `contentHash`, `updatedAt`, `visibility`, `sourceRef` (provenance). It is
  **not** included in the `contentHash` (§5.2).
- **Payload** — the content fields of an entity (name, modelId, prompt text,
  parameters …). Exactly these fields go into the canonical serialization and thus
  into the `contentHash`.
- **ProviderConfig** — provider definition (`providerType`, `kind`, `baseUrl`,
  `credentialRef`). §4.3.
- **ApiCredential** — reference to a secret + metadata (`providerType`, `label`,
  `keyFingerprint`). The key **plaintext** never lives in the entity/DB, only in the
  **SecretStore** (Block B). §4.4.
- **ModelRef** — model definition (`providerRef`, `modelId`, `function`,
  `parameterDefaults`). §4.5.
- **Prompt** — shareable post-processing prompt (`name`, `text`,
  `requiresSelection`, `autoApply`) — **without** the pill `type` (§13 D3). §4.6.
- **Profile** — the switchable unit (F24): transcription/completion ModelRef,
  ordered prompt references, system/style prompt selection, `AmbiguityMode`,
  parameter overrides. §4.7.
- **ProviderType** — `:shared` mirror enum of `AIProvider` (which lives in `:shared-ai`
  and is therefore not referenceable from `:shared`, §13 D2). Value parity enforced via
  test.

**Serialization & Sync**

- **Canonical serialization** — deterministic byte form of a payload
  (sorted object keys, defined number/null/string handling); basis of
  `contentHash` **and** the v3 file format. §5.
- **contentHash** — `sha256(canonical payload bytes)`, lowercase hex. Drift detector
  and sync watermark (F27). §5.2.
- **v3 format** — `{ "version": 3, "entities": [ … ] }`; the single codec implementation
  for file export **and** peer wire (F23). §5.4.
- **sourceRef** — provenance of a subscribed copy: `{ peerId, originalId,
  originalContentHash }` (F14 "fork + update hint"). `null` for locally created
  entities.
- **subscriptionMode** — `LOCAL | SUBSCRIBE | ONE_SHOT`; subscription state of a copy.
  In Block C created schema-side only (all migration entities = `LOCAL`); the
  sync semantics are Block E.

**Android Persistence**

- **Prefs→entity migration** — the one-time, idempotent migration of the current
  provider/model/key/parameter prefs into a **default profile** + associated
  entities (§8).
- **Prefs backup** — complete JSON dump of all SharedPreferences **before** the
  migration (rollback path, F22/D4.7). §8.4.
- **ProfileResolver** — the Android implementation of the `AiConfig` port (Block A):
  delivers the effective runner configuration from the active profile + credentials. §9.

> **contentHash ≠ keyFingerprint ≠ sourceRef.originalContentHash.** The
> `contentHash` identifies the **current** content of a local entity; the
> `keyFingerprint` is a key fingerprint **within** the `ApiCredential` payload
> (so a key change alters the hash without revealing the key); the
> `sourceRef.originalContentHash` is the frozen `contentHash` of the source peer
> **at the time of adoption** (basis for the "update available" comparison, Block E).

> **ProviderType ≠ ProviderKind.** `ProviderType` is the provider vendor
> (`OPENAI`, `ANTHROPIC`, `CUSTOM`, …, mirror of `AIProvider`). `ProviderKind` is
> `LOCAL | GATEWAY` — whether the provider addresses a vendor API directly (`LOCAL`) or
> will use a peer as a gateway in the future (`GATEWAY`, F31 reserved, not selectable in v1).

## 1. Vision and Motivation

### 1.1 Why this model exists

Dictate's entire AI configuration lives today as ~40 flat
SharedPreferences strings (§3.1): two provider selections (transcription +
rewording), per provider an API key in **plaintext**, a model string, a
custom host, plus parameter prefs (temperature, max tokens, reasoning effort). This
form is bound to exactly one Android installation: not shareable, not
combinable, not versionable, and the keys sit unencrypted in the
prefs XML. The desktop companion (Block D) and the peer catalog (Block E) need
the same configuration in a shareable, platform-neutral form — the common root
is an **entity-based model** in `:shared`.

### 1.2 Which problem this solves

- **Shareability.** Provider, models, prompts and (encrypted) keys become
  entities with stable UUID identity and `contentHash` — the basis for
  file export (v3) and peer sync (Block E).
- **Combinability.** A **profile** bundles transcription model,
  completion model, ordered prompts, parameters and `AmbiguityMode` into a
  switchable unit (F17/F24) — instead of a single globally scattered
  constellation.
- **Security.** Keys leave the entity/DB as a value entirely; the plaintext
  lives only in the **SecretStore** (Block B). The entity holds only a reference +
  fingerprint.
- **Drift detection for free.** The `contentHash` over the canonical form detects
  "edited locally?" and "peer changed?" without additional machinery (F27).

### 1.3 Discarded Alternatives

- **Keep prefs, just encrypt keys.** Discarded: solves neither shareability nor
  combinability; the desktop host would still need a second configuration model
  → duplicated truth.
- **Define entities separately per platform (Room + SQLDelight, each its own
  DTO).** Discarded per plan-D3: the serialization/hash format must be **one**
  truth in `:shared`, otherwise the Android and desktop hash drift apart and
  the peer sync breaks. Native persistence stays platform-specific (Room/SQLDelight),
  but the DTOs + codec are shared.
- **`contentHash` over the kotlinx standard JSON output.** Discarded: kotlinx-JSON
  guarantees neither a sorted key order across refactorings nor a
  stable float representation — both break byte identity. Hence an explicit
  canonicalization (§5, JCS subset).
- **Feature flag "entity model active" for gentle coexistence.** Discarded per F22/
  D4.7 (hard migration). The rollback path is instead the **prefs backup export**
  before the migration (§8.4).

### 1.4 What this model concretely delivers

1. A **single** canonical serialization that is at once the hash basis, file export
   and peer wire (F23/F27) — no second codec.
2. **UUID identity + provenance** (`sourceRef`), so that Block E can implement fork/update
   without a merge problem (F14/F29).
3. **Lossless Android migration** with backup rollback (F22).
4. A **port-clean** resolution path (`ProfileResolver` → `AiConfig`) that decouples the
   pipeline from pref knowledge — the same seam that Block D/E use.
5. `GATEWAY` path **reserved, not built** (F31): Double-Enum enables the later
   migration cleanly.

## 1a. Architecture Walkthrough

### 1a.0 Layer diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  :shared/config/ (NEU)                                     (unten)  │
│  ConfigEntity-DTOs (@Serializable) + Konform Validation<T>          │
│  + CanonicalJson + contentHash + CatalogCodec (v3)                  │
│  Reine JVM, jvmTarget 1.8, kein Android/okhttp-Leak (SharedPurity)  │
└─────────────────────────────────────────────────────────────────────┘
        ▲ referenziert DTOs                    ▲ referenziert DTOs
        │                                      │
┌───────┴──────────────────────────┐  ┌────────┴──────────────────────────┐
│  :app/config/ (NEU, Block C)     │  │  :companion (Block D/E)           │
│  Room-Tabellen v12 + Mapper      │  │  SQLDelight-Spiegel (out of scope) │
│  ConfigEntityMigration           │  └────────────────────────────────────┘
│  ProfileResolver → AiConfig      │
│  (implementiert Port aus Block A)│
└───────┬──────────────────────────┘
        │ liefert AiConfig
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  :shared-ai (Block A)                                               │
│  RunnerFactory/AIOrchestrator lesen AiConfig statt SharedPreferences │
│  AIProvider, ParameterRegistry, PromptTypeClassifier                 │
└─────────────────────────────────────────────────────────────────────┘
```

> [!IMPORTANT]
> **Layering constraint (load-bearing).** `:shared` sits **below** `:shared-ai`.
> `AIProvider`, `ParameterRegistry`, `PromptTypeClassifier`, `AmbiguityMode` (today in
> `:app`, move into `:shared-ai` per Block A) are **not** referenceable from `:shared`.
> The entities in `:shared` therefore define their own mirror enums
> (`ProviderType`, `ModelFunction`, `AmbiguityModeValue`) with parity tests against the
> `:shared-ai` originals (§4.8, §13 D2). Whoever breaks this rule creates a
> cyclic dependency that `SharedPurityTest`/Gradle refuses immediately.

### 1a.1 Resolution flow (one transcription)

```
DictateInputMethodService (Aufnahme fertig)
    → AIOrchestrator.transcribe(...)            [:shared-ai, Block A]
        → factory: RunnerFactory(aiConfig)       aiConfig = ProfileResolver [:app, C2]
            → aiConfig.provider(TRANSCRIPTION)
                : ProfileResolver liest ActiveProfileId (Pref)
                → profiles[activeId].transcriptionModelRef
                → model_refs[ref].providerRef
                → provider_configs[pref].providerType     : ProviderType
            → aiConfig.apiKey(...)
                → provider_configs[pref].credentialRef
                → SecretStore.get(SecretRef(credentialId)) : ByteArray?  [Block B]
            → aiConfig.baseUrl(...) = providerConfig.baseUrl ?: providerType.default
            → aiConfig.parameters(...) = modelRef.parameterDefaults
                                          ⊕ profile.parameterOverrides
```

### 1a.2 Read-this-before-implementing checklist

- [ ] EVERY new `@Serializable` config DTO gets a co-located `Validation<T>`
  in `:shared/config/ConfigValidations.kt` and runs only through the `CatalogCodec`
  (ADR-0016 pattern, §5.4).
- [ ] EVERY finite-set column (Room) = Kotlin enum + SQL `CHECK` (Double-Enum,
  docs/DATABASE-PATTERNS.md). Affects `provider_type`, `kind`, `function`,
  `ambiguity_mode`, `system_prompt_mode`, `style_prompt_mode`, `visibility`,
  `subscription_mode` (§7.2).
- [ ] `contentHash` is recomputed on **every** write path (create/edit/import/migration)
  from the current payload, never adopted from an external source (§5.3). Denormalized cache
  in the sense of docs/DATABASE-PATTERNS.md "Denormalized Cache Columns".
- [ ] No `Json.encodeToString`/`decodeFromString` for config payloads outside the
  `CatalogCodec`/`CanonicalJson` (ADR-0016).
- [ ] Never write plaintext keys into a config table — only SecretRef +
  fingerprint (§4.4, F12).
- [ ] `GATEWAY` exists as a `ProviderKind` value but is **not selectable** in v1;
  `providerConfig` validation rejects creation with `kind = GATEWAY` (active test,
  §12).
- [ ] Prompt pill `type` (PROMPT/TEXT, ADR-0024) remains an Android-Room-only column;
  the shared `Prompt` entity does not know it (§13 D3).
- [ ] Prefs→entity migration is idempotent (flag-gated) and writes the prefs backup
  **beforehand** (§8.4).

## 2. Acceptance Criteria

1. **Entities + codec (`:shared`).** The five `@Serializable` DTOs (§4) exist in
   `shared/src/main/kotlin/net/devemperor/dictate/shared/config/`, each with a
   co-located `Validation<T>`; `CatalogCodec.encode/decode` is the only
   v3 door. `SharedPurityTest` stays green (no Android/okhttp import).
2. **Canonicalization stability.** Snapshot tests fix the byte identity of the canonical
   form per entity type. `contentHash` satisfies: same payload ⇒ same hash;
   order of declaration/key position irrelevant ⇒ same hash; any
   value change ⇒ new hash (§12).
3. **v3 round-trip.** `CatalogCodec` round-trips v3→v3 byte-stable; v1/v2 prompt files
   are importable via the Android legacy path (§10.4) (ADR-0024 rules preserved).
4. **Room v11→v12.** Migration creates `provider_configs`, `api_credentials`,
   `model_refs`, `profiles`, `profile_prompts` and extends `prompts` with the
   provenance columns; all finite-set columns carry `CHECK` constraints;
   `MigrationTo12Test` (instrumented) verifies acceptance of valid + rejection of invalid
   enum values.
5. **Prefs→entities lossless.** `ConfigEntityMigrationTest` with a populated
   v11 fixture (all provider slots, keys, models, parameters, prompts) creates a
   default profile whose resolution via `ProfileResolver` delivers **byte-identically** the
   same runner configuration as the old pref-based `RunnerFactory` (§9.4
   characterization test).
6. **Key security.** After the migration no config table contains a
   plaintext key; the migrated `*ApiKey*` prefs are removed from SharedPreferences
   (grep test on the pref constants, jointly with Block B2).
7. **Backup present.** Before the migration a complete prefs backup JSON exists
   in app-private storage; a second migration run is a no-op (idempotency test).
8. **UI rework.** `APISettingsActivity` (or successor screens) work
   entity-based (provider → models → profiles), incl. duplication/reordering of the
   profile list analogous to `PromptsOverview`; no UI code references any of the
   migrated pref constants anymore (grep test); Robolectric smoke for the new navigation
   green.
9. **Pill parity.** The prompt pill behavior on the phone (ADR-0024) is unchanged:
   `prompts.type` still exists, `PromptsOverview` behaves identically plus
   origin badge.

## 3. Current-State Configuration Inventory

### 3.1 DictatePrefs — migration categorization

Source: `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt:12-317`.
Category "→ Entity" = moves into the entity model per §8; "Global" = stays
SharedPreferences (F17: device-bound/UX, not part of the shareable profile).

| Pref (constant) | Type | Purpose | Category |
|---|---|---|---|
| `TranscriptionProvider` | String | active transcription provider | → Entity (default profile → transcription ModelRef → ProviderConfig) |
| `RewordingProvider` | String | active completion provider | → Entity (completion ModelRef → ProviderConfig) |
| `TranscriptionApiKeyOpenAI/Groq/Custom/OpenRouter/ElevenLabs` | String | plaintext keys | → **SecretStore** + ApiCredential (§8.2, Block B2) |
| `RewordingApiKeyOpenAI/Groq/Anthropic/OpenRouter/Custom` | String | plaintext keys | → **SecretStore** + ApiCredential |
| `TranscriptionOpenAIModel/GroqModel/ElevenLabsModel/CustomModel` | String | model IDs | → ModelRef.modelId (function=TRANSCRIPTION) |
| `RewordingOpenAIModel/GroqModel/AnthropicModel/OpenRouterModel/CustomModel` | String | model IDs | → ModelRef.modelId (function=COMPLETION) |
| `TranscriptionCustomHost` / `RewordingCustomHost` | String | custom base URL | → ProviderConfig.baseUrl |
| `ElevenLabsKeytermsRaw` / `ElevenLabsKeytermsParsed` | String | ElevenLabs key terms | → ModelRef.parameterDefaults (transcription, §4.5) |
| `TemperatureOpenAI/Groq/Anthropic/OpenRouter` | Float | parameter override | → ModelRef.parameterDefaults / Profile.parameterOverrides (§8.3) |
| `MaxTokensOpenAI/Groq/Anthropic/OpenRouter` | Int | parameter override | → ModelRef.parameterDefaults / Profile.parameterOverrides |
| `ReasoningEffortOpenAI` | String | parameter override | → ModelRef.parameterDefaults / Profile.parameterOverrides |
| `StylePromptSelection` / `StylePromptCustomText` | Int/String | transcription style prompt (PromptMode) | → Profile.stylePromptMode/-CustomText |
| `SystemPromptSelection` / `SystemPromptCustomText` | Int/String | rewording system prompt (PromptMode) | → Profile.systemPromptMode/-CustomText |
| `AmbiguityMode` | String | check mode (ADR-0013) | → Profile.ambiguityMode |
| `ProxyEnabled` / `ProxyHost` | Bool/String | proxy | **Global** (port `ProxyConfig`, Block A — not part of the profile) |
| `RewordingEnabled`, `AutoFormattingEnabled`, `InstantOutput`, `AutoEnter*`, `InstantRecording`, `ResendButton`, `Vibration`, `AudioFocus`, `UseBluetoothMic`, `Animations`, `SmallMode`, `SingleRowMode`, `AccessibilityContextEnabled` | various | IME feature toggles | **Global** |
| `Theme`, `AccentColor`, `AppLanguage`, `OverlayCharacters`, `OutputSpeed`, `WidgetOpacity`, `HistoryPanelHeightDp`, `Overlay*` | various | UI/theme/overlay | **Global** |
| `Windows*` (5 prefs) | various | PC dispatch pairing (ADR-0017) | **Global** |
| `UserId`, `OnboardingComplete`, `LastVersionCode`, `Flag*`, `InputLanguages`, `InputLanguagePos` | various | system/state | **Global** |
| `LastFileName`, `TranscriptionAudioFile`, `QueuedPromptIds`, `LegacyAudioPurgedV4`, cleanup/rolling/cache prefs | various | internal pipeline state | **Global** |
| **NEW** `ActiveProfileId` | String | pointer to the active profile | **Global** (pointer, not shareable content) |
| **NEW** `ConfigEntityMigrationDone` | Int | idempotency flag of the §8 migration | **Global** |

> [!NOTE]
> **Proxy stays global.** The proxy is device/network context, not a shareable
> profile attribute, and is read in Block A anyway via the `ProxyConfig` port.
> It does **not** move into an entity. Should a need later arise to set it per
> profile, that is a new decision (§14).

### 3.2 APISettingsActivity — current structure

Source: `app/src/main/java/net/devemperor/dictate/settings/APISettingsActivity.java`
(783 lines). Two parallel, nearly identical sections:

- **Transcription section** (`setupTranscriptionSection`, l. 155-308): provider spinner
  (`AIProvider.withTranscription()`), API key `EditText` (watcher → pref), custom-host/
  model fields, model spinner (hardcoded OR `ModelFetcher.fetchModels`), per
  provider its own key/model pref (`getTranscriptionApiKeyPref`,
  `getSavedTranscriptionModel`).
- **Rewording section** (`setupRewordingSection`, l. 312-455): analogous, plus dynamic
  **parameter UI** (`updateParameterUI`, l. 465-500) from `ParameterRegistry`
  against the `PARAM_PREFS` map (FLOAT_RANGE SeekBar, INT_RANGE EditText, ENUM spinner,
  `mutuallyExclusiveWith` logic).

Each field writes **directly** into a pref via `DictatePrefsKt.put`. The rework (§10)
replaces these direct write paths with entity CRUD, but keeps `ModelFetcher`,
`ParameterRegistry` and `ParameterDef` rendering as building blocks.

### 3.3 Runner config resolution (current)

Source: `app/.../ai/factory/RunnerFactory.kt` + `ai/AIOrchestrator.kt`. The
`RunnerFactory` today reads **directly** from `SharedPreferences`:

- `getProvider(function)` ← `Pref.TranscriptionProvider` / `Pref.RewordingProvider`
  (RunnerFactory.kt:50-56)
- `getModelName(function)` ← per-provider model pref (RunnerFactory.kt:58-81)
- `getApiKey(provider, function)` ← per-(provider,function) key pref (RunnerFactory.kt:83-103)
- `getBaseUrl(provider, function)` ← `CustomHost` pref for CUSTOM, otherwise
  `provider.defaultBaseUrl` (RunnerFactory.kt:105-113)
- Parameters: `AIOrchestrator.resolveParameters` ← `ParameterRegistry` ∩ `PARAMETER_PREFS`
  (AIOrchestrator.kt:168-205), sentinel `-1`/`""` = server default

**Exactly this resolution path** is the target of the `AiConfig` port (Block A) and is in
§9 reproduced byte-identically by the `ProfileResolver`.

### 3.4 PromptEntity / schema v11 / PromptImportExport

- Room version **11** (`DictateDatabase.kt:51`), schema asset `app/schemas/…/11.json`.
- `prompts` table (11.json): `id INTEGER PK AUTOINCREMENT, pos, name, prompt,
  requires_selection, auto_apply, type TEXT NOT NULL` with
  `CHECK (type IN ('PROMPT','TEXT'))` (ADR-0024, `MIGRATION_10_11`).
- `PromptEntity.kt` — Double-Enum `type`/`typeEnum` (PromptType PROMPT|TEXT).
- **PromptImportExport.java** — `EXPORT_VERSION = 2`. Export: `{version:2, prompts:[
  {name, prompt, requiresSelection, autoApply, type}]}`. Import accepts v2
  (type verbatim, normalized) and v1 (no `type` → `PromptTypeClassifier.classify`).
  TEXT pills are clamped to `requiresSelection=false, autoApply=false` on import.

> [!IMPORTANT]
> ADR-0024 remains **untouched** in Block C. `prompts.type`, `PromptTypeClassifier`,
> the v1/v2 import rules and the pill press behavior do not change. The v3 codec
> is additive; v1/v2 import continues via the existing Android path (§10.4).

## 4. Architecture Specification — Entities (`:shared`)

All types live in
`shared/src/main/kotlin/net/devemperor/dictate/shared/config/`, are
`@Serializable` (kotlinx-serialization, already an `api` dependency of `:shared`,
build.gradle:31) and carry a co-located `Validation<T>` in
`ConfigValidations.kt` (Konform 0.11.1, ADR-0016 pattern).

### 4.1 Envelope + common value types

```kotlin
package net.devemperor.dictate.shared.config

import kotlinx.serialization.Serializable

/** Provenienz einer bezogenen Kopie (F14). null bei lokal erzeugten Entitäten. */
@Serializable
data class SourceRef(
    val peerId: String,
    val originalId: String,
    /** contentHash des Originals zum Zeitpunkt der Übernahme — Basis des „Update"-Vergleichs. */
    val originalContentHash: String,
)

@Serializable
enum class Visibility { PRIVATE, SHARED }

/** Bezugs-Zustand. In Block C immer LOCAL; SUBSCRIBE/ONE_SHOT-Semantik ist Block E. */
@Serializable
enum class SubscriptionMode { LOCAL, SUBSCRIBE, ONE_SHOT }
```

The envelope fields are **not** modeled as a separate wrapper object, but
as identically named fields of every entity (`id`, `contentHash`, `updatedAt`,
`visibility`, `sourceRef`, `subscriptionMode`). Reason: the canonical serialization
excludes them via a **fixed field name list** (§5.2) — a wrapper would nest the
payload and complicate the canonicalization.

### 4.2 Payload-vs-Envelope convention

Every ConfigEntity internally separates **Payload** (hash-relevant) from **Envelope**
(metadata). Implemented via a common constant:

```kotlin
/** Feldnamen, die aus der kanonischen (hash-relevanten) Form ausgeschlossen werden. */
val ENVELOPE_FIELDS: Set<String> =
    setOf("id", "contentHash", "updatedAt", "visibility", "sourceRef", "subscriptionMode")
```

`CanonicalJson` (§5.1) removes these keys recursively at the topmost object level of the
entity before hashing. Thus the `contentHash` stays stable regardless of whether a copy has
a different `id`, `visibility` or `sourceRef` — exactly the property that Block E
needs for fork dedup.

### 4.3 ProviderConfig

```kotlin
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
    /** LOCAL = direkte Vendor-API; GATEWAY reserviert (F31), in v1 nicht wählbar. */
    val kind: ProviderKind = ProviderKind.LOCAL,
    val label: String,
    /** null → providerType.defaultBaseUrl; nur für CUSTOM/GATEWAY inhaltlich relevant. */
    val baseUrl: String? = null,
    /** uuid einer ApiCredentialEntity, oder null (z. B. lokaler Custom-Endpoint ohne Key). */
    val credentialRef: String? = null,
)
```

### 4.4 ApiCredential

```kotlin
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
     * Abdruck des Schlüssels: sha256(key)-Hex, erste 16 Zeichen. Damit ändert ein
     * Key-Wechsel den contentHash, OHNE dass der Key im Payload/Index steht (F12).
     * Der Klartext-Key liegt AUSSCHLIESSLICH im SecretStore unter SecretRef(id).
     */
    val keyFingerprint: String,
)
```

> [!CAUTION]
> There is **no** field for the key value. The plaintext lives only in the SecretStore
> (Block B), addressed via `SecretRef` = the `id` of this entity. A reviewer who
> finds an `apiKey`/`secret` field here has found an F12 violation.

### 4.5 ModelRef

```kotlin
@Serializable
data class ModelRefEntity(
    // ── Envelope ── (id, contentHash, updatedAt, visibility, sourceRef, subscriptionMode)
    val id: String,
    val contentHash: String = "",
    val updatedAt: Long = 0,
    val visibility: Visibility = Visibility.PRIVATE,
    val sourceRef: SourceRef? = null,
    val subscriptionMode: SubscriptionMode = SubscriptionMode.LOCAL,
    // ── Payload ──
    val providerRef: String,           // uuid einer ProviderConfigEntity
    val modelId: String,               // z. B. "gpt-4o-mini"
    val function: ModelFunction,       // TRANSCRIPTION | COMPLETION
    val label: String? = null,
    /**
     * Parameter-Defaults als kanonische String-Werte (sortiert), z. B.
     * {"temperature":"0.7","max_tokens":"4096"}. String statt Zahl bewusst — vermeidet
     * IEEE-754-Kanonik (§5.1). Interpretation über ParameterRegistry (Block A).
     * Transcription-spezifisch: {"keyterms":"<parsed-json>"} für ElevenLabs.
     */
    val parameterDefaults: Map<String, String> = emptyMap(),
)
```

### 4.6 Prompt

```kotlin
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
```

> [!NOTE]
> **No pill `type`.** The shared prompt entity models only the shareable
> post-processing prompt. The Android `prompts.type` (PROMPT/TEXT, ADR-0024) remains
> a Room-only column; TEXT pills are literal snippets, not a shareable AI prompt,
> and are not exported as a `PromptV3Entity` (§13 D3). On v3 import a
> Android `prompts` row with `type = PROMPT` is created.

### 4.7 Profile

```kotlin
@Serializable
data class ProfilePromptRef(
    val promptRef: String,   // uuid → PromptV3Entity (bzw. Android prompts.uuid, §7.3)
    val autoApply: Boolean = false,
)

@Serializable
enum class PromptSelectionMode { NONE, PREDEFINED, CUSTOM }  // Spiegel von PromptMode (0/1/2)

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
    /** Geordnete Nachbearbeitungs-Prompts (Reihenfolge signifikant für den Hash). */
    val orderedPrompts: List<ProfilePromptRef> = emptyList(),
    val stylePromptMode: PromptSelectionMode = PromptSelectionMode.PREDEFINED,
    val stylePromptCustomText: String = "",
    val systemPromptMode: PromptSelectionMode = PromptSelectionMode.PREDEFINED,
    val systemPromptCustomText: String = "",
    val ambiguityMode: AmbiguityModeValue = AmbiguityModeValue.ALWAYS_INSERT,
    /** Completion-Parameter-Overrides über die ModelRef-Defaults hinaus. */
    val parameterOverrides: Map<String, String> = emptyMap(),
)
```

> [!NOTE]
> `is_active` is **not** a profile field (it is not shareable content and would pollute the
> hash). The active profile is a global pointer `Pref.ActiveProfileId`
> (§3.1, §9.2).

### 4.8 Mirror enums + parity requirement

Because of the layering constraint (§1a.0) `:shared` defines its own enums; the parity
to the `:shared-ai`/`:app` originals is enforced via test:

```kotlin
@Serializable enum class ProviderType { OPENAI, GROQ, ANTHROPIC, ELEVENLABS, OPENROUTER, CUSTOM }
@Serializable enum class ProviderKind { LOCAL, GATEWAY }   // GATEWAY reserviert (F31)
@Serializable enum class ModelFunction { TRANSCRIPTION, COMPLETION }
@Serializable enum class AmbiguityModeValue { ALWAYS_INSERT, AUTO, ALWAYS_REVIEW }
```

Parity tests (in the module that sees both sides — `:shared-ai` or `:app`):
- `ProviderType.entries.map{it.name}` == `AIProvider.entries.map{it.name}`
  (AIProvider has exactly these 6 values, AIProvider.kt).
- `AmbiguityModeValue.entries.map{it.name}` == `AmbiguityMode.entries.map{it.persistKey}`.
- `ModelFunction.entries.map{it.name}` == `AIFunction.entries.map{it.name}`.
- `PromptSelectionMode.entries` corresponds with `PromptMode.value` 0/1/2.

Mappers (in `:shared-ai`/`:app`, not in `:shared`): `AIProvider.toProviderType()` /
`ProviderType.toAIProvider()` etc.

> [!IMPORTANT]
> **DECIDED (plan §3 D5.a, 2026-07-20):** The mirror approach of this
> section is binding — `AIProvider`/`AmbiguityMode`/`AIFunction`
> stay in `:shared-ai` (Block A deliberately introduces no
> `:shared-ai`→`:shared` edge), `:shared` defines the wire enums
> itself. This matches the existing wire-vs-domain doctrine
> (ADR-0016: `SessionOriginWire` ↔ `SessionOrigin` + mapper +
> parity tests). The alternative noted here earlier (moving the originals into
> `:shared`) is discarded; parity tests + mappers live in
> `:app` and are a mandatory gate. Details: §13 D6, plan §3 D5.a.

## 5. Canonical Serialization + contentHash + v3 Format

### 5.1 CanonicalJson — the deterministic byte form

The canonical form follows a **subset of RFC 8785 (JSON Canonicalization
Scheme)** — enough for byte stability, without needing the full number canonicalization
(because all non-integer values are modeled as strings, §4.5):

1. **Serialize** the entity payload with kotlinx to a `JsonElement`.
2. **Remove** recursively at the top object level the `ENVELOPE_FIELDS` (§4.2).
3. **Canonicalize** the `JsonElement` tree:
   - `JsonObject`: members **sorted** by key, sorting = comparison of the
     UTF-16 code-unit sequences of the key strings (Kotlin `String.compareTo`, equivalent
     to JCS).
   - `JsonArray`: order **preserved** (significant — e.g. `orderedPrompts`).
   - `JsonPrimitive` String: JSON minimal escaping (RFC 8259 §7; only the
     mandatory escapes `" \ \b \f \n \r \t` and `\u00XX` for control characters < 0x20;
     no unnecessary `\u` escapes).
   - `JsonPrimitive` number: only **integers** occur → decimal without leading plus sign,
     without leading zeros, without exponent. (Floating-point numbers do not exist in the model;
     temperature and the like are strings in `parameterDefaults`/`parameterOverrides`.)
   - `Boolean`/`null`: `true`/`false`/`null` (the latter only if a nullable
     payload field is set — see 4.).
4. **Null handling:** `explicitNulls = false` — a `null` payload field is an
   **absent** key, not `"field":null`. Defaults are materialized with `encodeDefaults = true`
   so that the hash does not depend on whether a value was set explicitly or via
   default.
5. **Emit** compactly (no whitespace), **UTF-8** bytes.

```kotlin
object CanonicalJson {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun <T> canonicalBytes(value: T, serializer: KSerializer<T>): ByteArray {
        val tree = json.encodeToJsonElement(serializer, value)
        val stripped = stripEnvelope(tree)           // entfernt ENVELOPE_FIELDS top-level
        return canonicalize(stripped).toByteArray(Charsets.UTF_8)
    }
    // canonicalize: rekursiv, Objekt-Keys sortiert, Arrays in-order, Minimal-Escaping
}
```

> [!WARNING]
> `encodeDefaults` and `explicitNulls` must be set **exactly** like this and must never differ
> between `:app` (Android) and `:companion` (desktop) — otherwise the hashes
> drift and the peer sync (Block E) breaks. Therefore `CanonicalJson` lives
> as the **single** instance in `:shared`, not as a per-platform copy.

### 5.2 contentHash

```kotlin
fun <T> contentHash(value: T, serializer: KSerializer<T>): String {
    val bytes = CanonicalJson.canonicalBytes(value, serializer)
    return MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }   // lowercase Hex, 64 Zeichen
}
```

Since the `ENVELOPE_FIELDS` are removed, it automatically holds: two entities with the same
payload but different `id`/`visibility`/`sourceRef` have the **same**
`contentHash`. That is the property desired by F27/Block E (fork dedup,
drift detection).

### 5.3 Recompute-on-write invariant

`contentHash` and `updatedAt` are **denormalized cache columns** (docs/DATABASE-PATTERNS.md
"Denormalized Cache Columns"). Rule:

- On **every** write path (create, edit, import, migration) `contentHash` is recomputed from
  the current payload, never adopted from a file/a peer.
- On **import** (v3 file or peer): recompute the supplied `contentHash`
  and compare with the file value; on divergence → warning/rejection (integrity
  check, Block E tightens this for peers).
- `updatedAt = System.currentTimeMillis()` on every content change.

The write choke point on Android is a `ConfigRepository` (§7.4) that, before every
DAO `upsert`, sets `contentHash`/`updatedAt` (§5.3) — analogous to `SessionManager` in the
denormalized-cache rules.

### 5.4 v3 format + CatalogCodec

```kotlin
@Serializable
data class CatalogFileV3(
    val version: Int = 3,
    val entities: List<CatalogEntry>,
)

/** Getaggte Union über den kind-Diskriminator; die eine Codec-Tür (ADR-0016). */
@Serializable
sealed interface CatalogEntry {
    @Serializable @SerialName("provider")   data class Provider(val entity: ProviderConfigEntity) : CatalogEntry
    @Serializable @SerialName("credential") data class Credential(val entity: ApiCredentialEntity) : CatalogEntry
    @Serializable @SerialName("model")      data class Model(val entity: ModelRefEntity) : CatalogEntry
    @Serializable @SerialName("prompt")     data class Prompt(val entity: PromptV3Entity) : CatalogEntry
    @Serializable @SerialName("profile")    data class Profile(val entity: ProfileEntity) : CatalogEntry
}

object CatalogCodec {
    /** Validiert + serialisiert; wirft ProtocolViolationException bei Verstoß (ADR-0016). */
    fun encode(file: CatalogFileV3): String
    /** Deserialisiert + validiert; DecodeResult<CatalogFileV3> (Ok/Malformed/Invalid). */
    fun decode(raw: String): DecodeResult<CatalogFileV3>
}
```

- The **v3 file export** (SAF, §10.5) and the **peer wire** (Block E) use
  **the same** `CatalogCodec` — that is the "single codec implementation" from F23.
- The export file body is produced via `CanonicalJson` (byte-reproducible); the
  per-entity `contentHash` fields carry the hash computed over the **payload**
  (§5.2), so that a recipient can independently recompute them.
- `ApiCredentialEntity` in the catalog carries only metadata (`keyFingerprint`), **never** the
  key value (F12). The secret delivery is a separate, authorized call in
  Block E — not part of the v3 file.

> [!IMPORTANT]
> **v1/v2 prompt files do NOT go through `CatalogCodec`.** They are legacy prompt
> exports (ADR-0024) and are routed by the Android import dispatcher (§10.4) to the
> existing `PromptImportExport` path. `CatalogCodec` is exclusively
> v3. That keeps the pill classification (ADR-0024) exactly preserved and the v3 codec
> clean (one format).

## 6. Directory Layout

```
shared/src/main/kotlin/net/devemperor/dictate/shared/config/          [NEW]  (C1)
├── Entities.kt                       [NEW]  die fünf @Serializable-DTOs + Envelope-Typen
├── ConfigEnums.kt                    [NEW]  ProviderType, ProviderKind, ModelFunction,
│                                            AmbiguityModeValue, PromptSelectionMode, Visibility, SubscriptionMode
├── ConfigValidations.kt             [NEW]  Konform Validation<T> je DTO (ADR-0016)
├── CanonicalJson.kt                  [NEW]  kanonische Byte-Form + ENVELOPE_FIELDS
├── ContentHash.kt                    [NEW]  sha256-Hex über CanonicalJson
└── CatalogCodec.kt                   [NEW]  v3-Format (CatalogFileV3, CatalogEntry, encode/decode)

shared/src/test/kotlin/net/devemperor/dictate/shared/config/          [NEW]
├── CanonicalJsonTest.kt              [NEW]  Byte-Snapshots, Key-Sort, Envelope-Ausschluss
├── ContentHashTest.kt                [NEW]  Determinismus-Matrix (§12)
├── ConfigValidationsTest.kt          [NEW]  je DTO ≥1 Verstoß + GATEWAY-Ablehnung
└── CatalogCodecTest.kt               [NEW]  v3-Round-Trip, Malformed/Invalid

app/src/main/java/net/devemperor/dictate/config/                      [NEW]  (C2)
├── entity/                           [NEW]  Room-Entities: ProviderConfigRoom, ApiCredentialRoom,
│                                            ModelRefRoom, ProfileRoom, ProfilePromptRoom + Enum-Klassen
├── dao/                              [NEW]  ProviderConfigDao, ApiCredentialDao, ModelRefDao,
│                                            ProfileDao, ProfilePromptDao
├── ConfigEntityMapper.kt            [NEW]  Room-Row ⇄ :shared-DTO (dünne Mapper, Vorbild SessionEntityMapper)
├── ConfigRepository.kt              [NEW]  Schreib-Choke-Point: setzt contentHash/updatedAt (§5.3)
├── ConfigEntityMigration.kt         [NEW]  Prefs→Entitäten, Backup, Idempotenz (§8)
├── PrefsBackup.kt                    [NEW]  vollständiger Prefs→JSON-Dump (§8.4)
└── ProfileResolver.kt               [NEW]  implementiert AiConfig-Port (Block A) (§9)

app/src/main/java/net/devemperor/dictate/database/migration/
└── MigrationTo12.kt                  [NEW]  v11→v12 (§7.2)

app/src/main/java/net/devemperor/dictate/database/
├── DictateDatabase.kt               [EDIT] version=12, +5 Entities, +MIGRATION_11_12
app/schemas/net.devemperor.dictate.database.DictateDatabase/
└── 12.json                          [NEW]  exportiertes Schema v12 (KSP-generiert)

app/src/main/java/net/devemperor/dictate/preferences/
├── DictatePrefs.kt                  [EDIT] +ActiveProfileId, +ConfigEntityMigrationDone
app/src/main/java/net/devemperor/dictate/settings/
├── APISettingsActivity.java         [EDIT/REPLACE] Umbau auf Entitätenmodell (§10)
├── ProvidersActivity.*              [NEW]  Provider-Verwaltung (§10.1)
├── ProfilesActivity.*               [NEW]  Profil-Liste + Editor (§10.3)
app/src/main/java/net/devemperor/dictate/rewording/
├── PromptsOverviewActivity.java     [EDIT] Herkunfts-Badge (§10.6)
├── PromptImportExport.java          [KEEP] v1/v2-Legacy-Import unverändert (§10.4)
```

**File delta:** ~6 new `:shared` files + 4 tests · ~10 new `:app/config` files ·
1 new migration + schema asset · 1 pref edit · 3-4 new/reworked settings screens.

## 7. Room Persistence v11→v12 (`:app`)

### 7.1 Principle

The `:shared` DTOs are **not** Room entities (Room cannot annotate `:shared` classes,
and the envelope/payload split does not fit onto `@Entity`). Instead, per entity a
**dedicated Room class** in `config/entity/` + a thin **mapper**
(`ConfigEntityMapper`) ⇄ `:shared` DTO — the same pattern as `SessionEntityMapper`
(plan-D3).

### 7.2 New tables + Double-Enum CHECKs

`MigrationTo12.kt`, pattern like `MigrationTo11.kt` (table-create instead of alter for CHECKs).
All finite-set columns carry `CHECK` (docs/DATABASE-PATTERNS.md):

```sql
CREATE TABLE provider_configs (
    id TEXT NOT NULL PRIMARY KEY,
    provider_type TEXT NOT NULL
        CHECK (provider_type IN ('OPENAI','GROQ','ANTHROPIC','ELEVENLABS','OPENROUTER','CUSTOM')),
    kind TEXT NOT NULL DEFAULT 'LOCAL' CHECK (kind IN ('LOCAL','GATEWAY')),
    label TEXT NOT NULL,
    base_url TEXT,
    credential_ref TEXT,
    -- Provenienz + Envelope
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL'
        CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE api_credentials (
    id TEXT NOT NULL PRIMARY KEY,
    provider_type TEXT NOT NULL CHECK (provider_type IN ('OPENAI','GROQ','ANTHROPIC','ELEVENLABS','OPENROUTER','CUSTOM')),
    label TEXT NOT NULL,
    key_fingerprint TEXT NOT NULL,
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL' CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE model_refs (
    id TEXT NOT NULL PRIMARY KEY,
    provider_ref TEXT NOT NULL,
    model_id TEXT NOT NULL,
    function TEXT NOT NULL CHECK (function IN ('TRANSCRIPTION','COMPLETION')),
    label TEXT,
    parameter_defaults TEXT NOT NULL DEFAULT '{}',   -- kanonisches JSON (Map<String,String>)
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL' CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX index_model_refs_provider_ref ON model_refs(provider_ref);

CREATE TABLE profiles (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    transcription_model_ref TEXT,
    completion_model_ref TEXT,
    style_prompt_mode TEXT NOT NULL DEFAULT 'PREDEFINED' CHECK (style_prompt_mode IN ('NONE','PREDEFINED','CUSTOM')),
    style_prompt_custom_text TEXT NOT NULL DEFAULT '',
    system_prompt_mode TEXT NOT NULL DEFAULT 'PREDEFINED' CHECK (system_prompt_mode IN ('NONE','PREDEFINED','CUSTOM')),
    system_prompt_custom_text TEXT NOT NULL DEFAULT '',
    ambiguity_mode TEXT NOT NULL DEFAULT 'ALWAYS_INSERT'
        CHECK (ambiguity_mode IN ('ALWAYS_INSERT','AUTO','ALWAYS_REVIEW')),
    parameter_overrides TEXT NOT NULL DEFAULT '{}',
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL' CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE profile_prompts (
    profile_id TEXT NOT NULL,
    pos INTEGER NOT NULL,
    prompt_ref TEXT NOT NULL,
    auto_apply INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (profile_id, pos),
    FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
);
CREATE INDEX index_profile_prompts_prompt_ref ON profile_prompts(prompt_ref);
```

### 7.3 Extend the `prompts` table

The existing `prompts` table (v11) is **kept** (ADR-0024, pill `type` stays)
and augmented with provenance/envelope columns — table-recreate (SQLite has no
`ADD CHECK`), copy data:

```sql
CREATE TABLE prompts_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    uuid TEXT NOT NULL DEFAULT '',           -- stabile Identität für Profil-Referenzen + v3
    pos INTEGER NOT NULL, name TEXT, prompt TEXT,
    requires_selection INTEGER NOT NULL, auto_apply INTEGER NOT NULL,
    type TEXT NOT NULL DEFAULT 'PROMPT' CHECK (type IN ('PROMPT','TEXT')),   -- ADR-0024, unverändert
    visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','SHARED')),
    subscription_mode TEXT NOT NULL DEFAULT 'LOCAL' CHECK (subscription_mode IN ('LOCAL','SUBSCRIBE','ONE_SHOT')),
    source_peer_id TEXT, source_original_id TEXT, source_original_hash TEXT,
    content_hash TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL DEFAULT 0
);
INSERT INTO prompts_new (id, pos, name, prompt, requires_selection, auto_apply, type)
    SELECT id, pos, name, prompt, requires_selection, auto_apply, type FROM prompts;
DROP TABLE prompts; ALTER TABLE prompts_new RENAME TO prompts;
-- danach: uuid + content_hash für alle Zeilen backfillen (§8, im Migrations-Code, nicht SQL)
```

> [!NOTE]
> `prompts.id` remains the Room autoincrement PK (`PromptEntity.id: Int`,
> backward-compatible with all existing references). The new `uuid` column is the
> **shareable** identity that profiles (`profile_prompts.prompt_ref`) and v3 export
> use. The backfill (§8.5) assigns each row a UUID + `content_hash`.

### 7.4 DAOs + ConfigRepository

- DAOs take `String` for all enum columns (Double-Enum rule), convenience accessor
  `xxxEnum` with a `getOrDefault` fallback on each Room entity (docs/DATABASE-PATTERNS.md).
- `ConfigRepository` is the only write path: it maps DTO→Room, sets
  `content_hash = ContentHash.of(payload)` + `updated_at = now` (§5.3), and writes
  transactionally (profile + `profile_prompts` together).

### 7.5 DictateDatabase registration

`DictateDatabase.kt`: `version = 12`; `entities` array extended by the 5 Room entities;
`.addMigrations(..., MIGRATION_10_11, MIGRATION_11_12)`; `exportSchema` stays `true`
→ `12.json` is generated and committed (the migration test needs the asset).

## 8. Prefs→Entities Migration

### 8.1 Location, trigger, idempotency

- Class `ConfigEntityMigration` (`app/config/`), needs `SharedPreferences` +
  `DictateDatabase` + `SecretStore` (Block B) → cannot live in the SP-only
  `PrefsMigration.kt`. It runs **after** the DB build at app/IME start (where
  `LegacyAudioFileMigration` runs today), **after** `PrefsMigration.migrateProviderPrefs`
  (the int→string provider migration must run first).
- **Idempotency:** `Pref.ConfigEntityMigrationDone` (Int, default 0). Runs only if
  `< CURRENT_MIGRATION_VERSION` (=1). Pattern like `LegacyAudioPurgedV4`.
- Order relative to Block B2: This migration creates `api_credentials` rows and
  stores the keys via `SecretStore.put` — i.e. it **is** the place where the
  plaintext keys move from the prefs into the SecretStore (aligns with B2). The
  `depends_on: C2→{C1,B2,A3}` ensures the SecretStore port exists.

### 8.2 Mapping — provider + credential + model

For the migration the `RunnerFactory` logic (§3.3) is read backwards. Per **function**
(transcription, completion) determine the active provider and create the entities:

| Step | Source (pref) | Target entity |
|---|---|---|
| 1. For each provider with a **non-empty** key: ApiCredential | `*ApiKey*` | `ApiCredentialEntity{providerType, label="<Provider> Key", keyFingerprint=sha256(key)[..16]}`; key → `SecretStore.put(SecretRef(id), keyBytes)` |
| 2. For each provider with a key or active selection: ProviderConfig | Provider + `*CustomHost` | `ProviderConfigEntity{providerType, kind=LOCAL, label, baseUrl=CustomHost?|null, credentialRef=<cred.id>|null}` |
| 3. Transcription model | `TranscriptionProvider` + `Transcription<Prov>Model` (+ `ElevenLabsKeytermsParsed`) | `ModelRefEntity{providerRef, modelId, function=TRANSCRIPTION, parameterDefaults={keyterms:…}?}` |
| 4. Completion model | `RewordingProvider` + `Rewording<Prov>Model` | `ModelRefEntity{providerRef, modelId, function=COMPLETION, parameterDefaults=<§8.3>}` |

`label` generation deterministic from `providerType.displayName`. Custom provider:
`baseUrl` from `TranscriptionCustomHost`/`RewordingCustomHost` (separate — if both
are Custom with a different host, **two** ProviderConfigs are created; documented in
the migration code).

### 8.3 Mapping — parameters

The parameter prefs (`PARAMETER_PREFS`, AIOrchestrator.kt:199-205) are adopted with the
sentinel rule (`< 0` / empty = omit server default) into `parameterDefaults` of the
**completion ModelRef** (not into the profile — they are provider/model-near):

- `temperature` (Float ≥ 0) → `"temperature":"<toCanonicalDecimal(v)>"`
- `max_tokens`/`max_completion_tokens` (Int > 0) → `"max_tokens":"<v>"`
  (key name per provider according to `ParameterRegistry`, Anthropic `max_tokens` otherwise
  `max_completion_tokens`)
- `reasoning_effort` (non-empty) → `"reasoning_effort":"<v>"`

`toCanonicalDecimal(Float)`: shortest lossless decimal representation, `.` as
separator, no exponent (e.g. `0.7`, `1`, `1.5`). Only this string form goes into the
hash (§5.1) — hence fixed here.

### 8.4 Prefs backup (rollback path, F22/D4.7)

**Before** every entity creation: a complete dump of all SharedPreferences to
`context.filesDir/backups/prefs-backup-v11-<epochMillis>.json`. Format: flat
JSON object `{ "<key>": <value> }` over `sp.all` (types: Boolean/Int/Long/Float/String/
Set<String>). The dump is the documented rollback: no auto-restore, but a
complete, readable image. `PrefsBackup.write(sp, dir)` is idempotent (does not overwrite,
writes exactly once per migration run).

> [!CAUTION]
> The backup dump contains the **plaintext keys** (it mirrors the prefs 1:1). It lives in
> app-private storage (`filesDir`, not external), is **never** shared/exported, and
> should be allowed to be deleted after successful migration + verification (optional
> cleanup step, §14 Gap 4). It is deliberately not `SecretStore` content — it is the
> pre-migration snapshot.

### 8.5 Default profile + prompt backfill

1. **Prompt backfill:** every existing `prompts` row gets a `uuid` (v4) +
   `content_hash` (over the `PromptV3Entity` projection; TEXT pills are **ignored**
   as a `PromptV3` payload with `type` here — the hash covers only name/text/flags).
   `visibility=PRIVATE`, `subscription_mode=LOCAL`.
2. **Create default profile:** `ProfileEntity{name="Default", transcriptionModelRef=
   <§8.2 step3>, completionModelRef=<step4>, orderedPrompts=[all prompts by
   pos, autoApply=<row.auto_apply>], stylePromptMode=fromValue(StylePromptSelection),
   stylePromptCustomText, systemPromptMode=fromValue(SystemPromptSelection),
   systemPromptCustomText, ambiguityMode=fromPersistKey(AmbiguityMode),
   parameterOverrides={}}`. `content_hash`/`updated_at` via `ConfigRepository`.
3. **Activation:** `Pref.ActiveProfileId = <default.id>`.
4. **Key cleanup:** remove the migrated `*ApiKey*` prefs from SharedPreferences
   (jointly with B2; Acceptance §2.6). The remaining migrated prefs (provider/model/
   param/prompt-selection/ambiguity) are **not** deleted immediately (just no longer
   read) — they are secured in the backup anyway, and a later `removeObsoletePrefs`
   (PrefsMigration pattern) can clear them. **Exception keys**: those must go (§2.6).
5. **Set flag:** `Pref.ConfigEntityMigrationDone = 1`.

All steps in **one** DB transaction (except SecretStore `put`, which is idempotent per
SecretRef). On a crash mid-way: the second run sees `Done=0`, the backup
already exists (idempotent), and entity creation is idempotent via
deterministic keys (§8.6).

### 8.6 Idempotency details

- Entity `id`s are derived **deterministically** during the migration from a namespace +
  source characteristic (`UUID.nameUUIDFromBytes("providerconfig:openai".toByteArray())`
  etc.), not randomly — so a second (partial) run produces the same ids instead of
  duplicates. (Prompts keep their already-assigned `uuid`.)
- `SecretStore.put` with the same SecretRef is an overwrite, not an error.

## 9. ProfileResolver (AiConfig port)

### 9.1 Port requirement (abstract, Block A owns the signature)

Block A defines the `AiConfig` port (§ plan A3). The **requirement** from Block C's
point of view: "delivers the effective configuration from the active profile + credentials" — per
`AIFunction` (TRANSCRIPTION/COMPLETION):

- `provider(function): ProviderType/AIProvider`
- `modelName(function): String`
- `apiKey(function): String` (or `ByteArray?` from SecretStore)
- `baseUrl(function): String`
- `parameters(function, modelId): Map<String, Any>` (registry-interpreted)
- plus profile aspects that are prefs today: `stylePrompt` selection, `systemPrompt`
  selection, `ambiguityMode`.

> [!NOTE]
> The **exact** method signature, package and return types of `AiConfig` belong to Block A
> (`research/shared-ai-extraktion.md`, deliberately not read here). This spec
> describes only what the `ProfileResolver` must deliver; the adapter signature is
> aligned at C2 implementation time against the then-existing port (§14 Gap 1).

### 9.2 Resolution

`ProfileResolver(sp, db, secretStore)`:

1. `activeId = sp.get(Pref.ActiveProfileId)`; `profile = profileDao.byId(activeId)`.
2. For `function`: `modelRef = modelRefDao.byId(function==TRANSCRIPTION ?
   profile.transcriptionModelRef : profile.completionModelRef)`.
3. `provider = providerConfigDao.byId(modelRef.providerRef)`.
4. `modelName = modelRef.modelId`.
5. `baseUrl = provider.baseUrl ?: provider.providerType.toAIProvider().defaultBaseUrl`.
6. `apiKey = provider.credentialRef?.let { secretStore.get(SecretRef(it)) }?.decodeToString() ?: ""`.
7. `parameters = modelRef.parameterDefaults ⊕ profile.parameterOverrides` (profile
   wins), then interpreted by `ParameterRegistry` (type/range as today).
8. `ambiguityMode = profile.ambiguityMode.toAmbiguityMode()`; style/system prompt from
   `profile.*PromptMode/*CustomText` + `PromptTemplates` (Block A).

### 9.3 Fallback semantics

- **No active profile** (`ActiveProfileId` empty or profile missing): the resolver delivers
  an **empty** configuration — `provider = OPENAI` (today's default), `modelName`/
  `apiKey = ""`, `baseUrl = default`. This reproduces exactly today's
  "not configured" state (empty key ⇒ existing "API key missing" UX kicks in,
  APISettingsActivity/pipeline unchanged).
- **Profile without ModelRef** (e.g. completion never configured): `modelName/apiKey = ""`
  for this function; transcription can still work. Byte-identical to
  today's "provider chosen, but no model/key".
- **Credential missing in SecretStore** (reference present, value gone — e.g. Keystore loss,
  Block B): `apiKey = ""` → the same "key missing" UX. No crash.

### 9.4 Characterization test (behavioral neutrality)

Core acceptance of C2 (§2.5): For a matrix of pref constellations (each provider
as transcription/completion, with/without custom host, with/without parameters):

1. Set prefs fixture.
2. Run `ConfigEntityMigration`.
3. Query the old `RunnerFactory(sp)` (pref-based) **and** the new `RunnerFactory(profileResolver)`
   the same `getProvider/getModelName/getApiKey/getBaseUrl` + `resolveParameters`.
4. Assert: **identical** values (provider, model, key, baseUrl, parameter map).

This test is the behavioral-neutrality proof (test-first: first fix the expectation against
the unmigrated code, then build migration + resolver).

## 10. Settings UI Rework (C3)

Target layout: **provider → models → profiles**, instead of the two parallel
provider sections. Building blocks `ModelFetcher`, `ParameterRegistry`, `ParameterDef` rendering
stay; the **write paths** switch from pref→entity (via `ConfigRepository`).

### 10.1 Provider management

List of all `ProviderConfigEntity` (label, providerType, "key set" badge,
origin badge local/peer). Editor: providerType selection, `label`, for CUSTOM `baseUrl`,
attach credential (new key → `SecretStore.put` + `ApiCredentialEntity`; or
reference an existing credential). `kind=GATEWAY` is **not selectable** in the UI (F31).

### 10.2 Model management (two-stage, data-driven)

Per provider: model selection from the **union** of (a) `ModelFetcher.fetchModels`/
`getHardcodedModels` (live), (b) existing `ModelRefEntity` (subscribed/local),
(c) free text (Anthropic/Custom — §14 Gap 2, Anthropic has no `/models` endpoint).
The selection creates/updates a `ModelRefEntity`. Parameter UI as today
(`updateParameterUI`) — values write into `ModelRefEntity.parameterDefaults` or
`ProfileEntity.parameterOverrides` (editor context decides).

### 10.3 Profile management (UX role model PromptsOverview)

Profile list with **duplicate** and **reorder** analogous to the freshly reworked
`PromptsOverviewActivity` + `PromptListMutations.kt` (`copyOf`/`resequenced` pattern):

- List: name, active profile marked, origin badge.
- Actions: new, duplicate (`ProfileListMutations.copyOf` analogous to `PromptListMutations`),
  reorder, set active (`Pref.ActiveProfileId`), delete.
- Editor: choose transcription ModelRef, choose completion ModelRef, order prompts
  (`orderedPrompts` with autoApply toggle), style/system prompt selection, `AmbiguityMode`,
  parameter overrides.

**No** profile switcher in the keyboard UI (D4.4) — profile selection exclusively here.

### 10.4 Import dispatcher (v1/v2/v3)

SAF import reads the file, dispatches by version detection:

- **v3** (`{"version":3,...}`) → `CatalogCodec.decode` → entities (with
  `contentHash` recompute check, §5.3) → `ConfigRepository` upsert.
- **v2/v1** (prompt file) → existing `PromptImportExport.parse` → Android
  `prompts` rows **with** pill `type` (ADR-0024 unchanged) → afterwards
  `uuid`/`content_hash` backfill (§8.5 logic as a helper).

### 10.5 v3 export (SAF)

Export collects the entities to be shared (selection in the UI: single profile including
referenced ModelRefs/ProviderConfigs/Prompts, or whole categories),
serializes via `CatalogCodec.encode(CatalogFileV3(...))`. **Credentials**: only
metadata (`keyFingerprint`), never the key value (F12) — for a local file export
this means an exported `ApiCredentialEntity` is, without accompanying
secret delivery (Block E), only an empty shell; in the v1 file export it is advisable
to **omit** credentials (file export shares prompts/profiles/models, not keys).
Decision documented as §13 D5.

### 10.6 PromptsOverview + origin badge

`PromptsOverviewActivity` gets an origin badge (local/peer) from the new
`prompts` provenance columns. Otherwise unchanged (pill behavior, ADR-0024). A read-only
explorer for peer prompts is Block E3.

## 11. Migration Plan (Chunk Split)

This spec covers the plan chunks **C1, C2, C3**. Each chunk compiles and tests
in isolation.

1. **C1 — `:shared/config` (scaffold chunk).** Enums, DTOs, validations, CanonicalJson,
   ContentHash, CatalogCodec + tests (§4, §5). No Android reference. Compile state:
   `:shared` green, `SharedPurityTest` green, canonicalization/hash/codec tests green.
   *Dependency:* none (possible in parallel with A3/B1, plan §7).
2. **C2 — persistence + migration + resolver (consume chunk).** Room v11→v12
   (§7), `ConfigEntityMapper`, `ConfigRepository`, `PrefsBackup`, `ConfigEntityMigration`
   (§8), `ProfileResolver` (§9). `RunnerFactory`/`AIOrchestrator` read `AiConfig`
   (seam to A3/B2). Compile state: `:app` green; MigrationTest + characterization test
   green. *Dependency:* C1, B2 (SecretStore), A3 (AiConfig port).
3. **C3 — UI rework (consume chunk).** Provider/model/profile screens (§10),
   import dispatcher, v3 export, PromptsOverview badge. Compile state: `:app` green;
   Robolectric smoke green; grep test "no migrated pref key in the UI code". *Dependency:*
   C2.

## 12. Testing Approach

**Canonicalization + hash (`:shared`, C1):**
- `CanonicalJsonTest`: byte snapshot per entity type (fixed fixtures); key sorting
  (semantically equal object with a different declaration/insertion order ⇒ same
  bytes); envelope exclusion (different `id`/`visibility`/`sourceRef` ⇒ same
  canonical bytes); unicode/escaping (umlauts, control characters).
- `ContentHashTest`: determinism matrix — same payload ⇒ same hash;
  value change (each payload field individually) ⇒ new hash; `orderedPrompts` re-sort
  ⇒ **new** hash (array order significant); envelope change ⇒ **same** hash.
- `ConfigValidationsTest`: per DTO ≥1 violation (empty `label`, invalid `modelId`,
  empty `keyFingerprint`); **`GATEWAY` rejection** as an active test (creation
  `kind=GATEWAY` ⇒ `Invalid`).
- `CatalogCodecTest`: v3 round-trip byte-stable; Malformed (no JSON, unknown
  `kind` discriminator) vs. Invalid (contract violation) correctly separated (ADR-0016).

**Room migration (`:app`, C2, instrumented):**
- `MigrationTo12Test` (`MigrationTestHelper`, v11→v12): populated v11 fixture; after
  migration: new tables exist, `prompts` data preserved + `uuid`/`content_hash`
  backfilled; per CHECK column one "valid value accepted" + "invalid value ⇒
  `SQLiteConstraintException`" (Double-Enum test template, docs/DATABASE-PATTERNS.md).

**Prefs→entities (`:app`, C2):**
- `ConfigEntityMigrationTest`: fixture with all provider slots/keys/models/parameters
  → default profile correct; keys retrievable in the SecretStore, `*ApiKey*` prefs empty;
  backup JSON exists; **idempotency** (second run = no-op, no duplicates).
- `ProfileResolverCharacterizationTest` (§9.4): matrix — old vs. new RunnerConfig
  byte-identical.

**UI (`:app`, C3, Robolectric):**
- Smoke of the new navigation (provider→model→profile), profile duplicate/reorder
  (unit test on `ProfileListMutations`, role model `PromptListMutations` tests).
- grep test: no UI code references a migrated pref constant.

**Pending:**
- `GATEWAY` runner resolution (`pending: block-e-gateway`) — enum reserved, pipeline
  rejects today; the active rejection test above covers the non-selectability.

## 13. Decision Log

### D1 — Entities as flat DTOs with an envelope field exclusion list, no wrapper

**Trigger:** contentHash must exclude envelope metadata, but the canonical form
should stay simple.
**Decision:** Envelope fields lie flat on each entity; `CanonicalJson` removes
them via the fixed `ENVELOPE_FIELDS` name list (§4.2), instead of nesting the payload into an
`Envelope{payload}` wrapper.
**Rationale:** flat DTOs are easier to serialize/validate/map; a wrapper
would nest every consumer site (Room mapper, UI) by one level, with no gain.
The exclusion is a 6-element constant — trivially testable.

### D2 — Mirror enums in `:shared` instead of a reference to the `:shared-ai` originals

**Trigger:** `AIProvider`/`AmbiguityMode`/`AIFunction` lie (after Block A) in
`:shared-ai`, which sits **above** `:shared` — not referenceable from `:shared`.
**Decision:** `:shared` defines `ProviderType`/`ProviderKind`/`ModelFunction`/
`AmbiguityModeValue`/`PromptSelectionMode` itself; parity tests (§4.8) enforce
value equality; mappers live in `:shared-ai`/`:app`.
**Alternatives:** (a) move `AIProvider` into `:shared` — a Block A decision,
not Block C; escalated to Block A as Gap 1. (b) Mirror without parity — discarded, because
drift would break the contentHash unnoticed.

### D3 — Shared `Prompt` entity without pill `type`; Android `prompts.type` stays Room-only

**Trigger:** ADR-0024 has `prompts.type` (PROMPT/TEXT); the shareable prompt entity should,
per the concept, have no pill fields.
**Decision:** `PromptV3Entity` = {name, text, requiresSelection, autoApply}. The
Android `prompts.type` remains a Room-only column (ADR-0024 unchanged); TEXT pills
are not exported as a v3 entity; v3 import creates `type=PROMPT`.
**Rationale:** TEXT pills are literal snippets, not a shareable AI prompt (concept §6).
Keeps ADR-0024 (pill behavior, classification) fully untouched and the v3 codec
clean (one format). v1/v2 prompt import stays the Android legacy path (§10.4).

### D4 — Active profile as a global pref pointer, not as an `is_active` column

**Trigger:** exactly one profile is active; `is_active` on each row would be a
multi-row invariant.
**Decision:** `Pref.ActiveProfileId` (String). `is_active` is **not** a profile field
(would pollute the hash and is not shareable content).
**Rationale:** a pointer has no invariant to maintain; the active profile is
device-local, not part of the shareable content.

### D5 — v3 file export omits credentials as empty shells (keys only via a Block E call)

**Trigger:** `ApiCredentialEntity` carries only metadata; a file export without
secret delivery would be worthless and confusing.
**Decision:** The SAF file export (§10.5) shares prompts/profiles/models/providers, but
**no** credentials (F12: key delivery is a separate authorized peer call,
Block E). A profile that references a credential exports the reference metadata;
the recipient must set the key themselves.
**Rationale:** prevents the illusion that a file export transfers keys; keeps the
key delivery exclusively in the auditable Block E path.

### D6 — Enum layering decided: wire enums stay, no move into `:shared` (plan D5.a)

**Trigger:** Cross-spec decision of the plan architect 2026-07-20 (assignment by the
team lead); §14 Gap 1 / §4.8 TIP had escalated the seam to Block A.
**Decision:** The mirror approach from §4.8/D2 is binding. `AIProvider`/
`AmbiguityMode`/`AIFunction` stay in `:shared-ai` (package-preserving move
per shared-ai spec §3.4); `:shared` keeps its own wire enums
(`ProviderType`/`ProviderKind`/`ModelFunction`/`AmbiguityModeValue`/
`PromptSelectionMode`). Parity tests + mappers live in `:app` (sees both
modules) and are a mandatory gate.
**Rationale:** Exactly the existing wire-vs-domain doctrine (ADR-0016;
`SessionOriginWire` ↔ `SessionOrigin` + `SessionEntityMapper`): domain enums
carry behavior (capabilities, `forcesTurn`) that does not belong in the wire module;
a move would additionally have introduced the module coupling deliberately avoided by
Block A (`:shared-ai`→`:shared`) and broken the package-preserving move concept.
Drift is test-prevented — as in the existing code.
**Alternatives:** move the originals into `:shared` (discarded, see above);
mirror without parity test (discarded already in D2).

### Freshness pass 2026-07-20 — as-built structure (post-implementation)

**Trigger:** Integration check after completion of Block A–E (finding `integ-1`,
green) — reconciliation of the five block specs against the built state before the
F-stage archival/EN translation.
**As-built vs. spec:**
1. **Wire enum home consolidated.** D6 names the config family
   (`ProviderType`/`ProviderKind`/`ModelFunction`/`AmbiguityModeValue`/
   `PromptSelectionMode`) in `:shared`. What was built is ONE common home
   `shared/src/main/kotlin/net/devemperor/dictate/shared/config/ConfigEnums.kt`,
   which additionally carries the catalog wire enums needed by peer-catalog §5.2/§5.3
   — as `Visibility` (`PRIVATE`/`SHARED`) and `SubscriptionMode`
   (`LOCAL`/`SUBSCRIBE`/`ONE_SHOT`), **not** as separate `shared.catalog.*Wire`
   copies. The "wire" name suffix is dropped (one home ⇒ no domain/wire
   name collision to defuse). D6's enumeration was the config subset; the
   consolidation is faithful to the D5.a doctrine (one SSoT enum module), thus superseding
   the scattered placement, not the decision itself.
2. **Management UI (companion side).** The companion-side entity editor
   landed consolidated in `companion/.../ui/config/ManagementScreen.kt` +
   `ConfigViewModel.kt` (one screen), not in separate `ui/profiles`/`ui/models`/
   `ui/prompts` — detail lies with desktop-host §9.2 (freshness pass entry there).
   The Android settings (§10 C3) are unaffected by this.
**Assessment:** No code impact, D5-endorsed and parity-tested
(`ConfigEntityCheckParityTest`). Body unchanged; this entry is the
normative as-built correction of the enum package references.

## 14. Information Gaps

1. ~~**`AiConfig` port signature (Block A) / enum placement**~~ — **partially
   closed 2026-07-20 (D6 / plan D5.a):** enum placement is decided
   (originals in `:shared-ai`, wire enums in `:shared`, mirror approach
   binding). What remains open is only the exact `AiConfig` port signature —
   defined in `research/shared-ai-extraktion.md` §4; the C2 adapter aligns
   itself with the port specified there. *Owner:* Block A agent (signature),
   C2 agent (adapter).
2. **Anthropic model list.** No `/models` endpoint in the OpenAI format → free text stays
   (plan §10 Gap 4). *Owner:* C3. *Fallback:* ModelRef entities (shareable curation)
   mitigate this; free-text field in the model selector.
3. **SecretRef format (Block B).** Whether `SecretRef` = credential UUID string or a
   structured namespace is owned by Block B1. *Owner:* Block B agent. *Fallback:*
   §8/§9 assume `SecretRef(credentialId)`; adjustable without a model change.
4. **Backup cleanup.** Whether/when the prefs backup (§8.4) is automatically deleted
   (after N days? after the first successful resolver run?) is open. *Owner:* C2 agent.
   *Fallback:* the backup stays in place (app-private) until an explicit cleanup decision.
5. **Two custom providers with the same host.** If the transcription and
   rewording CustomHost are identical, the migration could create **one** instead of two
   ProviderConfigs (dedup). *Owner:* C2 agent. *Fallback:* conservatively two
   ProviderConfigs (§8.2) — never wrong, only possibly redundant; dedup optional.

## 15. References

- **Plan:** `~/.claude/plans/desktop-companion-v1.md` — Block C (§5), decisions
  F14/F17/F22/F23/F24/F27/F31, D3/D4.4/D4.7.
- **Concept:** `research/konzept-skizze.md` (§4 entity model, §5 profile name, §6
  "not ported"), `research/bestandsaufnahme.md`, `research/fragenkatalog.md`.
- **Sister specs:** `research/shared-ai-extraktion.md` (Block A, AiConfig port),
  `research/secretstore.md` (Block B, SecretStore/SecretRef) — both emerging in parallel,
  seams in §14.
- **ADRs (binding):** ADR-0016 (wire DTO + Konform + ProtocolCodec — pattern for
  CatalogCodec), ADR-0024 (prompt pill types — untouched), ADR-0012 (model resolution
  via conversation), ADR-0013 (AmbiguityMode). Plan-scoped: `adr-config-entity-model`,
  `adr-secret-store`, `adr-shared-ai-module`.
- **Conventions:** `docs/DATABASE-PATTERNS.md` (Double-Enum, denormalized cache,
  data-preservation rule), `~/.claude/snippets/test-first-patterns.md`.
- **Key code:** `app/.../preferences/DictatePrefs.kt` (current-state inventory),
  `app/.../ai/factory/RunnerFactory.kt` + `ai/AIOrchestrator.kt` (config resolution),
  `app/.../settings/APISettingsActivity.java` (UI rework target),
  `app/.../rewording/PromptImportExport.java` + `PromptListMutations.kt` (v1/v2 + UX role model),
  `app/.../database/migration/MigrationTo11.kt` (migration pattern),
  `shared/.../protocol/{ProtocolCodec,Dtos,Validations}.kt` (codec role model).
