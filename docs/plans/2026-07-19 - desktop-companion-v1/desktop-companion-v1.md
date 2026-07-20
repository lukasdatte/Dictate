# Desktop-Companion v1 — Diktat am PC, Entitätenmodell, Peer-Katalog

---
date: 2026-07-19
author: Lukas + Claude Code (Konzept-Session desktop-concept)
type: Plan
status: Implementierungsbereit — Verständnis-Check vollständig beantwortet (2026-07-19, siehe §3 D4)
context: Ausbau des Dictate-Companion zum eigenständigen Desktop-Diktier-Host (Compose-UI, Hotkey, volle Pipeline) plus Profil-/Entitätenmodell mit sofortiger Android-Migration und Peer-Katalog-Verteilung über Tailscale.
related-plan: n/a (top-level plan)
related-adrs: erweitert/berührt ADR-0007, 0009, 0012, 0013, 0015, 0016, 0017, 0020, 0024, 0025, 0027; 8 neue plan-scoped ADR-Drafts (§6)
archive_target: 2026-07-19 - desktop-companion-v1
---

Dieser Plan ist die Implementierungs-Vorlage für den per Fragenkatalog
(F1–F34, alle entschieden am 2026-07-19) festgelegten Desktop-Ausbau. Er ist
für die Ausführung mit `implement-long-plan-v3` strukturiert (6 Blöcke,
16 Chunks nach dem D1a/D1b-Split — Begründung in §7 und im
Plan-Conventions-Block).

> [!IMPORTANT]
> **SSoT-Regel seit der Spec-Vertiefung (2026-07-20):** Für die Blöcke A–E
> existieren fünf implementer-ready Specs unter
> `docs/plans/2026-07-19 - desktop-companion-v1/research/`:
> `shared-ai-extraktion.md` (A), `secretstore.md` (B),
> `entitaetenmodell-android.md` (C), `desktop-host.md` (D),
> `peer-katalog.md` (E). **Die Specs sind die kanonische Detailquelle** —
> die Chunk-Beschreibungen in §5 sind Stubs mit Spec-§-Verweisen; Inhalt lebt
> nicht doppelt. Die Cross-Spec-Entscheidungen stehen in §3 D5 und sind in den
> betroffenen Specs als Decision-/Change-History-Nachträge dokumentiert.
> Zusätzlich final: `reports/e2e-runbook.md` (16 manuelle E2E-Fälle) und zwei
> Groundwork-Commits (E2E-Infra versioniert, Schema-Assets für androidTest).

Konzept-Vorarbeit (Bestandsaufnahme, Skizze, Fragenkatalog): wird von A1 aus
`tmp/desktop-concept/` als weitere `research/`-Dateien eingecheckt (D4.1).

## Table of Contents

- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Decision Log](#3-decision-log)
- [§4 Directory Layout (Ziel-Topologie)](#4-directory-layout-ziel-topologie)
- [§5 Building Blocks](#5-building-blocks)
- [§6 ADR-Drafts (plan-scoped)](#6-adr-drafts-plan-scoped)
- [§7 Sequenzierung und Parallelisierung](#7-sequenzierung-und-parallelisierung)
- [§8 Test-Strategie](#8-test-strategie)
- [§9 Risiken](#9-risiken)
- [§10 Information Gaps](#10-information-gaps)
- [§11 Iteration Log](#11-iteration-log)
- [§12 References](#12-references)
- [Plan Conventions (implement-long-plan-v3)](#plan-conventions-compatibility-block-für-implement-long-plan-v3)

## 1. Vision and Motivation

### 1.1 Warum dieses Vorhaben existiert

Dictate diktiert heute ausschließlich auf dem Phone; der Desktop-Companion ist
ein passiver Text-Empfänger (ADR-0017/0027). Der User will am PC diktieren wie
am Phone: definierte Tastenkombination → warmes Mini-Panel → Aufnahme →
Transkription → Nachbearbeitung → Auto-Insert in die fokussierte App — ohne
Phone in der Hand. Gleichzeitig sind Konfigurationsdaten (Provider, Modelle,
Prompts, API-Keys) heute Pref-Strings einer einzelnen Android-Installation:
nicht teilbar, nicht kombinierbar, Keys im Klartext. Das Vorhaben löst beides
in einem Zug, weil die gemeinsame Wurzel dieselbe ist: ein geteilter,
plattformneutraler AI-Kern plus ein entitätenbasiertes Konfigurationsmodell.

### 1.2 Was das Vorhaben liefert

1. **Desktop-Diktat:** Companion nimmt selbst auf (javax.sound), führt die
   AI-Pipeline selbst aus (geteilter `:shared-ai`-Kern), fügt per bestehendem
   `TextInserter` ein. Hotkey + fokus-freies Compose-Mini-Panel + voller
   Prüfmodus inkl. Re-dictate + History in Room-Schema-Parität.
2. **Profil-System:** `ProviderConfig`/`ModelRef`/`Prompt`/`Profile` als
   versionierte, teilbare Entitäten; Android migriert sofort mit (Prefs→DB,
   `APISettingsActivity`-Umbau).
3. **SecretStore:** projektweite verschlüsselte Key-Ablage (Android Keystore /
   Windows DPAPI), Migration der Klartext-Keys.
4. **Peer-Katalog:** jeder Companion ist Anbieter UND Bezieher im Tailnet;
   hash-basierter Abo-Sync (SUBSCRIBE/ONE_SHOT), Änderungs-Benachrichtigung,
   Peer Explorer, v3-Datei-Export mit identischer Serialisierung; headless
   Hub-Peer als Deployment-Variante.

### 1.3 Discarded Alternatives

- **Browser-UI (TS-SPA vom Companion serviert):** verworfen per F1 — zweite
  Sprache, UI-Wire-Protokoll und TS-Codegen für null funktionalen Mehrwert im
  Single-User-Kontext; Compose-Desktop existiert bereits und kann Fenster/
  Hotkey/Warmhalten nativ.
- **KMP/Wasm für Code-Sharing bis in den Browser:** verworfen — superseded
  ADR-0015 ohne Not, Kotlin-Ceiling 2.1.20, AI-SDKs bleiben ohnehin JVM-only.
- **Zero-Knowledge-Key-Sharing (Share-Passwort/sealed-box):** verworfen per
  F12 zugunsten Envelope-Encryption — der Peer-Betreiber ist im
  Self-Hosted-Kontext ohnehin vertrauenswürdig; Zero-Knowledge bleibt
  dokumentierte spätere Härtungsoption.
- **Zentraler Hub-Server als eigenes System:** verworfen per F25/F8 — der
  „Hub" ist eine `--headless`-Deployment-Variante des Companion-Servers,
  kein eigenes Modul/Protokoll.
- **Reduziertes Desktop-History-Schema:** verworfen per F15 — volle
  Room-Parität, damit Regenerate/Review/Step-Ketten identisch funktionieren.

## 1a. Architecture Walkthrough

### 1a.0 ASCII Stack Diagram (Ziel-Architektur)

```
┌─────────────────────────────────────────────────────────────────────┐
│  PEER-NETZ (Tailnet)                                     (top)      │
│  Protokoll: /v1/catalog-Familie (additiv, ProtocolCodec+Konform)    │
│  Form:   jeder Companion = Anbieter+Bezieher; --headless = Hub-Peer │
└─────────────────────────────────────────────────────────────────────┘
              ↓ pull-only, Root-Hash → Entity-Diff (Block E)
┌──────────────────────────────┐  ┌───────────────────────────────────┐
│  :app (Android-IME)          │  │  :companion (Desktop-Host)        │
│  bestehende Pipeline; NEU:   │  │  NEU: capture/ + pipeline/ +      │
│  Entitätenmodell-UI (C3),    │  │  hotkey/ + ui/panel/ + Review +   │
│  SecretStore (B), Abo-Sync   │  │  History-Parität + Peer Explorer  │
│  read-only-Explorer (E2/E3)  │  │  (Blöcke D + E)                   │
└──────────────────────────────┘  └───────────────────────────────────┘
              ↓ konsumiert                     ↓ konsumiert
┌─────────────────────────────────────────────────────────────────────┐
│  :shared-ai (NEU, pure JVM)                                         │
│  AIProvider, Runner, AIOrchestrator-Kern, Prompt/Conversation,      │
│  ReviewDecision, ParameterRegistry, ModelFetcher — hinter Ports     │
│  (AiConfig, UsageSink, ProxyConfig, AudioDurationReader,            │
│  SecretStore)                                                       │
└─────────────────────────────────────────────────────────────────────┘
              ↓ nutzt DTO-/Codec-Fundament
┌─────────────────────────────────────────────────────────────────────┐
│  :shared (bestehend, pure JVM)                          (bottom)    │
│  Wire-Protokoll (Dispatch/Sync/Input + NEU Catalog-Familie),        │
│  NEU: Konfigurations-Entitäten + kanonische v3-Serialisierung +     │
│  contentHash                                                        │
└─────────────────────────────────────────────────────────────────────┘
```

### 1a.1 Schicht `:shared` — Entitäten + Wire

- **Purpose:** SSoT für alle Wire-Formate und die neuen Konfigurations-Entitäten.
- **File:** `shared/src/main/kotlin/net/devemperor/dictate/shared/`
- **Contract:** `ProtocolCodec` bleibt die einzige Codec-Tür (ADR-0016); die
  kanonische Entitäts-Serialisierung ist zugleich v3-Dateiformat und Hash-Basis.
- **Detail:** §5 Block C (C1), Block E (E1).

### 1a.2 Schicht `:shared-ai` — geteilter AI-Kern

- **Purpose:** genau EINE Implementierung von Providern/Runnern/Prompt-Logik
  für beide Plattformen; Plattform-Zugriffe ausschließlich über Ports.
- **File:** `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/` (Package
  bleibt `net.devemperor.dictate.ai` → minimale Import-Diffs in `:app`).
- **Contract:** `AiConfig`, `UsageSink`, `ProxyConfig`, `AudioDurationReader`,
  `SecretStore` (Ports); `SharedAiPurityTest` analog `SharedPurityTest`
  (kein Android, kein Ktor; okhttp/SDKs erlaubt — eigene Dependency-Policy).
- **Detail:** §5 Block A.

### 1a.3 Schicht Plattform-Hosts

- **`:app`:** implementiert die Ports mit SharedPreferences/Room/SecretStore;
  UI-Umbau auf Entitätenmodell (C2/C3); Abo-Sync-Bezieher (E2).
- **`:companion`:** implementiert die Ports mit SQLDelight/CompanionSettings/
  SecretStore; neue Subsysteme `capture/`, `pipeline/`, `hotkey/`, `ui/panel/`;
  Katalog-Server + Sync-Engine + Peer Explorer.
- **Detail:** §5 Blöcke B–E.

### 1a.4 Read-this-before-implementing Checklist

- [ ] JEDE neue Wire-Payload: `@Serializable`-DTO + co-lokiertes
  `Validation<T>` + ausschließlich via `ProtocolCodec` (ADR-0016, §5 E1).
- [ ] Kotlin-Ceiling: keine Library gebaut mit Kotlin > 2.1.20, compilerweit
  (ADR-0015). Gilt auch für `:shared-ai` und alle neuen Dependencies.
- [ ] Neue Endpoints additiv + `HealthResponse`-Capability-Flag, kein
  Protocol-Version-Bump (ADR-0025-Muster, §5 E1).
- [ ] Finite-Set-Spalten (Room UND SQLDelight): Double-Enum-Pattern —
  Kotlin-Enum + SQL-CHECK (docs/DATABASE-PATTERNS.md).
- [ ] Prefs nie über rohe String-Keys — nur `DictatePrefs.kt`; neue
  Secret-Werte nur über den `SecretStore`-Port (§5 B1).
- [ ] Reducer-Reinheit im Desktop-Pipeline-State (kein IO in Reducern) —
  Muster aus ADR-0001, ohne den Android-Orchestrator zu portieren.
- [ ] Extraktions-Chunks (A2/A3) sind verhaltensneutral: kein Verhalten der
  Android-App ändert sich; bestehende Tests bleiben grün.
- [ ] Jeder Bugfix mit Regression-Test, der auf ungefixtem Code rot war
  (test-first-Konvention, §8).

## 2. Acceptance Criteria

Global (pro Block verfeinert in §5):

1. **Build-Invariante:** `./gradlew build` grün über alle Module (`:app`,
   `:shared`, `:shared-ai`, `:companion`); `SharedPurityTest` und neuer
   `SharedAiPurityTest` grün.
2. **Verhaltensneutralität der Extraktion:** Nach Block A verhält sich die
   Android-App unverändert (bestehende Unit-/Instrumented-Tests grün, kein
   Diff im API-Traffic-Aufbau — verifiziert durch bestehende Runner-Tests).
3. **Desktop-Diktat E2E:** Hotkey → Panel <100 ms sichtbar → Aufnahme
   (Start/Pause/Resume/Discard) → Transkription+Nachbearbeitung über konfigu-
   riertes Profil → Auto-Insert (Windows) bzw. Clipboard+UI-Hinweis (Linux);
   Session inkl. Steps/Conversation in der Companion-DB persistiert.
4. **Prüfmodus-Parität:** `AmbiguityMode`-Semantik identisch zu ADR-0013;
   Re-dictate erzeugt Refinement-Session + Conversation-Continuation; Verdikt
   via geteiltem `ReviewDecision` (ein Codepfad, kein Fork).
5. **Schema-Parität:** SQLDelight-Schema deckt `sessions`/`transcriptions`/
   `processing_steps`/`conversation_messages` mit identischen Enum-Vokabularen
   + CHECK-Constraints; automatisierte Parity-Tests (§8) grün.
6. **Android-Migration:** Bestehende Installationen migrieren verlustfrei
   (Prefs→Entitäten, Klartext-Keys→SecretStore); Migrations-Tests mit
   Fixture-Prefs/DB v11 grün; keine Klartext-Keys mehr in SharedPreferences
   nach Migration (Test prüft Abwesenheit).
7. **Peer-Sync:** Zwei Companion-Instanzen (Test-Harness, In-Memory) teilen
   Prompt/Profil/ModelRef/Credential; SUBSCRIBE erkennt Änderung über
   Root-Hash-Abgleich und aktualisiert + benachrichtigt; ONE_SHOT bleibt
   stehen; lokal abgekoppelte Kopie (Fork) wird nie überschrieben; Credential
   landet beim Empfänger ausschließlich im SecretStore.
8. **Kanonische Serialisierung stabil:** Snapshot-Tests fixieren Byte-Identität
   der kanonischen Form (Hash-Basis); v3-Datei-Export = Katalog-Wire-Format
   (ein Codec, Round-Trip-Tests inkl. v1/v2-Import-Kompatibilität).
9. **ADR-Vollständigkeit:** Die 8 ADR-Drafts aus §6 existieren als plan-scoped
   Drafts und sind vor Plan-Archivierung promoted (docs/decisions/ + Index).

## 3. Decision Log

Die 34 Grundsatz-Entscheidungen wurden vom User + Team-Lead im Fragenkatalog
getroffen (`tmp/desktop-concept/fragenkatalog.md`, Stand 2026-07-19) und sind
für diesen Plan bindend. Kompaktform (Form B; ⚠ = Abweichung von der
ursprünglichen Empfehlung):

| # | Entscheidung |
|---|---|
| F1 ⚠ | Compose-Desktop-UI, KEIN Browser (F3/F13 entfallen) |
| F2 | AI-Pipeline auf dem Companion via `:shared-ai` |
| F4 | Audio-Aufnahme im Companion (javax.sound) |
| F5 | Natives Compose-Fenster, rahmenlos, always-on-top, <50 ms Toggle |
| F6 | Windows-first für Hotkey/Insertion, alles hinter Ports, Linux-Dogfooding Clipboard+Button |
| F7/F25 | Anbieter = Companions + optionaler headless Hub-Peer; Android reiner Bezieher |
| F8 | Headless Peer = `--headless`-Variante des Companion, kein eigenes Modul |
| F9 | Pull-only Katalog-Sync |
| F10 | Pairing-Modell wiederverwendet für Peer-Auth |
| F11 | Projektweiter SecretStore-Port inkl. Android-Klartext-Key-Migration |
| F12 ⚠ | Envelope-Encryption: anbietende Peers dürfen entschlüsseln; TLS + SecretStore Pflicht; kein Share-Passwort |
| F14 | Fork + Update-Hinweis (`sourceRef` + Herkunfts-Hash) |
| F15 ⚠ | VOLLE Room-Schema-Parität in SQLDelight, mit Parity-Tests |
| F16 | Companion-DB = gemeinsames Archiv (Phone-Sync + Desktop-Sessions, origin-getrennt); Peers nie Diktat-Speicher |
| F17 | Profil-Inhalt: Transcription-/Completion-ModelRef, Parameter-Overrides, geordnete Prompts+autoApply, System-Prompt-Auswahl, AmbiguityMode; Credentials nur referenziert |
| F18 ⚠ | Voller Prüfmodus ab v1 inkl. Re-dictate |
| F19 | Recording-Kern 1:1 in Compose; Verwaltungs-Screens eigene Layouts mit Farb-/Formsprache |
| F20 | Prompt-Wahl nur übers Profil (Dropdown im Panel) |
| F21 | Fokus-freies Panel + Auto-Insert; Bestätigung als Fallback-Setting |
| F22 ⚠ | Android migriert SOFORT mit v1 aufs Entitätenmodell |
| F23 | Datei-Export bleibt; v3 = Peer-Wire-Format, eine Codec-Implementierung |
| F24 | Einheit heißt „Profil" (`Profile`) |
| F26 ⚠ | Discovery: manuell+QR UND Tailscale-API-Enumeration (hinter Port) |
| F27 | Root-Hash + per-Entity-contentHash; kanonische Serialisierung = v3-Format |
| F28 | Jedes Gerät pollt selbst; Notification als Sync-Ergebnis; Intervall konfigurierbar |
| F29 | Abonnierte Kopien read-only; Bearbeiten = explizites Fork-Abkoppeln |
| F30 | Peer = Adresse+Pairing-Credential; `peerId` public-key-fähig ausgelegt |
| F31 | Gateway-Pfad: nur drei Nahtstellen (AI-Naht, `GATEWAY`-Enum reserved, Protokoll-Namensraum) |
| F32 | Katalog-Familie auf dem bestehenden Wire-Stack |
| F33 | Offline-Peers still tolerieren; Staleness-Anzeige |
| F34 | Peer Explorer: Bezugs- + Angebots-Sicht; Katalog-Browsen in Editor-UIs; Android read-only |

Plan-interne Folge-Entscheidungen (D-Nummern, Form A):

### D1 — `:shared-ai` als viertes Modul, Package bleibt `net.devemperor.dictate.ai`

**Trigger:** Extraktion (F2) braucht einen Zielort; `:shared` hat den strengen
`SharedPurityTest` (jvmTarget 1.8, kein okhttp-api-Leak als Designziel).
**Decision:** Neues Gradle-Modul `:shared-ai` (pure `kotlin("jvm")`, eigene
Dependency-Policy: openai-java/anthropic-java/okhttp erlaubt, Android/Ktor
verboten via `SharedAiPurityTest`). Package-Name bleibt unverändert, damit die
`:app`-Diffs fast nur Build-Dateien betreffen.
**Rationale:** trennt Wire-Reinheit (`:shared`) von SDK-Schwere (`:shared-ai`);
minimiert Extraktions-Risiko. Details/Alternativen: ADR-Draft `adr-shared-ai-module`.

### D2 — Desktop-Pipeline als schlanker eigener Orchestrator, kein Port von `state/`

**Trigger:** Android-`state/` (19 Module) ist auf IME-Achsen zugeschnitten.
**Decision:** `companion/pipeline/` bekommt einen kleinen Zustandsautomaten
(4 Achsen: recording, pipeline-queue, review, panel) nach den ADR-0001-Regeln
(reine Reducer, eine Dispatch-Tür, IO in Effekten), aber als Neuimplementierung
mit ADR-0009-Queue-Semantik (seriell, geordnet).
**Rationale:** Muster teilen, nicht Code mit 15 irrelevanten Achsen schleppen.

### D3 — Entitäten leben pro Plattform in der Plattform-DB, Serialisierung in `:shared`

**Trigger:** Room (Android) und SQLDelight (Companion) können keine Entities teilen.
**Decision:** `:shared` definiert die plattformneutralen Entitäts-DTOs +
kanonische Serialisierung + `contentHash`; Room/SQLDelight halten je eigene
Tabellen und mappen über dünne Mapper (Vorbild `SessionEntityMapper`).
Parity-Tests erzwingen identische Enum-Vokabulare.
**Rationale:** SSoT fürs Format, native Persistenz pro Plattform — dasselbe
bewährte Muster wie beim Session-Sync (ADR-0020).

### D4 — Verständnis-Check-Antworten (Team-Lead + User, 2026-07-19)

**Trigger:** Die 7 Rückfragen aus der Planvorstellung wurden vollständig
entschieden; alle Antworten bestätigen die Planannahmen.
**Decision:**
1. Chunk A1 legt den Plan-Ordner `docs/plans/2026-07-19 - desktop-companion-v1/`
   mit `adrs/` UND `research/` an und checkt die drei Konzept-Dokumente aus
   `tmp/desktop-concept/` als `research/` ein; die Plan-Datei selbst bleibt bis
   zur Archivierung in `~/.claude/plans/`.
2. Desktop-Audio: WAV 16 kHz mono für v1; Opus/OGG nur bei Bedarf später.
3. D2-Fokus-Spike: Der Fallback (Vordergrund-Fenster beim Hotkey merken, vor
   Insert restaurieren) ist ein definierter, gleichwertiger Pfad — Scheitern
   des `WS_EX_NOACTIVATE`-Spikes ist KEINE Eskalation; beide Pfade werden in
   `adr-desktop-panel-ui` dokumentiert.
4. Android-Profil-UX: KEIN Profil-Switcher in der Keyboard-UI — Profilwahl nur
   in den Settings (aktives Profil).
5. Android-Abo-Sync: voller Sync mit WorkManager-Hintergrund-Polling +
   System-Notifications; nur der Explorer ist read-only.
6. Der bestehende PC-Diktier-Modus (ADR-0027, Phone nimmt auf) bleibt
   unverändert parallel bestehen; der Desktop-Modus ist rein additiv.
7. C2 ist eine harte Migration ohne Koexistenz-Flag; Rollback-Pfad =
   Prefs-Backup-Export vor der Migration.
**Rationale:** Alle Antworten wählen die im Plan bereits verankerte Option —
keine Struktur- oder Sequenzänderung nötig; §10 Gaps 1–3 damit geschlossen.

### D5 — Cross-Spec-Entscheidungen der Spec-Vertiefung (Architekt, 2026-07-20)

**Trigger:** Fünf Opus-High-Specs (siehe SSoT-Block oben) haben sieben
blockübergreifende Punkte aufgeworfen bzw. offen gelassen; Auftrag Team-Lead
2026-07-20, Entscheidung durch den Plan-Architekten.

**Decision:**

a) **Enum-Layering A↔C — Wire-Enums in `:shared` BESTÄTIGT, kein Move von
   `AIProvider` nach `:shared`, keine `:shared-ai`→`:shared`-Kante.**
   `:shared` definiert `ProviderType`/`ProviderKind`/`ModelFunction`/
   `AmbiguityModeValue`/`PromptSelectionMode` selbst (entitaetenmodell §4.8);
   Mapper + Paritäts-Tests leben in `:app` (sieht beide Module) und sind
   Pflicht-Gate. Begründung: Das ist exakt die bestehende Wire-vs-Domain-
   Doktrin des Repos (ADR-0016: `SessionOriginWire` ↔ `SessionOrigin` +
   `SessionEntityMapper` + Paritäts-Tests) — die Domain-Enums tragen Verhalten
   (`AIProvider`-Capabilities/BaseUrls, `AmbiguityMode.forcesTurn`), das nicht
   ins Wire-Modul gehört; ein Move würde zudem die von Block A bewusst
   vermiedene Modul-Kopplung einführen (shared-ai-Spec §1a.0: „beide sind
   unabhängige Blätter") und das Package-erhaltende Move-Konzept brechen.
   Drift ist test-verhindert, nicht konventions-verhindert — identisch zum
   Bestand. Schließt entitaetenmodell §14 Gap 1; der §4.8-TIP (Alternative) ist
   damit verworfen.

b) **Companion-Entitäts-Tabellen: D3 legt sie VOLLSTÄNDIG an (inkl.
   Provenienz-Spalten nach der DDL aus peer-katalog §5.2); E1 legt nur
   `peers`/`subscriptions`/`catalog_access_log` an. Neue Kante E1→D3.**
   Begründung: Ein Migrations-Owner statt Zwei-Stufen-Schema (Spec-Option
   „wer zuerst landet" ist nicht deterministisch planbar); die Gegenrichtung
   (D3→E1) erzeugte einen Block-Zyklus D↔E, weil E3 ohnehin in die
   D3-Editor-Screens integriert. Provenienz-Spalten bleiben bis E2 NULL —
   harmlos. Schließt peer-katalog §15 Gap 1.

c) **D1-Sub-Schnitt VERBINDLICH übernommen: D1a (SQLDelight-Vollparität +
   `received_texts`-Ablösung + Sync-Umbau) vor D1b (Capture + Pipeline).**
   Begründung: Die Ablösung mit fünf ohne Assertion-Änderung grün zu
   haltenden Bestands-Tests (`SyncE2ETest`, `CompanionE2ETest`,
   `MultiConnectorE2ETest`, `TruncatedResponseE2ETest`,
   `SqlDelightHistoryRepositoryTest`; desktop-host §3.5) ist das höchste
   Regressionsrisiko des Blocks — als eigener Chunk bekommt sie fokussiertes
   Audit und entkoppelt das Risiko vom Pipeline-Aufbau (desktop-host §14 D2,
   dort „optional" — hier verbindlich).

d) **`PromptTypeClassifier` bleibt in `:app` — bestätigt** (Abweichung von
   der ursprünglichen Plan-A2-Liste). Begründung: hängt an `PromptType`
   (16 Pill-Dateien, ADR-0024); Pills sind bewusst desktop-fremd (F6 des
   Anforderungskatalogs / shared-ai §9). Plan-A2-Stub korrigiert.

e) **`AmplitudeProcessor`: MOVE nach `:shared-ai` (package-erhaltend,
   `net.devemperor.dictate.core`), ausgeführt in A2 — statt Kopie in
   `:companion`** (überstimmt desktop-host §15 Gap 4 „v1: Kopie").
   Begründung: F19 verlangt 1:1-Design-Parität des Recording-Kerns — die
   Amplituden-Kurvenparameter SIND die Design-Spec; eine Kopie driftet
   unsichtbar. Die Klasse ist pures `kotlin.math`, der Move kostet einen
   `git mv` im ohnehin laufenden A2.

f) **WorkManager bestätigt** (`androidx.work:work-runtime-ktx` als neue
   `:app`-Dependency; peer-katalog §6.5). Prüfauftrag an den E2-Agenten:
   Kandidaten-Version VOR Einbau gegen Kotlin-Metadata ≤ 2.1.20 prüfen und im
   Chunk dokumentieren (R4); Fallback `AlarmManager`+`JobScheduler`-Eigenbau
   nur nach belegter Inkompatibilität. Deckt D4.5 (volles
   Hintergrund-Polling).

g) **`received_texts`-Ablösung bestätigt als Plan-Entscheidung** (ersetzt
   §10 Gap 5): Ablösung statt Koexistenz; Sync-Felder wandern in die
   1:1-Begleittabelle `dispatch_state`; Backfill `received_texts`→`sessions`
   (+`dispatch_state`) in der SQLDelight-Migration, danach DROP
   (desktop-host §3.4/§14 D1). Verhaltensneutralitäts-Beweis = die fünf
   Bestands-Tests aus (c).

**Zusatzfestlegung (Folge aus b/c/g) — SQLDelight-Migrations-Nummern:**
D1a und E1 beanspruchten beide `2.sqm`. Verbindliche Vergabe nach
Sequenzierung: **D1a = `2.sqm`** (Parität + dispatch_state + Backfill/Drop),
**D3 = `3.sqm`** (Entitäts-Tabellen), **E1 = `4.sqm`**
(peers/subscriptions/catalog_access_log).

**Rationale gesamt:** Alle sieben Punkte folgen denselben Kriterien: bestehende
Repo-Doktrin vor Neuerfindung (a), ein Owner pro Schema-Fläche (b, g),
Risiko-Isolation in eigene Audit-Einheiten (c), DRY über Modul-Grenzen nur wo
Verhalten identisch bleiben MUSS (e), dokumentierte Ceiling-Prüfung statt
Verbot (f).

## 4. Directory Layout (Ziel-Topologie)

```
Dictate/
├── shared/                                  [EDIT]  + catalog/-DTOs, Entitäten, v3-Codec, contentHash (C1, E1)
├── shared-ai/                               [NEW]   AI-Kern-Extraktion (A2/A3): ai/-Pakete + Ports + SharedAiPurityTest
├── app/
│   └── src/main/java/net/devemperor/dictate/
│       ├── ai/                              [MOVE]  Großteil → :shared-ai; Rest: Android-Port-Implementierungen (A3)
│       ├── secrets/                         [NEW]   SecretStore-Android-Impl (Keystore AES-GCM) + Migration (B1/B2)
│       ├── config/                          [NEW]   Entitäten-Room-Tabellen, Mapper, Profil-Resolver (C2)
│       ├── settings/APISettingsActivity     [EDIT]  Umbau auf Entitätenmodell (C3)
│       ├── rewording/                       [EDIT]  Herkunfts-Badge, v3-Import/Export (C3)
│       └── peers/                           [NEW]   Abo-Sync-Bezieher + read-only Explorer (E2/E3)
├── companion/src/main/kotlin/.../companion/
│   ├── capture/                             [NEW]   javax.sound-Aufnahme, Segmente, AmplitudeFeed (D1b)
│   ├── pipeline/                            [NEW]   Desktop-Orchestrator (D2-Entscheidung), Queue, Review-Logik (D1b/D3)
│   ├── hotkey/                              [NEW]   GlobalHotkey-Port + Win32-Impl + Noop (D2)
│   ├── ui/panel/                            [NEW]   Mini-Panel (fokus-frei), Recording-UI, Review-UI, Profil-Dropdown (D2/D3)
│   ├── ui/{prompts,models,profiles,peers}/  [NEW]   Verwaltungs-Screens + Peer Explorer (D3/E3)
│   ├── data/                                [EDIT]  Schema-Parität + dispatch_state (D1a, 2.sqm), Entitäts-Tabellen (D3, 3.sqm), peers/subscriptions/access_log (E1, 4.sqm)
│   ├── secrets/                             [NEW]   SecretStore-Desktop-Impl (DPAPI + Fallback) (B1)
│   ├── catalog/                             [NEW]   Katalog-Routes, Sync-Engine, Discovery-Port (E1/E2)
│   └── Main.kt                              [EDIT]  --headless-Flag (E3)
└── docs/plans/2026-07-19 - desktop-companion-v1/
    └── adrs/                                [NEW]   8 plan-scoped ADR-Drafts (A1, §6)
```

**File-Delta grob:** 1 neues Modul, ~6 neue Companion-Subsysteme, ~4 neue
App-Pakete, ~25 verschobene Dateien, 8 ADR-Drafts.

## 5. Building Blocks

> Format je Chunk: **Stub** — Ziel + Spec-Verweis + geschärfte Akzeptanz.
> Die fachliche Detail-SSoT ist die jeweilige Spec unter `research/` (siehe
> SSoT-Block am Dateianfang); dieser Plan wiederholt sie nicht. Ein Chunk =
> ein großer Fokusbereich für einen Agenten (v3-Konvention). Der
> Implementierungs-Agent liest die Spec-§§ seines Chunks VOLLSTÄNDIG.

### Block A — Fundament: ADRs + `:shared-ai`-Extraktion

**Goal:** Der AI-Kern existiert genau einmal, pure JVM, hinter Ports;
Android verhält sich unverändert. Alle Grundsatz-ADRs sind als Drafts fixiert.

**Chunk A1 — ADR-Drafts (alle 8) + Konzept-Research einchecken.** Autor-Chunk
ohne Produktions-Code: legt `docs/plans/2026-07-19 - desktop-companion-v1/`
mit `adrs/` und `research/` an; checkt die drei Konzept-Dokumente aus
`tmp/desktop-concept/` (bestandsaufnahme, konzept-skizze, fragenkatalog) als
`research/` ein (D4.1); schreibt die 8 Drafts nach §6-Spezifikation (Format:
`knowledge-adr-format` + `~/.claude/templates/adr.md`; Status `Proposed
(plan-scoped — pending promotion)`; Filename ohne Nummer). Die fünf Specs +
`reports/e2e-runbook.md` liegen bereits im Plan-Ordner und werden mit
eingecheckt. Akzeptanz: 8 ADR-Dateien, jede mit allen Pflichtsektionen inkl.
Alternatives + Decision History Initial-Entry; Entscheidungs-Inhalte
deckungsgleich mit §3 (inkl. D5!); `research/` enthält Konzept-Dokumente +
Specs, `tmp/desktop-concept/` danach obsolet (löschen).

**Chunk A2 — Pure Moves.** → **Spec `shared-ai-extraktion.md`**: Inventar
§3.1–3.4, Gradle-Scaffold + Purity §5, Move-Schritte A2.0–A2.3 (§6),
Directory §7. Enum-Moves package-erhaltend (Split-Package-Muster, §3.4);
**per D5.d bleibt `PromptTypeClassifier` in `:app`** (Spec §9-Footgun);
**per D5.e zusätzlich `core/AmplitudeProcessor.kt` package-erhaltend nach
`:shared-ai` moven** (nicht im Spec-Inventar — Nachtrag Spec §11).
Akzeptanz: Spec-§2 Kriterien 1–3, 5 (Modul compiliert auf jvmTarget 1.8;
Build grün; `SharedAiPurityTest` grün inkl. Negativ-Selbsttest;
`git log --follow` intakt).

**Chunk A3 — Ports + Runner/Orchestrator.** → **Spec
`shared-ai-extraktion.md`**: Port-Signaturen §4.1–4.5 (`AiConfig`,
`UsageSink`, `ProxyConfig`, `AudioDurationReader`), App-Kopplungen §3.5,
Move-Schritte A3.1–A3.7 (§6), Charakterisierungs-Tests §8.1–8.2
(VOR dem Move geschrieben). `org.json` → kotlinx-serialization (A3.4).
Akzeptanz: Spec-§2 Kriterien 2, 4, 6 (Verhaltensneutralität: alle
`:app`-Tests grün ohne Assertion-Änderung, kein Diff im API-Traffic;
kein AI-Kern-Pfad liest mehr direkt SharedPreferences/UsageDao/
MediaMetadataRetriever — grep-Prüfung).

**Risiken:** Spec §9 (Footgun-Tabelle) + §10 (Gaps 1–4; Gap 4
SDK-Bytecode-Target = Eskalationsfall, siehe §9 R9 hier).

### Block B — SecretStore (projektweit)

**Goal:** Kein Klartext-Key mehr auf keiner Plattform; ein Port, zwei Impls.

**Chunk B1 — Port + Impls.** → **Spec `secretstore.md`**: Port-Design §4
(`SecretStore` in `:shared-ai`, Package `net.devemperor.dictate.ai.secrets`,
keine neue Dependency), Android-Keystore-Impl §5 (inkl. Robolectric-
Cipher-Seam §5.4, Backup-Ausschluss §5.3), Desktop-Impl §6 (DPAPI via
vorhandenem jna-platform `Crypt32Util` + File-Fallback, `available`-Flag).
Akzeptanz: Spec-§2 Kriterien 1–3 (Port geteilt + Build/Purity grün;
Round-Trip pro Impl byte-identisch, DPAPI als pending; Fehler-Semantik
`DecryptionFailed` statt Leerstring).

**Chunk B2 — Android-Key-Migration.** → **Spec `secretstore.md`**:
Migrations-Design §7 — verbindlich **11 Slots** (alle `Pref.*ApiKey*` PLUS
`WindowsDeviceSecret`, §7.1/Gap 1), Legacy-Namespace `SecretRef("legacy",…)`
mit C2-Re-Mapping (§7.2 — B2 läuft VOR C2), Reihenfolge-Invariante
Backup→put→remove→Flag (§7.3), Rollback-Export §7.6.
Akzeptanz: Spec-§2 Kriterien 4–6 (11-Slot-Fixture verlustfrei; idempotent +
frische Installation ok; kein Codepfad liest alte Pref-Keys — grep-Test).

**Risiken:** Spec §11 (Footguns: IV-Reuse, Pref-vor-put-Löschung,
Backup-Blob ohne Key) + §4.3/§5.3 (KEK gerätegebunden — Re-Entry nach
Restore, in `adr-secret-store` festgehalten).

### Block C — Entitätenmodell + Android-Umbau (F22)

**Goal:** ProviderConfig/ModelRef/Prompt/Profil als teilbare Entitäten mit
kanonischer Serialisierung; Android läuft vollständig darauf.

**Chunk C1 — Entitäten + v3-Codec (`:shared`).** → **Spec
`entitaetenmodell-android.md`**: Entitäts-DTOs + Envelope §4.1–4.7,
Wire-Enums §4.8 (**per D5.a bestätigt** — `ProviderType`/`ProviderKind`/
`ModelFunction`/`AmbiguityModeValue`/`PromptSelectionMode` in `:shared`,
Paritäts-Tests + Mapper in `:app`; der §4.8-TIP ist verworfen), kanonische
Serialisierung + contentHash + v3-Format §5 (inkl. `keyFingerprint`-Regel
§4.4 und Recompute-on-write §5.3). `GATEWAY` reserviert (F31).
Akzeptanz: Spec-§2 AK1–AK3 (Codec + Purity; Kanonik-Stabilität inkl.
Key-Umordnung; v3-Round-Trip v1/v2/v3) + Enum-Paritäts-Tests grün.

**Chunk C2 — Android-Persistenz + Profil-Resolver.** → **Spec
`entitaetenmodell-android.md`**: Room v11→v12 §7 (5 neue Tabellen +
`prompts`-Recreate, CHECKs), Prefs→Entitäten-Migration §8 (Backup §8.4 VOR
allem; Default-Profil §8.5 in EINER Transaktion; deterministische UUIDs
§8.6; SecretStore-Re-Mapping legacy→credential per secretstore §7.2),
`ProfileResolver` als `AiConfig` §9 (+ Charakterisierungs-Test §9.4).
Akzeptanz: Spec-§2 AK4–AK7 (MigrationTest CHECK-Annahme/-Ablehnung;
byte-gleiche Runner-Konfiguration; grep-Keyfreiheit; Backup + Idempotenz).

**Chunk C3 — Android-UI-Umbau.** → **Spec `entitaetenmodell-android.md`**:
Settings-Umbau §10 (Provider-/Modell-/Profil-Verwaltung §10.1–10.3 — NUR
Settings, kein Keyboard-Switcher per D4.4; Import-Dispatcher §10.4;
v3-Export §10.5; PromptsOverview-Badge §10.6). ADR-0024/Pills unangetastet.
Akzeptanz: Spec-§2 AK8–AK9 + manuelle E2E-Checkliste (Profil anlegen →
diktieren → Modellwechsel wirkt; Fälle im `reports/e2e-runbook.md`).

**Risiken:** größter Bestandseingriff (783-Zeilen-Activity); harte Migration
ohne Koexistenz-Flag (D4.7) mit Prefs-Backup als Rollback; Spec-§14 Gaps 2–5
(Anthropic-Freitext, SecretRef-Format, Backup-Aufräumung, Custom-Dedup) mit
benannten Ownern.

### Block D — Desktop-Diktat (Companion-Host)

**Goal:** Vollständiges Diktat am PC: Aufnahme → Pipeline → Insert/Review,
mit warmem Panel und Hotkey.

**Chunk D1a — Schema-Vollparität + `received_texts`-Ablösung (per D5.c).**
→ **Spec `desktop-host.md`**: Enum-Vokabulare §3.2, Tabellen-Übersetzung
§3.3, Ablösung + `dispatch_state` + Backfill/Drop §3.4 (**Migration
`2.sqm`**, D5-Nummernvergabe), Sync-/Repo-Umbau §3.5, Parity-Test-Design
§3.6. Akzeptanz: Spec-§2 Kriterien 1–4 — insbesondere: die fünf
Bestands-Tests (`SyncE2ETest`, `CompanionE2ETest`, `MultiConnectorE2ETest`,
`TruncatedResponseE2ETest`, `SqlDelightHistoryRepositoryTest`) bleiben
**ohne Assertion-Änderung** grün; Parity-Suite rot bei künstlichem Drift;
`received_texts`-MigrationTest verlustfrei.

**Chunk D1b — Aufnahme + Desktop-Pipeline.** → **Spec `desktop-host.md`**:
Capture §4 (WAV 16 kHz mono §4.1, Geräte §4.2, Rolling-Segments §4.3,
Amplituden-Feed §4.4 — nutzt den per D5.e nach `:shared-ai` gemovten
`AmplitudeProcessor`; Upload-Limit-Verifikation §4.5), Pipeline §5
(Controller §5.1, Phasenmodell §5.2, `DesktopUiState` §5.3,
Reducer-Reinheit §5.4, Schritte §5.5, Queue §5.6; Übergangs-`AiConfig`
aus `CompanionSettings` bis D3). Akzeptanz: Spec-§2 Kriterien 5–6
(Headless-Diktat-E2E mit Fake-Runnern: WAV-Fixture → Session +
Transcription + Step + Conversation persistiert; Reducer-Übergänge +
Enqueue unit-getestet).

**Chunk D2 — Hotkey + Panel + Recording-UI + Insert.** → **Spec
`desktop-host.md`**: `GlobalHotkey`-Port §6.1, `PanelWindowControl` §6.2,
Fokus-Spike (Zeitbox ~1 Tag) + `FocusRestorationPolicy`-Fallback §6.3
(D4.3: Scheitern keine Eskalation; pending-Test nicht „grün faken"),
Recording-UI-Nachbau §7 (Parameter-Tabellen §7.1–7.3, Compose §7.4),
Insert/Auto-Insert §8.5. Akzeptanz: Spec-§2 Kriterien 8–10 (Fokus-Politik
beide Pfade unit-getestet; Design-Parameter-Übernahme; manuelle
Windows-Abnahme per e2e-runbook).

**Chunk D3 — Review + Profil/Modell/Prompt-UI + Entitäts-Tabellen.**
→ **Spec `desktop-host.md`**: Review §8 (AmbiguityMode §8.1,
`ReviewDecision` verbatim §8.2, Re-dictate §8.3, Zustände §8.4,
Insert/Discard §8.5), Verwaltungs-/History-UI §9 (Panel-Einstieg +
Profil-Dropdown §9.1, Screens §9.2, History §9.3). **Per D5.b legt D3
zusätzlich die Companion-Entitäts-Tabellen VOLLSTÄNDIG an** —
`provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts`
nach der DDL aus peer-katalog §5.2 inkl. Provenienz-Spalten, als
**Migration `3.sqm`**, Enum-Parität zu C1 per Parity-Test. Akzeptanz:
Spec-§2 Kriterium 7 (Verdikt-Matrix + `REVIEW_REFINEMENT`-Session) +
Review-E2E mit Fake-Runner + Entitäts-Tabellen-Migration mit Test.

**Risiken:** Spec §13 (Footgun-Tabelle: ReviewDecision-Nachbau,
Sync-Cursor-Ordnung, Reducer-IO, companion-lokale Enum-Kopien) + §15
(Gap 2 Upload-Limits, Gap 3 Cold-Resume bewusst v1-out, Gap 5
C1-Stub-Koordination).

### Block E — Peer-Katalog + Abo-Sync + Peer Explorer

**Goal:** Entitäten teilen und beziehen zwischen Peers im Tailnet; Provenienz
sichtbar; headless Hub-Peer möglich.

**Chunk E1 — Protokoll + Server-Seite.** → **Spec `peer-katalog.md`**:
Wire-Familie §3 (DTOs/Validations/Endpoints/`supportsCatalog`,
`CatalogClient`), Server §4 (`CatalogService`/Routes, Root-Hash §4.2,
Credential-Call + Audit-Zeile §4.3), Schema §5 — **per D5.b nur**
`peers`/`subscriptions`/`catalog_access_log` (**Migration `4.sqm`**); die
Entitäts-Tabellen kommen aus D3 (neue Kante E1→D3). Akzeptanz: Spec-§2
AC1–AC5 (Additivität ohne Version-Bump; Konform-Vollständigkeit;
Root-Hash-Determinismus; Auth-Parität 401; Credential-Isolation +
Audit-Zeile pro Auslieferung).

**Chunk E2 — Sync-Engine + Benachrichtigung + Android-Bezieher.** → **Spec
`peer-katalog.md`**: Engine §6 (No-Op-Pfad §6.1, Verify-vor-Write §6.3,
Fork-Schutz §6.4, Offline §6.5-Staleness), Scheduler §6.5 (Companion-Timer;
Android `CatalogSyncWorker` unter WorkManager — **per D5.f bestätigt**,
Ceiling-Prüfauftrag VOR Einbau dokumentieren), Notification §7 (AWT
`SystemTray` per Spec-D6; Android System-Notification). Akzeptanz: Spec-§2
AC6–AC10 (Idempotenz mit genau einem GET; Update-Erkennung; Fork-Schutz
`subscription_mode=NULL`; Staleness statt Fehler; Zwei-Peer-E2E §11).

**Chunk E3 — Peer Explorer + Discovery + headless.** → **Spec
`peer-katalog.md`**: Explorer §8 (Bezugs-/Angebots-Sicht, Zustandsmatrix
§8.1; Android read-only), Discovery + headless §9 (`PeerDiscovery`-Port,
Tailscale-CLI-Impl + Noop §9.2; `--headless` §9.3), Editor-Integration in
die D3-Screens. Akzeptanz: Spec-§2 AC11–AC13 (Discovery-Fallback leere
Liste; headless-Boot ohne Compose; Explorer-Zustandsmatrix).

**Risiken:** Spec §15 (Gap 3 Tray-Koexistenz-Spike Compose-`Tray` vs. AWT
`TrayIcon` — Owner E2; Gap 4 `peerId`-Bildung v1 = opaque UUID; Gap 5
Tailscale CLI vs. LocalAPI; Gap 6 Credential-`contentHash`-Basis, gekoppelt
an secretstore §12 Gap 4 — Owner E1 + `adr-peer-catalog`).

### Block F — Abschluss: ADR-Promotion + Doku + E2E

**Goal:** Entscheidungen promoted, Dokumentation konsistent, Gesamtabnahme.

**Chunk F1 — Promotion + Doku + Abnahme.** ADR-Drafts promoten
(nächste freie Nummern ab 0028, Index-Zeilen, Cross-Links Plan↔ADR
bidirektional); ADR-0015 Decision-History-Eintrag (viertes Modul),
ADR-0017-Erweiterungs-Verweise; `CLAUDE.md` (Module, `:shared-ai`, neue
Konventionen SecretStore/Entitäten), `docs/DATABASE-PATTERNS.md`
(SQLDelight-Parity-Abschnitt), Companion-README; manuelle
E2E-Abnahme-Checkliste (Windows-Gerät: §2 Kriterien 3/4/7 durchspielen) als
Runbook-artige Checkliste im Plan-Ordner. Akzeptanz: §2 Kriterium 9;
docs-Referenzen ohne tote Links; Abnahme-Checkliste vom User abgehakt.

## 6. ADR-Drafts (plan-scoped)

Ablage: `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-{slug}.md`,
Status `Proposed (plan-scoped — pending promotion)`, Format per
`knowledge-adr-format` (alle Pflichtsektionen; Research zitiert
`tmp/desktop-concept/`-Recherche + Fragenkatalog-Entscheidungen). Inhalte:

| Slug | Kernentscheidung | Verhältnis zu bestehenden ADRs |
|---|---|---|
| `adr-shared-ai-module` | Viertes pure-JVM-Modul `:shared-ai` für den AI-Kern, Ports (AiConfig/UsageSink/ProxyConfig/AudioDurationReader), eigene Purity-Policy, Package unverändert | erweitert ADR-0015 (Decision-History-Eintrag dort bei Promotion) |
| `adr-secret-store` | Projektweiter `SecretStore`-Port (Keystore/DPAPI/Fallback), Migration der Klartext-Keys, Keys gerätegebunden | löst den expliziten Defer aus ADR-0017 §F-3 auf |
| `adr-config-entity-model` | ProviderConfig/ModelRef/Prompt/Profil als Entitäten; kanonische Serialisierung + contentHash = v3-Format; `GATEWAY` reserved; Android-Migration Prefs→DB | neue Grundlage; berührt ADR-0024 (Prompt-Felder), ADR-0012 (Modell-Auflösung via Profil) |
| `adr-desktop-dictation-host` | Companion wird Aufnahme+Pipeline-Host (javax.sound, serielle Queue nach ADR-0009-Semantik, `:shared-ai`-Pipeline); Phone-Pfad bleibt unverändert | erweitert ADR-0017-Rollenmodell (Companion nicht mehr nur Empfänger); ADR-0007-Muster übernommen |
| `adr-desktop-panel-ui` | Compose-Mini-Panel: rahmenlos, always-on-top, fokus-frei (Spike + Fallback), globaler Hotkey hinter Port, Auto-Insert-Politik F21 | Analogon zum Render-Host-Muster ADR-0004/0027 auf Desktop-Seite |
| `adr-desktop-review` | Voller Prüfmodus auf dem Desktop-Host inkl. Re-dictate; geteilte `ReviewDecision`-Authority | **revidiert** die „Review ist IME-only"-Festlegung aus ADR-0013/ADR-0027-F8 (Supersede-Teilaspekt, beide referenzieren) |
| `adr-peer-catalog` | Peer-Katalog-Familie auf dem Wire-Stack: pull-only, Root-Hash + contentHash, SUBSCRIBE/ONE_SHOT, Fork-Regel, Envelope-Credential-Delivery, Discovery-Port, headless Peer, `/v1/ai/*`-Namensraum reserved | erweitert ADR-0016/0025 (additive Familie), erweitert ADR-0017/0020 (neue Autoritäts-Richtung nur für Konfigurations-Entitäten, Diktate ausgenommen per F16) |
| `adr-companion-history-parity` | Volle Session-Schema-Parität in SQLDelight + Parity-Test-Pflicht; `received_texts`-Ablösung; Companion-DB als gemeinsames Archiv (F16) | erweitert ADR-0014/0020 (Filter-Definitionsgleichheit gilt auch für Desktop-Sessions) |

## 7. Sequenzierung und Parallelisierung

```
A1 (ADRs) ──┐
A2 ── A3 ───┼──→ B1 ── B2 ──┐
            │                ├──→ C2 ── C3
            └──→ C1 ─────────┘     │
A2 ──→ D1a ──→ D1b ──→ D2 ────────┤
        │               └── D3 ←──┘  (D3 braucht C1-Profil-Typen;
        │                    │        legt Entitäts-Tabellen an, D5.b)
C1 ─────┼───────────────────→│
        └────────────→ E1 ←──┘ ── E2 ── E3
B1 ───────────────────────────────┘ (Credential-Ablage)
alles ──→ F1
```

Begründung:

- **A zuerst** — jede weitere Arbeit baut auf `:shared-ai` (D, C2) bzw. den
  ADR-Fixierungen (alle) auf. A1 ist von A2/A3 unabhängig und läuft parallel.
- **B früh** — C2 (Key-Ablage im Entitätenmodell) und E2 (Credential-Bezug)
  brauchen den SecretStore; B1 hängt nur am A2-Scaffold.
- **C1 parallel zu A3/B1** — reine `:shared`-Arbeit ohne Abhängigkeit auf den
  AI-Kern. C2/C3 danach seriell (Migration vor UI).
- **D parallel zu C** ab A2/A3: **D1a** (Schema/Sync, per D5.c eigener Chunk)
  braucht nur die A2-Enum-Moves; **D1b** (Capture/Pipeline) braucht A3 und
  läuft gegen eine Übergangs-`AiConfig` aus `CompanionSettings`; erst D3
  gates auf C1-Typen und legt zusätzlich die Companion-Entitäts-Tabellen an
  (D5.b, Migration `3.sqm`).
- **E nach C1 + D3 + B1** (D5.b: E1 braucht die D3-Entitäts-Tabellen; E1
  selbst legt nur `peers`/`subscriptions`/`catalog_access_log` als `4.sqm`
  an). E verliert damit etwas Parallelität zu D — bewusst in Kauf genommen,
  weil E3 ohnehin in die D3-Editor-Screens integriert und die Alternative
  (D3→E1) einen Block-Zyklus D↔E erzeugt hätte.
- **F strikt zuletzt** (Promotion erst, wenn die Entscheidungen implementiert
  „aktiv" sind — lifecycle-adr-Regel).

Block-Zahl-Begründung (v3 empfiehlt 1 Block/Plan): Das Vorhaben umfasst fünf
fachlich disjunkte Programme (Extraktion, Security, Konfigurationsmodell,
Desktop-Host, Verteilung) mit echten Parallelisierungs-Gewinnen und
unterschiedlichen Audit-Linsen — ein Monoblock würde die Audit-Zyklen
entwerten. 6 Blöcke / 16 Chunks (nach D5.c) ist die gröbste Schnittführung,
die die Abhängigkeits-Gates aus dem Diagramm noch abbildet.

## 8. Test-Strategie

Konventionen: `~/.claude/snippets/test-first-patterns.md` (TDD für Neubau,
pending für dokumentierte Lücken, Regression-Tests für jeden Bugfix — rot vor
grün).

1. **Charakterisierungs-Tests VOR Extraktion (A3, C2):** Verhalten des
   Bestands (Runner-Konfiguration aus Prefs, Proxy-Anwendung) wird als Test
   fixiert, BEVOR verschoben/migriert wird; dieselben Tests laufen danach
   gegen die Port-Adapter — das ist der Verhaltensneutralitäts-Beweis.
2. **Parity-Tests (D1, laufend):** Room-Schema (exportierte JSON-Schemas in
   `app/schemas/`) ↔ SQLDelight (`verifyMigrations` + Schema-Snapshot) —
   automatisierter Abgleich der Enum-Vokabulare und CHECK-Constraints
   (Vorbild `OriginCheckConstraintParityTest`); Pflicht-Test bei jeder
   Schema-Änderung (in `adr-companion-history-parity` verankert).
3. **Kanonik-Snapshots (C1):** Byte-Snapshots der kanonischen Serialisierung
   pro Entitätstyp; Hash-Determinismus (gleicher Inhalt ⇒ gleicher Hash,
   Feld-Umordnung ⇒ gleicher Hash, Wertänderung ⇒ neuer Hash).
4. **Migrations-Tests (B2, C2):** Room `MigrationTestHelper` v11→v12 mit
   befüllter Fixture; Prefs-Fixtures für Key- und Provider-Migration;
   Idempotenz (doppelter Lauf = No-Op).
5. **E2E-Tests im Companion (D1, E1, E2):** bestehendes Muster
   (`CompanionE2ETest`, In-Memory-Container, echter Ktor-Server) erweitern:
   Diktat-Pipeline mit Fake-Runnern; Zwei-Peer-Katalog-Szenarien
   (Änderung/Fork/Offline/One-Shot); Truncated/Malformed-Payloads gegen die
   Katalog-Routes (`TruncatedResponseE2ETest`-Vorbild).
6. **Review-Matrix (D3):** ADR-0013-Verdikt-Matrix (3 Modi × needsClarification
   × message-blank) als parametrisierte Suite gegen den Desktop-Aufrufer.
7. **UI-nahe Tests:** ViewModel-Tests nach bestehendem Companion-Muster
   (`HistoryViewModelTest` …) für Panel-, Review-, Explorer-ViewModels;
   Android: Robolectric-Smoke für neue Settings-Navigation. Kein
   Screenshot-Diffing (kein Bestand dafür).
8. **Pending-Tests:** `GATEWAY`-Enum-Ablehnung (reserviert, nicht wählbar) als
   aktiver Test; fokus-freies Fenster als pending, bis der D2-Spike
   entschieden ist (`pending: D2-focus-spike`).

## 9. Risiken

| # | Risiko | Schwere | Mitigation |
|---|---|---|---|
| R1 | Fokus-freies Compose-Fenster unter Windows nicht sauber machbar | mittel | Spike als erste D2-Aufgabe; entschiedener Fallback (Fokus-Restauration) per F21; ADR-Draft dokumentiert beide |
| R2 | C2/C3-Migration beschädigt reale Nutzerdaten | hoch | Fixture-basierte MigrationTests, Prefs-Backup-Export vor Migration, Superset-Mapping dokumentiert |
| R3 | Doppel-Schema-Drift Room↔SQLDelight | mittel | Parity-Tests als Pflicht-Gate (ADR-verankert), gemeinsame Enum-Quellen in `:shared` wo möglich |
| R4 | Kotlin-Ceiling 2.1.20 blockiert eine gewünschte neue Dependency (Audio-Encoder, DPAPI-Wrapper) | mittel | Dependency-Kandidaten je Chunk VOR Einbau gegen Ceiling prüfen; Präferenz: JNA-Eigenbau statt neuer Libs (DPAPI, RegisterHotKey sind kleine Flächen) |
| R5 | javax.sound-Gerätezoo (Default-Mixer falsch, Sample-Raten) | mittel | Geräteauswahl-UI + persistierte Wahl; WAV 16-bit/16-kHz-Downsample als robuster Default (§10 Gap 2) |
| R6 | Scope-Größe (5 Programme in einem Plan) | hoch | strikte Block-Gates (§7), Audit pro Block (v3), Verständnis-Check-Rückfragen vor Start (§10) |
| R7 | ADR-0017/0020-Erweiterung wird im Review als Supersede-pflichtig bewertet | niedrig | `adr-peer-catalog`/`adr-desktop-dictation-host` sind explizit als Erweiterungs-ADRs geschnitten; Konfliktflächen im Draft benannt |
| R8 | Credential-Bezug öffnet ungewollte Key-Exfiltration im Peer-Netz | mittel | Secret-Auslieferung nur über expliziten, einzeln autorisierten Call; nie im Index; Audit-Log-Zeile pro Auslieferung (E1) |
| R9 | AI-SDKs tragen >Java-8-Bytecode → `:shared-ai` mit jvmTarget 1.8 nicht baubar (shared-ai §10 Gap 4) | niedrig | Annahme früh in A2 verifizieren; falls verletzt: echter Blocker → Eskalation (würde `:app`-jvmTarget-Bump erzwingen, außerhalb Block A) |
| R10 | `api`-Sichtbarkeit der SDKs in `:shared-ai` leakt SDK-Typen breiter als nötig (shared-ai §5.2/§10 Gap 2) | niedrig | Im A2/A3-Compile prüfen, ob die Runner-Oberfläche SDK-Typen wirklich trägt; wenn nicht: auf `implementation` zurückstufen (schmaler ist besser) |
| R11 | Tray-Koexistenz Compose-`Tray` vs. AWT-`TrayIcon` (ein Slot, zwei Besitzer; peer-katalog §15 Gap 3) | niedrig | E2-Spike; Fallback: AWT-`TrayIcon` wird alleiniger Besitzer, Compose-`Tray` entfällt (Spec-D6) |
| R12 | Fokus-Spike frisst unbegrenzt Zeit | niedrig | Zeitbox ~1 Tag (desktop-host §6.3); danach verbindlicher Wechsel auf den `FocusRestorationPolicy`-Fallback (D4.3, keine Eskalation) |
| R13 | Split-Package (`database.entity`/`preferences`/`core` über Modulgrenze) als latenter Smell (shared-ai §10 Gap 1) | niedrig | Bewusster Trade-off für Null-Import-Diffs; im ADR-Draft dokumentiert; spätere Konsolidierung möglich |

## 10. Information Gaps

1. ~~**Ablageort der Konzept-Dokumente**~~ — **geschlossen 2026-07-19 (D4.1):**
   A1 checkt die drei Dokumente als `research/` ein, Plan-Ordner wird bei A1
   angelegt, Plan-Datei bleibt bis Archivierung in `~/.claude/plans/`.
2. ~~**Audio-Format Desktop**~~ — **geschlossen 2026-07-19 (D4.2):** WAV
   16 kHz mono für v1; Opus/OGG nur bei Bedarf später. Rest-Aufgabe für den
   D1-Agenten: Upload-Limits der Provider gegen ~2 MB/min verifizieren und im
   Chunk dokumentieren (reine Verifikation, keine offene Entscheidung).
3. ~~**Fokus-freies Fenster**~~ — **entscheidungsseitig geschlossen 2026-07-19
   (D4.3):** Spike bleibt als technische D2-Aufgabe; beide Ausgänge sind
   definierte Pfade (kein Eskalationsfall), Dokumentation in
   `adr-desktop-panel-ui`.
4. ~~**Anthropic-Modell-Liste**~~ — **verlagert 2026-07-20:** in der Spec
   `entitaetenmodell-android.md` §14 Gap 2 detailliert (Freitext + ModelRef-
   Kuration, Owner C3). Auf Plan-Ebene geschlossen.
5. ~~**`received_texts`-Bestandsdaten**~~ — **geschlossen 2026-07-20
   (D5.g):** Ablösung statt Koexistenz; `dispatch_state`-Begleittabelle,
   Backfill + DROP in Migration `2.sqm` (desktop-host §3.4/§14 D1); die fünf
   Bestands-Tests sind der Verhaltensneutralitäts-Beweis (D1a-Akzeptanz).
6. ~~**Benachrichtigungs-Mechanik Windows-Tray**~~ — **entscheidungsseitig
   geschlossen 2026-07-20:** AWT `SystemTray`/`TrayIcon.displayMessage`
   (peer-katalog §7.1/§14 D6); Rest-Unschärfe = Koexistenz-Spike mit
   Compose-`Tray` (peer-katalog §15 Gap 3, Owner E2 — hier R11).

Detail-Gaps leben seit der Spec-Vertiefung in den Specs selbst
(shared-ai §10: Gaps 1–4 · secretstore §12: Gaps 2–4 · entitaetenmodell §14:
Gaps 2–5 · desktop-host §15: Gaps 2/3/5 · peer-katalog §15: Gaps 2–6) —
jeweils mit benanntem Chunk-Owner und Fallback. Die durch D5 geschlossenen
Spec-Gaps (entitaetenmodell Gap 1, desktop-host Gap 4, peer-katalog Gap 1)
sind dort nachgetragen.

## 11. Iteration Log

### 2026-07-19 — Initialfassung

- **Trigger:** Alle 34 Fragenkatalog-Entscheidungen liegen vor (Team-Lead-
  Nachricht 2026-07-19); Auftrag Implementierungsplan.
- **Reasoning:** Plan direkt auf dem Entscheidungs-Stand aufgesetzt;
  Konzept-Skizze (`tmp/desktop-concept/konzept-skizze.md`) parallel auf den
  Entscheidungs-Stand gebracht (F1-Umschwenk Compose, F12/F15/F18/F22).
- **What changed:** Erstversion mit 6 Blöcken / 15 Chunks, 8 ADR-Draft-Specs,
  Sequenzierungs-DAG, Test-Strategie, Risiken, Gaps.

### 2026-07-19 — Verständnis-Check eingearbeitet, Status implementierungsbereit

- **Trigger:** Team-Lead-Antwort auf alle 7 Rückfragen (alle bestätigen die
  Planannahmen).
- **Reasoning:** Keine der Antworten ändert Struktur, Blöcke, Chunks oder
  Sequenzierung — Einarbeitung als Decision-Log-Eintrag D4 + Gap-Schließung.
- **What changed:** Frontmatter-Status → Implementierungsbereit; neues §3 D4
  (7 Antworten); A1 um research/-Einchecken + tmp-Aufräumen erweitert; C3
  präzisiert (kein Keyboard-Profil-Switcher, D4.4); D2-Fallback als
  gleichwertiger Pfad markiert (D4.3); §10 Gaps 1–3 geschlossen.

### 2026-07-20 — Spec-Integration + Cross-Spec-Entscheidungen (D5)

- **Trigger:** Fünf implementer-ready Specs (~300 KB, Opus-High-Recherchen)
  liegen unter `research/`; Team-Lead-Auftrag: Integration + Entscheidung der
  sieben Cross-Spec-Punkte.
- **Reasoning:** SSoT-Regel — Detail lebt in den Specs, der Plan wird zur
  Stub-Ebene mit geschärften Akzeptanz-Verweisen; die sieben Punkte (a–g)
  entschieden nach den Kriterien Repo-Doktrin-Treue, ein Schema-Owner,
  Risiko-Isolation, DRY, Ceiling-Disziplin.
- **What changed:** §5 komplett auf Spec-Stubs umgestellt; neues §3 D5
  (a–g + Migrations-Nummernvergabe 2/3/4.sqm); D1 in D1a/D1b gesplittet
  (16 Chunks); D3 übernimmt die Companion-Entitäts-Tabellen, E1→D3-Kante;
  §7-DAG aktualisiert; §9 um R9–R13 erweitert; §10 Gaps 4–6 geschlossen/
  verlagert + Verweis auf Spec-Gaps; §12 um Specs/Runbook ergänzt;
  Plan-Conventions-Block aktualisiert. Specs erhielten Entscheidungs-
  Nachträge (Change-History/Decision-Log-Einträge).

## 12. References

- **Specs (SSoT je Block):** `docs/plans/2026-07-19 - desktop-companion-v1/
  research/{shared-ai-extraktion,secretstore,entitaetenmodell-android,
  desktop-host,peer-katalog}.md` · E2E: `reports/e2e-runbook.md` (16 Fälle) ·
  Orchestrierung: `chunks.json`, `desktop-companion-v1.state.md` (im
  Plan-Ordner)
- Konzept-Vorarbeit (nach A1 im Plan-Ordner): `research/bestandsaufnahme.md`,
  `research/konzept-skizze.md`, `research/fragenkatalog.md`
  (F1–F34, entschieden 2026-07-19)
- Bestehende ADRs (bindend): `docs/decisions/0009` (Queue), `0012`
  (Conversation), `0013` (Review), `0014` (History), `0015` (Monorepo/Ceiling),
  `0016` (Wire-SSoT), `0017` (Rollen/Pairing), `0018` (TextInserter),
  `0020` (Sync), `0023` (Bind), `0024` (Prompt-Types), `0025` (additive
  Endpoints), `0027` (PC-Dictation)
- Schlüssel-Code: `shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/`
  (ProtocolCodec/Dtos/Validations), `companion/src/main/kotlin/net/devemperor/
  dictate/companion/` (Container/Server/Domain/Data),
  `app/src/main/java/net/devemperor/dictate/ai/` (Extraktions-Quelle),
  `app/.../preferences/DictatePrefs.kt`, `app/.../database/`
- Konventionen: `docs/DATABASE-PATTERNS.md`,
  `~/.claude/snippets/test-first-patterns.md`,
  `~/.claude/snippets/docs/lifecycle-adr.md`
- ADRs dieses Plans (nach F1 promoted, `docs/decisions/`): `0028` (`:shared-ai`
  Modul), `0029` (SecretStore), `0030` (Entitätenmodell/v3), `0031`
  (Desktop-Diktat-Host), `0032` (Desktop-Panel/Hotkey), `0033` (Desktop-Review),
  `0034` (Peer-Katalog), `0035` (Companion-History-Parität). Erweitern/revidieren
  `0012`/`0013`/`0014`/`0015`/`0017`/`0020`/`0027` (Decision-History-Einträge dort).
  Draft-Historie via `git log --follow` (in F1 aus dem ehemaligen Plan-Ordner
  `adrs/` nach `docs/decisions/` promoted).

## Plan Conventions (Compatibility-Block für implement-long-plan-v3)

- **Blöcke/Chunks:** A(A1,A2,A3) → B(B1,B2) · C(C1,C2,C3) ·
  D(D1a,D1b,D2,D3) · E(E1,E2,E3) → F(F1). 16 Chunks.
- **depends_on:** A2→A1(nein, unabhängig); A3→A2; B1→A2; B2→{B1,A3};
  C1→(—); C2→{C1,B2,A3}; C3→C2; **D1a→A2; D1b→{D1a,A3}; D2→D1b;
  D3→{D2,C1}; E1→{C1,D3}**; E2→{E1,B1}; E3→E2; F1→alle.
- **SQLDelight-Migrations-Vergabe (D5):** D1a=`2.sqm`, D3=`3.sqm`,
  E1=`4.sqm`.
- **Commit-Prefix:** `[<Block>.<Chunk>] <Titel> (desktop-companion-v1)`.
- **Audit-Linsen-Hinweis:** Block C zusätzlich mit Migrations-/Datenverlust-
  Linse; Block E zusätzlich mit Security-Linse (Credential-Pfade).
- **Custom-Trigger:** Jede Schema-Änderung in D/E triggert die Parity-Test-
  Suite; jeder neue Wire-DTO triggert Konform-Ablehnungstests.
- **E2E-Strategie:** §8 Punkte 5–6; manuelle Windows-Abnahme in F1.
