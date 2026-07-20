---
date: 2026-07-19
author: Lukas + Claude (Opus, groundwork agent — Spec research Block E)
status: Spec — programmer-ready, no invented detail
context: Implementer-ready Spec for Block E of the desktop-companion-v1 plan — peer catalog protocol, hash-based subscription sync, Peer Explorer, discovery and headless peer operation.
related-plan: ../desktop-companion-v1.md
related-adrs: ADR-0016, ADR-0017, ADR-0020, ADR-0023, ADR-0025 (binding); adr-peer-catalog (plan-scoped, to be created in A1); adr-secret-store, adr-config-entity-model (prerequisites from Block B/C)
---

# Block E — Peer Catalog, Subscription Sync, Peer Explorer, Discovery, headless Operation

This spec describes the fourth and final feature block of the desktop-companion-v1
initiative: the sharing and fetching of configuration entities
(`ProviderConfig`/`ModelRef`/`Prompt`/`Profile`/`ApiCredential`) between peers in the
Tailnet. It builds **additively** on the existing wire stack (ADR-0016/0017/0025) —
no protocol-version bump, one new `supportsCatalog` health flag, the same
`ProtocolCodec` door, the same pairing model. Block E presupposes Block C1 (entities +
canonical v3 serialization in `:shared`) and Block B1 (SecretStore port); the
sequencing is `C1 → E1 → E2 → E3`, `B1 → E2` (see plan §7).

The audience is the implementation agent who builds E1/E2/E3 without having seen the
concept session. All file pointers are `path:line` against the state in the worktree
`feature/desktop-companion-v1`.

## Table of Contents

- [Glossary](#glossary)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Wire Catalog Family (E1, `:shared`)](#3-wire-catalog-family-e1-shared)
- [§4 Companion Server Side (E1)](#4-companion-server-side-e1)
- [§5 SQLDelight Schema — peers, subscriptions, entity mirror (E1)](#5-sqldelight-schema--peers-subscriptions-entity-mirror-e1)
- [§6 Sync Engine (E2)](#6-sync-engine-e2)
- [§7 Notification — Companion Tray + Android Notification (E2)](#7-notification--companion-tray--android-notification-e2)
- [§8 Peer Explorer UI (E3)](#8-peer-explorer-ui-e3)
- [§9 Discovery + headless Operation (E3)](#9-discovery--headless-operation-e3)
- [§10 Directory Layout](#10-directory-layout)
- [§11 Two-Peer Testability + E2E](#11-two-peer-testability--e2e)
- [§12 Migration Plan (E1 → E2 → E3)](#12-migration-plan-e1--e2--e3)
- [§13 Testing Approach](#13-testing-approach)
- [§14 Decision Log](#14-decision-log)
- [§15 Information Gaps](#15-information-gaps)
- [§16 References](#16-references)

## Glossary

**Protocol & Wire**
- **Catalog family** — the new additive payload family (`/v1/catalog*`) on the
  existing wire stack. Three endpoints (index, entity, credential) plus a
  health flag. No version bump (§3, ADR-0025 pattern).
- **Root hash** — SHA-256 over the sorted list of `(id, contentHash)` pairs
  of all `visibility = SHARED` entities of a peer. A single GET answers
  "did anything change at all?" (§6).
- **contentHash** — SHA-256 over the **canonical** serialization of a single
  entity (defined in C1, `:shared`). Simultaneously sync watermark and drift detector.
- **Konform validation** — the `Validation<T>` next to every DTO (ADR-0016). Every
  new catalog DTO brings its own, otherwise the value is never checked.

**Subscription & Fetch**
- **Peer** — any participant that speaks the catalog family. Only someone who runs a
  server can offer (a Companion or a `--headless` hub); Android is a pure
  fetcher (F7/F25).
- **Subscription** — a fetched local copy of a peer entity. Two modes:
  `SUBSCRIBE` (sync keeps it up to date via hash comparison) and `ONE_SHOT` (a one-time
  frozen copy, no sync).
- **Fork / detach** — a fetched copy is read-only; "edit" explicitly detaches it
  (`subscription_mode = NULL`), makes it locally editable, and sync never
  overwrites it again (F29). The provenance (`source_peer_id`/`source_hash`)
  is retained as display metadata (F14).
- **visibility** — per entity `PRIVATE | SHARED`. Only `SHARED` entities appear
  in a peer's catalog index.
- **Staleness** — the time since the last *successful* contact with a peer.
  If it exceeds a threshold, the peer is "stale"; an unreachable peer
  is not an error but a silent indication (F33).

**Security**
- **Envelope encryption** — the offering peer holds the credential in its
  SecretStore, delivers it on fetch over the TLS channel, and the receiver places
  it immediately into its own SecretStore. No plaintext key on disk (F12).
- **Pairing** — the same one-time-token→device-secret model from ADR-0017, now
  also for peer↔peer: the fetching peer is the HTTP client of the offering one (F10).

**Discovery & Operation**
- **PeerDiscovery** — the port that finds peer candidates. Two paths: manual
  address/QR entry AND Tailscale enumeration (`tailscale status --json`, F26).
- **headless peer** — the Companion with the `--headless` flag: server runs, Compose UI
  does not. A deployment variant of the same code, no separate module (F8).

> **Subscription ≠ Fork ≠ ONE_SHOT.** A *subscription* (`SUBSCRIBE`) is a
> live-maintained copy — sync updates it. *ONE_SHOT* is a frozen
> copy without a sync binding, but with provenance display. A *fork* is a formerly
> subscribed copy that the user has edited — sync never touches it again. All
> three are rows in the same entity table; they differ only in the
> `subscription_mode` (`SUBSCRIBE` / `ONE_SHOT` / `NULL`).

> **Root hash ≠ contentHash.** The *root hash* is a peer-wide fingerprint
> over *all* shared entities (a cheap "did anything change?"). The
> *contentHash* is the fingerprint of *one* entity (which one exactly
> changed, and is my local copy detached?).

## 1. Vision and Motivation

### 1.1 Why this block exists

After Block C, providers/models/prompts/profiles are shareable, versioned entities
with canonical serialization and `contentHash` (C1). But they sit isolated on
one device each. Block E closes the loop: a user curates a prompt or
profile set on a Companion, makes it `SHARED`, and other peers (further
Companions, a headless hub, the phone) fetch it and keep it automatically
up to date. That is the "distribution architecture" from the concept sketch §4.

### 1.2 What the block solves

- **No manual re-syncing of config.** A changed profile at the offerer
  lands at the fetcher automatically (poll + root hash + notification).
- **Key sharing without plaintext.** API keys travel envelope-encrypted (TLS +
  SecretStore on both sides, F12) — they never appear in the catalog index.
- **Provenance is visible.** The Peer Explorer shows what was fetched from whom,
  in which state (current/update/detached/stale) and — conversely — who fetches what
  from one's own peer (F34).
- **Deployment as a hub.** A `--headless` Companion on a VM is a
  permanent offerer, without needing a second system/protocol (F8).

### 1.3 Discarded Alternatives

- **Push sync / back channel.** Rejected (F9): the existing stack deliberately has
  no back channel (ADR-0017). Pull-only with root-hash polling is cheap (one
  GET, usually without follow-up requests) and consistent with ADR-0020.
- **Reuse cursor sync as in ADR-0020.** Rejected: entities are small,
  rarely changed, individually identifiable — hashes additionally provide the
  drift detection (locally edited? peer changed?) for free. The session sync stays
  cursor-based; the catalog sync is hash-based (D2 in the Decision Log).
- **Zero-knowledge key sharing.** Rejected (F12) in favor of envelope encryption —
  the peer operator is trustworthy anyway in the self-hosted context.
  Zero-knowledge remains a documented later hardening.
- **Own peer protocol / own codec.** Rejected (F32): the family sits on
  `ProtocolCodec` + Konform + `ErrorEnvelope`, exactly like the input-command family
  (ADR-0025). Two codecs would be two truths.

### 1.4 What the block concretely delivers

1. An additive endpoint branch that lets older peers degrade cleanly (404 +
   `supportsCatalog = false`).
2. Idempotent sync that, when unchanged, costs exactly one HTTP call.
3. Fork protection by construction: a detached copy is no longer `SUBSCRIBE`
   in `subscription_mode`, and the sync query never touches it.
4. One audit-log entry per credential delivery (R8 mitigation).

## 1a. Architecture Walkthrough

### 1a.0 ASCII stack (catalog flow between two peers)

```
┌─────────────────────────────────────────────────────────────────────┐
│  ANBIETENDER PEER (Companion oder --headless Hub)      (Server)     │
│  Route:  GET /v1/catalog          → CatalogIndexResponse{rootHash}  │
│          GET /v1/catalog/entity/{id}    → CatalogEntityResponse      │
│          GET /v1/catalog/credential/{id}→ CatalogCredentialResponse  │
│  Auth:   bestehende authenticated{}-Wand (Pairing, ADR-0017)        │
│  Filter: nur visibility = SHARED; Credential-Wert nur im 3. Call    │
│  Store:  CatalogService ← SqlDelight (entity-Tabellen + SecretStore)│
└─────────────────────────────────────────────────────────────────────┘
              ↑ HTTPS pull (Tailscale/TLS), CatalogClient (:shared)
┌─────────────────────────────────────────────────────────────────────┐
│  BEZIEHENDER PEER — Companion ODER Android                          │
│  CatalogSyncEngine:  rootHash-Vergleich → Entity-Diff → Pull →      │
│                      contentHash-Verify → lokale Kopie updaten       │
│                      (nur wenn nicht FORKED) + Notification          │
│  Scheduler:  Companion = Timer + Panel-Start                        │
│              Android   = WorkManager-Periodik + App-Start           │
│  Store:  entity-Tabellen (Spiegel C2) + subscriptions + peers       │
│          Credentials → SecretStore (B1)                             │
│  UI:     Peer Explorer (Companion: voll; Android: read-only)        │
└─────────────────────────────────────────────────────────────────────┘
```

### 1a.1 Layer `:shared` — Wire Catalog Family (E1)

- **Purpose:** SSoT of the catalog payloads; used verbatim by both peer roles.
- **File:** `shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/`
  (`Dtos.kt:238` end — append new DTOs; `Validations.kt:140`; `Endpoints.kt:23`;
  `ErrorEnvelope.kt:41` — new `ErrorCode`s) + a new `CatalogClient.kt` next to
  `client/DispatchClient.kt:1`.
- **Contract:** `ProtocolCodec.decode/encode` remains the only door
  (`ProtocolCodec.kt:50`); every new DTO carries `protocolVersion` first and has a
  co-located `Validation<T>`.

### 1a.2 Layer Companion server — CatalogService + Routes (E1)

- **Purpose:** mount the three routes behind the existing auth; filter the offer;
  deliver the credential separately authorized; write an audit row.
- **File:** `companion/.../server/routes/CatalogRoutes.kt` [NEW],
  `companion/.../domain/CatalogService.kt` [NEW]; wired in
  `CompanionServer.kt:104` (the `authenticated{ … }` block) and `CompanionContainer.kt:67`.

### 1a.3 Layer fetcher — sync engine + persistence (E2)

- **Purpose:** root-hash comparison, diff, pull, verify, fork protection, staleness,
  notification. Two scheduler hosts (Companion timer, Android WorkManager), one
  engine core in `:shared` (pure JVM) plus platform-specific ports.
- **File:** `shared/.../sync/CatalogSyncEngine.kt` [NEW] (analogous to `sync/SyncClient.kt`);
  `companion/.../catalog/` [NEW]; `app/.../peers/` [NEW].

### 1a.4 Layer UI — Peer Explorer + Discovery (E3)

- **Purpose:** fetch and offer views (Companion Compose), read-only variant
  (Android Settings); discovery port with Tailscale impl; `--headless` entry point.
- **File:** `companion/.../ui/peers/` [NEW], `companion/.../catalog/discovery/` [NEW],
  `companion/.../Main.kt:62` (the `--headless` branch); `app/.../peers/ui/` [NEW].

### 1a.5 Read-this-before-implementing Checklist

- [ ] Every new catalog DTO: `@Serializable` in `Dtos.kt` + co-located
  `Validation<T>` in `Validations.kt` + round-trip and ≥1 rejection test in the same
  commit (ADR-0016 failure mode). Enums as **separate** wire enums (pattern
  `SessionOriginWire`, `Dtos.kt:63`), not the internal entity enums.
- [ ] `supportsCatalog` **defaults to `false`** in `HealthResponse` — no
  version bump (ADR-0025, `Dtos.kt:237` pattern).
- [ ] The credential secret value **never** appears in the index and **never** in a
  `CatalogEntityResponse` — only in its own, individually authorized
  `GET /v1/catalog/credential/{id}` (R8, §4.3).
- [ ] New SQLDelight finite-set columns: Double-Enum (Kotlin enum + SQL CHECK) +
  `EnumColumnAdapter`; parity test following the `OriginCheckConstraintParityTest` pattern
  (`OriginCheckConstraintParityTest.kt:1`).
- [ ] `ErrorEnvelope.message`/`details` never carry a secret or payload value
  (redaction contract, `ErrorEnvelope.kt:54`); catalog errors name only id/limit.
- [ ] Fetched credentials land **immediately** in the SecretStore (B1), never in a
  plaintext column of the entity table.
- [ ] Sync writes a `SUBSCRIBE` copy only if its contentHash matches against the
  index value **and** against the recomputation from the canonical serialization
  (verify-before-write, §6.3).

## 2. Acceptance Criteria

1. **Additivity:** `HealthResponse.supportsCatalog` defaults to `false`; a peer
   without catalog routes answers 404, and the `CatalogClient` maps that to
   `CatalogError.EndpointMissing` (pattern `DispatchClient.input()`,
   `DispatchClient.kt:118`). `ProtocolVersion.CURRENT` stays `1`.
2. **Konform completeness:** Every catalog DTO has a `Validation<T>`; a
   `ValidationsTest` case per DTO checks at least one violation.
3. **Root-hash determinism:** Same entity set ⇒ same root hash;
   reordering the entities ⇒ same root hash; exactly one entity change ⇒
   a new root hash (snapshot/determinism test).
4. **Auth parity:** catalog index, entity and credential are only reachable with a valid
   device secret (401 otherwise, like `CompanionE2ETest.health_withoutPairing`,
   `CompanionE2ETest.kt:352`).
5. **Credential isolation:** The secret value appears in no index and no
   `CatalogEntityResponse`; only the credential call delivers it, and every delivery
   writes an audit row (test checks index/entity payload for absence of the
   sentinel + presence of the audit row).
6. **Sync idempotency:** Second run without a peer change = one HTTP call (index), no
   entity fetch, no write, no notification.
7. **Update detection:** Offerer changes a prompt ⇒ fetcher run detects via
   root hash, pulls only the changed entity, verifies the contentHash,
   updates the local copy and fires the notification hook.
8. **Fork protection:** A detached (`subscription_mode = NULL`) copy is
   overwritten by no sync run; `ONE_SHOT` likewise remains untouched.
9. **Offline tolerance:** An unreachable peer produces no error, but instead
   updates `last_contact_at` and, after the threshold, is displayed as `STALE`.
10. **Two-peer E2E:** Two in-process `embeddedServer(CIO, port=0)` instances share
    prompt/profile/ModelRef/credential over the real `CatalogClient`; the credential
    lands at the receiver exclusively in the (fake) SecretStore (E2E following the
    `CompanionE2ETest` pattern).
11. **Discovery port:** `TailscalePeerDiscovery` parses fixture `tailscale status --json`
    into candidates; absence of the CLI ⇒ empty list, no crash (noop fallback).
12. **headless boot:** `--headless` starts server + persistence without a Compose window
    (boot test: `CompanionBootstrap.start()` returns `ReadyCompanion`, no AWT/Skiko).
13. **Explorer state matrix:** ViewModel tests cover `CURRENT` / `UPDATE_AVAILABLE`
    / `FORKED` / `STALE` / `SOURCE_REMOVED`.

## 3. Wire Catalog Family (E1, `:shared`)

### 3.1 Endpoints (`Endpoints.kt`)

Append after `INPUT` (`Endpoints.kt:23`). Namespace cut so that a
later `/v1/ai/*` family fits additively alongside (F31, Decision Log D5).

```kotlin
/** Peer-Katalog-Familie (GET, authenticated). Additiv — kein Version-Bump (ADR-0025). */
const val CATALOG = "$BASE/catalog"
/** Index: Root-Hash + Entitäts-Metadaten (kein Credential-Wert). */
const val CATALOG_ENTITY = "$BASE/catalog/entity"      // + "/{id}"
/** Einzeln autorisierter Secret-Wert einer Credential-Entität (§4.3). */
const val CATALOG_CREDENTIAL = "$BASE/catalog/credential" // + "/{id}"

/** Entitäts-Id-Länge (UUIDv4 = 36, plus Reserve). */
const val MAX_ENTITY_ID_LENGTH = 64
/** Root-/Content-Hash: lowercase hex SHA-256 = 64 Zeichen. */
const val HASH_LENGTH = 64
/** Katalog-Index-Deckel: mehr geteilte Entitäten sind ein Bug oder Angriff. */
const val MAX_CATALOG_ENTRIES = 2_000
/** Kanonischer Entitäts-Payload (v3-JSON einer Entität). */
const val MAX_ENTITY_PAYLOAD_LENGTH = 64_000
```

### 3.2 DTOs (`Dtos.kt`, append after `HealthResponse`)

```kotlin
// ── Catalog ─────────────────────────────────────────────────────────────────────

/**
 * The wire kind of a catalog entity — a SEPARATE enum from the C1 domain entity types,
 * exactly as SessionOriginWire is separate from SessionOrigin (Dtos.kt:63). The wire
 * vocabulary must not be dragged along by an internal C1 refactor. UNKNOWN is the landing
 * zone the receiver's mapper uses for a kind a newer provider introduced.
 */
@Serializable
enum class CatalogEntityKindWire { PROVIDER_CONFIG, MODEL_REF, PROMPT, PROFILE, CREDENTIAL, UNKNOWN }

/**
 * One row of the catalog index — metadata ONLY, never a payload and never a secret.
 * `contentHash` is the SHA-256 over the entity's canonical serialization (C1); for a
 * CREDENTIAL it is the hash over the encrypted at-rest blob / key fingerprint, never the
 * plaintext (F12). `updatedAt` drives the "letzter Abgleich"-display, not the diff.
 */
@Serializable
data class CatalogEntry(
    val id: String,
    val kind: CatalogEntityKindWire,
    val contentHash: String,
    val updatedAt: Long,
    /** Human label for the offer view (e.g. prompt name, provider label). No payload. */
    val label: String,
)

/**
 * GET /v1/catalog — the whole shared offer of a peer, plus its rootHash.
 *
 * The rootHash is SHA-256 over the sorted `id + contentHash` concatenation of all entries;
 * a single GET answers "did anything change at all?" before any per-entity fetch (§6.1).
 */
@Serializable
data class CatalogIndexResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val rootHash: String,
    val entries: List<CatalogEntry>,
)

/**
 * GET /v1/catalog/entity/{id} — the canonical v3 payload of ONE non-credential entity.
 *
 * `payload` is the exact canonical serialization (C1) the receiver re-hashes to verify
 * `contentHash` (§6.3). A CREDENTIAL is NEVER served here — its route is /credential/{id}.
 */
@Serializable
data class CatalogEntityResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val id: String,
    val kind: CatalogEntityKindWire,
    val contentHash: String,
    val payload: String,
)

/**
 * GET /v1/catalog/credential/{id} — the envelope-delivered secret value (F12).
 *
 * Reached only by an explicitly authorized call; every delivery writes an audit row (R8).
 * The receiver puts `secret` straight into its own SecretStore (B1) and never persists it
 * in a column. `provider`/`label` are metadata for the SecretStore namespace.
 */
@Serializable
data class CatalogCredentialResponse(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val id: String,
    val provider: String,
    val label: String,
    /** Plaintext key, TLS in transit, straight into the receiver's SecretStore. */
    val secret: String,
)
```

`HealthResponse` (`Dtos.kt:223`) gains a field:

```kotlin
/**
 * Whether this companion serves the /v1/catalog family. Additive, defaulted false so an
 * older peer's health response decodes as "no support" under ignoreUnknownKeys — the
 * beziehende Peer reads it during discovery/health and can skip a peer that cannot offer.
 */
val supportsCatalog: Boolean = false,
```

### 3.3 Validations (`Validations.kt`, append)

```kotlin
private val HASH_PATTERN = Regex("^[0-9a-f]{${Endpoints.HASH_LENGTH}}$")
private val ENTITY_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,${Endpoints.MAX_ENTITY_ID_LENGTH}}$")

val catalogIndexResponse = Validation<CatalogIndexResponse> {
    CatalogIndexResponse::protocolVersion { supportedProtocol() }
    CatalogIndexResponse::rootHash { pattern(HASH_PATTERN) }
    CatalogIndexResponse::entries {
        maxItems(Endpoints.MAX_CATALOG_ENTRIES)
        onEach {
            CatalogEntry::id { pattern(ENTITY_ID_PATTERN) }
            CatalogEntry::contentHash { pattern(HASH_PATTERN) }
            CatalogEntry::label { maxLength(Endpoints.MAX_DEVICE_NAME_LENGTH) }
        }
    }
}

val catalogEntityResponse = Validation<CatalogEntityResponse> {
    CatalogEntityResponse::protocolVersion { supportedProtocol() }
    CatalogEntityResponse::id { pattern(ENTITY_ID_PATTERN) }
    CatalogEntityResponse::contentHash { pattern(HASH_PATTERN) }
    // No {value} on payload — redaction rule (ErrorEnvelope.kt:54): a length breach names
    // the limit, never the payload.
    CatalogEntityResponse::payload { minLength(1); maxLength(Endpoints.MAX_ENTITY_PAYLOAD_LENGTH) }
}

val catalogCredentialResponse = Validation<CatalogCredentialResponse> {
    CatalogCredentialResponse::protocolVersion { supportedProtocol() }
    CatalogCredentialResponse::id { pattern(ENTITY_ID_PATTERN) }
    CatalogCredentialResponse::provider { minLength(1); maxLength(64) }
    // NEVER a {value} constraint on `secret` — it would copy the key into logs.
    CatalogCredentialResponse::secret { minLength(1) }
}
```

### 3.4 New ErrorCodes (`ErrorEnvelope.kt`)

```kotlin
/** 404 — the requested entity is unknown OR not shared. One code for both, on purpose:
 *  telling them apart would leak which private entities exist (parallel to the uniform 401). */
CATALOG_ENTITY_NOT_FOUND,
```

`respondEnvelope` (`ProtocolCalls.kt:126`) and the `CompanionException` hierarchy
(`domain/DomainErrors.kt`) get the matching exception + StatusPages mapping
(pattern: the existing `ValidationException` → 400). The `CatalogClient` maps
`CATALOG_ENTITY_NOT_FOUND` to `CatalogError.EntityGone` (§6.4, an entity deleted at
the offerer).

### 3.5 CatalogClient (`shared/.../client/CatalogClient.kt` [NEW])

Parallel to `DispatchClient` (`DispatchClient.kt:1`), **pure JVM** — used verbatim by
Companion (fetcher) AND Android (fetcher). Reused: `DispatchTransport`,
`OkHttpDispatchTransport`, `Credentials`, `AuthHeaders`, `ProtocolCodec`. Three
`read` calls (GET, bodyless; version in the header via `AuthHeaders.forDevice`):

```kotlin
class CatalogClient(
    private val transport: DispatchTransport,
    private val credentials: () -> Credentials?,
) {
    fun index(): DispatchResult<CatalogIndexResponse>          // GET /v1/catalog
    fun entity(id: String): DispatchResult<CatalogEntityResponse>       // GET /v1/catalog/entity/{id}
    fun credential(id: String): DispatchResult<CatalogCredentialResponse> // .../credential/{id}
}
```

> [!NOTE]
> The `CatalogClient` returns `DispatchResult<T>`/`DispatchError` (reused
> from `DispatchError.kt:1`) — 404 → `EndpointMissing` (old peer) vs.
> `CATALOG_ENTITY_NOT_FOUND` in the envelope → its own error path. The
> classification follows `DispatchClient.classifyError` exactly (`DispatchClient.kt:200`); a
> `CatalogError` extension by `EntityGone` suffices.

## 4. Companion Server Side (E1)

### 4.1 Routing integration

`CatalogRoutes` are mounted in the `authenticated { … }` block of `companionModule`
(`CompanionServer.kt:104-109`) — exactly like `dispatchRoutes`/`inputRoutes`:

```kotlin
authenticated(container.authService) {
    dispatchRoutes(container.dispatchService)
    inputRoutes(container.inputCommandService)
    syncRoutes(container.syncService)
    healthRoutes(container.healthService)
    catalogRoutes(container.catalogService)   // NEU
}
```

`catalogRoutes` reads the `{id}` path parameter, validates it against
`ENTITY_ID_PATTERN`, and answers via `respondProtocol` (`ProtocolCalls.kt:106`).
The authenticated peer is available via `call.device` (`AuthPlugin.kt:25`) —
that is the audit identity for the credential row.

### 4.2 CatalogService (`companion/.../domain/CatalogService.kt` [NEW])

Responsibility: build the index (only `visibility = SHARED`), compute the root hash, deliver a single
entity as a canonical payload, deliver the credential separately + audit. Constructor-
wired in `CompanionContainer` (`CompanionContainer.kt:67`, next to
`healthService`).

```kotlin
class CatalogService(
    private val entities: CatalogEntityRepository,  // SqlDelight, §5
    private val secretStore: SecretStore,           // B1-Port
    private val auditLog: CatalogAuditLog,           // §4.3
    private val clock: ClockPort,
) {
    fun index(): CatalogIndexResponse
    /** @throws CompanionException.CatalogEntityNotFoundException wenn id unbekannt ODER nicht SHARED. */
    fun entity(id: String): CatalogEntityResponse
    /** Schreibt eine Audit-Zeile (peerDeviceId, id, at) und liefert den Secret-Wert aus SecretStore. */
    fun credential(id: String, peerDeviceId: String): CatalogCredentialResponse
}
```

**Root hash (deterministic, C1-canonicity-dependent):**

```kotlin
fun rootHash(entries: List<CatalogEntry>): String =
    Secrets.sha256(
        entries.sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.contentHash}" }
    )
```

`Secrets.sha256` (`Secrets.kt:44`) already exists and is pure. Sorting
by `id` makes the hash independent of the DB order (Acceptance 3). The
`contentHash` itself comes from C1 (`:shared`), stored in the entity column —
the CatalogService does not recompute it, it reads it.

### 4.3 Credential delivery + audit

For a `CREDENTIAL` entity, the index carries only `label`/`provider` and a
`contentHash` over the **encrypted at-rest blob or key fingerprint** — never
the plaintext (F12). Only `GET /v1/catalog/credential/{id}` then:

1. checks that the entity exists and `visibility = SHARED` (otherwise 404),
2. fetches the plaintext from its own `SecretStore` (B1),
3. writes an audit row `catalog_access_log(peer_device_id, entity_id, at)`,
4. answers with `CatalogCredentialResponse` over TLS.

> [!CAUTION]
> The secret value must **never** reach `ErrorEnvelope`, logs or the index
> (`ErrorEnvelope.kt:54`, redaction contract). Konform constraints on `secret`
> carry no `{value}`. The audit log is the R8 mitigation and the basis for the
> offer view "who fetched what and when" (§8.2).

### 4.4 HealthService

`HealthService` (`HealthService.kt:1`) gets `supportsCatalog = true` set fixed
(the Companion always serves the family once E1 is built) — analogous to
`supportsInputCommands = inputPerformer.available`. Since the Companion mounts the routes
unconditionally, it is a constant `true`; Android does not set it at all in its
health response (Android has no server).

## 5. SQLDelight Schema — peers, subscriptions, entity mirror (E1)

All new tables in `companion/.../db/Companion.sq` (`Companion.sq:1`), migration
as `migrations/2.sqm` (v2 → v3, pattern `1.sqm`). Double-Enum throughout:
Kotlin enum column (`AS <Enum>`) + SQL CHECK; `EnumColumnAdapter` in
`CompanionDatabase.build` (`CompanionDatabase.kt:39`). Parity test per enum column
following the `OriginCheckConstraintParityTest` pattern.

> [!IMPORTANT]
> **Schema ownership coordination (see §15 Gap 1):** The entity tables
> (`provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts`) are the
> Companion mirror of the C2 Android structure and are needed by D3 (local management)
> **and** E1 (fetched copies). This spec defines the complete
> schema incl. provenance columns; if D3 lands before E1, D3 introduces the base tables
> and E1 adds the provenance columns via migration. The enum vocabularies
> must be identical to C1/C2 (parity test, §13).

### 5.1 peers

```sql
CREATE TABLE peers (
    -- Opaque, public-key-fähig ausgelegt (F30): heute serverName/UUID, später ein
    -- Key-Fingerprint. Wird NICHT aus der Adresse abgeleitet (Adresse ist wechselbar).
    peer_id         TEXT NOT NULL PRIMARY KEY,
    display_name    TEXT NOT NULL,
    -- MagicDNS-Name + Port, z. B. "heim-pc.tailXXXX.ts.net:8756".
    address         TEXT NOT NULL,
    -- Unser Pairing-Credential FÜR diesen Peer (wir sind sein HTTP-Client).
    -- Das Secret liegt im SecretStore; hier nur die Referenz (B1).
    device_id       TEXT NOT NULL,
    secret_ref      TEXT NOT NULL,
    added_at        INTEGER NOT NULL,
    -- Letzter VERSUCH (auch fehlgeschlagen) — treibt kein Staleness.
    last_contact_at INTEGER,
    -- Letzter ERFOLGREICHER Kontakt — Basis der Staleness-Anzeige (§6.5).
    last_success_at INTEGER,
    -- Root-Hash beim letzten erfolgreichen Lauf — der billige Änderungs-Detektor.
    last_root_hash  TEXT
);
```

No `status` enum in the table: the status (`OK`/`STALE`/`UNREACHABLE`) is
**derived** from `last_success_at` + threshold (domain, testable), not persisted —
so no drift between stored and actual state can arise
(the same materialization argument as ADR-0023 §2, inverted: here the state is
purely time-derived, so do not materialize).

### 5.2 Entity tables (mirror of C2) + provenance

Exemplified by `prompts`; `provider_configs`/`model_refs`/`profiles` analogous with their
C1 fields. The provenance/sync columns are the same on **every** entity table:

```sql
CREATE TABLE prompts (
    id                TEXT NOT NULL PRIMARY KEY,
    -- C1-Felder (name, text, requires_selection, auto_apply, …) …
    name              TEXT NOT NULL,
    text              TEXT NOT NULL,
    -- Kanonik-Hash aus C1 — Sync-Watermark, hier gespeichert, nicht neu berechnet.
    content_hash      TEXT NOT NULL,
    visibility        TEXT AS net.devemperor.dictate.shared.catalog.VisibilityWire NOT NULL
                      CHECK (visibility IN ('PRIVATE','SHARED')) DEFAULT 'PRIVATE',
    -- Provenienz (NULL = lokal erstellt). source_* bleiben nach Fork als Anzeige erhalten (F14).
    source_peer_id    TEXT,
    source_entity_id  TEXT,
    source_hash       TEXT,
    -- Sync-Steuerachse: SUBSCRIBE = live, ONE_SHOT = eingefroren, NULL = lokal/geforkt.
    subscription_mode TEXT AS net.devemperor.dictate.shared.catalog.SubscriptionModeWire
                      CHECK (subscription_mode IS NULL OR subscription_mode IN ('SUBSCRIBE','ONE_SHOT')),
    updated_at        INTEGER NOT NULL,
    FOREIGN KEY (source_peer_id) REFERENCES peers(peer_id) ON DELETE SET NULL
);
```

`ON DELETE SET NULL`: if the user deletes a peer, its fetched copies become
local — not deleted along with it (the copy now belongs to the receiver). `visibility` and
`subscription_mode` are **separate wire enums in `:shared`** (package
`shared/.../catalog/`), so that Android Room (C2) and Companion SQLDelight share the same
source (parity by construction, the D3 argument from ADR-0016).

### 5.3 subscriptions

The `subscriptions` table is the **sync journal**: which local entity is mirrored from
which peer, in which mode, at which state.

```sql
CREATE TABLE subscriptions (
    -- Lokale Entitäts-Id (die Kopie). Eine Kopie hat höchstens eine Subscription.
    local_entity_id  TEXT NOT NULL PRIMARY KEY,
    peer_id          TEXT NOT NULL,
    -- Id der Entität BEIM ANBIETER (kann von local_entity_id abweichen).
    source_entity_id TEXT NOT NULL,
    kind             TEXT AS net.devemperor.dictate.shared.protocol.CatalogEntityKindWire NOT NULL
                     CHECK (kind IN ('PROVIDER_CONFIG','MODEL_REF','PROMPT','PROFILE','CREDENTIAL','UNKNOWN')),
    mode             TEXT AS net.devemperor.dictate.shared.catalog.SubscriptionModeWire NOT NULL
                     CHECK (mode IN ('SUBSCRIBE','ONE_SHOT')),
    -- Zuletzt bezogener contentHash — Diff-Basis und Drift-Detektor.
    last_hash        TEXT NOT NULL,
    last_checked_at  INTEGER,
    -- Abgeleiteter Anzeige-Zustand wird NICHT gespeichert (siehe §8.1) —
    -- er ergibt sich aus mode + last_hash vs. Peer-Index + entity.subscription_mode.
    FOREIGN KEY (peer_id) REFERENCES peers(peer_id) ON DELETE CASCADE
);
```

> [!NOTE]
> The redundancy `entity.subscription_mode` vs. `subscriptions.mode` is intentional and
> read from both sides: the sync asks "which local copies should I touch?" —
> that is a query against `subscriptions` JOIN `entity WHERE subscription_mode IS
> NOT NULL`. A **fork** sets `entity.subscription_mode = NULL` **and** deletes the
> `subscriptions` row in one transaction; afterwards no run touches the copy
> anymore (Acceptance 8). SSoT remains `subscriptions` for "is there a binding", the
> entity column mirrors it for the fast sync query.

### 5.4 catalog_access_log (offer view + audit, R8)

```sql
CREATE TABLE catalog_access_log (
    id             INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    peer_device_id TEXT NOT NULL,     -- welches Gerät hat abgeholt (call.device.deviceId)
    entity_id      TEXT NOT NULL,     -- welche Entität
    kind           TEXT AS net.devemperor.dictate.shared.protocol.CatalogEntityKindWire NOT NULL
                   CHECK (kind IN ('PROVIDER_CONFIG','MODEL_REF','PROMPT','PROFILE','CREDENTIAL','UNKNOWN')),
    at             INTEGER NOT NULL
);
```

Credential deliveries are **always** logged (security); entity fetches
optional (for "last fetch" in the offer view, §8.2). Minimalism: no
payload, only who/what/when.

## 6. Sync Engine (E2)

### 6.1 Core (`shared/.../sync/CatalogSyncEngine.kt` [NEW], pure JVM)

Analogous to `SyncClient` (`SyncClient.kt:74`) — pure, testable against a
`FakeCatalogTransport`, platform-neutral, shared by Companion and Android. The
flow per peer with ≥1 active `SUBSCRIBE` subscription:

```
1. index = catalogClient.index()                      // ein GET
   ├─ Failure(Unreachable) → peers.last_contact_at = now; RETURN Stale-Ergebnis (§6.5)
   └─ Failure(EndpointMissing) → Peer kann kein Katalog mehr; markieren, RETURN
2. if index.rootHash == peers.last_root_hash          // billiger No-Op-Pfad
     → peers.last_success_at = now; RETURN NoChange (Acceptance 6)
3. für jede lokale SUBSCRIBE-Kopie dieses Peers:
     entry = index.entries.firstOrNull { it.id == subscription.source_entity_id }
     ├─ entry == null            → SOURCE_REMOVED (§6.4): Kopie behalten, markieren, notify
     ├─ entry.contentHash == subscription.last_hash → unverändert, skip
     └─ sonst → PULL (§6.2)
4. peers.last_root_hash = index.rootHash; peers.last_success_at = now
5. wenn ≥1 Kopie aktualisiert/entfernt: NotificationHook.fire(changes)  (§7)
```

Result type analogous to `SyncOutcome` (`DispatchError.kt` KDoc references it): a
`sealed class CatalogSyncOutcome { NoChange, Updated(list), PartialVerifyFailure(list),
Stale, PeerUnreachable, EndpointMissing }`.

### 6.2 Pull of a changed entity

```
resp = catalogClient.entity(entry.id)          // bzw. credential(entry.id) für CREDENTIAL
├─ Failure(EntityGone/404) → wie SOURCE_REMOVED behandeln
verify: resp.contentHash == entry.contentHash                       (Index↔Payload)
verify: C1.contentHash(C1.parse(resp.payload)) == resp.contentHash  (Payload↔Neuberechnung)
├─ Verify fehlgeschlagen → Kopie NICHT schreiben, PartialVerifyFailure, log (§6.3)
└─ ok → lokale Kopie in Transaktion updaten:
        entity.* = parsed fields; entity.content_hash = resp.contentHash;
        subscription.last_hash = resp.contentHash; subscription.last_checked_at = now
```

For `CREDENTIAL`: `resp = catalogClient.credential(id)` → `secret` **immediately** into the
SecretStore (`secretStore.put(SecretRef(entityId), resp.secret.toByteArray())`), the
entity row carries only `provider`/`label`/`content_hash` (fingerprint), never the
plaintext (Acceptance 5, F12).

### 6.3 Verify-before-write (two hash checks)

Both checks are mandatory: the first catches a lying/broken offerer
(index says X, payload is Y), the second catches a canonicity drift between
offerer and fetcher C1 (different serialization version). On
failure, the local copy stays unchanged and the run reports
`PartialVerifyFailure` — no unverified record ever lands in the DB
(Acceptance 7 hardening, R-analogue to the `SyncClient` stall guard).

### 6.4 Edge cases

| Case | Behavior |
|---|---|
| Peer unreachable | `last_contact_at = now`, `last_success_at` untouched → staleness display, no error spam (F33, Acceptance 9) |
| Root hash equal | one GET, no writes, no notification (Acceptance 6) |
| Hash mismatch (index↔payload or payload↔recompute) | copy unchanged, `PartialVerifyFailure`, log row (§6.3) |
| Entity deleted at the offerer (`entry == null` / 404) | **keep** local copy, mark subscription as `SOURCE_REMOVED`, notification — never auto-delete (destructive) |
| Local copy forked (`subscription_mode = NULL`) | sync query does not select it at all → never overwritten (Acceptance 8) |
| `ONE_SHOT` copy | no `SUBSCRIBE` subscription → never in the sync run (Acceptance 8) |
| Credential fetch fails, entity ok | entity updated, credential subscription stays at the old state, warning |

### 6.5 Scheduler

- **Companion:** a `CatalogSyncScheduler` with a `java.util.Timer`/coroutine ticker,
  interval from `CompanionSettings` (`CompanionSettings.kt`), plus a trigger on
  panel/window open and on app start (pattern: ADR-0020 app-start trigger).
  Runs on a background dispatcher, best-effort, never throws into the UI thread.
- **Android:** `CatalogSyncWorker : CoroutineWorker` under **WorkManager**
  (`androidx.work:work-runtime-ktx` — a NEW dependency, Kotlin-ceiling check R4, §15
  Gap 2). `PeriodicWorkRequest` (min. 15 min, interval from `Pref`) +
  a one-time `OneTimeWorkRequest` on app start (D4.5). The worker instantiates the
  shared `CatalogSyncEngine` against the C2 Room tables + Android SecretStore.

## 7. Notification — Companion Tray + Android Notification (E2)

A `NotificationPort` (fun interface, `domain/port/`) separates "what to notify" from "how
to notify". The sync calls `notificationPort.notify(SyncNotification(peerName, changes))`.

### 7.1 Companion tray (SOLVES plan Gap 6)

> [!IMPORTANT]
> The Compose Desktop `Tray` (`Main.kt:129`) offers **no** notification API. The
> existing code has no `SystemTray`/`TrayIcon.displayMessage` code (grep confirms).
> The smallest working solution (plan §10 Gap 6): AWT `java.awt.SystemTray` +
> `TrayIcon.displayMessage(caption, text, TrayIcon.MessageType.INFO)`.

`AwtNotificationPort` [NEW] in `platform/windows/` (also works under Linux with
a supported tray):

```kotlin
class AwtNotificationPort : NotificationPort {
    private val trayIcon: TrayIcon? =
        if (SystemTray.isSupported()) SystemTray.getSystemTray().trayIcons.firstOrNull() else null
    override val available get() = trayIcon != null
    override fun notify(n: SyncNotification) {
        trayIcon?.displayMessage("Dictate — ${n.peerName}", n.summary, TrayIcon.MessageType.INFO)
    }
}
```

Fallback `NoopNotificationPort` (headless, `SystemTray.isSupported() == false`) —
pattern `NoopTextInserter` (`platform/fallback/NoopTextInserter.kt`). Wiring via
`PlatformModule.detect()` (`platform/PlatformModule.kt`), so that headless and Linux
degrade cleanly. `available` flag analogous to `TextInserter`.

> [!NOTE]
> The Compose `Tray` and an AWT `TrayIcon` can compete for the same tray
> slot. Concretely to be clarified in the chunk (§15 Gap 3): either replace the Compose `Tray`
> with a single AWT `TrayIcon` (which then carries menu **and** notifications),
> or register the AWT `TrayIcon` additively. Recommendation: **one**
> AWT `TrayIcon` as SSoT, the Compose `Tray` is dropped — reduces the
> double-slot risk. Spike-like, not an escalation case.

### 7.2 Android notification

`AndroidNotificationPort` [NEW] following the existing pattern
`PipelineNotificationCoordinator.kt:248` (`NotificationCompat.Builder(context,
CHANNEL_ID)`): its own `NotificationChannel` "Peer sync" (id e.g.
`catalog_sync`), importance `DEFAULT`. POST_NOTIFICATIONS runtime permission (API 33+)
via the existing `NotificationPermissionPolicy.kt`. A tap opens the read-only
Peer Explorer in the Settings (§8.3).

## 8. Peer Explorer UI (E3)

### 8.1 Fetch view (Companion, Compose)

Screens under `companion/.../ui/peers/` [NEW], ViewModel pattern
`HistoryViewModel`/`HistoryViewModelTest` (`ui/history/HistoryViewModel.kt`).

- **PeerListScreen:** list of peers — name, address, derived status
  (`OK`/`STALE`/`UNREACHABLE`, from `last_success_at` + threshold, §5.1), "last
  reached". Action "add peer" → discovery (§9).
- **PeerDetailScreen:** per peer, the fetched entities with type, mode
  (`SUBSCRIBE`/`ONE_SHOT`), derived state and actions:
  - **sync-now** — a one-time `CatalogSyncEngine` run against this peer.
  - **unsubscribe** — delete the subscription; the copy remains frozen (fork-like).
  - **fork** — set `subscription_mode = NULL` + delete the subscription row
    (transaction, §5.3), the copy becomes locally editable.

The **derived state** (not stored, §5.3 note) is computed in the ViewModel:

| State | Condition |
|---|---|
| `CURRENT` | `subscription.last_hash == entry.contentHash` in the last index |
| `UPDATE_AVAILABLE` | peer `entry.contentHash != subscription.last_hash` |
| `FORKED` | `entity.subscription_mode == NULL` and `source_peer_id != NULL` |
| `STALE` | peer `last_success_at` older than the threshold |
| `SOURCE_REMOVED` | entity no longer present in the last index |

### 8.2 Offer view (Companion, F34)

"What do I offer?" = visibility management of one's own entities (toggle `visibility`)
plus — read-only — the **last fetch** per shared entity from the
`catalog_access_log` (§5.4). What the server knows about fetchers is deliberately minimal:
who (peer_device_id), what (entity_id/kind), when (at) — no content, no
frequency analysis. Credential fetches are always visible here (security
transparency).

### 8.3 Android read-only explorer

Anchored as its own **Settings page** (`app/.../peers/ui/` [NEW], reachable from
the existing Settings, pattern `settings/`). Shows the peer list + fetched entities
with state — **no** offer view (Android offers nothing, F7), **no**
fork/visibility actions except "sync-now"/"unsubscribe". The editor integration
"fetch from peer" tab (prompt/profile/model lists) is desktop-only; Android
shows the fetched copies in the reworked `PromptsOverview` with a provenance badge
(C3 groundwork).

## 9. Discovery + headless Operation (E3)

### 9.1 Manual path + pairing reuse

A peer is fetched by the fetching peer becoming the HTTP client of the offering one —
via **exactly** the existing pairing (ADR-0017, F10):

1. The offering Companion shows its pairing QR/code (existing
   `PairingDialog`, `ui/pairing/PairingDialog.kt`; `PairingService.issue()`,
   `PairingService.kt:43`).
2. The fetching peer enters address + code (or scans) and redeems via
   `DispatchClient.pair(token, deviceId, deviceName)` (`DispatchClient.kt:60`) — it
   is now a paired "device" of the offerer and holds `Credentials`.
3. From the result a `peers` row is made: `peer_id` (opaque, F30 — initially the
   `serverName` from `PairResponse`/Health or a UUID), `address`, `device_id`,
   `secret_ref` (secret into the SecretStore).

The `dictate://pair` URI (`PairingUri.kt:1`) is reused unchanged — it
already carries `baseUrl` + `token` and is platform-neutral.

### 9.2 Tailscale enumeration (behind a port, F26)

```kotlin
fun interface PeerDiscovery { fun discover(): List<PeerCandidate> }   // domain/port/
data class PeerCandidate(val magicDnsName: String, val address: String)
```

`TailscalePeerDiscovery` [NEW] in `catalog/discovery/`:
- Calls `tailscale status --json` (CLI) or the LocalAPI and parses the peer list
  (MagicDNS names, online status). Pure JSON parsing via kotlinx-serialization
  against a DTO of the relevant fields (`Peer.DNSName`, `TailscaleIPs`, `Online`).
- **Availability detection, no hard dependency:** if `tailscale` is not in PATH
  / no output → empty list (noop fallback, Acceptance 11). Pattern:
  `JvmNetworkInterfaces` swallows enumeration errors into an empty list
  (`JvmNetworkInterfaces.kt:38`).
- Enumeration yields only **candidates**; a candidate becomes a real peer only after a health probe
  (`GET /v1/health` → `supportsCatalog == true`) and pairing (§9.1).
  The CGNAT classification (`Ipv4.isCgnat`, `Ipv4.kt:34`) can pre-filter candidates.

`NoopPeerDiscovery` as fallback (non-Tailscale environment, tests).

### 9.3 headless operation (`--headless`, F8)

`Main.kt` (`Main.kt:62`) gets an early branch **before** `application {}`:

```kotlin
if (args.contains(FLAG_HEADLESS)) {
    val ready = CompanionBootstrap.start()          // Server + Persistenz, kein Compose
    Runtime.getRuntime().addShutdownHook(Thread { ready.server.stop(); guard.release() })
    // NotificationPort = Noop (kein Tray); block main thread bis SIGTERM.
    Thread.currentThread().join()
    return
}
```

`CompanionBootstrap.start()` (`CompanionBootstrap.kt:47`) is already Compose-free and
returns `ReadyCompanion` — headless uses exactly the same path, just skips
`application {}`. The `SingleInstanceGuard` (`Main.kt:63`) runs before it, unchanged.
`PlatformModule.detect()` returns `NoopNotificationPort` in the headless case.
Autostart-capable: the same process, just without a window (pattern `--minimized`,
`Main.kt:68`).

## 10. Directory Layout

```
shared/src/main/kotlin/net/devemperor/dictate/shared/
├── protocol/
│   ├── Dtos.kt                        [EDIT]  + Catalog-DTOs, +supportsCatalog (E1)
│   ├── Validations.kt                 [EDIT]  + catalog*-Validations (E1)
│   ├── Endpoints.kt                   [EDIT]  + CATALOG*/Limits (E1)
│   └── ErrorEnvelope.kt               [EDIT]  + CATALOG_ENTITY_NOT_FOUND (E1)
├── catalog/                           [NEW]   VisibilityWire, SubscriptionModeWire (E1)
├── client/CatalogClient.kt            [NEW]   pull-Client, pure JVM (E1)
└── sync/CatalogSyncEngine.kt          [NEW]   hash-basierte Sync-Logik (E2)

companion/src/main/kotlin/.../companion/
├── domain/
│   ├── CatalogService.kt              [NEW]   Index/Entity/Credential + Audit (E1)
│   ├── CatalogAuditLog.kt             [NEW]   Schreib-/Lesepfad access_log (E1)
│   └── port/NotificationPort.kt       [NEW]   (E2)
├── server/routes/CatalogRoutes.kt     [NEW]   3 Routes im authenticated{} (E1)
├── data/
│   ├── Companion.sq                   [EDIT]  peers, subscriptions, entity-Tabellen, access_log
│   ├── migrations/2.sqm               [NEW]   v2→v3 (E1)
│   ├── SqlDelightCatalogRepository.kt [NEW]   Entitäts-/Peers-/Subs-Zugriff (E1)
│   └── CompanionDatabase.kt           [EDIT]  neue EnumColumnAdapter (E1)
├── catalog/                           [NEW]
│   ├── CatalogSyncScheduler.kt        [NEW]   Timer + Panel-/App-Start-Trigger (E2)
│   └── discovery/{PeerDiscovery,Tailscale,Noop}.kt [NEW] (E3)
├── platform/windows/AwtNotificationPort.kt [NEW]  Tray-Notification, LÖST Gap 6 (E2)
├── platform/fallback/NoopNotificationPort.kt [NEW] (E2)
├── ui/peers/{PeerListScreen,PeerDetailScreen,OfferScreen,PeerExplorerViewModel}.kt [NEW] (E3)
└── Main.kt                            [EDIT]  --headless-Zweig (E3)

app/src/main/java/net/devemperor/dictate/
├── peers/                             [NEW]   Bezieher-Client + SecretStore-Ablage (E2)
│   ├── CatalogSyncWorker.kt           [NEW]   WorkManager CoroutineWorker (E2)
│   ├── AndroidNotificationPort.kt     [NEW]   (E2)
│   └── ui/PeerExplorerFragment.kt     [NEW]   read-only Settings-Seite (E3)
└── (Room-Migration + entity-Tabellen: aus C2; E2 ergänzt Provenienz-Nutzung)
```

**File delta roughly:** ~6 new `:shared` files, ~14 new Companion files, ~4 new
app files, 1 SQLDelight migration, 1 new Android dependency (WorkManager).

## 11. Two-Peer Testability + E2E

The E2E pattern is `CompanionE2ETest` (`CompanionE2ETest.kt:49`): a **real**
`embeddedServer(CIO, port=0)`, a **real** `:shared` client over **real** HTTP on
127.0.0.1, only inserter/clock as fakes. For Block E, **two** containers +
servers are started (offerer A, fetcher B):

```kotlin
class CatalogE2ETest {
    // Anbieter A: SqlDelight in-memory, eigene shared-Entitäten + Credential im Fake-SecretStore.
    private val providerDb = CompanionDatabase.inMemory()
    private val providerServer = CompanionServer(providerContainer, hosts=listOf("127.0.0.1"), port=0)
    // Bezieher B: eigener Container; sein CatalogClient zeigt auf providerServer.boundPort().
    private val subscriberSecretStore = FakeSecretStore()
    // Pairing über die echten Routes (pairedCredentials()-Muster, CompanionE2ETest.kt:394),
    // dann catalogClient(credsToA).index()/entity()/credential().
}
```

E2E cases (following plan §8.5 + the `TruncatedResponseE2ETest` model):
- **share+fetch:** A shares a prompt → B.index() sees it → B.entity() → contentHash
  verified → local copy in B.
- **change:** A changes the prompt → root hash changes → B run pulls only the one,
  notification hook fires.
- **fork protection:** B forks the copy → A changes again → B run leaves the copy standing.
- **one-shot:** B fetches `ONE_SHOT` → A changes → B run ignores it.
- **offline:** `providerServer.stop()` → B run → `PeerUnreachable`, `last_contact_at`
  set, `last_success_at` untouched, no error.
- **credential isolation:** A.index()/entity() payloads do **not** contain the secret
  sentinel; only B.credential() delivers it → lands only in the `FakeSecretStore`; A has
  a `catalog_access_log` row.
- **auth:** B without credentials → 401 on all three routes.
- **malformed/truncated:** truncated catalog payload → `Malformed`, no sentinel in the
  envelope (`TruncatedResponseE2ETest` pattern).
- **determinism:** root hash equal on reordering, new on change (unit against
  `CatalogService.rootHash`).

## 12. Migration Plan (E1 → E2 → E3)

Chunk-oriented (implement-long-plan-v3); every step leaves `./gradlew build`
green.

1. **E1a — Wire (`:shared`).** Catalog DTOs + Validations + Endpoints + ErrorCode +
   `catalog/` enums + `CatalogClient`. Tests: `ProtocolCodecTest`/`ValidationsTest`
   cases per DTO. *Compile state:* `:shared` green, nothing consumes it yet.
2. **E1b — Schema (`companion/data`).** `Companion.sq` + `2.sqm` (peers,
   subscriptions, entity tables, access_log) + EnumColumnAdapter +
   `SqlDelightCatalogRepository`. Tests: parity tests per enum column,
   `SchemaMigratorTest` extension v2→v3, `verifyMigrations` green.
3. **E1c — Server (`companion`).** `CatalogService` + `CatalogRoutes` + wiring in
   `companionModule`/`CompanionContainer` + `supportsCatalog = true`. Tests:
   `CatalogE2ETest` (index/entity/credential/auth, one peer). *Compile state:*
   Companion serves the family; the fetcher side is still missing.
4. **E2a — Sync engine (`:shared`).** `CatalogSyncEngine` + `CatalogSyncOutcome` against
   repository ports. Tests: `CatalogSyncEngineTest` (NoChange/Updated/Verify-Fail/
   Fork-Skip/SourceRemoved) with `FakeCatalogTransport`.
5. **E2b — Companion fetcher.** `CatalogSyncScheduler` + `NotificationPort` +
   `AwtNotificationPort`/Noop + two-peer `CatalogE2ETest`. *Compile state:* two
   Companions sync.
6. **E2c — Android fetcher.** WorkManager dependency + `CatalogSyncWorker` +
   `AndroidNotificationPort` + channel; reads C2 Room tables + Android SecretStore.
   Tests: worker unit (Robolectric/Fake), notification hook.
7. **E3a — Peer Explorer (Companion).** `ui/peers/` screens + ViewModel
   (state matrix). Tests: `PeerExplorerViewModelTest`.
8. **E3b — Discovery + headless.** `PeerDiscovery` port + `TailscalePeerDiscovery`
   (fixture JSON) + `--headless` branch. Tests: discovery parse test, headless boot test.
9. **E3c — Android read-only explorer.** Settings page; "fetch from peer" tab
   (desktop). Tests: Robolectric smoke.

## 13. Testing Approach

- **Wire (E1a):** round-trip + ≥1 violation per DTO (`ProtocolCodecTest`/
  `ValidationsTest` pattern, `ProtocolCodecTest.kt:145`). Root-hash determinism as
  a unit against `CatalogService.rootHash`.
- **Parity (E1b):** per new enum column a `*CheckConstraintParityTest` following
  `OriginCheckConstraintParityTest.kt:1` (every enum value insertable; a
  non-enum value is rejected by CHECK). Enum-vocabulary equality Room(C2)↔
  SQLDelight enforced via the shared `:shared/catalog` enum.
- **Migration (E1b):** `SchemaMigratorTest` v2→v3 with a populated fixture DB;
  `verifyMigrations` (Gradle) holds `.sqm` against the schema snapshot.
- **E2E (E1c/E2b):** `CatalogE2ETest` (one peer and two peers), real Ktor servers,
  real `CatalogClient` — the cases from §11.
- **Sync engine (E2a):** `CatalogSyncEngineTest` with `FakeCatalogTransport` +
  in-memory repos — all edge cases from §6.4 as a parameterized suite; idempotency
  (second run = one call, no write).
- **UI (E3):** `PeerExplorerViewModelTest` (state matrix
  CURRENT/UPDATE/FORKED/STALE/SOURCE_REMOVED) following the `HistoryViewModelTest` pattern.
- **Discovery (E3):** parse test against fixture `tailscale status --json`; absence
  → empty list.
- **headless (E3):** boot test — `CompanionBootstrap.start()` returns `ReadyCompanion`
  without AWT/Compose (pattern `CompanionBootstrapTest`).
- **Pending:** the `GATEWAY` enum rejection is a C1 matter; on the E side: a pending test
  for the later `/v1/ai/*` family is **not** needed (F31 = only reserve the namespace,
  no code).

## 14. Decision Log

### D1 — Catalog family additive, `CatalogClient` as a second `:shared` client

**Trigger:** F32 — catalog on the existing wire stack. **Decision:** a new
`/v1/catalog*` family in `Dtos.kt`/`Validations.kt`/`Endpoints.kt`, a new
`CatalogClient` next to `DispatchClient` (not mixed into `DispatchClient` — SRP,
different error semantics). **Rationale:** exactly the ADR-0025 pattern (input-command was
the second family, catalog the third); `supportsCatalog` flag + 404 degradation
instead of a version bump. **Alternatives:** merge the family into `DispatchClient` — rejected,
the client would no longer be "the one dispatch view".

### D2 — Hash-based, not cursor-based

**Trigger:** F27 + relationship to ADR-0020. **Decision:** root hash (peer-wide) +
per-entity contentHash instead of a `(createdAt, id)` cursor. **Rationale:** entities are
small, rarely changed, individually identifiable; the hash additionally provides the
drift detection for free (locally edited? peer changed?). The session sync (ADR-0020)
stays cursor-based — two different data characteristics, two methods.

### D3 — Fork protection via sync-query selection

**Trigger:** F29. **Decision:** a fork sets `subscription_mode = NULL` + deletes the
`subscriptions` row in one transaction; the sync query selects only
`subscription_mode = 'SUBSCRIBE'` copies. **Rationale:** fork protection becomes a
property of the selection, not a special case in the write path — the sync cannot
"see" a forked copy at all (Acceptance 8, more robust than a check before every
write).

### D4 — Staleness derived, not persisted

**Trigger:** F33. **Decision:** `peers` stores `last_success_at`; the status
(`OK`/`STALE`/`UNREACHABLE`) is derived in the ViewModel from time + threshold.
**Rationale:** no persisted state that can drift from reality (analogue
to the ADR-0023 §2 materialization argument, here inverted: purely time-derived ⇒
do not materialize).

### D5 — Namespace `/v1/catalog*`, `/v1/ai*` only reserved

**Trigger:** F31. **Decision:** catalog endpoints under `/v1/catalog`; the later
gateway/proxy family gets `/v1/ai*` — only as a documented placeholder in the
ADR/Endpoints comment, **no** code. **Rationale:** additive extensibility without
speculative code (concept sketch §4 "Long-term server path").

### D6 — Tray notification via AWT `SystemTray`, one TrayIcon as SSoT

**Trigger:** plan Gap 6 (the Compose `Tray` cannot notify). **Decision:**
`AwtNotificationPort` with `TrayIcon.displayMessage`; recommendation to replace the Compose `Tray`
with a single AWT `TrayIcon` (menu + notification in one slot).
**Rationale:** the smallest working solution without a new dependency; avoids
double-slot competition. A spike, not an escalation case (§15 Gap 3).

### D7 — Table ownership decided: D3 creates the entity tables (plan D5.b)

**Trigger:** cross-spec decision by the plan architect 2026-07-20; §15
Gap 1 had delegated the ownership to the orchestrator sequencing.
**Decision:** **D3** creates `provider_configs`/`model_refs`/`prompts`/
`profiles`/`profile_prompts` COMPLETELY — following the DDL from §5.2 of this
spec **incl. provenance columns** (they stay NULL until E2), as migration
`3.sqm`. **E1** creates only `peers`/`subscriptions`/`catalog_access_log`
(migration `4.sqm`; D1a holds `2.sqm`). New depends_on edge **E1→D3**.
§5.2 of this spec remains the DDL SSoT; the D3 agent implements it.
**Rationale:** one migration owner per schema surface instead of a two-stage
migration ("whoever lands first") — deterministically plannable; the reverse direction
(D3→E1) would have created a block cycle D↔E, since E3 integrates
into the D3 editor screens. Enum parity with C1/C2 remains a mandatory gate (parity test, §13).
**Alternatives:** E1 creates everything (old fallback) — rejected due to a
cycle; two-stage migration — rejected due to non-determinism.

### D8 — Freshness pass 2026-07-20 (post-implementation, before archival)

**Trigger:** integration check after completion of Block A–E (finding `integ-1`,
green) — reconciliation against the built state. D7 had already fixed the migration
assignment (D3=`3.sqm`, E1=`4.sqm`, D1a=`2.sqm`) as-built; the following point about
enum placement was still open at the D7 time.
**As-built vs. spec — catalog wire enums consolidated:** §5.2/§5.3 reference
`net.devemperor.dictate.shared.catalog.VisibilityWire` and
`...catalog.SubscriptionModeWire` as separate catalog wire enums. What was built was
NO `shared.catalog` package; both live in the shared config-enum home
`shared/.../config/ConfigEnums.kt` as:
- `Visibility` (`PRIVATE`/`SHARED`) — the "Wire" name suffix is dropped (one home ⇒
  no domain/wire name collision).
- `SubscriptionMode` (`LOCAL`/`SUBSCRIBE`/`ONE_SHOT`) — the §5.2/§5.3 `NULL` case
  "local/forked" became the explicit `LOCAL` member: the columns are
  `NOT NULL DEFAULT 'LOCAL'` with `CHECK (... IN ('LOCAL','SUBSCRIBE','ONE_SHOT'))`
  (Companion.sq), a fork sets `subscription_mode = 'LOCAL'` instead of `NULL`. The
  CHECK vocabulary is thus updated relative to §5.2 ("`IS NULL OR IN ('SUBSCRIBE','ONE_SHOT')`").

`CatalogEntityKindWire` stayed in `shared.protocol` as specified. The enum parity
is pinned via `ConfigEntityCheckParityTest` / `CatalogAccessCheckParityTest`.
**Assessment:** no code impact; D5.a-doctrine-faithful (one SSoT enum module). Body
unchanged; this entry is the normative as-built correction of the
`shared.catalog.*Wire` package references in §3/§5.

## 15. Information Gaps

1. ~~**Companion entity tables — ownership D3 vs. E1**~~ — **closed
   2026-07-20 (§14 D7 / plan D5.b):** D3 creates everything (incl.
   provenance, `3.sqm`); E1 only `peers`/`subscriptions`/`catalog_access_log`
   (`4.sqm`); edge E1→D3. The DDL SSoT remains §5.2.
2. **WorkManager version against the Kotlin ceiling 2.1.20 (R4).** — **Dependency
   CONFIRMED per plan D5.f** (`androidx.work:work-runtime-ktx`, full
   background polling per D4.5); only the version choice remains open.
   *Owner:* E2c agent — check the candidate version against the
   ceiling before integration (ADR-0015), document in the chunk. *Fallback:* if a
   compatible `work-runtime` version is missing, a homegrown `AlarmManager`+`JobScheduler`
   solution (smaller surface) — but only after proven incompatibility.
3. **Compose `Tray` vs. AWT `TrayIcon` coexistence.** One tray slot, two possible
   owners. *Owner:* E2b agent (spike). *Fallback:* AWT `TrayIcon` as sole
   owner (menu + notification), the Compose `Tray` is dropped (D6 recommendation).
4. **`peerId` formation today.** F30 requires "designed to be public-key-capable", but v1 has
   no peer public keys yet (the device-secret model is symmetric, ADR-0017).
   *Owner:* E3b agent. *Fallback:* `peer_id` = opaque UUID generated on adding;
   the column wide enough for a later key fingerprint; no behavior depends today
   on the formation rule.
5. **Tailscale access: CLI vs. LocalAPI.** `tailscale status --json` (CLI, PATH-
   dependent) or the LocalAPI (Unix socket/named pipe). *Owner:* E3b agent —
   choose the smallest robust variant (probably CLI, since parsed the same
   cross-platform). *Fallback:* CLI; absence ⇒ empty list (no blocker).
6. **Credential `contentHash` definition.** The index hash of a `CREDENTIAL` is to be formed over
   the encrypted at-rest blob or a key fingerprint — the exact choice
   depends on the SecretStore format from B1. *Owner:* E1 agent in coordination with B1.
   *Fallback:* SHA-256 over a stable key fingerprint (e.g. hash of the plaintext
   with a fixed salt), so that a change is detectable without exposing the plaintext.

## 16. References

- **Plan:** `../desktop-companion-v1.md` §5 Block E (E1/E2/E3), §3 Decision Log
  (F7–F34), §7 sequencing, §8 test strategy, §9 R8, §10 Gap 6.
- **Concept:** `konzept-skizze.md` §4 (peer catalog, subscription sync, encryption,
  gateway path).
- **Binding ADRs:** `docs/decisions/0016-wire-protocol-typed-dtos-konform.md`
  (ProtocolCodec/Konform/additive versioning), `0017-client-server-roles-transport-pairing.md`
  (pairing, auth, Tailscale transport), `0020-lazy-cursor-sync.md` (sync model,
  deliberately NOT adopted for the catalog — D2), `0023-companion-bind-address.md`
  (Tailscale detection `Ipv4.isCgnat`, materialization argument), `0025-input-command-protocol.md`
  (additive endpoint family — the pattern).
- **Plan-scoped ADR:** `adrs/adr-peer-catalog.md` (to be created in A1; this spec is
  its implementation basis) — cross-link reciprocally.
- **Key code (Wire):** `shared/.../protocol/Dtos.kt`, `Validations.kt`,
  `Endpoints.kt`, `ProtocolCodec.kt:50`, `ErrorEnvelope.kt`; `client/DispatchClient.kt:118,200`
  (404 and error classification as a pattern); `auth/PairingUri.kt`, `Secrets.kt:44`,
  `AuthHeaders.kt`.
- **Key code (Companion):** `server/CompanionServer.kt:96-111`,
  `server/plugins/AuthPlugin.kt:25,58`, `server/ProtocolCalls.kt:106,126`,
  `server/routes/SyncRoutes.kt` (route pattern), `domain/HealthService.kt`,
  `domain/PairingService.kt`, `data/Companion.sq`, `data/SchemaMigrator.kt`,
  `data/CompanionDatabase.kt:39`, `data/OriginCheckConstraintParityTest.kt`,
  `CompanionContainer.kt:67`, `CompanionBootstrap.kt:47`, `Main.kt:62`,
  `platform/JvmNetworkInterfaces.kt:38`, `domain/net/Ipv4.kt:34`,
  `domain/net/AddressKind.kt`.
- **Key code (test patterns):** `server/CompanionE2ETest.kt` (real server +
  client), `server/TruncatedResponseE2ETest.kt` (malformed payloads),
  `ui/history/HistoryViewModelTest.kt` (ViewModel pattern).
- **Android:** `core/PipelineNotificationCoordinator.kt:248` (NotificationCompat),
  `core/NotificationPermissionPolicy.kt`, `rewording/PromptImportExport.java`
  (v1/v2 import as C1 context).
- **Conventions:** `docs/DATABASE-PATTERNS.md` (Double-Enum),
  `~/.claude/snippets/test-first-patterns.md`.
