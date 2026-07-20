# Desktop-Diktier-Host — Implementer-Spec für Block D

---
date: 2026-07-19
author: Lukas + Claude Code
type: Spec
status: Spec — programmer-ready
context: Verbindliche Bau-Anleitung für Block D des Plans desktop-companion-v1 — der Companion wird eigenständiger Desktop-Diktier-Host (SQLDelight-Vollparität, javax.sound-Aufnahme, Desktop-Pipeline, globaler Hotkey, fokus-freies Compose-Panel mit Recording-UI-Nachbau, voller Prüfmodus inkl. Re-dictate, Verwaltungs-/History-UI).
related-plan: ../../../../.claude/plans/desktop-companion-v1.md
related-adrs: 0007, 0009, 0011, 0012, 0013, 0016, 0018, 0020, 0023, 0027
---

Diese Spec konkretisiert **Chunk D1 (Datenmodell + Aufnahme + Pipeline)**, **D2
(Hotkey + Panel + Recording-UI + Insert)** und **D3 (Review + Verwaltungs-UI +
History)** aus dem Plan `desktop-companion-v1`. Sie beschreibt _was gebaut wird_:
die SQLDelight-Schema-Ablösung inkl. `received_texts`-Migration, die
javax.sound-Aufnahme mit Rolling-Segmenten, den neu-implementierten
Desktop-Orchestrator (D2-Plan-Entscheidung: kein Port von `state/`), Hotkey- und
Fenster-Ports, den 1:1-Compose-Nachbau der Android-Recording-Optik und den
vollen Prüfmodus mit dem geteilten `ReviewDecision`.

Sie ist **kein** Ersatz für die ADR-Drafts (Chunk A1). Die Grundsatz­entscheidungen
leben in `adr-desktop-dictation-host`, `adr-desktop-panel-ui`, `adr-desktop-review`
und `adr-companion-history-parity`; diese Spec setzt sie um. Sie baut auf der
Schwester-Spec **`shared-ai-extraktion.md`** auf: der dort extrahierte
`:shared-ai`-Kern (Runner, `AIOrchestrator`, `ai/conversation/*`, `ReviewDecision`,
`ParameterRegistry`) und seine Ports (`AiConfig`, `UsageSink`, `ProxyConfig`,
`AudioDurationReader`) sind hier Vorbedingung — Block D **konsumiert** sie, es
extrahiert nichts. Credential-Ablage kommt aus **`secretstore.md`** (Block B).

## Table of Contents

- [Glossary](#glossary)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Datenmodell — SQLDelight-Vollparität (D1)](#3-datenmodell--sqldelight-vollparität-d1)
- [§4 Audio-Capture — javax.sound (D1)](#4-audio-capture--javaxsound-d1)
- [§5 Desktop-Pipeline / Orchestrator (D1)](#5-desktop-pipeline--orchestrator-d1)
- [§6 Hotkey + fokus-freies Panel (D2)](#6-hotkey--fokus-freies-panel-d2)
- [§7 Recording-UI-Nachbau (D2)](#7-recording-ui-nachbau-d2)
- [§8 Review-Panel + Re-dictate (D3)](#8-review-panel--re-dictate-d3)
- [§9 Verwaltungs- + History-UI (D3)](#9-verwaltungs--history-ui-d3)
- [§10 Directory Layout](#10-directory-layout)
- [§11 Chunk-Grenzen D1 / D2 / D3](#11-chunk-grenzen-d1--d2--d3)
- [§12 Testing Approach](#12-testing-approach)
- [§13 Footguns / Anti-Patterns](#13-footguns--anti-patterns)
- [§14 Decision Log](#14-decision-log)
- [§15 Information Gaps](#15-information-gaps)
- [§16 References](#16-references)

## Glossary

### Module & Komponenten

- **`:companion`** — das JVM-Desktop-Modul (Compose-Desktop + Ktor + SQLDelight).
  Ist heute ein passiver Text-Empfänger (ADR-0017/0027); Block D macht es zum
  aktiven Diktier-Host. Package `net.devemperor.dictate.companion`.
- **`capture/`** — NEUES Subsystem: javax.sound-Aufnahme, Geräteauswahl,
  Rolling-WAV-Segmente, Amplituden-Feed (§4).
- **`pipeline/`** — NEUES Subsystem: Desktop-Orchestrator, ein schlanker
  Zustandsautomat + serielle Job-Queue (§5).
- **`hotkey/`** — NEUES Subsystem: `GlobalHotkey`-Port + Win32-Impl + Noop (§6).
- **`ui/panel/`** — NEUES Subsystem: rahmenloses, always-on-top, fokus-freies
  Compose-Fenster mit Recording-, Review- und Profil-UI (§6–§8).
- **`DesktopDictationController`** — der Einstiegs-Orchestrator im
  Companion-Prozess, der Hotkey → Aufnahme → Pipeline → Panel → Insert
  verdrahtet (§5.1). Analogon zur Android-God-Class, aber klein.

### Zustands- & Domänenbegriffe

- **`DictationPhase`** — die vier Pipeline-Phasen `RECORDING → TRANSCRIBING →
  POST_PROCESSING → (REVIEW | INSERTED | CANCELLED | FAILED)` (§5.2).
- **`DesktopUiState`** — der reine Compose-State des Panels (recording, pipeline,
  review, panelVisible); vom Reducer erzeugt, kein Wire-Protokoll (§5.3).
- **Prüfmodus / Review** — der volle Nachbearbeitungs-Dialog (ADR-0013), bei dem
  das Modell rückfragen kann und der User per Stimme antwortet. Verdikt über den
  geteilten `ReviewDecision.decide` (§8).
- **Re-dictate** — eine transcription-only-Folgeaufnahme im Review, die als
  `ConversationContinuation`-Turn an die geprüfte Session angehängt wird
  (Origin `REVIEW_REFINEMENT`, §8.3).
- **`AmbiguityMode`** — die Tri-State-Politik `ALWAYS_INSERT | AUTO |
  ALWAYS_REVIEW` (geteilt aus `:shared-ai`), pro Profil gewählt (§8.1).
- **`ReviewDecision`** — die geteilte, reine Verdikt-Funktion `decide(mode,
  needsClarification, message): Verdict{INSERT, REVIEW}` — **ein** Codepfad für
  Phone und Desktop (§8.2).

### Persistenz & Parität

- **Room-Parität** — das SQLDelight-Schema deckt `sessions` /`transcriptions` /
  `processing_steps` / `conversation_messages` mit **identischen
  Enum-Vokabularen und CHECK-Constraints** wie das Android-Room-Schema v11 (§3).
- **Double-Enum-Pattern** — jede Finite-Set-Spalte ist ZWEIMAL modelliert:
  Kotlin-`enum` (`AS ...` in `.sq`) **und** SQL-`CHECK` (docs/DATABASE-PATTERNS.md).
- **`host_origin`** — companion-EIGENE Achse `PHONE_SYNC | DESKTOP_DICTATION`,
  die Phone-gespiegelte von lokal-diktierten Sessions trennt (F16, §3.3).
  Orthogonal zu `sessions.origin` (der Pipeline-Origin).
- **Parity-Test** — der programmatische Abgleich der SQLDelight-CHECK-Vokabulare
  gegen die Room-Referenzmengen (Vorbild `OriginCheckConstraintParityTest`, §3.6).
- **Rolling-Segments** — die ADR-0007-Idee „always-one-ahead": die Aufnahme
  schreibt periodisch neue WAV-Segmente, damit ein Crash höchstens ein Intervall
  verliert (§4.3).

### Plattform-Ports

- **`GlobalHotkey`** — Port für die globale Tastenkombination; Win32
  `RegisterHotKey` via JNA, Linux-Noop (§6.1).
- **`PanelWindowControl`** — Port für Fenster-Sichtbarkeit + Fokus-Freiheit
  (`WS_EX_NOACTIVATE`-Spike) + Fokus-Restaurations-Fallback (§6.2/§6.3).
- **`TextInserter`** — der BESTEHENDE Insertions-Port (ADR-0018), verbatim
  wiederverwendet für Auto-Insert (§8.5).

> **`sessions.origin` ≠ `host_origin` ≠ `AmbiguityMode`.** `sessions.origin`
> (KEYBOARD/HISTORY_REPROCESS/POST_PROCESSING/REVIEW_REFINEMENT) ist der
> **Pipeline**-Origin einer Session, Room-parität. `host_origin`
> (PHONE_SYNC/DESKTOP_DICTATION) ist die companion-eigene **Herkunfts**-Achse
> (Gerät). `AmbiguityMode` ist die **Review-Politik**, nichts davon persistiert
> auf der Session (transientes Verdikt, ADR-0013).

## 1. Vision and Motivation

### 1.1 Warum dieser Desktop-Host existiert

Dictate diktiert heute nur am Phone; der Companion ist ein passiver
Text-Empfänger, der Phone-Diktate per `/v1/dispatch` in das Vordergrundfenster
tippt (ADR-0018/0027) und Phone-History über den Sync spiegelt (ADR-0020). Der
User will am PC diktieren **wie am Phone** — Hotkey → warmes Mini-Panel →
Aufnahme → Transkription → Nachbearbeitung → Auto-Insert — ohne das Phone in der
Hand. Block D liefert genau diesen Diktier-Kreislauf im Companion-Prozess: eigene
Aufnahme (javax.sound), eigene Pipeline (geteilter `:shared-ai`-Kern), eigene
History (Room-Parität) und den vollen Prüfmodus.

### 1.2 Was Block D löst

1. **Kein zweiter AI-Kern.** Die Pipeline läuft gegen den in Block A extrahierten
   `:shared-ai`-Kern hinter Ports — genau EINE Implementierung von Runnern,
   Prompt-Logik, Conversation und `ReviewDecision` für Phone und Desktop
   (CLAUDE.md-Konvention „nie SDKs direkt", ADR-0013-Verdikt geteilt).
2. **Kein Schema-Drift.** Die Desktop-History nutzt dasselbe Session-Modell wie
   Android (Regenerate/Review/Step-Ketten funktionieren identisch), abgesichert
   durch Parity-Tests statt durch Disziplin (F15, ADR-0007/0012/0013-Schemata).
3. **Kein Fokus-Diebstahl.** Das Panel ist warm und fokus-frei — es klaut dem
   Zielfenster nicht den Fokus, sonst ginge der Auto-Insert ins Leere (F21).
4. **Additive Koexistenz.** Der bestehende PC-Diktier-Modus (ADR-0027, Phone
   nimmt auf, Companion tippt) bleibt unverändert parallel bestehen (D4.6). Der
   Desktop-Modus ist rein additiv.

### 1.3 Discarded Alternatives

- **Port des Android-`state/`-Orchestrators (19 Module).** Verworfen per
  Plan-D2: `state/` ist auf IME-Achsen zugeschnitten (Layout, MotionScene,
  Widget, InfoBar, Hover …). Der Desktop braucht ~4 Achsen. Neuimplementierung
  nach den ADR-0001-Regeln (reine Reducer, eine Dispatch-Tür, IO in Effekten)
  ist billiger und wartbarer als 15 irrelevante Achsen mitzuschleppen (§5).
- **`received_texts` als zweite History-Tabelle behalten (Koexistenz).**
  Verworfen (§14 D1): zwei Tabellen für „welche Texte existieren" = doppelte
  Wahrheit, genau das SSoT-Anti-Pattern. Statt Koexistenz: einmalige
  Backfill-Migration in das `sessions`-Modell, `received_texts` entfällt, der
  Sync landet fortan direkt im Session-Archiv (§3.4).
- **`AudioRecord`-artiges rohes PCM ohne WAV-Container.** Nicht nötig: javax.sound
  `TargetDataLine` liefert PCM, das wir direkt als WAV (RIFF-Header + PCM) ohne
  Encoder-Dependency schreiben — kein AAC/Opus-Encoder im Kotlin-Ceiling-Risiko
  (F4/D4.2, ADR-0015). Opus/OGG bleibt spätere Option (§15 Gap 2).
- **Review nur am Phone (ADR-0013-Surface-Constraint).** Der Desktop-Host
  **revidiert** die „Review ist IME-only"-Festlegung für seine eigene Surface
  (`adr-desktop-review`); die Verdikt-Logik selbst bleibt geteilt und unverändert.

### 1.4 Was Block D konkret bringt

1. Diktat am PC in <100 ms Panel-Latenz, mit Start/Pause/Resume/Discard.
2. History-Parität → Regenerate, Review, Conversation-Ketten wie am Phone.
3. Voller Prüfmodus inkl. iterativem Re-dictate — ab v1, keine Stufung (F18).
4. Windows-Auto-Insert über den erprobten `TextInserter`; Linux-Dogfooding über
   Clipboard + Tray/Panel-Button (F6).

## 1a. Architecture Walkthrough

### 1a.0 ASCII Stack Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  ui/panel/  — Compose-Mini-Panel (fokus-frei, warm)     (top)       │
│  Rendert:  DesktopUiState (Recording-Canvas §7, Review §8, Profil)  │
│  Ports:    PanelWindowControl (Sichtbarkeit + WS_EX_NOACTIVATE)     │
└─────────────────────────────────────────────────────────────────────┘
        ↑ StateFlow<DesktopUiState>          ↓ Intents (dispatch)
┌─────────────────────────────────────────────────────────────────────┐
│  pipeline/  — DesktopDictationController + Reducer + JobQueue       │
│  Achsen:   recording · pipeline · review · panel (reine Reducer)    │
│  Semantik: serielle Run-Queue (ADR-0009), ReviewDecision (ADR-0013) │
└─────────────────────────────────────────────────────────────────────┘
     ↓ nutzt                    ↓ nutzt                  ↓ nutzt
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐
│ capture/         │  │ :shared-ai (A)   │  │ hotkey/ + TextInserter   │
│ javax.sound WAV  │  │ AIOrchestrator   │  │ GlobalHotkey (Win32/Noop)│
│ Rolling-Segments │  │ converse/        │  │ Win32TextInserter (0018) │
│ AmplitudeReader  │  │ transcribe       │  │                          │
└──────────────────┘  └──────────────────┘  └──────────────────────────┘
        ↓ persistiert                 ↓ Ports (AiConfig/UsageSink)
┌─────────────────────────────────────────────────────────────────────┐
│  data/  — SQLDelight (Room-Parität)                     (bottom)    │
│  sessions · transcriptions · processing_steps · conversation_messages│
│  + host_origin-Achse · dispatch_state (Sync-Landung) · Parity-Tests │
└─────────────────────────────────────────────────────────────────────┘
```

### 1a.1 Schicht `ui/panel/` — die warme Fenster-Surface

- **Purpose:** rendert `DesktopUiState` deklarativ; das Fenster bleibt ab
  Prozessstart hidden-warm und toggelt in <100 ms.
- **File:** `companion/.../companion/ui/panel/PanelWindow.kt` (NEU).
- **Contract:** `PanelWindowControl` (Port) für `show()/hide()` + Fokus-Freiheit;
  ViewModel liefert `StateFlow<DesktopUiState>`.
- **Detail:** §6.2, §7, §8.

### 1a.2 Schicht `pipeline/` — der Desktop-Orchestrator

- **Purpose:** die einzige Dispatch-Tür; reine Reducer bilden die vier Achsen ab,
  Effekte führen IO aus (Aufnahme starten, `converse`, insert).
- **File:** `companion/.../companion/pipeline/DesktopDictationController.kt` +
  `DictationReducer.kt` (NEU).
- **Contract:** `dispatch(DictationIntent)`; `state: StateFlow<DesktopUiState>`;
  serielle `JobQueue` (ADR-0009-Semantik).
- **Detail:** §5.

### 1a.3 Schicht `capture/` — die Aufnahme

- **Purpose:** javax.sound `TargetDataLine` → 16 kHz mono 16-bit WAV-Segmente;
  RMS-Amplituden-Feed für die UI.
- **File:** `companion/.../companion/capture/AudioCaptureService.kt` (NEU).
- **Contract:** `start(deviceRef)/pause()/resume()/stop(): CaptureResult`;
  `amplitudes: Flow<Float>`.
- **Detail:** §4.

### 1a.4 Schicht `data/` — die Room-Paritäts-Persistenz

- **Purpose:** volles Session-Archiv; `received_texts` abgelöst; Sync landet in
  `sessions` + `dispatch_state`.
- **File:** `companion/src/main/sqldelight/.../db/Companion.sq` (EDIT) +
  `2.sqm`-Migration (NEU).
- **Contract:** SQLDelight-Repos + ColumnAdapter (Double-Enum); Parity-Tests.
- **Detail:** §3.

### 1a.5 Read-this-before-implementing checklist

- [ ] **Jede Finite-Set-Spalte** in `.sq`: Kotlin-`enum` (`AS`) **und** SQL-`CHECK`
  (Double-Enum, §3.2). Fehlt der CHECK → Parity-Test-Rot.
- [ ] **`verifyMigrations = true` ist an** (`companion/build.gradle:14`): jede
  `.sq`-Änderung braucht eine `N.sqm`-Migration + neuen `databases/N.db`-Snapshot,
  sonst Build-Rot (§3.5).
- [ ] **Kein Verhalten der Android-App** ändert sich in Block D — Block D fasst
  `:app` nicht an (außer der Enum-Referenzliste im Parity-Test, read-only).
- [ ] **Reducer sind rein** — kein IO, keine `System.currentTimeMillis()`; Zeit +
  UUID + Aufnahme + `converse` + insert leben in Effekten (`ClockPort` existiert,
  §5.4).
- [ ] **`ReviewDecision.decide` wird NICHT nachgebaut** — der geteilte
  `:shared-ai`-Aufruf ist die einzige Verdikt-Autorität (§8.2).
- [ ] **Der `converse`-Systemprompt** kommt aus der persistierten `SYSTEM`-Zeile
  der Conversation, nicht aus einem Live-Template (ADR-0012, §8.3).
- [ ] **Audio-Format ist fix** 16 kHz / mono / 16-bit PCM-WAV (D4.2); Provider-
  Upload-Limits gegen ~2 MB/min verifizieren (§4.5, §15 Gap-Rest).

## 2. Acceptance Criteria

1. **Build-Invariante.** `./gradlew :companion:build` grün; `verifyMigrations`
   grün (neuer `2.db`/`3.db`-Snapshot konsistent); `:app` unverändert grün.
2. **Schema-Parität.** SQLDelight deckt `sessions` / `transcriptions` /
   `processing_steps` / `conversation_messages` mit identischen Enum-Vokabularen
   + CHECK-Constraints wie Room v11; die Parity-Test-Suite (§3.6) ist grün und
   scheitert rot, sobald ein CHECK-Vokabular von der Room-Referenz abweicht.
3. **`received_texts`-Ablösung.** Die Migration `2.sqm` (bzw. `3.sqm`, §3.4)
   überführt alle `received_texts`-Zeilen verlustfrei nach `sessions` (host_origin
   `PHONE_SYNC`) + `dispatch_state`; ein MigrationTest mit befüllter Fixture-DB
   verifiziert Zeilenzahl, Origin-Mapping und Cursor-Äquivalenz; `received_texts`
   existiert nach der Migration nicht mehr.
4. **Sync-Regression.** Alle bestehenden Sync-E2E-Tests (`SyncE2ETest`,
   `CompanionE2ETest`, `MultiConnectorE2ETest`, `TruncatedResponseE2ETest`) sind
   nach der Cursor-Umstellung auf `sessions`+`dispatch_state` grün; der
   Sync-Cursor pagt byte-identisch wie zuvor (ADR-0020-Ordnung `created_at,
   session_id`).
5. **Desktop-Diktat E2E (headless).** Fake-Runner + WAV-Fixture: Hotkey-Intent →
   Aufnahme → Transkription + Post-Processing über ein Profil → Auto-Insert
   (Fake-`TextInserter`); die Session inkl. `transcription` + `CONVERSATION_TURN`-
   Step + `conversation_messages` (SYSTEM + USER) ist in der Companion-DB
   persistiert (§5.5, §12).
6. **Pipeline-Zustandsübergänge.** Start/Pause/Resume/Discard und die
   Phasen-Übergänge `RECORDING → TRANSCRIBING → POST_PROCESSING → (REVIEW |
   INSERTED | CANCELLED | FAILED)` sind reducer-unit-getestet; ein zweiter
   Diktat-Trigger während laufender Pipeline reiht ein (ADR-0009), verwirft nicht.
7. **Prüfmodus-Parität.** Der Desktop-Aufrufer nutzt `ReviewDecision.decide`
   verbatim; die 5-Zeilen-Verdikt-Matrix (§8.2) läuft als parametrisierte Suite
   grün; Re-dictate erzeugt eine `REVIEW_REFINEMENT`-Session + einen
   `ConversationContinuation`-Turn, und das Panel aktualisiert non-terminal.
8. **Fokus-Politik.** Entweder der `WS_EX_NOACTIVATE`-Spike ist erfolgreich (Panel
   klaut nie den Fokus) ODER der Fallback greift (Vordergrundfenster beim
   Hotkey gemerkt, vor Insert restauriert); beide Pfade sind unit-getestet
   (Fake-Keyboard/Fake-WindowControl), das Spike-Scheitern ist **keine**
   Eskalation (D4.3). Das fokus-freie Fenster ist bis zur Spike-Entscheidung ein
   `pending: D2-focus-spike`-Test.
9. **Recording-UI.** Die Compose-Canvas-Recording-Optik nutzt die extrahierten
   Design-Parameter (§7): 30er Ring-Buffer, Log-Amplitude, Alters-Fade 0.4→1.0,
   HSV-Glow, Breathing-Puls 1500 ms; ViewModel-Tests decken die Zustände ab.
10. **Manuelle Windows-Abnahme.** Hotkey → Panel → Diktat → Insert in einen Editor
    (Notepad/Browser-Feld); Linux: Panel via Tray, Ergebnis in Clipboard +
    Hinweis. Checkliste in F1 abgehakt.

## 3. Datenmodell — SQLDelight-Vollparität (D1)

### 3.1 Ist-Stand + Ziel

Heute hält `Companion.sq` (v2) nur `devices`, `received_texts`, `settings`,
`key_command_chords` (`companion/src/main/sqldelight/.../db/Companion.sq:1-63`).
Der Companion-DB-Wrapper registriert ColumnAdapter zentral in
`data/CompanionDatabase.kt:38-47` (`EnumColumnAdapter()` pro Enum-Spalte);
`SchemaMigrator` fährt `PRAGMA user_version` hoch
(`data/SchemaMigrator.kt:31-56`); `verifyMigrations = true`
(`companion/build.gradle:14`) replayt jede `.sqm` gegen den `databases/N.db`-
Snapshot bei jedem Build.

Ziel (F15): die vier Session-Tabellen des Room-Schemas v11 in **voller Parität**
— identische Spalten, identische Enum-Vokabulare als CHECK, identische Indizes.
Das Room-Schema ist die SSoT der Struktur (`app/schemas/.../11.json`); die
Enum-Werte kommen aus den Room-Migrations-CHECKs (Room-JSON trägt keine CHECKs).

### 3.2 Enum-Vokabulare (SSoT für die CHECK-Constraints)

Diese Werte sind die persistierten `.name`-Strings der Room-Enums (verifiziert im
`:app`-Bestand). **Jede** dieser Spalten wird im Companion als
`TEXT AS <KotlinEnum>` + `CHECK (col IN (...))` modelliert (Double-Enum). Die
Kotlin-Enums werden companion-seitig neu definiert (Package
`net.devemperor.dictate.companion.domain.session`), weil `:companion` nicht auf
`:app` zugreifen kann (§3.6 löst die Parität per Test).

| Spalte | Kotlin-Enum | Werte (exakt) | Room-Quelle (file:line) | CHECK? |
|---|---|---|---|---|
| `sessions.type` | `SessionType` | `RECORDING, REWORDING, POST_PROCESSING` | `entity/SessionType.kt:3`; Mig `MigrationTo9.kt:46` | ja |
| `sessions.status` | `SessionStatus` | `RECORDING, RECORDING_INTERRUPTED, RECORDED, TRANSCRIBING, COMPLETED, FAILED, CANCELLED` | `entity/SessionStatus.kt:27`; Mig `MigrationTo9.kt:55` | ja |
| `sessions.origin` | `SessionOrigin` | `KEYBOARD, HISTORY_REPROCESS, POST_PROCESSING, REVIEW_REFINEMENT` | `entity/SessionOrigin.kt:22`; Mig `MigrationTo9.kt:60` | ja |
| `sessions.last_error_type` | `AiErrorType` | `INVALID_API_KEY, RATE_LIMITED, MODEL_NOT_FOUND, BAD_REQUEST, SERVER_ERROR, NETWORK_ERROR, CANCELLED, UNKNOWN` | `ai/AIProviderException.kt:18`; Mig `MigrationTo9.kt:66` | ja (nullable) |
| `processing_steps.step_type` | `StepType` | `AUTO_FORMAT, REWORDING, QUEUED_PROMPT, CONVERSATION_TURN` | `entity/StepType.kt:3`; Mig `MigrationTo8.kt:47` | ja |
| `processing_steps.status` | `StepStatus` | `SUCCESS, ERROR` | `entity/StepStatus.kt:3` | **nein in Room** (nur `TEXT NOT NULL`, `MigrationTo8.kt:63`) — Companion setzt CHECK **neu** (Verbesserung, §14 D3) |
| `processing_steps.response_format` | `ResponseFormatKind` | `JSON_SCHEMA, TOOL_USE, TEXT_FALLBACK` | `entity/ResponseFormatKind.kt:13`; Mig `MigrationTo8.kt:68` | ja (nullable) |
| `conversation_messages.role` | `MessageRole` | `SYSTEM, USER, ASSISTANT` | `entity/MessageRole.kt:19`; Mig `MigrationTo8.kt:108` | ja |
| `text_insertions.insertion_method` | `InsertionMethod` | `COMMIT, PASTE, WINDOWS_DISPATCH` | `entity/InsertionMethod.kt:10`; Mig `MigrationTo10.kt:50` | ja |

**Offene Vokabulare (KEIN CHECK, wie in Room):** `transcriptions.provider`,
`processing_steps.provider`, `usage.model_provider`, `completion_log.type`. Diese
tragen `AIProvider.name`-Strings bzw. Freitext und sind bewusst nicht Double-Enum
(Modell/Provider ist offen). Als `TEXT NOT NULL` ohne CHECK übernehmen.

> [!IMPORTANT]
> `AiErrorType` und `AIProvider` leben nach Block A in `:shared-ai`
> (`shared-ai-extraktion.md §3.4`). Der Companion importiert sie von dort — NICHT
> companion-lokal neu definieren, sonst driften die Fehler-/Provider-Vokabulare
> gegen den geteilten Kern. Nur die reinen Session-Struktur-Enums
> (`SessionType/Status/Origin`, `StepType/Status`, `ResponseFormatKind`,
> `MessageRole`, `InsertionMethod`) werden companion-lokal definiert, weil sie
> heute Room-only in `:app` liegen (§15 Gap 1 dokumentiert den SSoT-Wunsch).

### 3.3 Tabellen-Übersetzung (`Companion.sq`)

Die vier Room-Tabellen werden 1:1 übersetzt (Spaltennamen, Affinitäten, Nullability,
FKs, Indizes aus `11.json`). SQLDelight-Besonderheiten: `INTEGER AS kotlin.Boolean`
für Flags (primitive-adapters, wie bestehend); `value`→`value_`-Rename gilt hier
nicht. Companion-Erweiterung: **`sessions.host_origin`** (Double-Enum
`PHONE_SYNC | DESKTOP_DICTATION`) trennt Phone-Spiegel von Desktop-Diktat (F16).

Auszug `sessions` (die übrigen drei Tabellen analog, vollständige Spaltenliste aus
`11.json` §sessions/transcriptions/processing_steps/conversation_messages):

```sql
CREATE TABLE sessions (
    id                       TEXT NOT NULL PRIMARY KEY,
    type                     TEXT AS ...session.SessionType NOT NULL
                             CHECK (type IN ('RECORDING','REWORDING','POST_PROCESSING')),
    created_at               INTEGER NOT NULL,
    target_app_package       TEXT,
    language                 TEXT,
    audio_file_path          TEXT,
    audio_file_paths         TEXT NOT NULL DEFAULT '[]',   -- JSON-Array, wie Room (ADR-0007)
    audio_duration_seconds   INTEGER NOT NULL DEFAULT 0,
    parent_session_id        TEXT,
    status                   TEXT AS ...session.SessionStatus NOT NULL
                             CHECK (status IN ('RECORDING','RECORDING_INTERRUPTED','RECORDED',
                                               'TRANSCRIBING','COMPLETED','FAILED','CANCELLED')),
    origin                   TEXT AS ...session.SessionOrigin NOT NULL
                             CHECK (origin IN ('KEYBOARD','HISTORY_REPROCESS',
                                               'POST_PROCESSING','REVIEW_REFINEMENT')),
    queued_prompt_ids        TEXT,
    last_error_type          TEXT AS ...ai.AiErrorType
                             CHECK (last_error_type IS NULL OR last_error_type IN
                                    ('INVALID_API_KEY','RATE_LIMITED','MODEL_NOT_FOUND','BAD_REQUEST',
                                     'SERVER_ERROR','NETWORK_ERROR','CANCELLED','UNKNOWN')),
    last_error_message       TEXT,
    final_output_text        TEXT,
    input_text               TEXT,
    inserted_at              INTEGER,
    -- Companion-eigene Achse (F16), NICHT Teil der Room-Parität:
    host_origin              TEXT AS ...session.HostOrigin NOT NULL DEFAULT 'DESKTOP_DICTATION'
                             CHECK (host_origin IN ('PHONE_SYNC','DESKTOP_DICTATION')),
    FOREIGN KEY (parent_session_id) REFERENCES sessions(id) ON DELETE SET NULL
);
CREATE INDEX sessions_parent_session_id ON sessions(parent_session_id);
CREATE INDEX sessions_type       ON sessions(type);
CREATE INDEX sessions_created_at  ON sessions(created_at);
CREATE INDEX sessions_origin      ON sessions(origin);
CREATE INDEX sessions_status      ON sessions(status);
CREATE INDEX sessions_host_origin ON sessions(host_origin);   -- Companion-Filter Phone/Desktop
```

> [!NOTE]
> `text_insertions`, `completion_log`, `usage`, `prompts` aus Room v11 sind für
> Block D **nicht** paritätspflichtig: `usage` wird vom `UsageSink`-Port bedient
> (eigene kleine `usage`-Tabelle, §5.4), `prompts`/Profile kommen aus Block C
> (Entitätenmodell), `text_insertions`/`completion_log` sind Android-Audit-Detail.
> Block D übersetzt die **vier Kern-Session-Tabellen** + die companion-nötige
> `usage`. `text_insertions` ist optional (nur falls die History Re-Insert-Audit
> braucht — §9.3 nutzt stattdessen `dispatch_state`).

### 3.4 `received_texts`-Ablösung — Migration (löst Gap 5)

**Entscheidung (§14 D1):** `received_texts` wird **abgelöst**, nicht koexistiert.
Das `sessions`-Modell ist das einzige History-Archiv; die Sync-spezifischen Felder
(device-Bindung, dispatch-Watermark, Insertions-Outcome) wandern in eine schlanke
1:1-Begleittabelle `dispatch_state`, damit `sessions` **rein Room-parität** bleibt
(sonst würden device_id/dispatched/last_outcome die Parity-Diff verschmutzen).

```sql
CREATE TABLE dispatch_state (
    session_id   TEXT NOT NULL PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    device_id    TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    -- PC-Zeit: wann diese Zeile zuletzt hier geschrieben wurde (ex-received_at).
    received_at  INTEGER NOT NULL,
    -- 1 = kam über /v1/dispatch, 0 = nur vom Sync gespiegelt. Läuft nie zurück.
    dispatched   INTEGER AS kotlin.Boolean NOT NULL DEFAULT 0,
    last_outcome TEXT AS ...domain.model.InsertionOutcome
                 CHECK (last_outcome IS NULL OR last_outcome IN ('TYPED_CTRL_V','CLIPBOARD_ONLY','FAILED'))
);
-- Der Cursor-Achsen-Index wandert auf sessions (nur Phone-Sync-Zeilen paginieren):
CREATE INDEX sessions_sync_cursor ON sessions(created_at, id);
```

**Semantik-Mapping `received_texts` → `sessions` + `dispatch_state`:**

| `received_texts` | Ziel | Regel |
|---|---|---|
| `session_id` | `sessions.id` **und** `dispatch_state.session_id` | 1:1 |
| `text` | `sessions.final_output_text` | der empfangene Text = finaler Output |
| `created_at` | `sessions.created_at` | Cursor-Achse bleibt identisch |
| `origin` (SessionOriginWire) | `sessions.origin` | Mapping unten |
| `device_id` | `dispatch_state.device_id` | 1:1 |
| `received_at` | `dispatch_state.received_at` | 1:1 |
| `dispatched` | `dispatch_state.dispatched` | 1:1 |
| `last_outcome` | `dispatch_state.last_outcome` | 1:1 |
| — | `sessions.type` | `'RECORDING'` (Phone-Diktat) |
| — | `sessions.status` | `'COMPLETED'` |
| — | `sessions.host_origin` | `'PHONE_SYNC'` |
| — | `sessions.inserted_at` | `dispatched=1 ? received_at : NULL` |
| — | `sessions.audio_file_paths` | `'[]'` (Phone-Audio liegt nie hier) |

Origin-Mapping: `received_texts.origin` nutzt `SessionOriginWire` mit **fünf**
Werten inkl. `UNKNOWN` (`shared/.../Dtos.kt:64`); `sessions.origin` erlaubt nur
die vier Room-Werte. `UNKNOWN` → `'KEYBOARD'` (der Landing-Default; ADR-0016 nennt
`UNKNOWN` den „landing value for values the protocol doesn't map"). Die übrigen
vier sind namensgleich → direkt.

`2.sqm` (v2→v3) — reine SQL-Migration (idempotent, verifyMigrations-tauglich):

```sql
-- 2.sqm : companion schema v2 -> v3 (Room-Paritäts-Session-Modell)
-- 1) Neue Tabellen anlegen (sessions/transcriptions/processing_steps/
--    conversation_messages/dispatch_state/usage) — siehe Companion.sq.
-- 2) Backfill der bestehenden received_texts in das Session-Archiv:
INSERT INTO sessions (id, type, created_at, status, origin, final_output_text,
                      inserted_at, host_origin, audio_file_paths, audio_duration_seconds)
SELECT session_id, 'RECORDING', created_at, 'COMPLETED',
       CASE origin WHEN 'UNKNOWN' THEN 'KEYBOARD' ELSE origin END,
       text,
       CASE dispatched WHEN 1 THEN received_at ELSE NULL END,
       'PHONE_SYNC', '[]', 0
FROM received_texts;

INSERT INTO dispatch_state (session_id, device_id, received_at, dispatched, last_outcome)
SELECT session_id, device_id, received_at, dispatched, last_outcome
FROM received_texts;

-- 3) Alte Tabelle + Index entfernen:
DROP INDEX received_texts_cursor;
DROP TABLE received_texts;
```

> [!WARNING]
> Der FK `dispatch_state.device_id → devices` verlangt, dass jede
> `received_texts.device_id` in `devices` existiert — das ist durch den
> bestehenden FK `received_texts.device_id → devices` bereits garantiert, bleibt
> aber im MigrationTest zu prüfen (Fixture mit ≥1 device + ≥2 received_texts,
> davon eine `origin='UNKNOWN'` und eine `dispatched=0`).

### 3.5 SyncService- und Repo-Umbau (Blast-Radius)

Die Ablösung berührt den bestehenden Phone-Sync (ADR-0020). Die
`received_texts`-Queries in `Companion.sq:83-141` müssen auf
`sessions`+`dispatch_state` neu geschrieben werden — **verhaltensgleich**:

- `upsertReceivedText` → schreibt `sessions` (INSERT OR REPLACE der Parität-Felder,
  `host_origin='PHONE_SYNC'`) **und** `dispatch_state` mit der unveränderten
  „never downgrade dispatched"-Invariante (`MAX(dispatch_state.dispatched,
  excluded.dispatched)`, `Companion.sq:99-108`). Der `last_outcome`-DO-UPDATE-
  Ausschluss bleibt (nur `recordDispatch` schreibt ihn).
- `selectCursor` / `pageHistory` / `countHistory` → `FROM sessions JOIN
  dispatch_state` bzw. `WHERE host_origin='PHONE_SYNC'`, Ordnung `created_at DESC,
  id DESC` unverändert (ADR-0020-Paritätskontrakt).
- `recordDispatch` → `UPDATE dispatch_state`.

Betroffene Dateien: `data/SqlDelightHistoryRepository.kt`, `domain/SyncService.kt`,
`domain/DispatchService.kt` (schreibt `recordDispatch`), plus die E2E-Tests
(`SyncE2ETest`, `CompanionE2ETest`, `MultiConnectorE2ETest`,
`TruncatedResponseE2ETest`, `SqlDelightHistoryRepositoryTest`). Diese Tests sind
der Verhaltensneutralitäts-Beweis — sie müssen **ohne Assertion-Änderung** grün
bleiben (nur die interne Repo-Verdrahtung ändert sich). Das ist der Haupt-Aufwand
und das Hauptrisiko von D1 (§13, R-Sync).

### 3.6 Parity-Test-Design (programmatisch)

Vorbild `OriginCheckConstraintParityTest`
(`companion/.../data/OriginCheckConstraintParityTest.kt`). Zwei Test-Familien:

**(a) CHECK-Akzeptanz/Ablehnung** (pro Double-Enum-Spalte, wie das Vorbild):
- `everyEnumValue_isAccepted`: jeder companion-`enum`-Wert ist insertbar.
- `aValueTheEnumCannotProduce_isRejected`: ein Fantasiewert (`'TELEPATHY'`) wirft
  `CHECK constraint failed`. Das ist die Hälfte, die die erste erst wertvoll macht.

**(b) Room-Paritäts-Abgleich** (der neue, cross-schema Test — löst R3): Der
Companion-Test kann Room-Enums nicht importieren (`:app` ist Android). Deshalb hält
`RoomParityReference.kt` (im Companion-Test) **hartkodierte** Referenzmengen pro
Spalte, jede mit einem `// SSoT: app/.../entity/<Enum>.kt:<line>`-Kommentar. Der
Test asserted: `SessionType.entries.map { it.name }.toSet() ==
RoomParityReference.SESSION_TYPE`. Driftet der companion-`enum` **oder** vergisst
jemand, die Referenz nach einer Room-Änderung nachzuziehen, wird der Test rot mit
einer Diff-Meldung, die auf die Room-Quelle zeigt.

```kotlin
// RoomParityReference.kt (companion test) — the manual SSoT, guarded by a test.
object RoomParityReference {
    // SSoT: app/src/main/java/.../database/entity/SessionStatus.kt:27 (+ MigrationTo9.kt:55)
    val SESSION_STATUS = setOf("RECORDING","RECORDING_INTERRUPTED","RECORDED",
        "TRANSCRIBING","COMPLETED","FAILED","CANCELLED")
    // ... eine Menge pro paritätspflichtiger Spalte
}
```

> [!NOTE]
> Der echte cross-modul-SSoT (die Enums einmal in `:shared` definieren und Room
> **und** SQLDelight darauf mappen) ist wünschenswert, aber ein `:app`-Room-Refactor
> außerhalb Block D (§15 Gap 1). Bis dahin ist die getestete Referenzliste die
> pragmatische, wartbare Zwischenlösung — in `adr-companion-history-parity`
> verankert.

## 4. Audio-Capture — javax.sound (D1)

### 4.1 Format + Line-Setup

Fix (F4/D4.2): **16 kHz, mono, 16-bit signed little-endian PCM**, geschrieben als
WAV (RIFF/`WAVE`, `fmt `-Chunk + `data`-Chunk). Kein Encoder → keine neue
Dependency (ADR-0015-Ceiling). javax.sound ist im JDK, keine libs.versions-Änderung.

```kotlin
val format = AudioFormat(16_000f, 16, 1, /*signed*/ true, /*bigEndian*/ false)
val info = DataLine.Info(TargetDataLine::class.java, format)
val line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
line.open(format); line.start()
```

Der WAV-Header wird nach `stop()` mit der finalen `data`-Länge zurückgeschrieben
(seekbarer `RandomAccessFile`) — Standard-RIFF-Muster; ein `AudioSystem.write(
AudioInputStream, WAVE, file)` über den gepufferten Stream ist die einfachere,
bevorzugte Variante (schreibt den Header korrekt).

### 4.2 Geräte-Enumeration + Auswahl-Persistenz

`AudioSystem.getMixerInfo()` → Mixer filtern, die eine `TargetDataLine` mit dem
Format unterstützen. Die Auswahl (Mixer-Name) persistiert über die bestehende
`CompanionSettings`-Fassade (`domain/CompanionSettings.kt`), Key
`audio.inputDevice` (typisierter Getter/Setter analog `port`/`bindSelection`,
`CompanionSettings.kt:23-56`). Default: `null` → System-Default-Mixer. Ist der
gemerkte Mixer verschwunden → Fallback auf Default + Settings-Hinweis (R5-Mitigation).

### 4.3 Rolling-Segments (ADR-0007-Idee, Desktop-Adaption)

Android nutzt `MediaRecorder.setNextOutputFile()` (ADR-0007
Decision-History 2026-05-21). javax.sound hat kein Äquivalent — der Read-Loop läuft
selbst. Adaption: Der Capture-Thread liest `line.read(buffer)` in einer Schleife und
**rollt** alle `Pref audio.rollingSegmentSec` (Default 30 s) auf ein neues Segment,
indem er den aktuellen WAV finalisiert (Header schreiben) und
`{sessionId}_N.wav` öffnet. Ablage im Companion-Data-Dir unter einem NEUEN
`recordings/`-Unterverzeichnis von `AppPaths.dataDirectory()`
(`platform/AppPaths.kt:19` liefert heute nur `databaseFile()`; ein
`recordingsDirectory()` ergänzen).

Bei Pause: Read-Loop pausiert (`line.stop()`), Segment finalisieren. Resume:
`line.start()` in dasselbe oder ein neues Segment. Cold-Resume ist für v1 **kein**
Muss (Desktop-Prozess ist langlebig, kein FGS-Teardown wie Android) — die
Segmentliste landet als JSON in `sessions.audio_file_paths` (Parität mit ADR-0007),
sodass die Recovery später nachrüstbar ist (§15 Gap 3).

Merge für den Pipeline-Upload: WAV-Segmente gleicher Config concat = Header des
ersten + rohe `data`-Chunks der folgenden. Ein `WavConcat.merge(segments): File`
(reine Byte-Arbeit, unit-testbar) liefert das eine Upload-File. Bei genau einem
Segment: zero-copy (Datei direkt).

### 4.4 Amplituden-Feed (RMS) für die UI

Android liest `MediaRecorder.getMaxAmplitude()` (Peak 0–32767) alle 100 ms und
schiebt es durch `AmplitudeProcessor.process(Int): Float`
(`core/AmplitudeProcessor.kt:34-44`, Log-Normalisierung). javax.sound liefert PCM —
wir berechnen den **Peak** (oder RMS) selbst aus dem gerade gelesenen Buffer und
speisen denselben portierten `AmplitudeProcessor`:

```kotlin
// pro gelesenem 16-bit-PCM-Buffer: Peak-Betrag als 0..32767-Äquivalent
var peak = 0
for (i in 0 until n step 2) {
    val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)   // LE int16
    val a = kotlin.math.abs(s); if (a > peak) peak = a
}
val level: Float = amplitudeProcessor.process(peak)   // Log-Norm, effectiveMax=12000
```

`AmplitudeProcessor` ist reines `kotlin.math` (ln/min/max) und wandert mit dem
Recording-UI-Code nach `:companion` (§7 klärt den genauen Ablageort — Kopie vs.
`:shared-ai`). Emission als `Flow<Float>` bei ~10 Hz (Buffer-Größe so wählen, dass
`line.read` etwa alle 100 ms zurückkehrt: `16000 * 2 * 0.1 ≈ 3200` Bytes).

### 4.5 Provider-Upload-Limit (Rest-Aufgabe D4.2)

16 kHz mono 16-bit PCM-WAV ≈ **1,92 MB/min**. Der D1-Agent verifiziert die
Upload-Limits der konfigurierbaren Transcription-Provider (OpenAI 25 MB → ~13 min;
Groq; ElevenLabs) und dokumentiert sie im Chunk (reine Verifikation, keine offene
Entscheidung). Bei knappen Limits ist Opus/OGG die dokumentierte spätere Option
(§15 Gap 2).

## 5. Desktop-Pipeline / Orchestrator (D1)

### 5.1 `DesktopDictationController` — die eine Dispatch-Tür

Neuimplementierung nach ADR-0001-Regeln (Plan-D2), **kein** Port des
Android-`state/`. Der Controller ist das Desktop-Analogon zur
`DictateInputMethodService`-God-Class, aber klein: er hält die reine
`DesktopUiState`, nimmt `DictationIntent`s entgegen, ruft den Reducer, führt die
resultierenden Effekte aus und exponiert `state: StateFlow<DesktopUiState>` an das
Panel-ViewModel.

```kotlin
class DesktopDictationController(
    private val capture: AudioCaptureService,      // §4
    private val ai: AIOrchestrator,                // :shared-ai (hinter Ports)
    private val sessions: DesktopSessionRepository, // §3 SQLDelight
    private val inserter: TextInserter,            // ADR-0018 (bestehend)
    private val queue: JobQueue,                   // §5.6, ADR-0009-Semantik
    private val clock: ClockPort,                  // bestehend, domain/port/ClockPort.kt
    private val profiles: ActiveProfileSource,     // Block C; D1: Übergangs-AiConfig
) {
    val state: StateFlow<DesktopUiState>
    fun dispatch(intent: DictationIntent)          // Hotkey, Panel-Klicks, Job-Callbacks
}
```

> [!NOTE]
> D1/D2 laufen initial gegen eine **Übergangs-`AiConfig`** aus `CompanionSettings`
> (Plan §7: „Pipeline läuft initial gegen eine Übergangs-`AiConfig` aus
> CompanionSettings"); erst D3 gated auf die C1-Profil-Typen (`ActiveProfileSource`
> liefert dann ein echtes Profil). `AiConfig` ist der Port aus
> `shared-ai-extraktion.md §4.1`.

### 5.2 Phasenmodell (`DictationPhase`)

```
        dispatch(StartHotkey)
IDLE ───────────────────────────▶ RECORDING
                                     │ Pause/Resume (bleibt RECORDING)
                                     │ dispatch(StopRecording)
                                     ▼
                                 TRANSCRIBING ──(error)──▶ FAILED
                                     │ transcript ok
                                     ▼
                              POST_PROCESSING ──(error)──▶ FAILED
                                     │ converse ok → ReviewDecision.decide
                        ┌────────────┴─────────────┐
                   Verdict.INSERT             Verdict.REVIEW
                        ▼                          ▼
                    INSERTED  ◀── Insert ──────  REVIEW ──Discard──▶ CANCELLED
                                                   │ Re-dictate (§8.3)
                                                   ▼
                                          (neuer Turn, bleibt REVIEW)
   dispatch(Discard) aus RECORDING/… ──────────▶ CANCELLED
```

Phasen mappen auf die persistierten `sessions.status`-Werte: RECORDING→`RECORDING`,
TRANSCRIBING→`TRANSCRIBING`, POST_PROCESSING→`TRANSCRIBING` (kein eigener
Room-Status; der Turn ist Teil der Verarbeitung), INSERTED/REVIEW→`COMPLETED`,
FAILED→`FAILED`, CANCELLED→`CANCELLED`. Das ist Room-parität — keine neuen
Status-Werte.

### 5.3 `DesktopUiState` (reiner Compose-State)

```kotlin
data class DesktopUiState(
    val panelVisible: Boolean = false,
    val recording: RecordingUi = RecordingUi.Idle,          // Active/Paused + elapsed + level-buffer
    val pipeline: PipelineUi = PipelineUi.Idle,             // Transcribing/PostProcessing/Failed
    val review: ReviewUi? = null,                           // §8: message,output,refining,refinementRecording
    val queued: List<QueuedRun> = emptyList(),              // ADR-0009-Payload
)
```

Kein Wire-Protokoll, kein WebSocket — der Compose-Renderer liest den State direkt
(F1/F5-Vorteil). Die `review`-Sub-Achse spiegelt exakt die ADR-0013-Zustände
(`refining`, `refinementRecording`, §8).

### 5.4 Reducer-Reinheit + Effekte

Reducer: `(DesktopUiState, DictationIntent) -> Pair<DesktopUiState, List<Effect>>`.
**Kein IO in Reducern** (Checklist §1a.5). Effekte kapseln: `StartCapture`,
`StopCapture`, `RunTranscription`, `RunPostProcessing`, `RunContinuation`,
`InsertText`, `PersistSession`, `ShowPanel`/`HidePanel`. Zeit/UUID über `clock` +
`java.util.UUID` **im Effekt-Handler**. Usage-Tracking über den `UsageSink`-Port
(`shared-ai-extraktion.md §4.2`), der companion-seitig in eine kleine
`usage`-Tabelle schreibt (`SqlDelightUsageSink`).

### 5.5 Pipeline-Schritte (die drei `:shared-ai`-Aufrufe)

1. **Transkription:** `ai.transcribe(mergedWav, language, stylePrompt):
   TranscriptionResult` (`ai/AIOrchestrator.kt:41`). Persistiere eine
   `transcriptions`-Zeile (`is_current=1`, `version=1`).
2. **Post-Processing (Conversation):** baue `PostProcessingInputs` (transcript +
   Profil-Instructions + `includeAmbiguityTask = forceTurn`), erste User-Message
   via `ConversationTurnBuilder.buildFirstUserMessage(inputs)`
   (`ai/conversation/ConversationTurnBuilder.kt:36`), dann `ai.converse(messages,
   systemPrompt): ConversationResult` (`ai/AIOrchestrator.kt:130`). Persistiere
   einen `CONVERSATION_TURN`-Step (`chain_index=0`, `assistant_message=message`,
   `response_format=result.responseFormat`) + `conversation_messages`: eine
   `SYSTEM`-Zeile (turn-0-Systemprompt) + eine `USER`-Zeile (die gebaute Message).
   `final_output_text = result.output` uniform in derselben Transaktion (ADR-0013
   §3 Crash-Resilienz).
   `hasWork(inputs)==false` (reines Transkript, kein forceTurn) → kein Turn, bare
   Transkript einfügen (ADR-0012 §1).
3. **Verdikt:** `ReviewDecision.decide(mode, result.needsClarification,
   result.message)` (§8.2) → INSERT oder REVIEW.

### 5.6 Serielle Job-Queue (ADR-0009-Semantik, vereinfacht)

Eine `JobQueue` mit einem Worker-Thread, FIFO, ein Job zur Zeit. Ein zweiter
`StartHotkey`/`StopRecording` während laufender Pipeline **reiht ein** (dedup per
sessionId), verwirft nicht — Aufnahme-Reihenfolge = Einfüge-Reihenfolge =
Insert-Reihenfolge (ADR-0009 „Positive"). Anders als Android: **keine** parallele
Zweitaufnahme in v1 (Desktop hat eine Aufnahme; der User startet den nächsten
Hotkey erst nach `stop`). Der `ConversationContinuation`-Job (Re-dictate, §8.3)
läuft über dieselbe Queue.

## 6. Hotkey + fokus-freies Panel (D2)

### 6.1 `GlobalHotkey`-Port

```kotlin
interface GlobalHotkey {
    val available: Boolean
    fun register(combo: HotkeyCombo, onTrigger: () -> Unit): Boolean
    fun unregister()
}
```

- **Win32-Impl** (`platform/windows/Win32GlobalHotkey.kt`): `User32.RegisterHotKey`
  auf einem eigenen Message-Loop-Thread (`GetMessage`/`PeekMessage` → `WM_HOTKEY`).
  JNA `com.sun.jna.platform.win32.User32` ist bereits Dependency (JNA `5.19.1`,
  `libs.versions.toml:66`). Muster analog `Win32Keyboard.kt:105-131` (INPUT/SendInput
  nutzt dasselbe User32). `HotkeyCombo` (Modifier + VK) konfigurierbar in Settings
  (`CompanionSettings`-Key `hotkey.combo`), Default z. B. Ctrl+Alt+Space.
- **Noop-Impl** (`platform/fallback/NoopGlobalHotkey.kt`): `available=false`,
  `register` No-Op (Linux/macOS, F6). Auf Linux triggert der User die Aufnahme über
  Tray-Menü oder Panel-Button (§9.1).

Fake für Tests: `FakeGlobalHotkey` mit manuellem `trigger()` — Vorbild
`FakeInputCommandPerformer.kt`.

### 6.2 `PanelWindowControl` — Fenster-Wärme + Sichtbarkeit

```kotlin
interface PanelWindowControl {
    fun show()          // Panel sichtbar, positioniert
    fun hide()          // Panel unsichtbar, Prozess bleibt warm
    val focusFree: Boolean   // true, wenn WS_EX_NOACTIVATE-Spike erfolgreich
}
```

Das Compose-`Window` wird ab Prozessstart erzeugt und **hidden gehalten**
(`visible=false` / off-screen), damit der Toggle <100 ms ist (F5). Kein
Neu-Erzeugen pro Hotkey. Rahmenlos (`undecorated=true`), always-on-top
(`alwaysOnTop=true`) — beides direkt in Compose-Desktop-`Window(...)` verfügbar
(companion nutzt heute schon `Window` in `Main.kt:153`). Positionierung: nahe dem
Cursor oder zentriert unten (v1: fix zentriert-unten, konfigurierbar später).

### 6.3 Der Fokus-Spike (D2-Kernrisiko, R1) + Fallback

Ziel: das Panel nimmt dem Zielfenster **nie** den Fokus, sonst geht `SendInput`
(Ctrl+V) ins Panel statt in den Editor.

**Spike (erste D2-Aufgabe, Zeitbox ~1 Tag):**
1. Compose-`Window` erzeugen, AWT-`Window`-Handle über
   `Native.getComponentPointer(window)` bzw. `Native.getWindowPointer` (JNA) holen.
2. `HWND` daraus; `User32.GetWindowLong(hwnd, GWL_EXSTYLE)` lesen,
   `WS_EX_NOACTIVATE (0x08000000)` ODER-en, `SetWindowLong` zurückschreiben.
   Zusätzlich `WS_EX_TOOLWINDOW` (kein Alt-Tab-Eintrag) erwägen.
3. **Erfolgskriterium:** Fokus bleibt bei einem offenen Notepad, während das Panel
   sichtbar wird und der User einen Panel-Button klickt; ein direkt danach
   ausgelöster `TextInserter.insert` landet im Notepad. Verifikation manuell auf
   Windows + ein Instrumented-Marker.
4. `focusFree = true` bei Erfolg.

**Fallback (D4.3, gleichwertiger Pfad — Scheitern ist KEINE Eskalation):** Vor dem
Hotkey-Trigger das Vordergrundfenster merken (`User32.GetForegroundWindow()`), das
Panel darf Fokus nehmen; **vor** dem Insert das gemerkte Fenster restaurieren
(`SetForegroundWindow(savedHwnd)` + kurzer Settle-Delay), dann `insert`. Politik in
einer reinen, testbaren Klasse `FocusRestorationPolicy` (Fake-`WindowControl` +
Fake-Keyboard) — was gemerkt/restauriert wird, ist unit-getestet; die rohe
`SetForegroundWindow`-API sitzt hinter dem Port. Beide Pfade in
`adr-desktop-panel-ui` dokumentiert.

> [!CAUTION]
> Der fokus-freie-Fenster-Test bleibt bis zur Spike-Entscheidung ein
> `pending: D2-focus-spike`-Test (test-first-Konvention §8 des Plans). Nicht
> „grün faken" — erst die Spike-Entscheidung schaltet ihn scharf.

## 7. Recording-UI-Nachbau (D2)

Ziel (F19): die Android-Recording-Widget-Optik als Compose-Canvas 1:1 nachbauen.
Alle Design-Parameter sind reine Zahlen/Kurven und damit direkt portierbar; die
Android-`Drawable`/`Paint`-APIs werden durch Compose-`Canvas`/`drawRoundRect`
ersetzt.

### 7.1 Amplitude-Verarbeitung (portieren)

`AmplitudeProcessor` (`core/AmplitudeProcessor.kt`) ist reines `kotlin.math` und
wird nach `:companion` übernommen (Kopie in `capture/` oder — falls das
Recording-UI-Rendering auch geteilt werden soll — nach `:shared-ai`; für v1 Kopie,
§15 Gap 4). Parameter: `effectiveMax=12000`, Log-Normalisierung
`ln(1+clamp)/ln(1+12000)`, Ausgabe 0..1
(`AmplitudeProcessor.kt:36-44`). Produktions-Instanzen nutzen `attack=decay=1.0`
(EMA effektiv Passthrough) — für den Desktop identisch übernehmen.

### 7.2 Wellenform-Bars (Canvas)

| Parameter | Wert | Android-Quelle |
|---|---|---|
| Ring-Buffer / sichtbare Bars | **30** | `AmplitudeVisualizerDrawable.kt:66`, `RecordGlowFactory.kt:41` |
| Push | shift-left, neuester rechts, `coerceIn(0,1)` | `:96-103` |
| Bar-Abstand | 2 % der Bar-Fläche | `:220` |
| max/min Bar-Höhe | `0.55*h` / `0.06*h` | `:225-226` |
| Höhen-Mapping | `minH + (maxH-minH)*amplitude`, um Mitte zentriert | `:235,:244` |
| **Alters-Fade** | `alpha = 0.4 + 0.6*(i/(n-1))` (links α=0.4 … rechts α=1.0) | `:241-242` |
| Cap-Form | Pill, `cornerRadius = barWidth/2` | `:228` |
| Update-Frequenz | 100 ms / 10 Hz | Ticker `:326` |

### 7.3 Farb- + Form-Sprache

- **Bar-Farbe:** Accent → HSV, `sat*=0.4`, `val=1.0` (pastellig)
  (`VisualizerUtils.kt:11-16`).
- **Glow (Helligkeit koppelt an Amplitude):** `hsv[2] = baseV + 0.35*level`
  (`BorderGlowAnimation.kt:154-156`, `RecordGlowFactory.kt:43`); Pause-Baseline
  `+0.12` (`:38`).
- **Breathing-Puls:** ArgbEvaluator peak↔dim, **1500 ms**, INFINITE REVERSE, dim =
  `darken(peak, 0.18)` (`RecordingAnimationController.kt:320-326`). In Compose:
  `rememberInfiniteTransition` + `animateColor`. **Kein Ripple** — der frühere
  rote Ripple wurde 2026-05-23 entfernt (`RecordingAnimationController.kt:20-27`).
- **Layout** (links→rechts): `[Send-Icon] [Amplitude-Bars] [MM:SS] ["PC"-Badge]`
  (`AmplitudeVisualizerDrawable.kt:16-24`); Icon-Größe `0.45*h`, H-Padding `0.35*h`,
  Timer `%02d:%02d` bold weiß rechtsbündig.

### 7.4 Compose-Umsetzungsskizze

Ein `@Composable RecordingBar(state: RecordingUi)` mit `Canvas`, das den
30er-Level-Buffer aus `state` liest, die Bars per `drawRoundRect` zeichnet
(Alters-Fade als `alpha`), den Glow als Hintergrund-Tint über
`animateColorAsState` und den Puls über `InfiniteTransition`. Der Buffer wird vom
`amplitudes: Flow<Float>` (§4.4) über das ViewModel in den State geschoben.
ViewModel-Test deckt: Buffer-Shift, Idle/Active/Paused-Übergänge, Timer-Format.

## 8. Review-Panel + Re-dictate (D3)

### 8.1 `AmbiguityMode` kommt aus dem Profil (F20)

Die Politik ist der geteilte `AmbiguityMode` (`ALWAYS_INSERT | AUTO |
ALWAYS_REVIEW`), aus `:shared-ai` (heute
`preferences/AmbiguityMode.kt`, wandert mit dem conversation-Kern). Auf dem Desktop
wird er **nicht** live gelesen, sondern aus dem aktiven **Profil** genommen und beim
Aufnahme-Start gesnapshottet (Parität zu ADR-0013 K11: „ein konsistenter
Mode-Snapshot pro Lauf"). `forceTurn = mode.forcesTurn` (AUTO+ALWAYS_REVIEW,
`AmbiguityMode.kt:27`) → `includeAmbiguityTask = forceTurn`.

### 8.2 Verdikt über den geteilten `ReviewDecision` (ein Codepfad)

`ReviewDecision.decide(mode, needsClarification, message): Verdict{INSERT, REVIEW}`
(`ai/conversation/ReviewDecision.kt:28-34`) wird **verbatim** aufgerufen — nicht
nachgebaut. Der Desktop legt darüber sein eigenes Sichtbarkeits-Gate (statt Androids
`canShowReviewPanel()`): das Panel ist im Desktop-Modus immer sichtbar, also greift
das Verdikt direkt. Die vollständige Matrix als parametrisierte Testsuite (Akz. 7):

| Modus | needsClarification | message | Verdict |
|---|---|---|---|
| ALWAYS_INSERT | egal | egal | **INSERT** |
| AUTO | false | egal | **INSERT** |
| AUTO | true | blank/null | **INSERT** |
| AUTO | true | non-blank | **REVIEW** |
| ALWAYS_REVIEW | egal | egal | **REVIEW** |

`needsClarification` ist das transiente Wire-Feld aus `StructuredResponse`
(`ai/conversation/StructuredResponse.kt:19`) — nie persistiert, nie zurückgespielt
(`encode()` bleibt zweifeldig `{message, output}`, ADR-0012 §3).

### 8.3 Re-dictate = `ConversationContinuation` (ADR-0013 §6, Surface=Desktop)

Vorbild: `PipelineOrchestrator.continueConversationBlocking`
(`core/PipelineOrchestrator.kt:788-873`). Desktop-Nachbau als Effekt-Handler
`RunContinuation`:

1. Panel-Button „Re-dictate" → `dispatch(StartRefinement)`: Review-Achse setzt
   `refinementRecording=true` (Insert/Discard-Buttons disabled, ADR-0013 K1),
   Aufnahme S2 startet **transcription-only**, `sessions.origin =
   REVIEW_REFINEMENT`.
2. S2-Transkript fertig → **kein** Insert; `dispatch(RefinementTranscribed(text))`
   setzt `refining=true`, enqueued einen `ConversationContinuation`-Job.
3. Effekt `RunContinuation(reviewSessionId, followUpText)`:
   - `followUpMsg = ConversationTurnBuilder.buildFollowUpUserMessage(followUpText)`
     (`ConversationTurnBuilder.kt:78`, wickelt als `<user-reply>` — Instruktion,
     nicht Transkript).
   - `snapshot = sessions.loadConversation(reviewSessionId)` (persistierte Turns +
     `systemContent`).
   - `messages = ConversationReconstructor.toApiMessages(snapshot.turns,
     followUpMsg)` (`ConversationReconstructor.kt:49`).
   - `result = ai.converse(messages, snapshot.systemContent)` — Systemprompt aus der
     persistierten `SYSTEM`-Zeile, nicht aus einem Live-Template (ADR-0012 §3).
   - Persistiere als **neuen** Turn (`chain_index = maxChainIndex+1`,
     `appendConversationTurn`-Äquivalent), `final_output_text = result.output`
     uniform in-Transaktion.
   - Non-terminales `dispatch(ReviewTurnCompleted(output, message,
     needsClarification))` → erneut `ReviewDecision.decide` → Panel aktualisieren
     oder insert+close. Iteratives Re-dictate ist damit möglich.
   - Fehlerpfad: Follow-up als ERROR-Turn persistieren (auditierbar; `loadConversation`
     überspringt ERROR-Turns beim Replay), Panel zeigt Fehler.

### 8.4 Review-Panel-Zustände (Compose)

`ReviewUi(message: String?, output: String, refining: Boolean,
refinementRecording: Boolean)`. Rendering: Output immer; `message` nur wenn
non-blank (sonst output-only, ADR-0013 §5); „Refining…"-Hinweis bei `refining`;
Recording-Hinweis + Bars bei `refinementRecording`. Buttons: **Insert**,
**Re-dictate**, **Discard**. Insert/Discard disabled während `refinementRecording
|| refining` (ADR-0013 K1). Discard doppelt als Cancel des laufenden Refinements
(`JobQueue.cancel` + `CancelRefinement`-Intent).

### 8.5 Insert + Discard (ein Acknowledge-Kanal)

- **Insert:** `TextInserter.insert(output)` (ADR-0018, `domain/port/TextInserter.kt`)
  — Windows Ctrl+V in das (restaurierte, §6.3) Vordergrundfenster; Linux Clipboard +
  Hinweis (`available=false`). Danach Session als „acknowledged" markieren
  (`inserted_at = now`) und Panel schließen. Auto-Insert-Politik F21: bei
  `Verdict.INSERT` ohne Review direkt einfügen; Setting „vor Insert bestätigen"
  (`CompanionSettings`-Key `insertion.confirmBeforeInsert`, Default false) schaltet
  eine Bestätigung davor.
- **Discard:** `inserted_at = now` als Acknowledge (kein Insert), Panel schließen —
  ein Kanal für beide (ADR-0013 §4).

## 9. Verwaltungs- + History-UI (D3)

### 9.1 Panel-Einstieg + Profil-Dropdown (F20)

Das Panel trägt oben ein Profil-Dropdown (aktives Profil aus C1-Typen; D1/D2:
Übergangs-Auswahl aus Settings). Linux-Trigger (kein Hotkey): Tray-Menüeintrag
„Diktat starten" + Panel-Button. Der Tray existiert bereits
(`Main.kt:129-151`) — ein Eintrag ergänzen.

### 9.2 Verwaltungs-Screens (Compose, bestehender NavigationRail-Stil)

Neue Screens im bestehenden `ui/`-Muster (plain-Class-ViewModel + `MutableStateFlow`,
Vorbild `SettingsViewModel.kt:52-59`; injizierter `CoroutineScope` bei IO wie
`HistoryViewModel.kt:51-55`):

- **Profil-Editor:** Liste, Anlegen/Duplizieren/Verschieben, aktives Profil
  (Datengrundlage aus Block C; D3 konsumiert die C1-`Profile`-Typen).
- **Modell-Switcher:** zweistufig (Provider → Modell aus `ModelFetcher`
  (`ai/model/ModelFetcher.kt:42`) ∪ `ModelRef`s ∪ Freitext), gleiche
  `:shared-ai`-Datenquelle wie Android-C3 — Parameter-UI aus `ParameterRegistry`
  (`ai/model/ParameterRegistry.kt`).
- **Prompt-Editor:** lokal (Peer-Quelle kommt in E3).

Diese Screens hängen an Block C (`depends_on: D3→{D2,C1}`, Plan §7). Wo C1-Typen
fehlen, ist der Screen ein Stub gegen die Übergangs-Settings (§15 Gap 5).

### 9.3 History-Screen-Ausbau

Der bestehende `HistoryScreen`/`HistoryViewModel` liest heute `received_texts`; nach
der Ablösung (§3.4) liest er `sessions` (+ `dispatch_state` für Phone-Sync-Outcome).
Ausbau auf das Session-Modell: Detailansicht Transcript vs. finaler Output
(`transcriptions.text` vs. `sessions.final_output_text`), `host_origin`-Filter
(Phone/Desktop), Re-Insert (`TextInserter.insert(final_output_text)`). Das
`pageHistory`/`countHistory`-Query-Muster (`Companion.sq:131-141`, `instr()`-Suche)
bleibt, umgeschrieben auf `sessions`.

## 10. Directory Layout

```
companion/src/main/kotlin/net/devemperor/dictate/companion/
├── capture/                                    [NEW]
│   ├── AudioCaptureService.kt                  [NEW]  javax.sound TargetDataLine, Segmente
│   ├── WavWriter.kt / WavConcat.kt             [NEW]  RIFF-Header, Segment-Merge (unit-testbar)
│   ├── AudioDeviceCatalog.kt                   [NEW]  Mixer-Enumeration + Persistenz
│   └── AmplitudeProcessor.kt                   [NEW]  Kopie aus :app/core (reine Mathematik)
├── pipeline/                                   [NEW]
│   ├── DesktopDictationController.kt           [NEW]  die eine Dispatch-Tür (§5.1)
│   ├── DictationReducer.kt                     [NEW]  reine Reducer (§5.4)
│   ├── DesktopUiState.kt / DictationIntent.kt  [NEW]  State + Intents (§5.3)
│   ├── DictationEffects.kt                     [NEW]  Effekt-Handler (IO)
│   └── JobQueue.kt                             [NEW]  serielle Queue (ADR-0009-Semantik)
├── hotkey/                                     [NEW]
│   ├── GlobalHotkey.kt                         [NEW]  Port (§6.1)
│   └── HotkeyCombo.kt                          [NEW]
├── ui/panel/                                   [NEW]
│   ├── PanelWindow.kt / PanelWindowControl.kt  [NEW]  warmes fokus-freies Fenster (§6.2)
│   ├── RecordingBar.kt                         [NEW]  Canvas-Nachbau (§7)
│   ├── ReviewPanel.kt                          [NEW]  Prüfmodus-UI (§8.4)
│   └── PanelViewModel.kt                       [NEW]  StateFlow<DesktopUiState>
├── ui/{profiles,models,prompts}/               [NEW]  Verwaltungs-Screens (§9.2, C-abhängig)
├── domain/session/                             [NEW]
│   ├── SessionType/Status/Origin.kt, StepType/Status.kt,
│   │   ResponseFormatKind.kt, MessageRole.kt, InsertionMethod.kt,
│   │   HostOrigin.kt                           [NEW]  Double-Enum-Kotlin-Hälften (§3.2)
│   └── (AiErrorType/AIProvider: aus :shared-ai importiert, NICHT hier)
├── data/
│   ├── SqlDelightHistoryRepository.kt          [EDIT] received_texts→sessions+dispatch_state (§3.5)
│   ├── DesktopSessionRepository.kt             [NEW]  Session/Transcription/Step/Conversation-Writes
│   ├── SqlDelightUsageSink.kt                  [NEW]  UsageSink-Port-Impl (§5.4)
│   └── CompanionDatabase.kt                    [EDIT] neue ColumnAdapter registrieren (§3.1)
├── platform/
│   ├── windows/Win32GlobalHotkey.kt            [NEW]  RegisterHotKey (§6.1)
│   ├── windows/Win32PanelWindowControl.kt      [NEW]  WS_EX_NOACTIVATE-Spike + Fallback (§6.3)
│   ├── fallback/NoopGlobalHotkey.kt            [NEW]  Linux (§6.1)
│   └── AppPaths.kt                             [EDIT] recordingsDirectory() ergänzen (§4.3)
├── domain/SyncService.kt / DispatchService.kt  [EDIT] Cursor/dispatch auf neue Tabellen (§3.5)
└── domain/CompanionSettings.kt                 [EDIT] audio.inputDevice, hotkey.combo, insertion.* (§4.2,§6,§8.5)

companion/src/main/sqldelight/net/devemperor/dictate/companion/db/
├── Companion.sq                                [EDIT] +sessions/transcriptions/processing_steps/
│                                                      conversation_messages/dispatch_state/usage,
│                                                      -received_texts (§3.3)
├── migrations/2.sqm                            [NEW]  v2→v3 Backfill + Ablösung (§3.4)
└── databases/3.db                              [NEW]  Snapshot (verifyMigrations)
```

**File-Delta grob:** 4 neue Subsysteme (`capture`, `pipeline`, `hotkey`,
`ui/panel`), ~9 companion-lokale Session-Enums, 1 Schema-Migration + Ablösung,
~5 EDIT-Dateien im Sync-Pfad. Block D fasst **`:app` nicht an** (nur read-only als
Parity-Referenz).

## 11. Chunk-Grenzen D1 / D2 / D3

Der Plan schneidet D1 (Datenmodell+Aufnahme+Pipeline) / D2 (Hotkey+Panel+
Recording-UI+Insert) / D3 (Review+Verwaltung+History). Bewertung: **Der Schnitt ist
tragfähig und file-disjunkt**, mit **einer** empfohlenen Präzisierung.

- **D1** (`data/` + `capture/` + `pipeline/` + `domain/session/` + Sync-Umbau):
  in sich geschlossen, headless testbar (Fake-Runner, WAV-Fixture), keine
  UI-Abhängigkeit. Der `received_texts`-Sync-Umbau (§3.5) gehört klar zu D1
  (Schema-Eigentümer). **Größter Chunk** — vertretbar (ein großer Fokusbereich).
- **D2** (`hotkey/` + `ui/panel/` PanelWindow/RecordingBar + Insert): hängt an D1
  (`pipeline/` + `DesktopUiState`), file-disjunkt zu D1. Der Fokus-Spike ist die
  erste D2-Aufgabe (R1). Recording-UI-Canvas ist rein additiv.
- **D3** (`ui/panel/ReviewPanel` + `ui/{profiles,models,prompts}` + History):
  hängt an D2 (Panel-Gerüst) + C1 (Profil-Typen), file-disjunkt.

**Empfohlene Präzisierung (§14 D2):** Die **Enum-Definitionen + SQLDelight-Schema +
Parity-Tests** (reines `data/` + `domain/session/`) bilden einen sauberen,
früh-abschließbaren *Vorlauf* innerhalb D1. Falls D1 zu groß wird, ist der
natürliche Sub-Schnitt: **D1a Schema+Parität+Sync-Ablösung** / **D1b
Aufnahme+Pipeline**. D1a ist die Voraussetzung für alles Persistierende und hat das
höchste Regressionsrisiko (Sync) — es zuerst grün zu haben entkoppelt das Risiko vom
Pipeline-Aufbau. Kein zwingender Split, aber der empfohlene Bruch, falls der v3-Audit
D1 als zu breit bewertet.

## 12. Testing Approach

Konventionen: `~/.claude/snippets/test-first-patterns.md` (TDD für Neubau,
Regression-Tests rot-vor-grün, pending für dokumentierte Lücken).

- **Parity-Tests** (`data/`, §3.6): CHECK-Akzeptanz/Ablehnung pro Double-Enum-Spalte
  (Vorbild `OriginCheckConstraintParityTest`) + `RoomParityReference`-Abgleich.
  Pflicht-Gate bei jeder Schema-Änderung (`adr-companion-history-parity`).
- **MigrationTest** (`2.sqm`): In-Memory-DB auf v2 stampen, Fixture
  `received_texts` (≥1 device, Zeilen mit `origin='UNKNOWN'`, `dispatched=0/1`)
  einspielen, migrieren, `sessions`+`dispatch_state` verifizieren (Zeilenzahl,
  Origin-Mapping, `inserted_at`-Ableitung, `received_texts` weg). `verifyMigrations`
  deckt die Schema-Konsistenz separat ab.
- **Sync-Regression** (bestehende E2E, §2 Krit. 4): `SyncE2ETest`,
  `CompanionE2ETest`, `MultiConnectorE2ETest`, `TruncatedResponseE2ETest`,
  `SqlDelightHistoryRepositoryTest` — **ohne Assertion-Änderung** grün nach dem
  Cursor-Umbau. Das ist der Verhaltensneutralitäts-Beweis.
- **Pipeline-Reducer-Tests**: Phasenübergänge, Pause/Resume/Discard, ADR-0009-Enqueue
  (zweiter Trigger reiht ein). Reine JVM-Tests (Reducer sind IO-frei).
- **Pipeline-E2E (headless)**: Fake-`TranscriptionRunner`/`CompletionRunner`
  (Vorbild `RunnerFactory`-open-Seam, `shared-ai-extraktion.md §8`), WAV-Fixture →
  Session+Transcription+Step+Conversation persistiert.
- **Review-Matrix** (§8.2): parametrisierte Suite (3 Modi × needsClarification ×
  message-blank) gegen den Desktop-Aufrufer von `ReviewDecision`.
- **Re-dictate-E2E**: `needsClarification=true` → Panel hält; Re-dictate-Fixture →
  `REVIEW_REFINEMENT`-Session + Continuation-Turn persistiert + Panel aktualisiert.
- **WAV-Unit-Tests**: `WavWriter` (Header korrekt), `WavConcat` (Merge zweier
  Fixtures = gültige RIFF-Länge), `AmplitudeProcessor` (Log-Norm-Kurve).
- **Fokus-Politik**: `FocusRestorationPolicy` mit Fake-`WindowControl`+Fake-Keyboard
  (merken/restaurieren-Reihenfolge). Fokus-freies-Fenster als
  `pending: D2-focus-spike`, bis der Spike entschieden ist.
- **ViewModel-Tests** (Companion-Muster `HistoryViewModelTest`): Panel-, Review-,
  Recording-Buffer-Zustände.
- **Manuelle Windows-Abnahme** (F1): Hotkey→Panel→Diktat→Insert; Linux
  Tray→Clipboard.

## 13. Footguns / Anti-Patterns

- **`ReviewDecision` nachbauen.** Nicht kopieren, nicht „anpassen" — der geteilte
  `:shared-ai`-Aufruf ist die einzige Verdikt-Autorität (ein Codepfad, Akz. 7). Ein
  zweiter Decision-Zweig driftet garantiert.
- **CHECK vergessen.** Eine Finite-Set-Spalte ohne CHECK ist nur die halbe
  Double-Enum-Regel; der Parity-Test fängt es, aber nur wenn der Ablehnungs-Test
  (`'TELEPATHY'` wird abgelehnt) existiert — sonst grünt der Test trotz fehlendem
  CHECK (siehe `OriginCheckConstraintParityTest`-Kommentar).
- **`verifyMigrations` umgehen.** Eine `.sq`-Änderung ohne `.sqm` + Snapshot
  bricht den Build. Immer beides liefern (§3.5, `companion/build.gradle:14`).
- **Sync-Cursor-Ordnung ändern.** `created_at DESC, id DESC` ist der
  ADR-0020-Paritätskontrakt zum Phone; jede Abweichung überspringt/dupliziert Zeilen
  im Paging. Der Umbau ist rein mechanisch (Tabelle wechselt, Ordnung nicht).
- **Fokus klauen.** Ein normales Compose-Fenster nimmt beim Zeigen den Fokus →
  Ctrl+V geht ins Panel. Ohne bewiesenen Spike **immer** den Fallback (Fokus
  restaurieren vor Insert), nie „wird schon".
- **Systemprompt aus Live-Template.** Beim Continuation-`converse` den Systemprompt
  aus der persistierten `SYSTEM`-Zeile nehmen (`snapshot.systemContent`), nicht neu
  bauen — sonst driftet der Dialog nach Template-Änderungen (ADR-0012 §3).
- **Reducer mit IO/Zeit.** `System.currentTimeMillis()`/UUID/Aufnahme im Reducer =
  untestbar. Alles in Effekte, Zeit über `ClockPort`.
- **`AiErrorType`/`AIProvider` companion-lokal kopieren.** Aus `:shared-ai`
  importieren, sonst driften Fehler-/Provider-Vokabulare gegen den Kern (§3.2).

## 14. Decision Log

### D1 — `received_texts` wird abgelöst, nicht koexistiert (schließt Plan-Gap 5)

**Trigger:** Plan D1 + §10 Gap 5 verlangen die Klärung „Ablösung/Koexistenz" der
`received_texts`-Tabelle im neuen Session-Modell.

**Decision:** Ablösung. `sessions` ist das einzige History-Archiv (Room-parität);
die Sync-spezifischen Felder (device_id, dispatched, last_outcome, received_at)
wandern in eine 1:1-Begleittabelle `dispatch_state`; `received_texts` wird nach
Backfill gedroppt (`2.sqm`, §3.4). Der Phone-Sync schreibt/liest fortan
`sessions`+`dispatch_state` mit unveränderter Ordnung und „never-downgrade-
dispatched"-Invariante.

**Rationale:** Zwei Tabellen für „welche Texte existieren" = doppelte Wahrheit
(SSoT-Anti-Pattern, `knowledge-doc-format`). Eine einmalige Migration ist billiger
als dauerhafte Union-Reads + Kohärenz-Pflege. `dispatch_state` statt Spalten-in-
`sessions` hält die Room-Parität sauber (device/dispatched würden die Parity-Diff
verschmutzen).

**Alternatives Considered:** (a) Koexistenz + Union-Read — verworfen (dauerhafte
Doppel-Wahrheit). (b) Sync-Felder direkt in `sessions` — verworfen (verschmutzt die
Parity). (c) `received_texts` behalten, nur Desktop in `sessions` — verworfen
(History-UI müsste zwei Quellen mischen).

### D2 — D1 optional in D1a (Schema+Parität+Sync) / D1b (Aufnahme+Pipeline) teilbar

**Trigger:** D1 ist der breiteste Chunk; der Sync-Umbau trägt das höchste
Regressionsrisiko.

**Decision:** Empfehlung, kein Zwang: falls der Audit D1 als zu breit bewertet, ist
der file-disjunkte Bruch D1a (`data/` + `domain/session/` + Sync-Ablösung) vor D1b
(`capture/` + `pipeline/`). D1a zuerst grün entkoppelt das Sync-Risiko vom
Pipeline-Aufbau.

**Rationale:** Risiko-Isolierung; D1a ist Voraussetzung für alles Persistierende.

### D3 — `processing_steps.status` bekommt im Companion einen CHECK (über Room hinaus)

**Trigger:** Room hat für `processing_steps.status` **keinen** CHECK (nur
`TEXT NOT NULL`, `MigrationTo8.kt:63`), obwohl `StepStatus{SUCCESS,ERROR}` ein
Finite-Set ist.

**Decision:** Der Companion setzt den CHECK `status IN ('SUCCESS','ERROR')` — eine
strikte Verbesserung, kein Paritätsbruch (jeder Room-Wert bleibt gültig). Der
Parity-Test dokumentiert die bewusste Asymmetrie.

**Rationale:** Double-Enum-Vollständigkeit auf der grünen Wiese; die
Companion-Schema-Definition ist neu, also gibt es keinen Migrations-Zwang zur
Room-Lücke.

### D4 — Cross-Spec-Entscheidungen des Plan-Architekten (Plan §3 D5, 2026-07-20)

**Trigger:** Plan-Verfeinerung nach Vorliegen aller fünf Block-Specs; vier
Entscheidungen betreffen Block D.

**Decision:**
1. **D1a/D1b-Split ist VERBINDLICH** (nicht mehr „optional" wie in D2):
   D1a = §3 komplett (Schema + Parität + `received_texts`-Ablösung +
   Sync-Umbau), D1b = §4 + §5 (Capture + Pipeline). Die fünf Bestands-Tests
   aus §3.5 sind D1a-Akzeptanz.
2. **Companion-Entitäts-Tabellen: D3 legt sie VOLLSTÄNDIG an** (Plan D5.b) —
   `provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts`
   nach der DDL aus `peer-katalog.md` §5.2 **inkl. Provenienz-Spalten**;
   E1 legt nur `peers`/`subscriptions`/`catalog_access_log` an und bekommt
   die neue Kante E1→D3. Damit ist §9.2/„Datengrundlage aus Block C"
   präzisiert: die C1-**Typen** kommen aus `:shared`, die Companion-
   **Tabellen** aus D3.
3. **SQLDelight-Migrations-Nummern:** D1a = `2.sqm` (§3.4), D3 = `3.sqm`
   (Entitäts-Tabellen), E1 = `4.sqm` — löst die Nummern-Kollision zwischen
   §3.4 und peer-katalog §5 auf.
4. **`AmplitudeProcessor`: MOVE nach `:shared-ai` statt v1-Kopie** (Plan
   D5.e, überstimmt §15 Gap 4 und die Kopie-Verweise in §4.4/§7.1/§10):
   package-erhaltender Move in Chunk A2 (shared-ai-Spec §11-Nachtrag);
   `capture/` importiert die Klasse aus `:shared-ai`. Begründung: die
   Kurvenparameter sind die F19-Design-Spec — eine Kopie driftet unsichtbar.

**Rationale:** Risiko-Isolation (1), ein Schema-Owner pro Fläche + kein
D↔E-Block-Zyklus (2, 3), DRY wo Verhalten identisch bleiben MUSS (4).

### D5 — Freshness-Pass 2026-07-20 (Post-Implementation, vor Archivierung)

**Trigger:** Integrations-Check nach Abschluss Block A–E (Finding `integ-1`,
green) — Abgleich gegen den gebauten Stand. D4 hatte die Migrations-Nummern
(D1a=`2.sqm`, D3=`3.sqm`, E1=`4.sqm`) bereits as-built festgelegt; die folgenden
zwei Punkte waren beim D4-Zeitpunkt noch nicht ausgeführt.
**As-built vs. Spec:**
1. **`usage`-Tabelle liegt in `3.sqm` (D3), nicht in der D1a-Migration `2.sqm`.**
   §3.3-Note/§5.4 lesen die `usage`-Tabelle konzeptionell als D1-Begleiter des
   `UsageSink`-Ports; physisch wurde sie in die D3-Migration
   `companion/.../db/migrations/3.sqm` gefaltet (Inline-Header dort dokumentiert
   „Folded into this migration"). Verhalten unverändert — nur die
   Migrations-Zuordnung wanderte sich, um die Tabellen-Owner-Grenze sauber zu
   halten (D3 besitzt die zusätzlichen Companion-Tabellen).
2. **Verwaltungs-UI konsolidiert.** §9.2 skizziert getrennte Profil-/Modell-/
   Prompt-Editor-Screens (implizit `ui/profiles`/`ui/models`/`ui/prompts`).
   Gebaut wurde EIN konsolidierter Screen
   `companion/.../ui/config/ManagementScreen.kt` + `ConfigViewModel.kt`
   (Provider/Modell/Prompt/Profil in einer datengetriebenen Fläche) — DRY über die
   gemeinsame Entitäts-Bearbeitung, statt vier fast identischer Screens.
**Bewertung:** Kein Code-Impact; beide Punkte sind
charakterisierungs-/paritäts-getestet und in den Impl-Reports notiert. Body
unverändert; dieser Eintrag ist die normative As-built-Korrektur.

## 15. Information Gaps

1. **Cross-Modul-Enum-SSoT.** Die Session-Struktur-Enums sind companion-lokal
   dupliziert (Room-Original in `:app`), abgesichert nur per getesteter
   `RoomParityReference`. Der echte SSoT (Enums in `:shared`, Room+SQLDelight
   mappen darauf) verlangt einen `:app`-Room-Refactor außerhalb Block D. **Owner:**
   spätere Konsolidierung / `adr-companion-history-parity`. **Fallback:** getestete
   Referenzliste (§3.6).
2. **Audio-Format vs. Provider-Limits.** WAV 16 kHz mono ≈ 1,92 MB/min; die
   konkreten Upload-Limits pro Transcription-Provider sind vom D1-Agenten zu
   verifizieren (reine Verifikation, D4.2). **Fallback:** Opus/OGG als spätere
   Option, wenn ein Limit reißt.
3. **Cold-Resume der Aufnahme.** Für v1 nicht implementiert (Desktop-Prozess
   langlebig); die Segmentliste in `audio_file_paths` hält die Tür offen. **Owner:**
   Folge-Iteration. **Fallback:** kein Resume nach Companion-Crash (Aufnahme neu).
4. ~~**`AmplitudeProcessor`-Ablageort**~~ — **geschlossen 2026-07-20 (§14 D4.4
   / Plan D5.e):** Move nach `:shared-ai` (package-erhaltend, in Chunk A2)
   statt Kopie; `capture/` importiert aus `:shared-ai`.
5. **Verwaltungs-Screens ↔ Block C.** Profil-/Modell-/Prompt-Editor (§9.2) hängen an
   den C1-`Profile`-Typen; solange C1 nicht landet, sind sie Stubs gegen die
   Übergangs-`CompanionSettings`-`AiConfig`. **Owner:** D3-Agent koordiniert mit dem
   C-Strang (`depends_on: D3→{D2,C1}`). **Fallback:** Diktat läuft in D1/D2 gegen die
   Übergangs-Config.
6. **Windows-Tray-Notification-Mechanik.** Für Sync-/Fehler-Hinweise (AWT
   `TrayIcon.displayMessage` vs. Toast) — Owner ist laut Plan der E2-Agent (§10
   Gap 6 des Plans); Block D nutzt nur den Panel-Hinweis (Linux Clipboard).

## 16. References

- **Plan:** `~/.claude/plans/desktop-companion-v1.md` — §5 Block D (Chunks
  D1/D2/D3), §3 Entscheidungen F4/F15/F16/F18/F19/F20/F21 + D2/D4, §7
  Sequenzierung, §10 Gaps 2/5.
- **Schwester-Specs:** `shared-ai-extraktion.md` (Block A — `:shared-ai`-Kern +
  Ports, Vorbedingung), `secretstore.md` (Block B — Credential-Ablage).
- **ADR-Drafts (Block A1, plan-scoped):** `adr-desktop-dictation-host`,
  `adr-desktop-panel-ui`, `adr-desktop-review`, `adr-companion-history-parity`.
- **Bindende ADRs:** `docs/decisions/0007` (Rolling-Segments/Multi-File-Audio),
  `0009` (serielle Run-Queue), `0011` (getFinalOutput/pending/terminal-guard),
  `0012` (Post-Processing-Conversation + `{message,output}`), `0013`
  (Review-Panel + AmbiguityMode-Verdikt-Matrix), `0016` (Wire-SSoT), `0018`
  (TextInserter-Port), `0020` (Lazy-Cursor-Sync — Cursor-Paritätskontrakt), `0023`
  (Bind-Katalog), `0027` (PC-Dictation, bleibt additiv parallel).
- **Konventionen:** `docs/DATABASE-PATTERNS.md` (Double-Enum),
  `~/.claude/snippets/test-first-patterns.md`.
- **Schlüssel-Code (Ist-Stand):**
  - Room-Schema: `app/schemas/net.devemperor.dictate.database.DictateDatabase/11.json`;
    Enums `app/.../database/entity/{SessionType,SessionStatus,SessionOrigin,StepType,
    StepStatus,ResponseFormatKind,MessageRole,InsertionMethod}.kt`; CHECKs in
    `app/.../database/migration/MigrationTo{8,9,10,11}.kt`.
  - Companion-Schema + Migration: `companion/.../db/Companion.sq`,
    `data/SchemaMigrator.kt:31-56`, `data/CompanionDatabase.kt:38-47`,
    `companion/build.gradle:9-22`; Parity-Vorbild
    `.../data/OriginCheckConstraintParityTest.kt`.
  - AI-Kern (nach Block A in `:shared-ai`): `ai/AIOrchestrator.kt:41,130`,
    `ai/factory/RunnerFactory.kt`, `ai/conversation/{ReviewDecision:28,
    ConversationTurnBuilder:36,78, ConversationReconstructor:49, StructuredResponse:19}.kt`,
    `preferences/AmbiguityMode.kt:27`, `ai/model/{ModelFetcher:42,ParameterRegistry}.kt`.
  - Re-dictate-Vorbild: `core/PipelineOrchestrator.kt:788-873`.
  - Recording-Optik: `core/AmplitudeProcessor.kt:34-44`,
    `widget/AmplitudeVisualizerDrawable.kt`, `widget/VisualizerUtils.kt`,
    `widget/BorderGlowAnimation.kt:154`, `state/render/RecordingAnimationController.kt:320`.
  - Companion-Infra: `Main.kt` (Window/Tray :129-174), `CompanionContainer.kt`,
    `platform/AppPaths.kt:19`, `platform/windows/{Win32Keyboard,Win32TextInserter}.kt`,
    `domain/{CompanionSettings,SyncService,DispatchService}.kt`,
    `domain/port/TextInserter.kt`.
```
