# E2-Rest — Subscriber-Store + Sync-Verdrahtung (completion report)

**Date:** 2026-07-20 · **Scope:** der dokumentierte E2-Rest (Issue E2-1 / E3-1), der den
Important-Befund aus `post-audit-3bec2b8.md` schließt: den Peer-Katalog-Abo-Sync end-to-end
lauffähig machen. Baut auf dem bereits gelandeten Engine-Kern (`CatalogSyncEngine`, `:shared`)
und dem 3bec2b8-Schnitt (Angebots-Seite, Migration 4.sqm, Notification-Ports, Scheduler-Hosts) auf.

## Was gebaut wurde

### Geteilt (`:shared`)
- **`CatalogPayloadGraft`** (`268b76b`) — der hash-kritische Rekonstruktionsschritt: ein verifizierter,
  envelope-gestrippter Payload wird auf das lokale Envelope einer Kopie (id/Provenienz/`SUBSCRIBE`)
  gepfropft. Eine Quelle für beide Plattform-Stores (ein Per-Host-Klon würde die Rekonstruktion
  driften lassen und die Fork-Dedup brechen). Test belegt: gepfropfte Kopie hasht identisch zur Quelle
  trotz abweichender lokaler id; Envelope-Keys im Payload werden ignoriert.

### Companion
- **`SqlDelightCatalogSubscriberStore`** (`a9cbd9f`) — die produktive Engine-Schreibseite über die echten
  `peers`/`subscriptions`-Zeilen + den Config-Spiegel + SecretStore. `activeSubscriptions` filtert
  `mode = 'SUBSCRIBE'` (Fork-Schutz per Query, AC8); `applyEntityUpdate` pfropft + lässt
  `CompanionConfigRepository.save` den Hash neu berechnen; Credential-Klartext geht nur in den
  SecretStore. Neue `.sq`-Queries (peers-Watermark, Subscription-Diff, markSourceRemoved) — nur Queries,
  keine DDL, daher kein neues `.sqm`; `verifySqlDelightMigration` bleibt grün.
- **Verdrahtung** (`cf534bf`) — `PeerCatalogClientFactory` (peers-Zeile + SecretStore-Pairing-Secret →
  `CatalogClient`), `PeerStoreCatalogSyncTargets`, die drei E3-Explorer-Seams (Index / Sync-Now /
  Subscribe) und der `TakeoverCatalogSubscriber` (Pull + Verify-vor-Write + Kopie/Subscription-Zeile
  anlegen). `CompanionContainer.production` baut die Engine über den echten Store;
  `CompanionBootstrap.start` startet den Scheduler nach Server-Up (ADR-0020 App-Start-Trigger).

### Android
- **Room-Migration v12→v13** (`056b01a`) — `peers` + `subscriptions` (Double-Enum-CHECK auf `kind`/`mode`,
  `mode` als `SUBSCRIBE`/`ONE_SHOT`-Subset, FK peer→CASCADE). Entities + DAOs + Schema-Export `13.json`.
- **`AndroidCatalogSubscriberStore`** (`1cb920d`) — dieselbe Semantik über Room + `ConfigRepository` +
  `SecretStore`; nutzt `CatalogPayloadGraft` und für die Prompt-Art `CatalogImport.upsertPromptRow`
  (jetzt `internal`), damit Sync- und v3-Import-Empfangspfad nicht driften.
- **`AndroidCatalogSyncGateway`** (`9fbd43d`) — baut die geteilte Engine über den Room-Store +
  `AndroidNotificationPort` + je-Peer-`CatalogClient`; verdrahtet bei App-Start
  (`CatalogSync.gateway` + `CatalogSyncWorker.enqueue`).

### Credential-Fingerprint-Spec-Fix (`bae3b34`)
Domain-separiert + id-gesalzen statt nacktem Klartext-Hash (§4.3). Begründung: der `SecretStore`-Port
exponiert den At-Rest-Ciphertext bewusst nie (ADR-0029). Details im Audit-Report.

## Peer-Secret-Namespace-Konvention
Das Pairing-Secret FÜR einen Peer liegt im SecretStore unter `SecretRef("peer", <peers.secret_ref>)`.
Identisch auf beiden Hosts (`companion/.../catalog/PeerSecrets`, `app/.../peers/PeerSecrets`), analog zu
`ConfigSecrets`/`PairingSecrets`. Ein §9.1-Pair-Redemption schreibt das Secret dorthin; die
Client-Factory liest es dort.

## Teststand

Grün (vor jedem Commit + final):
- `:shared:test` (inkl. `CatalogPayloadGraftTest`)
- `:companion:test` (inkl. `SqlDelightCatalogSubscriberStoreTest` = AC6/AC7/AC8/AC10 + Credential-Pfad
  gegen den echten Store; `CatalogSyncE2ETest` = Zwei-Peer-In-Process-E2E subscribe→sync→update→notify +
  Idempotenz/ein-GET + Fork-Schutz über echtes HTTP; `CatalogServiceTest` Fingerprint-Regression)
- `:companion:verifySqlDelightMigration`
- `:app:testDebugUnitTest` voll (inkl. `AndroidCatalogSubscriberStoreTest` Robolectric = AC6/AC7/AC8 +
  Credential gegen echte In-Memory-Room-DB; `MigrationTo13MetadataTest`). Der bekannte order-abhängige
  Keystore-Flake trat in diesem Lauf nicht auf.

## Was pending bleibt
- **`MigrationTo13Test` (androidTest, instrumented)** — `runMigrationsAndValidate` gegen `13.json` +
  CHECK-Accept/Reject + FK-Cascade. Läuft nur auf Gerät/Emulator (`connectedDebugAndroidTest`), hier
  nicht ausführbar — gleicher Local-only-Status wie die `MigrationTo11/12Test`-Geschwister. JVM-Seite ist
  durch `MigrationTo13MetadataTest` (Versionspaar) + `AndroidCatalogSubscriberStoreTest` (die Tabellen
  vom echten Engine getrieben) abgedeckt.

## Abweichungen / Design-Entscheidungen
- **Subscribed-Copy-id = Source-id.** Der `TakeoverCatalogSubscriber` übernimmt die Quell-Entitäts-id als
  lokale id — eine bezogene Kopie ist ein wörtlicher Spiegel, sodass Payload-interne Referenzen
  (Profil→ModelRefs, Provider→CredentialRef) weiter auflösen und der recompute-Hash der Quelle gleicht.
  Das Schema erlaubt weiterhin abweichende ids; die Wahl ist eine Konvention des Takeover-Pfads.
- **`markSourceRemoved` persistiert keinen Status.** Es gibt keine `SOURCE_REMOVED`-Spalte — der Zustand
  wird im Explorer aus dem Live-Index abgeleitet (D4). `markSourceRemoved` stempelt nur `last_checked_at`.
- **`CatalogSyncWorker.enqueue` ist best-effort** gegen einen nicht initialisierten WorkManager
  (Robolectric/restringierter Prozess) — App-Start crasht nie; in Produktion (Manifest-Initializer) ist
  der Guard inert.
- **Credential-Mirror auf Android:** `applyCredentialUpdate` legt das Secret in den SecretStore und
  aktualisiert — falls eine `api_credentials`-Kopiezeile existiert — deren `keyFingerprint`
  (`sha256(key)`-hex[0..16), nie den Key). Der Companion hält keinen Credential-Spiegel (Wert nur im
  SecretStore), daher dort nur Secret + Watermark.

## Known minor
- **`AndroidCatalogSubscriberStore.promptDtoByUuid()` macht einen O(n)-Scan** — pro Prompt-Update ein
  `getAll().firstOrNull { it.uuid == uuid }` über die volle Prompt-Tabelle. Sauberer wäre eine
  `promptDao().byUuid(uuid)`-Query. Vorbestehend, kein Funktionsdefekt; als Follow-up notiert
  (docs-final `notes_for_final`, `app-peers`-Worker).
