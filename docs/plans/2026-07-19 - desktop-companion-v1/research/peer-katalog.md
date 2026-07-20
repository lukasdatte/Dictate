---
date: 2026-07-19
author: Lukas + Claude (Opus, groundwork agent — Spec-Recherche Block E)
status: Spec — programmer-ready, no invented detail
context: Implementer-ready Spec für Block E des Plans desktop-companion-v1 — Peer-Katalog-Protokoll, hash-basierter Abo-Sync, Peer Explorer, Discovery und headless Peer-Betrieb.
related-plan: ../desktop-companion-v1.md
related-adrs: ADR-0016, ADR-0017, ADR-0020, ADR-0023, ADR-0025 (bindend); adr-peer-catalog (plan-scoped, zu erstellen in A1); adr-secret-store, adr-config-entity-model (Voraussetzungen aus Block B/C)
---

# Block E — Peer-Katalog, Abo-Sync, Peer Explorer, Discovery, headless Betrieb

Diese Spec beschreibt den vierten und letzten Fach-Block des desktop-companion-v1-
Vorhabens: das Teilen und Beziehen von Konfigurations-Entitäten
(`ProviderConfig`/`ModelRef`/`Prompt`/`Profile`/`ApiCredential`) zwischen Peers im
Tailnet. Sie baut **additiv** auf dem bestehenden Wire-Stack (ADR-0016/0017/0025)
auf — kein Protocol-Version-Bump, ein neues `supportsCatalog`-Health-Flag, dieselbe
`ProtocolCodec`-Tür, dasselbe Pairing-Modell. Block E setzt Block C1 (Entitäten +
kanonische v3-Serialisierung in `:shared`) und Block B1 (SecretStore-Port) voraus;
die Sequenzierung ist `C1 → E1 → E2 → E3`, `B1 → E2` (siehe Plan §7).

Adressat ist der Implementierungs-Agent, der E1/E2/E3 baut, ohne die Konzept-Session
gesehen zu haben. Alle Datei-Pointer sind `Pfad:Zeile` gegen den Stand im Worktree
`feature/desktop-companion-v1`.

## Table of Contents

- [Glossar](#glossar)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Wire-Katalog-Familie (E1, `:shared`)](#3-wire-katalog-familie-e1-shared)
- [§4 Companion-Server-Seite (E1)](#4-companion-server-seite-e1)
- [§5 SQLDelight-Schema — peers, subscriptions, Entitäts-Spiegel (E1)](#5-sqldelight-schema--peers-subscriptions-entitäts-spiegel-e1)
- [§6 Sync-Engine (E2)](#6-sync-engine-e2)
- [§7 Benachrichtigung — Companion-Tray + Android-Notification (E2)](#7-benachrichtigung--companion-tray--android-notification-e2)
- [§8 Peer Explorer UI (E3)](#8-peer-explorer-ui-e3)
- [§9 Discovery + headless Betrieb (E3)](#9-discovery--headless-betrieb-e3)
- [§10 Directory Layout](#10-directory-layout)
- [§11 Zwei-Peer-Testbarkeit + E2E](#11-zwei-peer-testbarkeit--e2e)
- [§12 Migration Plan (E1 → E2 → E3)](#12-migration-plan-e1--e2--e3)
- [§13 Testing Approach](#13-testing-approach)
- [§14 Decision Log](#14-decision-log)
- [§15 Information Gaps](#15-information-gaps)
- [§16 References](#16-references)

## Glossar

**Protokoll & Wire**
- **Katalog-Familie** — die neue additive Payload-Familie (`/v1/catalog*`) auf dem
  bestehenden Wire-Stack. Drei Endpoints (Index, Entity, Credential) plus ein
  Health-Flag. Kein Version-Bump (§3, ADR-0025-Muster).
- **Root-Hash** — SHA-256 über die sortierte Liste der `(id, contentHash)`-Paare
  aller `visibility = SHARED`-Entitäten eines Peers. Ein einziger GET beantwortet
  „hat sich überhaupt etwas geändert?" (§6).
- **contentHash** — SHA-256 über die **kanonische** Serialisierung einer einzelnen
  Entität (definiert in C1, `:shared`). Zugleich Sync-Watermark und Drift-Detektor.
- **Konform-Validierung** — die `Validation<T>` neben jedem DTO (ADR-0016). Jeder
  neue Katalog-DTO bringt seine mit, sonst wird der Wert nie geprüft.

**Abo & Bezug**
- **Peer** — jeder Teilnehmer, der die Katalog-Familie spricht. Anbieten kann nur,
  wer einen Server betreibt (ein Companion oder ein `--headless`-Hub); Android ist
  reiner Bezieher (F7/F25).
- **Subscription** — eine bezogene lokale Kopie einer Peer-Entität. Zwei Modi:
  `SUBSCRIBE` (Sync hält sie per Hash-Abgleich aktuell) und `ONE_SHOT` (einmalige
  eingefrorene Kopie, kein Sync).
- **Fork / Abkoppeln** — eine bezogene Kopie ist read-only; „Bearbeiten" koppelt sie
  explizit ab (`subscription_mode = NULL`), macht sie lokal editierbar, und Sync
  überschreibt sie nie mehr (F29). Die Herkunft (`source_peer_id`/`source_hash`)
  bleibt als Anzeige-Metadatum erhalten (F14).
- **visibility** — pro Entität `PRIVATE | SHARED`. Nur `SHARED`-Entitäten erscheinen
  im Katalog-Index eines Peers.
- **Staleness** — die Zeit seit dem letzten *erfolgreichen* Kontakt mit einem Peer.
  Überschreitet sie eine Schwelle, ist der Peer „stale"; ein nicht erreichbarer Peer
  ist kein Fehler, sondern eine stille Anzeige (F33).

**Sicherheit**
- **Envelope-Encryption** — der anbietende Peer hält das Credential in seinem
  SecretStore, liefert es beim Beziehen über den TLS-Kanal aus, der Empfänger legt
  es sofort in seinen eigenen SecretStore. Kein Klartext-Key auf Platte (F12).
- **Pairing** — dasselbe One-Time-Token→Device-Secret-Modell aus ADR-0017, jetzt
  auch für Peer↔Peer: der beziehende Peer ist HTTP-Client des anbietenden (F10).

**Discovery & Betrieb**
- **PeerDiscovery** — der Port, der Peer-Kandidaten findet. Zwei Wege: manuelle
  Adresse/QR-Eingabe UND Tailscale-Enumeration (`tailscale status --json`, F26).
- **headless Peer** — der Companion mit `--headless`-Flag: Server läuft, Compose-UI
  nicht. Deployment-Variante desselben Codes, kein eigenes Modul (F8).

> **Subscription ≠ Fork ≠ ONE_SHOT.** Eine *Subscription* (`SUBSCRIBE`) ist eine
> live gehaltene Kopie — Sync aktualisiert sie. *ONE_SHOT* ist eine eingefrorene
> Kopie ohne Sync-Bindung, aber mit Herkunfts-Anzeige. Ein *Fork* ist eine ehemals
> abonnierte Kopie, die der Nutzer editiert hat — Sync fasst sie nie wieder an. Alle
> drei sind Zeilen in derselben Entitäts-Tabelle; sie unterscheiden sich nur im
> `subscription_mode` (`SUBSCRIBE` / `ONE_SHOT` / `NULL`).

> **Root-Hash ≠ contentHash.** Der *Root-Hash* ist ein Peer-weiter Fingerabdruck
> über *alle* geteilten Entitäten (billiges „hat sich irgendwas geändert?"). Der
> *contentHash* ist der Fingerabdruck *einer* Entität (welche genau hat sich
> geändert, und ist meine lokale Kopie abgekoppelt?).

## 1. Vision and Motivation

### 1.1 Warum dieser Block existiert

Nach Block C sind Provider/Modelle/Prompts/Profile teilbare, versionierte Entitäten
mit kanonischer Serialisierung und `contentHash` (C1). Sie liegen aber isoliert auf
je einem Gerät. Block E schließt den Kreis: ein Nutzer kuratiert ein Prompt- oder
Profil-Set auf einem Companion, macht es `SHARED`, und andere Peers (weitere
Companions, ein headless Hub, das Phone) beziehen es und halten es automatisch
aktuell. Das ist die „Verteilungs-Architektur" aus der Konzept-Skizze §4.

### 1.2 Was der Block löst

- **Kein manuelles Nachziehen von Config.** Ein geändertes Profil beim Anbieter
  landet beim Bezieher automatisch (Poll + Root-Hash + Notification).
- **Key-Teilen ohne Klartext.** API-Keys reisen envelope-verschlüsselt (TLS +
  beidseitiger SecretStore, F12) — sie erscheinen nie im Katalog-Index.
- **Provenienz ist sichtbar.** Der Peer Explorer zeigt, was von wem bezogen wurde,
  in welchem Zustand (aktuell/Update/abgekoppelt/stale) und — umgekehrt — wer was
  vom eigenen Peer bezieht (F34).
- **Deployment als Hub.** Ein `--headless`-Companion auf einer VM ist ein
  Dauer-Anbieter, ohne dass ein zweites System/Protokoll nötig wird (F8).

### 1.3 Discarded Alternatives

- **Push-Sync / Back-Channel.** Verworfen (F9): der bestehende Stack hat bewusst
  keinen Back-Channel (ADR-0017). Pull-only mit Root-Hash-Polling ist billig (ein
  GET, meist ohne Folge-Requests) und konsistent mit ADR-0020.
- **Cursor-Sync wie ADR-0020 wiederverwenden.** Verworfen: Entitäten sind klein,
  selten geändert, einzeln identifizierbar — Hashes liefern zusätzlich die
  Drift-Erkennung (lokal editiert? Peer geändert?) gratis. Der Session-Sync bleibt
  cursor-basiert; der Katalog-Sync ist hash-basiert (D2 im Decision Log).
- **Zero-Knowledge-Key-Sharing.** Verworfen (F12) zugunsten Envelope-Encryption —
  der Peer-Betreiber ist im Self-Hosted-Kontext ohnehin vertrauenswürdig.
  Zero-Knowledge bleibt dokumentierte spätere Härtung.
- **Eigenes Peer-Protokoll / eigener Codec.** Verworfen (F32): die Familie sitzt auf
  `ProtocolCodec` + Konform + `ErrorEnvelope`, exakt wie die Input-Command-Familie
  (ADR-0025). Zwei Codecs wären zwei Wahrheiten.

### 1.4 Was der Block konkret bringt

1. Ein additiver Endpoint-Zweig, der ältere Peers sauber degradieren lässt (404 +
   `supportsCatalog = false`).
2. Idempotenter Sync, der bei Unverändert genau einen HTTP-Call kostet.
3. Fork-Schutz per Konstruktion: eine abgekoppelte Kopie ist im `subscription_mode`
   nicht mehr `SUBSCRIBE`, und die Sync-Query greift sie nie an.
4. Ein Audit-Log-Eintrag pro Credential-Auslieferung (R8-Mitigation).

## 1a. Architecture Walkthrough

### 1a.0 ASCII-Stack (Katalog-Fluss zwischen zwei Peers)

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

### 1a.1 Schicht `:shared` — Wire-Katalog-Familie (E1)

- **Purpose:** SSoT der Katalog-Payloads; von beiden Peer-Rollen verbatim genutzt.
- **File:** `shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/`
  (`Dtos.kt:238` Ende — neue DTOs anhängen; `Validations.kt:140`; `Endpoints.kt:23`;
  `ErrorEnvelope.kt:41` — neue `ErrorCode`s) + neuer `CatalogClient.kt` neben
  `client/DispatchClient.kt:1`.
- **Contract:** `ProtocolCodec.decode/encode` bleibt die einzige Tür
  (`ProtocolCodec.kt:50`); jeder neue DTO trägt `protocolVersion` zuerst und hat eine
  co-lokierte `Validation<T>`.

### 1a.2 Schicht Companion-Server — CatalogService + Routes (E1)

- **Purpose:** die drei Routes hinter der bestehenden Auth mounten; Angebot filtern;
  Credential separat autorisiert ausliefern; Audit-Zeile schreiben.
- **File:** `companion/.../server/routes/CatalogRoutes.kt` [NEW],
  `companion/.../domain/CatalogService.kt` [NEW]; verdrahtet in
  `CompanionServer.kt:104` (`authenticated{ … }`-Block) und `CompanionContainer.kt:67`.

### 1a.3 Schicht Bezieher — Sync-Engine + Persistenz (E2)

- **Purpose:** Root-Hash-Vergleich, Diff, Pull, Verify, Fork-Schutz, Staleness,
  Notification. Zwei Scheduler-Hosts (Companion-Timer, Android-WorkManager), ein
  Engine-Kern in `:shared` (pure JVM) plus plattformspezifische Ports.
- **File:** `shared/.../sync/CatalogSyncEngine.kt` [NEW] (analog `sync/SyncClient.kt`);
  `companion/.../catalog/` [NEW]; `app/.../peers/` [NEW].

### 1a.4 Schicht UI — Peer Explorer + Discovery (E3)

- **Purpose:** Bezugs- und Angebots-Sicht (Companion Compose), read-only-Variante
  (Android Settings); Discovery-Port mit Tailscale-Impl; `--headless`-Einstieg.
- **File:** `companion/.../ui/peers/` [NEW], `companion/.../catalog/discovery/` [NEW],
  `companion/.../Main.kt:62` (`--headless`-Zweig); `app/.../peers/ui/` [NEW].

### 1a.5 Read-this-before-implementing Checklist

- [ ] Jeder neue Katalog-DTO: `@Serializable` in `Dtos.kt` + co-lokierte
  `Validation<T>` in `Validations.kt` + Round-Trip- und ≥1-Ablehnungs-Test im selben
  Commit (ADR-0016 Failure-Mode). Enums als **eigene** Wire-Enums (Muster
  `SessionOriginWire`, `Dtos.kt:63`), nicht die internen Entitäts-Enums.
- [ ] `supportsCatalog` **defaulted `false`** in `HealthResponse` — kein
  Version-Bump (ADR-0025, `Dtos.kt:237`-Muster).
- [ ] Der Credential-Secret-Wert erscheint **nie** im Index und **nie** in einem
  `CatalogEntityResponse` — nur im eigenen, einzeln autorisierten
  `GET /v1/catalog/credential/{id}` (R8, §4.3).
- [ ] Neue SQLDelight-Finite-Set-Spalten: Double-Enum (Kotlin-Enum + SQL-CHECK) +
  `EnumColumnAdapter`; Parity-Test nach `OriginCheckConstraintParityTest`-Muster
  (`OriginCheckConstraintParityTest.kt:1`).
- [ ] `ErrorEnvelope.message`/`details` tragen nie einen Secret- oder Payload-Wert
  (Redaktions-Kontrakt, `ErrorEnvelope.kt:54`); Katalog-Fehler nennen nur Id/Limit.
- [ ] Bezogene Credentials landen **sofort** im SecretStore (B1), nie in einer
  Klartext-Spalte der Entitäts-Tabelle.
- [ ] Sync schreibt eine `SUBSCRIBE`-Kopie nur, wenn ihr contentHash gegen den
  Index-Wert **und** gegen die Neuberechnung aus der kanonischen Serialisierung
  stimmt (Verify-vor-Write, §6.3).

## 2. Acceptance Criteria

1. **Additivität:** `HealthResponse.supportsCatalog` ist `false`-defaulted; ein Peer
   ohne Katalog-Routes antwortet 404, und der `CatalogClient` mappt das auf
   `CatalogError.EndpointMissing` (Muster `DispatchClient.input()`,
   `DispatchClient.kt:118`). `ProtocolVersion.CURRENT` bleibt `1`.
2. **Konform-Vollständigkeit:** Jeder Katalog-DTO hat eine `Validation<T>`; ein
   `ValidationsTest`-Fall pro DTO prüft mindestens eine Verletzung.
3. **Root-Hash-Determinismus:** Gleicher Entitäten-Satz ⇒ gleicher Root-Hash;
   Umordnung der Entitäten ⇒ gleicher Root-Hash; genau eine Entitäts-Änderung ⇒
   neuer Root-Hash (Snapshot-/Determinismus-Test).
4. **Auth-Parität:** Katalog-Index, -Entity und -Credential sind nur mit gültigem
   Device-Secret erreichbar (401 sonst, wie `CompanionE2ETest.health_withoutPairing`,
   `CompanionE2ETest.kt:352`).
5. **Credential-Isolation:** Der Secret-Wert erscheint in keinem Index und keinem
   `CatalogEntityResponse`; nur der Credential-Call liefert ihn, und jede Auslieferung
   schreibt eine Audit-Zeile (Test prüft Index-/Entity-Payload auf Abwesenheit des
   Sentinels + Vorhandensein der Audit-Zeile).
6. **Sync-Idempotenz:** Zweiter Lauf ohne Peer-Änderung = ein HTTP-Call (Index), kein
   Entity-Fetch, kein Schreibvorgang, keine Notification.
7. **Update-Erkennung:** Anbieter ändert einen Prompt ⇒ Bezieher-Lauf erkennt via
   Root-Hash, zieht nur die geänderte Entität, verifiziert den contentHash,
   aktualisiert die lokale Kopie und feuert den Notification-Hook.
8. **Fork-Schutz:** Eine abgekoppelte (`subscription_mode = NULL`) Kopie wird von
   keinem Sync-Lauf überschrieben; `ONE_SHOT` bleibt ebenfalls stehen.
9. **Offline-Toleranz:** Ein nicht erreichbarer Peer erzeugt keinen Fehler, sondern
   aktualisiert `last_contact_at` und wird nach der Schwelle als `STALE` angezeigt.
10. **Zwei-Peer-E2E:** Zwei In-Process-`embeddedServer(CIO, port=0)`-Instanzen teilen
    Prompt/Profil/ModelRef/Credential über den echten `CatalogClient`; Credential
    landet beim Empfänger ausschließlich im (Fake-)SecretStore (E2E nach
    `CompanionE2ETest`-Muster).
11. **Discovery-Port:** `TailscalePeerDiscovery` parst Fixture-`tailscale status --json`
    zu Kandidaten; Abwesenheit der CLI ⇒ leere Liste, kein Crash (Noop-Fallback).
12. **headless-Boot:** `--headless` startet Server + Persistenz ohne Compose-Window
    (Boot-Test: `CompanionBootstrap.start()` liefert `ReadyCompanion`, kein AWT/Skiko).
13. **Explorer-Zustandsmatrix:** ViewModel-Tests decken `CURRENT` / `UPDATE_AVAILABLE`
    / `FORKED` / `STALE` / `SOURCE_REMOVED` ab.

## 3. Wire-Katalog-Familie (E1, `:shared`)

### 3.1 Endpoints (`Endpoints.kt`)

Anhängen nach `INPUT` (`Endpoints.kt:23`). Namensraum so geschnitten, dass eine
spätere `/v1/ai/*`-Familie additiv daneben passt (F31, Decision Log D5).

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

### 3.2 DTOs (`Dtos.kt`, anhängen nach `HealthResponse`)

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

`HealthResponse` (`Dtos.kt:223`) bekommt ein Feld:

```kotlin
/**
 * Whether this companion serves the /v1/catalog family. Additive, defaulted false so an
 * older peer's health response decodes as "no support" under ignoreUnknownKeys — the
 * beziehende Peer reads it during discovery/health and can skip a peer that cannot offer.
 */
val supportsCatalog: Boolean = false,
```

### 3.3 Validations (`Validations.kt`, anhängen)

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

### 3.4 Neue ErrorCodes (`ErrorEnvelope.kt`)

```kotlin
/** 404 — the requested entity is unknown OR not shared. One code for both, on purpose:
 *  telling them apart would leak which private entities exist (parallel to the uniform 401). */
CATALOG_ENTITY_NOT_FOUND,
```

`respondEnvelope` (`ProtocolCalls.kt:126`) und die `CompanionException`-Hierarchie
(`domain/DomainErrors.kt`) bekommen die passende Exception + StatusPages-Mapping
(Muster: bestehende `ValidationException` → 400). Der `CatalogClient` mappt
`CATALOG_ENTITY_NOT_FOUND` auf `CatalogError.EntityGone` (§6.4, gelöschte Entität
beim Anbieter).

### 3.5 CatalogClient (`shared/.../client/CatalogClient.kt` [NEW])

Parallel zu `DispatchClient` (`DispatchClient.kt:1`), **pure JVM** — von Companion
(Bezieher) UND Android (Bezieher) verbatim genutzt. Reused: `DispatchTransport`,
`OkHttpDispatchTransport`, `Credentials`, `AuthHeaders`, `ProtocolCodec`. Drei
`read`-Calls (GET, bodyless; Version im Header via `AuthHeaders.forDevice`):

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
> Der `CatalogClient` gibt `DispatchResult<T>`/`DispatchError` zurück (wiederverwendet
> aus `DispatchError.kt:1`) — 404 → `EndpointMissing` (alter Peer) vs.
> `CATALOG_ENTITY_NOT_FOUND` im Envelope → eigener Fehlerpfad. Die Klassifikation
> folgt exakt `DispatchClient.classifyError` (`DispatchClient.kt:200`); eine
> `CatalogError`-Erweiterung um `EntityGone` genügt.

## 4. Companion-Server-Seite (E1)

### 4.1 Routing-Integration

`CatalogRoutes` werden im `authenticated { … }`-Block von `companionModule`
(`CompanionServer.kt:104-109`) gemountet — genau wie `dispatchRoutes`/`inputRoutes`:

```kotlin
authenticated(container.authService) {
    dispatchRoutes(container.dispatchService)
    inputRoutes(container.inputCommandService)
    syncRoutes(container.syncService)
    healthRoutes(container.healthService)
    catalogRoutes(container.catalogService)   // NEU
}
```

`catalogRoutes` liest den `{id}`-Pfadparameter, validiert ihn gegen
`ENTITY_ID_PATTERN`, und antwortet über `respondProtocol` (`ProtocolCalls.kt:106`).
Der authentifizierte Peer ist über `call.device` (`AuthPlugin.kt:25`) verfügbar —
das ist die Audit-Identität für die Credential-Zeile.

### 4.2 CatalogService (`companion/.../domain/CatalogService.kt` [NEW])

Verantwortung: Index bauen (nur `visibility = SHARED`), Root-Hash berechnen, einzelne
Entität als kanonischen Payload liefern, Credential separat + Audit. Konstruktor-
verdrahtet in `CompanionContainer` (`CompanionContainer.kt:67`, neben
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

**Root-Hash (deterministisch, C1-Kanonik-abhängig):**

```kotlin
fun rootHash(entries: List<CatalogEntry>): String =
    Secrets.sha256(
        entries.sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.contentHash}" }
    )
```

`Secrets.sha256` (`Secrets.kt:44`) ist bereits vorhanden und pure. Die Sortierung
nach `id` macht den Hash unabhängig von der DB-Reihenfolge (Acceptance 3). Der
`contentHash` selbst kommt aus C1 (`:shared`), gespeichert in der Entitäts-Spalte —
der CatalogService berechnet ihn nicht neu, er liest ihn.

### 4.3 Credential-Auslieferung + Audit

Der Index trägt für eine `CREDENTIAL`-Entität nur `label`/`provider` und einen
`contentHash` über den **verschlüsselten At-Rest-Blob bzw. Key-Fingerprint** — nie
den Klartext (F12). Erst `GET /v1/catalog/credential/{id}`:

1. prüft, dass die Entität existiert und `visibility = SHARED` ist (sonst 404),
2. holt den Klartext aus dem eigenen `SecretStore` (B1),
3. schreibt eine Audit-Zeile `catalog_access_log(peer_device_id, entity_id, at)`,
4. antwortet mit `CatalogCredentialResponse` über TLS.

> [!CAUTION]
> Der Secret-Wert darf **nie** in `ErrorEnvelope`, Logs oder den Index gelangen
> (`ErrorEnvelope.kt:54`, Redaktions-Kontrakt). Konform-Constraints auf `secret`
> tragen kein `{value}`. Der Audit-Log ist die R8-Mitigation und Grundlage der
> Angebots-Sicht „wer hat wann was abgeholt" (§8.2).

### 4.4 HealthService

`HealthService` (`HealthService.kt:1`) bekommt `supportsCatalog = true` fest gesetzt
(der Companion serviert die Familie immer, sobald E1 gebaut ist) — analog
`supportsInputCommands = inputPerformer.available`. Da der Companion die Routes
bedingungslos mountet, ist es ein konstantes `true`; Android setzt es in seinem
Health-Response gar nicht (Android hat keinen Server).

## 5. SQLDelight-Schema — peers, subscriptions, Entitäts-Spiegel (E1)

Alle neuen Tabellen in `companion/.../db/Companion.sq` (`Companion.sq:1`), Migration
als `migrations/2.sqm` (v2 → v3, Muster `1.sqm`). Double-Enum durchgängig:
Kotlin-Enum-Spalte (`AS <Enum>`) + SQL-CHECK; `EnumColumnAdapter` in
`CompanionDatabase.build` (`CompanionDatabase.kt:39`). Parity-Test pro Enum-Spalte
nach `OriginCheckConstraintParityTest`-Muster.

> [!IMPORTANT]
> **Schema-Eigentums-Koordination (siehe §15 Gap 1):** Die Entitäts-Tabellen
> (`provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts`) sind der
> Companion-Spiegel der C2-Android-Struktur und werden von D3 (lokale Verwaltung)
> **und** E1 (bezogene Kopien) gebraucht. Diese Spec definiert das vollständige
> Schema inkl. Provenienz-Spalten; wenn D3 vor E1 landet, führt D3 die Grundtabellen
> ein und E1 ergänzt die Provenienz-Spalten per Migration. Die Enum-Vokabulare
> müssen mit C1/C2 identisch sein (Parity-Test, §13).

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

Kein `status`-Enum in der Tabelle: der Status (`OK`/`STALE`/`UNREACHABLE`) wird aus
`last_success_at` + Schwelle **abgeleitet** (Domain, testbar), nicht persistiert —
so kann keine Drift zwischen gespeichertem und tatsächlichem Zustand entstehen
(dasselbe Materialisierungs-Argument wie ADR-0023 §2, invertiert: hier ist der
Zustand rein zeitabgeleitet, also nicht materialisieren).

### 5.2 Entitäts-Tabellen (Spiegel C2) + Provenienz

Beispielhaft `prompts`; `provider_configs`/`model_refs`/`profiles` analog mit ihren
C1-Feldern. Die Provenienz-/Sync-Spalten sind auf **jeder** Entitäts-Tabelle gleich:

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

`ON DELETE SET NULL`: löscht der Nutzer einen Peer, werden seine bezogenen Kopien
lokal — nicht mitgelöscht (die Kopie gehört jetzt dem Empfänger). `visibility` und
`subscription_mode` sind **eigene Wire-Enums in `:shared`** (Package
`shared/.../catalog/`), damit Android-Room (C2) und Companion-SQLDelight dieselbe
Quelle teilen (Parity per Konstruktion, D3-Argument aus ADR-0016).

### 5.3 subscriptions

Die `subscriptions`-Tabelle ist der **Sync-Journal**: welche lokale Entität wird von
welchem Peer gespiegelt, in welchem Modus, mit welchem Stand.

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
> Redundanz `entity.subscription_mode` vs. `subscriptions.mode` ist gewollt und
> zweiseitig gelesen: Der Sync fragt „welche lokalen Kopien soll ich anfassen?" —
> das ist eine Query gegen `subscriptions` JOIN `entity WHERE subscription_mode IS
> NOT NULL`. Ein **Fork** setzt `entity.subscription_mode = NULL` **und** löscht die
> `subscriptions`-Zeile in einer Transaktion; danach greift kein Lauf die Kopie mehr
> an (Acceptance 8). SSoT bleibt `subscriptions` für „gibt es eine Bindung", die
> Entitäts-Spalte spiegelt es für die schnelle Sync-Query.

### 5.4 catalog_access_log (Angebots-Sicht + Audit, R8)

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

Credential-Auslieferungen werden **immer** geloggt (Sicherheit); Entity-Fetches
optional (für „letzte Abholung" in der Angebots-Sicht, §8.2). Minimalismus: keine
Payload, nur Wer/Was/Wann.

## 6. Sync-Engine (E2)

### 6.1 Kern (`shared/.../sync/CatalogSyncEngine.kt` [NEW], pure JVM)

Analog `SyncClient` (`SyncClient.kt:74`) — pure, testbar gegen einen
`FakeCatalogTransport`, plattformneutral, von Companion und Android geteilt. Der
Ablauf pro Peer mit ≥1 aktiven `SUBSCRIBE`-Subscription:

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

Ergebnis-Typ analog `SyncOutcome` (`DispatchError.kt`-KDoc verweist darauf): eine
`sealed class CatalogSyncOutcome { NoChange, Updated(list), PartialVerifyFailure(list),
Stale, PeerUnreachable, EndpointMissing }`.

### 6.2 Pull einer geänderten Entität

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

Für `CREDENTIAL`: `resp = catalogClient.credential(id)` → `secret` **sofort** in den
SecretStore (`secretStore.put(SecretRef(entityId), resp.secret.toByteArray())`), die
Entitäts-Zeile trägt nur `provider`/`label`/`content_hash` (Fingerprint), nie den
Klartext (Acceptance 5, F12).

### 6.3 Verify-vor-Write (zwei Hash-Prüfungen)

Beide Prüfungen sind Pflicht: die erste fängt einen lügenden/kaputten Anbieter
(Index sagt X, Payload ist Y), die zweite fängt eine Kanonik-Drift zwischen
Anbieter- und Bezieher-C1 (unterschiedliche Serialisierungs-Version). Bei
Fehlschlag bleibt die lokale Kopie unverändert und der Lauf meldet
`PartialVerifyFailure` — kein unverifizierter Datensatz landet je in der DB
(Acceptance 7 Härtung, R-Analogon zu `SyncClient`-Stall-Guard).

### 6.4 Edge-Fälle

| Fall | Verhalten |
|---|---|
| Peer nicht erreichbar | `last_contact_at = now`, `last_success_at` unberührt → Staleness-Anzeige, kein Fehler-Spam (F33, Acceptance 9) |
| Root-Hash gleich | ein GET, keine Writes, keine Notification (Acceptance 6) |
| Hash-Mismatch (Index↔Payload oder Payload↔Recompute) | Kopie unverändert, `PartialVerifyFailure`, Log-Zeile (§6.3) |
| Entität beim Anbieter gelöscht (`entry == null` / 404) | lokale Kopie **behalten**, Subscription auf `SOURCE_REMOVED` markieren, Notification — nie auto-löschen (destruktiv) |
| Lokale Kopie geforkt (`subscription_mode = NULL`) | Sync-Query selektiert sie gar nicht → nie überschrieben (Acceptance 8) |
| `ONE_SHOT`-Kopie | keine `SUBSCRIBE`-Subscription → nie im Sync-Lauf (Acceptance 8) |
| Credential-Fetch schlägt fehl, Entity ok | Entität aktualisiert, Credential-Subscription bleibt auf altem Stand, Warnung |

### 6.5 Scheduler

- **Companion:** ein `CatalogSyncScheduler` mit `java.util.Timer`/Coroutine-Ticker,
  Intervall aus `CompanionSettings` (`CompanionSettings.kt`), plus Trigger bei
  Panel-/Fenster-Öffnen und bei App-Start (Muster: ADR-0020 App-Start-Trigger).
  Läuft auf einem Hintergrund-Dispatcher, best-effort, wirft nie in den UI-Thread.
- **Android:** `CatalogSyncWorker : CoroutineWorker` unter **WorkManager**
  (`androidx.work:work-runtime-ktx` — NEUE Dependency, Kotlin-Ceiling-Check R4, §15
  Gap 2). `PeriodicWorkRequest` (min. 15 min, Intervall aus `Pref`) +
  einmaliger `OneTimeWorkRequest` bei App-Start (D4.5). Der Worker instanziiert den
  geteilten `CatalogSyncEngine` gegen die C2-Room-Tabellen + Android-SecretStore.

## 7. Benachrichtigung — Companion-Tray + Android-Notification (E2)

Ein `NotificationPort` (fun interface, `domain/port/`) trennt „was melden" von „wie
melden". Der Sync ruft `notificationPort.notify(SyncNotification(peerName, changes))`.

### 7.1 Companion-Tray (LÖST Plan-Gap 6)

> [!IMPORTANT]
> Die Compose-Desktop-`Tray` (`Main.kt:129`) bietet **keine** Notification-API. Der
> Bestand hat keinen `SystemTray`/`TrayIcon.displayMessage`-Code (Grep bestätigt).
> Die kleinste funktionierende Lösung (Plan §10 Gap 6): AWT `java.awt.SystemTray` +
> `TrayIcon.displayMessage(caption, text, TrayIcon.MessageType.INFO)`.

`AwtNotificationPort` [NEW] in `platform/windows/` (funktioniert auch unter Linux mit
unterstütztem Tray):

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
Muster `NoopTextInserter` (`platform/fallback/NoopTextInserter.kt`). Verdrahtung über
`PlatformModule.detect()` (`platform/PlatformModule.kt`), damit headless und Linux
sauber degradieren. `available`-Flag analog `TextInserter`.

> [!NOTE]
> Die Compose-`Tray` und ein AWT-`TrayIcon` können um dasselbe Tray-Slot
> konkurrieren. Konkret zu klären im Chunk (§15 Gap 3): entweder die Compose-`Tray`
> durch ein einziges AWT-`TrayIcon` ersetzen (das dann Menü **und** Notifications
> trägt), oder den AWT-`TrayIcon` additiv registrieren. Empfehlung: **ein**
> AWT-`TrayIcon` als SSoT, Compose-`Tray` entfällt — reduziert die
> Doppel-Slot-Gefahr. Spike-Charakter, kein Eskalationsfall.

### 7.2 Android-Notification

`AndroidNotificationPort` [NEW] nach dem Bestandsmuster
`PipelineNotificationCoordinator.kt:248` (`NotificationCompat.Builder(context,
CHANNEL_ID)`): eigener `NotificationChannel` „Peer-Sync" (Id z. B.
`catalog_sync`), Importance `DEFAULT`. POST_NOTIFICATIONS-Runtime-Permission (API 33+)
über die bestehende `NotificationPermissionPolicy.kt`. Ein Tap öffnet den read-only
Peer Explorer in den Settings (§8.3).

## 8. Peer Explorer UI (E3)

### 8.1 Bezugs-Sicht (Companion, Compose)

Screens unter `companion/.../ui/peers/` [NEW], ViewModel-Muster
`HistoryViewModel`/`HistoryViewModelTest` (`ui/history/HistoryViewModel.kt`).

- **PeerListScreen:** Liste der Peers — Name, Adresse, abgeleiteter Status
  (`OK`/`STALE`/`UNREACHABLE`, aus `last_success_at` + Schwelle, §5.1), „zuletzt
  erreicht". Aktion „Peer hinzufügen" → Discovery (§9).
- **PeerDetailScreen:** pro Peer die bezogenen Entitäten mit Typ, Modus
  (`SUBSCRIBE`/`ONE_SHOT`), abgeleitetem Zustand und Aktionen:
  - **sync-now** — einmaliger `CatalogSyncEngine`-Lauf gegen diesen Peer.
  - **unsubscribe** — Subscription löschen; Kopie bleibt als eingefroren (Fork-artig).
  - **fork** — `subscription_mode = NULL` setzen + Subscription-Zeile löschen
    (Transaktion, §5.3), Kopie wird lokal editierbar.

Der **abgeleitete Zustand** (nicht gespeichert, §5.3-Note) ergibt sich im ViewModel:

| Zustand | Bedingung |
|---|---|
| `CURRENT` | `subscription.last_hash == entry.contentHash` im letzten Index |
| `UPDATE_AVAILABLE` | Peer-`entry.contentHash != subscription.last_hash` |
| `FORKED` | `entity.subscription_mode == NULL` und `source_peer_id != NULL` |
| `STALE` | Peer `last_success_at` älter als Schwelle |
| `SOURCE_REMOVED` | Entität im letzten Index nicht mehr vorhanden |

### 8.2 Angebots-Sicht (Companion, F34)

„Was biete ich an?" = Sichtbarkeits-Verwaltung der eigenen Entitäten (`visibility`
umschalten) plus — read-only — die **letzte Abholung** je geteilter Entität aus dem
`catalog_access_log` (§5.4). Was der Server über Bezieher weiß, ist bewusst minimal:
Wer (peer_device_id), Was (entity_id/kind), Wann (at) — kein Inhalt, keine
Häufigkeits-Analyse. Credential-Abholungen sind hier immer sichtbar (Sicherheits-
Transparenz).

### 8.3 Android read-only-Explorer

Verankert als eigene **Settings-Seite** (`app/.../peers/ui/` [NEW], erreichbar aus
den bestehenden Settings, Muster `settings/`). Zeigt Peer-Liste + bezogene Entitäten
mit Zustand — **keine** Angebots-Sicht (Android bietet nichts an, F7), **keine**
Fork/Sichtbarkeits-Aktionen außer „sync-now"/„unsubscribe". Die Editor-Integration
„Von Peer beziehen"-Tab (Prompt-/Profil-/Modell-Listen) ist Desktop-only; Android
zeigt die bezogenen Kopien im überarbeiteten `PromptsOverview` mit Herkunfts-Badge
(C3-Vorarbeit).

## 9. Discovery + headless Betrieb (E3)

### 9.1 Manueller Weg + Pairing-Wiederverwendung

Ein Peer wird bezogen, indem der beziehende Peer HTTP-Client des anbietenden wird —
über **exakt** das bestehende Pairing (ADR-0017, F10):

1. Der anbietende Companion zeigt seinen Pairing-QR/-Code (bestehender
   `PairingDialog`, `ui/pairing/PairingDialog.kt`; `PairingService.issue()`,
   `PairingService.kt:43`).
2. Der beziehende Peer gibt Adresse + Code ein (oder scannt) und redeemt via
   `DispatchClient.pair(token, deviceId, deviceName)` (`DispatchClient.kt:60`) — er
   ist jetzt ein gepaartes „Gerät" des Anbieters und hält `Credentials`.
3. Aus dem Ergebnis wird eine `peers`-Zeile: `peer_id` (opaque, F30 — initial der
   `serverName` aus `PairResponse`/Health oder eine UUID), `address`, `device_id`,
   `secret_ref` (Secret in den SecretStore).

Die `dictate://pair`-URI (`PairingUri.kt:1`) wird unverändert wiederverwendet — sie
trägt bereits `baseUrl` + `token` und ist plattformneutral.

### 9.2 Tailscale-Enumeration (hinter Port, F26)

```kotlin
fun interface PeerDiscovery { fun discover(): List<PeerCandidate> }   // domain/port/
data class PeerCandidate(val magicDnsName: String, val address: String)
```

`TailscalePeerDiscovery` [NEW] in `catalog/discovery/`:
- Ruft `tailscale status --json` (CLI) bzw. die LocalAPI und parst die Peer-Liste
  (MagicDNS-Namen, Online-Status). Reines JSON-Parsing über kotlinx-serialization
  gegen ein DTO der relevanten Felder (`Peer.DNSName`, `TailscaleIPs`, `Online`).
- **Verfügbarkeits-Erkennung, kein Hard-Dependency:** ist `tailscale` nicht im PATH
  / kein Output → leere Liste (Noop-Fallback, Acceptance 11). Muster:
  `JvmNetworkInterfaces` schluckt Enumerations-Fehler in eine leere Liste
  (`JvmNetworkInterfaces.kt:38`).
- Enumeration liefert nur **Kandidaten**; ein Kandidat wird erst nach Health-Probe
  (`GET /v1/health` → `supportsCatalog == true`) und Pairing (§9.1) ein echter Peer.
  Die CGNAT-Klassifikation (`Ipv4.isCgnat`, `Ipv4.kt:34`) kann Kandidaten vorfiltern.

`NoopPeerDiscovery` als Fallback (Nicht-Tailscale-Umgebung, Tests).

### 9.3 headless Betrieb (`--headless`, F8)

`Main.kt` (`Main.kt:62`) bekommt einen frühen Zweig **vor** `application {}`:

```kotlin
if (args.contains(FLAG_HEADLESS)) {
    val ready = CompanionBootstrap.start()          // Server + Persistenz, kein Compose
    Runtime.getRuntime().addShutdownHook(Thread { ready.server.stop(); guard.release() })
    // NotificationPort = Noop (kein Tray); block main thread bis SIGTERM.
    Thread.currentThread().join()
    return
}
```

`CompanionBootstrap.start()` (`CompanionBootstrap.kt:47`) ist bereits Compose-frei und
liefert `ReadyCompanion` — headless nutzt exakt denselben Pfad, überspringt nur
`application {}`. Der `SingleInstanceGuard` (`Main.kt:63`) läuft davor unverändert.
`PlatformModule.detect()` liefert im headless-Fall `NoopNotificationPort`.
Autostart-tauglich: derselbe Prozess, nur ohne Fenster (Muster `--minimized`,
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

**File-Delta grob:** ~6 neue `:shared`-Dateien, ~14 neue Companion-Dateien, ~4 neue
App-Dateien, 1 SQLDelight-Migration, 1 neue Android-Dependency (WorkManager).

## 11. Zwei-Peer-Testbarkeit + E2E

Das E2E-Muster ist `CompanionE2ETest` (`CompanionE2ETest.kt:49`): **echter**
`embeddedServer(CIO, port=0)`, **echter** `:shared`-Client über **echtes** HTTP auf
127.0.0.1, nur Inserter/Clock als Fakes. Für Block E werden **zwei** Container +
Server gestartet (Anbieter A, Bezieher B):

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

E2E-Fälle (nach Plan §8.5 + `TruncatedResponseE2ETest`-Vorbild):
- **teilen+beziehen:** A shared Prompt → B.index() sieht ihn → B.entity() → contentHash
  verifiziert → lokale Kopie in B.
- **Änderung:** A ändert Prompt → Root-Hash ändert sich → B-Lauf zieht nur den einen,
  Notification-Hook feuert.
- **Fork-Schutz:** B forkt die Kopie → A ändert erneut → B-Lauf lässt die Kopie stehen.
- **One-Shot:** B bezieht `ONE_SHOT` → A ändert → B-Lauf ignoriert.
- **Offline:** `providerServer.stop()` → B-Lauf → `PeerUnreachable`, `last_contact_at`
  gesetzt, `last_success_at` unberührt, kein Fehler.
- **Credential-Isolation:** A.index()/entity()-Payloads enthalten den Secret-Sentinel
  **nicht**; nur B.credential() liefert ihn → landet nur im `FakeSecretStore`; A hat
  eine `catalog_access_log`-Zeile.
- **Auth:** B ohne Credentials → 401 auf allen drei Routes.
- **Malformed/Truncated:** truncated Katalog-Payload → `Malformed`, kein Sentinel im
  Envelope (`TruncatedResponseE2ETest`-Muster).
- **Determinismus:** Root-Hash gleich bei Umordnung, neu bei Änderung (Unit auf
  `CatalogService.rootHash`).

## 12. Migration Plan (E1 → E2 → E3)

Chunk-orientiert (implement-long-plan-v3); jeder Schritt hinterlässt `./gradlew build`
grün.

1. **E1a — Wire (`:shared`).** Katalog-DTOs + Validations + Endpoints + ErrorCode +
   `catalog/`-Enums + `CatalogClient`. Tests: `ProtocolCodecTest`/`ValidationsTest`-
   Fälle pro DTO. *Compile-Stand:* `:shared` grün, nichts konsumiert es noch.
2. **E1b — Schema (`companion/data`).** `Companion.sq` + `2.sqm` (peers,
   subscriptions, entity-Tabellen, access_log) + EnumColumnAdapter +
   `SqlDelightCatalogRepository`. Tests: Parity-Tests je Enum-Spalte,
   `SchemaMigratorTest`-Erweiterung v2→v3, `verifyMigrations` grün.
3. **E1c — Server (`companion`).** `CatalogService` + `CatalogRoutes` + Verdrahtung in
   `companionModule`/`CompanionContainer` + `supportsCatalog = true`. Tests:
   `CatalogE2ETest` (Index/Entity/Credential/Auth, ein Peer). *Compile-Stand:*
   Companion serviert die Familie; Bezieher-Seite fehlt noch.
4. **E2a — Sync-Engine (`:shared`).** `CatalogSyncEngine` + `CatalogSyncOutcome` gegen
   Repository-Ports. Tests: `CatalogSyncEngineTest` (NoChange/Updated/Verify-Fail/
   Fork-Skip/SourceRemoved) mit `FakeCatalogTransport`.
5. **E2b — Companion-Bezieher.** `CatalogSyncScheduler` + `NotificationPort` +
   `AwtNotificationPort`/Noop + Zwei-Peer-`CatalogE2ETest`. *Compile-Stand:* zwei
   Companions syncen.
6. **E2c — Android-Bezieher.** WorkManager-Dependency + `CatalogSyncWorker` +
   `AndroidNotificationPort` + Channel; liest C2-Room-Tabellen + Android-SecretStore.
   Tests: Worker-Unit (Robolectric/Fake), Notification-Hook.
7. **E3a — Peer Explorer (Companion).** `ui/peers/`-Screens + ViewModel
   (Zustandsmatrix). Tests: `PeerExplorerViewModelTest`.
8. **E3b — Discovery + headless.** `PeerDiscovery`-Port + `TailscalePeerDiscovery`
   (Fixture-JSON) + `--headless`-Zweig. Tests: Discovery-Parse-Test, headless-Boot-Test.
9. **E3c — Android read-only-Explorer.** Settings-Seite; „Von Peer beziehen"-Tab
   (Desktop). Tests: Robolectric-Smoke.

## 13. Testing Approach

- **Wire (E1a):** Round-Trip + ≥1-Verletzung pro DTO (`ProtocolCodecTest`/
  `ValidationsTest`-Muster, `ProtocolCodecTest.kt:145`). Root-Hash-Determinismus als
  Unit gegen `CatalogService.rootHash`.
- **Parity (E1b):** je neue Enum-Spalte ein `*CheckConstraintParityTest` nach
  `OriginCheckConstraintParityTest.kt:1` (jeder Enum-Wert insertierbar; ein
  Nicht-Enum-Wert wird per CHECK abgelehnt). Enum-Vokabular-Gleichheit Room(C2)↔
  SQLDelight über den geteilten `:shared/catalog`-Enum erzwungen.
- **Migration (E1b):** `SchemaMigratorTest` v2→v3 mit befüllter Fixture-DB;
  `verifyMigrations` (Gradle) hält `.sqm` gegen Schema-Snapshot.
- **E2E (E1c/E2b):** `CatalogE2ETest` (ein Peer und zwei Peers), echte Ktor-Server,
  echter `CatalogClient` — die Fälle aus §11.
- **Sync-Engine (E2a):** `CatalogSyncEngineTest` mit `FakeCatalogTransport` +
  In-Memory-Repos — alle Edge-Fälle aus §6.4 als parametrisierte Suite; Idempotenz
  (zweiter Lauf = ein Call, kein Write).
- **UI (E3):** `PeerExplorerViewModelTest` (Zustandsmatrix
  CURRENT/UPDATE/FORKED/STALE/SOURCE_REMOVED) nach `HistoryViewModelTest`-Muster.
- **Discovery (E3):** Parse-Test gegen Fixture-`tailscale status --json`; Abwesenheit
  → leere Liste.
- **headless (E3):** Boot-Test — `CompanionBootstrap.start()` liefert `ReadyCompanion`
  ohne AWT/Compose (Muster `CompanionBootstrapTest`).
- **Pending:** `GATEWAY`-Enum-Ablehnung ist C1-Sache; auf E-Seite: ein pending-Test
  für die spätere `/v1/ai/*`-Familie ist **nicht** nötig (F31 = nur Namensraum
  reservieren, kein Code).

## 14. Decision Log

### D1 — Katalog-Familie additiv, `CatalogClient` als zweiter `:shared`-Client

**Trigger:** F32 — Katalog auf bestehendem Wire-Stack. **Decision:** Eine neue
`/v1/catalog*`-Familie in `Dtos.kt`/`Validations.kt`/`Endpoints.kt`, ein neuer
`CatalogClient` neben `DispatchClient` (nicht in `DispatchClient` eingemischt — SRP,
andere Fehler-Semantik). **Rationale:** exakt das ADR-0025-Muster (Input-Command war
die zweite Familie, Katalog die dritte); `supportsCatalog`-Flag + 404-Degradation
statt Version-Bump. **Alternatives:** Familie in `DispatchClient` mergen — verworfen,
der Client bliebe nicht mehr „die eine Dispatch-Sicht".

### D2 — Hash-basiert, nicht cursor-basiert

**Trigger:** F27 + Verhältnis zu ADR-0020. **Decision:** Root-Hash (Peer-weit) +
per-Entity-contentHash statt `(createdAt, id)`-Cursor. **Rationale:** Entitäten sind
klein, selten geändert, einzeln identifizierbar; der Hash liefert zusätzlich die
Drift-Erkennung gratis (lokal editiert? Peer geändert?). Der Session-Sync (ADR-0020)
bleibt cursor-basiert — zwei verschiedene Datencharakteristiken, zwei Verfahren.

### D3 — Fork-Schutz per Sync-Query-Selektion

**Trigger:** F29. **Decision:** Ein Fork setzt `subscription_mode = NULL` + löscht die
`subscriptions`-Zeile in einer Transaktion; die Sync-Query selektiert nur
`subscription_mode = 'SUBSCRIBE'`-Kopien. **Rationale:** Fork-Schutz wird eine
Eigenschaft der Selektion, kein Sonderfall im Schreibpfad — der Sync kann eine
geforkte Kopie gar nicht „sehen" (Acceptance 8, robuster als eine Prüfung vor jedem
Write).

### D4 — Staleness abgeleitet, nicht persistiert

**Trigger:** F33. **Decision:** `peers` speichert `last_success_at`; der Status
(`OK`/`STALE`/`UNREACHABLE`) wird im ViewModel aus Zeit + Schwelle abgeleitet.
**Rationale:** kein persistierter Zustand, der von der Realität driften kann (Analogon
zu ADR-0023 §2 Materialisierungs-Argument, hier invertiert: rein zeitabgeleitet ⇒
nicht materialisieren).

### D5 — Namensraum `/v1/catalog*`, `/v1/ai*` nur reserviert

**Trigger:** F31. **Decision:** Katalog-Endpoints unter `/v1/catalog`; die spätere
Gateway-/Proxy-Familie bekommt `/v1/ai*` — nur als dokumentierter Platzhalter im
ADR/Endpoints-Kommentar, **kein** Code. **Rationale:** additive Erweiterbarkeit ohne
spekulativen Code (Konzept-Skizze §4 „Langfristiger Server-Pfad").

### D6 — Tray-Notification via AWT `SystemTray`, ein TrayIcon als SSoT

**Trigger:** Plan-Gap 6 (Compose-`Tray` kann nicht benachrichtigen). **Decision:**
`AwtNotificationPort` mit `TrayIcon.displayMessage`; Empfehlung, die Compose-`Tray`
durch ein einziges AWT-`TrayIcon` zu ersetzen (Menü + Notification in einem Slot).
**Rationale:** kleinste funktionierende Lösung ohne neue Dependency; vermeidet
Doppel-Slot-Konkurrenz. Spike, kein Eskalationsfall (§15 Gap 3).

### D7 — Tabellen-Eigentum entschieden: D3 legt die Entitäts-Tabellen an (Plan D5.b)

**Trigger:** Cross-Spec-Entscheidung des Plan-Architekten 2026-07-20; §15
Gap 1 hatte das Eigentum an die Orchestrator-Sequenzierung delegiert.
**Decision:** **D3** legt `provider_configs`/`model_refs`/`prompts`/
`profiles`/`profile_prompts` VOLLSTÄNDIG an — nach der DDL aus §5.2 dieser
Spec **inkl. Provenienz-Spalten** (sie bleiben bis E2 NULL), als Migration
`3.sqm`. **E1** legt nur `peers`/`subscriptions`/`catalog_access_log` an
(Migration `4.sqm`; D1a hält `2.sqm`). Neue depends_on-Kante **E1→D3**.
§5.2 dieser Spec bleibt die DDL-SSoT; der D3-Agent implementiert sie.
**Rationale:** Ein Migrations-Owner pro Schema-Fläche statt Zwei-Stufen-
Migration („wer zuerst landet") — deterministisch planbar; die Gegenrichtung
(D3→E1) hätte einen Block-Zyklus D↔E erzeugt, da E3 in die D3-Editor-Screens
integriert. Enum-Parität zu C1/C2 bleibt Pflicht-Gate (Parity-Test, §13).
**Alternatives:** E1 legt vollständig an (alter Fallback) — verworfen wegen
Zyklus; Zwei-Stufen-Migration — verworfen wegen Nicht-Determinismus.

### D8 — Freshness-Pass 2026-07-20 (Post-Implementation, vor Archivierung)

**Trigger:** Integrations-Check nach Abschluss Block A–E (Finding `integ-1`,
green) — Abgleich gegen den gebauten Stand. D7 hatte die Migrations-Zuordnung
(D3=`3.sqm`, E1=`4.sqm`, D1a=`2.sqm`) bereits as-built; der folgende Punkt zur
Enum-Platzierung war beim D7-Zeitpunkt noch offen.
**As-built vs. Spec — Katalog-Wire-Enums konsolidiert:** §5.2/§5.3 referenzieren
`net.devemperor.dictate.shared.catalog.VisibilityWire` und
`...catalog.SubscriptionModeWire` als separate Katalog-Wire-Enums. Gebaut wurde
KEIN `shared.catalog`-Package; beide leben im gemeinsamen Config-Enum-Heim
`shared/.../config/ConfigEnums.kt` als:
- `Visibility` (`PRIVATE`/`SHARED`) — Namenszusatz „Wire" entfällt (ein Heim ⇒
  keine Domain-/Wire-Namenskollision).
- `SubscriptionMode` (`LOCAL`/`SUBSCRIBE`/`ONE_SHOT`) — der §5.2/§5.3-`NULL`-Fall
  „lokal/geforkt" wurde zum expliziten `LOCAL`-Member: die Spalten sind
  `NOT NULL DEFAULT 'LOCAL'` mit `CHECK (... IN ('LOCAL','SUBSCRIBE','ONE_SHOT'))`
  (Companion.sq), ein Fork setzt `subscription_mode = 'LOCAL'` statt `NULL`. Das
  CHECK-Vokabular ist damit gegenüber §5.2 („`IS NULL OR IN ('SUBSCRIBE','ONE_SHOT')`")
  aktualisiert.

`CatalogEntityKindWire` blieb wie spezifiziert in `shared.protocol`. Die Enum-Parität
ist per `ConfigEntityCheckParityTest` / `CatalogAccessCheckParityTest` gepinnt.
**Bewertung:** Kein Code-Impact; D5.a-Doktrin-treu (ein SSoT-Enum-Modul). Body
unverändert; dieser Eintrag ist die normative As-built-Korrektur der
`shared.catalog.*Wire`-Package-Referenzen in §3/§5.

## 15. Information Gaps

1. ~~**Companion-Entitäts-Tabellen — Eigentum D3 vs. E1**~~ — **geschlossen
   2026-07-20 (§14 D7 / Plan D5.b):** D3 legt vollständig an (inkl.
   Provenienz, `3.sqm`); E1 nur `peers`/`subscriptions`/`catalog_access_log`
   (`4.sqm`); Kante E1→D3. DDL-SSoT bleibt §5.2.
2. **WorkManager-Version gegen Kotlin-Ceiling 2.1.20 (R4).** — **Dependency
   per Plan D5.f BESTÄTIGT** (`androidx.work:work-runtime-ktx`, volles
   Hintergrund-Polling per D4.5); offen bleibt nur die Versionswahl.
   *Owner:* E2c-Agent — Kandidaten-Version vor Einbau gegen das
   Ceiling prüfen (ADR-0015), im Chunk dokumentieren. *Fallback:* falls eine
   kompatible `work-runtime`-Version fehlt, ein `AlarmManager`+`JobScheduler`-
   Eigenbau (kleinere Fläche) — aber erst nach belegter Inkompatibilität.
3. **Compose-`Tray` vs. AWT-`TrayIcon`-Koexistenz.** Ein Tray-Slot, zwei mögliche
   Besitzer. *Owner:* E2b-Agent (Spike). *Fallback:* AWT-`TrayIcon` als alleiniger
   Besitzer (Menü + Notification), Compose-`Tray` entfällt (D6-Empfehlung).
4. **`peerId`-Bildung heute.** F30 verlangt „public-key-fähig ausgelegt", v1 hat aber
   noch keine Peer-Public-Keys (das Device-Secret-Modell ist symmetrisch, ADR-0017).
   *Owner:* E3b-Agent. *Fallback:* `peer_id` = opaque UUID beim Hinzufügen erzeugt;
   Spalte breit genug für einen späteren Key-Fingerprint; kein Verhalten hängt heute
   an der Bildungsregel.
5. **Tailscale-Zugriff: CLI vs. LocalAPI.** `tailscale status --json` (CLI, PATH-
   abhängig) oder die LocalAPI (Unix-Socket/Named-Pipe). *Owner:* E3b-Agent —
   kleinste robuste Variante wählen (vermutlich CLI, da plattformübergreifend
   gleich geparst). *Fallback:* CLI; Abwesenheit ⇒ leere Liste (kein Blocker).
6. **Credential-`contentHash`-Definition.** Der Index-Hash einer `CREDENTIAL` ist über
   den verschlüsselten At-Rest-Blob bzw. einen Key-Fingerprint zu bilden — die exakte
   Wahl hängt am SecretStore-Format aus B1. *Owner:* E1-Agent in Abstimmung mit B1.
   *Fallback:* SHA-256 über einen stabilen Key-Fingerprint (z. B. Hash des Klartexts
   mit fixem Salt), damit Änderung erkennbar ist, ohne den Klartext preiszugeben.

## 16. References

- **Plan:** `../desktop-companion-v1.md` §5 Block E (E1/E2/E3), §3 Decision Log
  (F7–F34), §7 Sequenzierung, §8 Test-Strategie, §9 R8, §10 Gap 6.
- **Konzept:** `konzept-skizze.md` §4 (Peer-Katalog, Abo-Sync, Verschlüsselung,
  Gateway-Pfad).
- **Bindende ADRs:** `docs/decisions/0016-wire-protocol-typed-dtos-konform.md`
  (ProtocolCodec/Konform/additive Versionierung), `0017-client-server-roles-transport-pairing.md`
  (Pairing, Auth, Tailscale-Transport), `0020-lazy-cursor-sync.md` (Sync-Vorbild,
  bewusst NICHT übernommen für Katalog — D2), `0023-companion-bind-address.md`
  (Tailscale-Erkennung `Ipv4.isCgnat`, Materialisierungs-Argument), `0025-input-command-protocol.md`
  (additive Endpoint-Familie — das Muster).
- **Plan-scoped ADR:** `adrs/adr-peer-catalog.md` (zu erstellen in A1; diese Spec ist
  seine Implementierungsgrundlage) — reziprok verlinken.
- **Schlüssel-Code (Wire):** `shared/.../protocol/Dtos.kt`, `Validations.kt`,
  `Endpoints.kt`, `ProtocolCodec.kt:50`, `ErrorEnvelope.kt`; `client/DispatchClient.kt:118,200`
  (404- und Fehler-Klassifikation als Muster); `auth/PairingUri.kt`, `Secrets.kt:44`,
  `AuthHeaders.kt`.
- **Schlüssel-Code (Companion):** `server/CompanionServer.kt:96-111`,
  `server/plugins/AuthPlugin.kt:25,58`, `server/ProtocolCalls.kt:106,126`,
  `server/routes/SyncRoutes.kt` (Route-Muster), `domain/HealthService.kt`,
  `domain/PairingService.kt`, `data/Companion.sq`, `data/SchemaMigrator.kt`,
  `data/CompanionDatabase.kt:39`, `data/OriginCheckConstraintParityTest.kt`,
  `CompanionContainer.kt:67`, `CompanionBootstrap.kt:47`, `Main.kt:62`,
  `platform/JvmNetworkInterfaces.kt:38`, `domain/net/Ipv4.kt:34`,
  `domain/net/AddressKind.kt`.
- **Schlüssel-Code (Test-Muster):** `server/CompanionE2ETest.kt` (echter Server +
  Client), `server/TruncatedResponseE2ETest.kt` (Malformed-Payloads),
  `ui/history/HistoryViewModelTest.kt` (ViewModel-Muster).
- **Android:** `core/PipelineNotificationCoordinator.kt:248` (NotificationCompat),
  `core/NotificationPermissionPolicy.kt`, `rewording/PromptImportExport.java`
  (v1/v2-Import als C1-Kontext).
- **Konventionen:** `docs/DATABASE-PATTERNS.md` (Double-Enum),
  `~/.claude/snippets/test-first-patterns.md`.
