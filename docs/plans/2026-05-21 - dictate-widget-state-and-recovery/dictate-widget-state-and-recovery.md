---
name: dictate-widget-state-and-recovery
archive_target: "2026-05-21 - dictate-widget-state-and-recovery"
status: Proposed
language: de
---

# Dictate — WidgetState + Crash-Recovery Refactor

**Status:** Proposed
**Created:** 2026-05-21
**Author:** Lukas + Claude Code
**Related ADRs:** [[ADR-0008]] (Surface-Axes, supersedes [[ADR-0005]]), [[ADR-0007]] (Multi-File-Repository, erweitert)

> **Scope:** Ein zusammenhängender Refactor, der **drei** Probleme adressiert,
> deren Lösungen architektonisch denselben Code berühren:
>
> 1. **Pause-Button im Widget-Modus** — Senden-Button bekommt im Widget einen
>    Pause-Toggle; Host-Eingabe wird im Widget komplett blockiert.
> 2. **Origin-Tracking statt Triangle-FSM** — WidgetState mit
>    `Visible(origin=USER|PIPELINE)` ersetzt die heutige
>    `ViewMode.{KEYBOARD,WIDGET,HOVER}` Triangle-FSM; die IME-View-Visibility
>    wird als orthogonale Achse explizit.
> 3. **Crash-Recovery mit Auto-Continuation** — Multi-Segment-Audio über
>    Crashes hinweg fortsetzen (Variante A); Rolling-Segments (L2) gegen
>    Audio-Verlust; InfoBar-Producer für Pipeline-Output-Recovery.

## 1 — Motivation

### 1.1 Origin-Verlust in der Triangle-FSM

Die heutige `ViewMode`-Enum (KEYBOARD | WIDGET | HOVER) packt drei orthogonale
Achsen in eine Variable: *welche Oberfläche ist sichtbar*, *wer hat sie
ausgelöst*, *läuft Pipeline/Recording*. Die `computeViewMode`-Truth-Table
löst Konflikte zwischen Row 3 (`!imeView && userPrefersWidget → WIDGET`)
und Row 4 (`!imeView && pipelineActive → HOVER`) durch Row-Priorität —
ein Patch vom 2026-05-21, der semantisch verloren lässt, **warum** wir
gerade in WIDGET sind.

Konsequenz: nach Pipeline-Ende kann das System nicht entscheiden, ob
es zu KEYBOARD (Pipeline war Trigger) oder zu WIDGET (User wollte Widget)
zurückkehren soll — die Information ist verloren.

### 1.2 Senden im Widget ohne Eingabeziel

Heute ist Senden im WIDGET-Modus erlaubt (`resolveOverlayRecordAction`
returnt eine echte Action). Aber: wenn die Tastatur nicht sichtbar ist,
ist auch keine garantierte InputConnection da — der Text geht
potenziell verloren. HOVER ist heute schon geschützt (return null),
WIDGET nicht.

### 1.3 Crash-Recovery ist destruktiv

Bei Process-Death während `RecordingState.Active` setzt
`PipelineRecovery.runStatusPromotion` heute alle RECORDING-Sessions
auf FAILED und löscht die zugehörigen Audio-Files. Der User verliert
die komplette Aufnahme.

ADR-0007 hat die Multi-Segment-Architektur (`allocateNext`) für den
Cold-Resume-Use-Case bereits **designed**, aber **nicht aktiviert**.
Damit der User nahtlos nach einem Crash weitermachen kann, muss diese
Verkabelung jetzt erfolgen — und das überschneidet sich mit dem
Surface-Axes-Refactor an mehreren Stellen (Recording-Module,
InfoBar-System, ActionResolvers).

## 2 — Architektur-Ziel

```
┌─────────────────────────────────────────────────────────────────────┐
│ State-Modell nach Refactor                                          │
│                                                                      │
│  DictateUiState                                                      │
│   ├─ widget: WidgetState (Hidden | Visible(origin: WidgetOrigin))   │
│   ├─ imeViewVisible: Boolean    ◄── orthogonale Achse              │
│   ├─ recording                  ◄── unverändert                     │
│   ├─ pipeline                   ◄── unverändert                     │
│   ├─ overlay (suppress-bit, position, permission)                   │
│   └─ pendingSessions (hydratisiert bei Boot)                        │
│                                                                      │
│  WidgetOrigin:                                                       │
│   ├─ USER     ← sticky (User-Toggle)                                │
│   └─ PIPELINE ← transient (auto-show + auto-close)                  │
│                                                                      │
│  Surface-Rendering (jede Achse einzeln, beide gleichzeitig möglich) │
│   ├─ keyboard rendered  ⇔  imeViewVisible == true                   │
│   └─ widget   rendered  ⇔  widget is Visible                        │
│                                                                      │
│  Audio-Layer (erweitert)                                            │
│   ├─ AudioFileRepository (persistent in cacheDir/audio/)            │
│   │   ├─ allocateFirst(sessionId)                                   │
│   │   ├─ allocateNext(sessionId)    ◄── jetzt verkabelt            │
│   │   ├─ readForPipeline(sessionId) → PipelineAudioResult           │
│   │   │   ├─ Complete(file)                                         │
│   │   │   └─ PartialRecovery(file, ignoredSegments, lostSeconds)   │
│   │   └─ rollingRotate (L2, periodisches setNextOutputFile)         │
│   └─ Codec-Param-Persistence (B.3 aus ADR-0007)                     │
│                                                                      │
│  Recovery-Layer (erweitert)                                         │
│   ├─ PipelineRecovery                                               │
│   │   ├─ RECORDING + Segments existieren                            │
│   │   │     → RECORDING_INTERRUPTED (statt FAILED + delete)         │
│   │   ├─ Auto-Continuation: Record-Klick → reuse sessionId          │
│   │   └─ Trash-Btn löscht alles                                     │
│   └─ FGS-Lifecycle (Crash-Resilience)                               │
│       ├─ START_REDELIVER_INTENT (Re-Delivery nach Process-Kill)     │
│       ├─ BootCompletedReceiver → WorkManager-One-Shot Recovery-Job  │
│       ├─ Persistent FGS-Notification (DB-re-derived nach Restart)   │
│       └─ Idempotenz: Double-Recovery (Boot + IME-Bind) ist no-op    │
│                                                                      │
│  InfoBar-Layer (Recovery ist SILENT, nur Probleme zeigen)          │
│   ├─ Partial-Recovery    ← "N Sekunden Audio verloren beim Senden" │
│   │  (nur wenn ein Segment unlesbar war — sonst nichts)            │
│   └─ Pending-Insertion   ← "Text bereit: »erste 60 Zeichen…« ✓"   │
│      (nach FGS-Pipeline-Done, wenn IME nicht da war)               │
└─────────────────────────────────────────────────────────────────────┘
```

## 3 — Transitions (ersetzt ADR-0005 T1-T7)

| ID | Trigger | Pre-Bedingung | Resultat |
|---|---|---|---|
| W1 | User klickt Widget-Toggle | `widget == Hidden` | `widget = Visible(USER)` |
| W2 | User klickt Close-Btn im Widget | `widget is Visible` | `widget = Hidden` + `suppressBit = true` + `recording.Active → Paused` (Pipeline läuft weiter) |
| W3 | `OnImeViewHidden` + (recording aktiv ∨ pipeline läuft) | `widget == Hidden && !suppressBit` | `widget = Visible(PIPELINE)` |
| W4 | `OnImeViewShown` | `widget == Visible(PIPELINE)` | `widget = Hidden` |
| W5 | `OnImeViewShown` | `widget == Visible(USER)` | bleibt — sticky |
| W6 | `recording=Idle ∧ pipeline=Idle` | `widget == Visible(PIPELINE)` | `widget = Hidden` |
| W7 | `recording.Idle → Preparing` | `suppressBit == true` | `suppressBit = false` (heute schon so) |
| W8 | `recording.Paused → Active` (Resume) | `suppressBit == true` | `suppressBit = false` (NEU) |

**Eigenschaften:**
- **Strukturelles Sticky-Verhalten**: durch W5 garantiert (statt Row-3-Patch).
- **Origin-Erhaltung**: USER-Origin überlebt jeden auto-Trigger (W3 feuert nur wenn `widget==Hidden`).
- **Pipeline-Continuation**: W2 verhindert Cancel — die Pipeline läuft im FGS fertig und das Result wird per InfoBar dem User angeboten.

## 4 — Blocks

> Sequenz-Constraints: B0 → (B1, B3 parallel) → (B2, B4) → B5

### B0 — Docs

**Acceptance Criteria:**
- [ ] ADR-0008 (WidgetState supersedes 0005) geschrieben, Status: Proposed
- [ ] ADR-0007 Decision-History-Entry für Continuation + L2 + PipelineAudioResult
- [ ] ADR-0005 Decision-History-Entry "Superseded by 0008", Status: Superseded
- [ ] Dieses Plan-Hauptfile mit Block-Acceptance-Criteria
- [ ] ADR-Index aktualisiert

**Dependencies:** —

### B1 — Audio-Repository-Refactor + Continuation-Foundation

**Ziel:** Multi-Segment-Architektur (ADR-0007) wird aktiviert und um
Rolling-Segments + Continuation erweitert. Repository wird sauber von
RecordingManager getrennt.

**Acceptance Criteria:**
- [ ] `allocateNext(sessionId)` ist verkabelt — Call-Sites in
      `RecordingHardwareAdapter` für Cold-Resume und für Pause/Resume
- [ ] Codec-Param-Persistence (ADR-0007 B.3-Mitigation): bei
      `allocateNext` werden Sample-Rate, Bitrate, Channel-Count vom
      letzten Segment via `MediaExtractor` gelesen und neuer
      MediaRecorder identisch konfiguriert
- [ ] Rolling-Segments (L2): periodischer `setNextOutputFile`-Trigger
      (Default 30s, konfigurierbar via `Pref.RollingSegmentIntervalSec`)
      — bei Crash maximaler Audio-Verlust = Intervall
- [ ] `PipelineAudioResult` sealed class eingeführt:
      `Complete(file)` und `PartialRecovery(file, ignoredSegmentIndices,
      estimatedLostSeconds)`
- [ ] `readForPipeline(sessionId)` returnt `PipelineAudioResult`:
      MediaMuxer-Concat überspringt unlesbare Segments und meldet sie
      im Ignored-Set
- [ ] Audio-Repository hat keine Recording-Specific-Logic mehr (saubere
      Trennung Repo ↔ Hardware-Adapter)
- [ ] **FGS-Lifecycle für Crash-Resilience (User-Option 3, 2026-05-21):**
      - [ ] `DictatePipelineService.onStartCommand` returnt
            `START_REDELIVER_INTENT` statt `START_STICKY` —
            re-delivery von Last-Intent nach Process-Kill ermöglicht
            Service-side Recovery von In-Flight-Pipeline-Jobs.
      - [ ] FGS-Notification (heute "Aufnahme läuft") überlebt
            Process-Kill: Notification-Channel ist persistent
            (`IMPORTANCE_LOW`), TextResId wird beim FGS-Restart aus
            DB-State re-derived (kein In-Memory-Fallback).
      - [ ] `BootCompletedReceiver` (NEU): nach System-Boot wird ein
            One-Shot `WorkManager`-Job geplant, der die Room-DB öffnet,
            `PipelineRecovery.runStatusPromotion` ausführt und
            orphaned Audio-Files cleant — **ohne** den IME-Service
            zu starten (IMEs starten nicht via BOOT_COMPLETED).
            Ergebnis: beim nächsten IME-Use ist State bereits sauber.
      - [ ] `DictateOrchestrator.init()` Recovery-Pfad ist idempotent:
            Double-Recovery (BootReceiver + IME-Bind) ist no-op.

**Dependencies:** B0 (ADR-0007-Update gibt Vertrag vor)

### B2 — PipelineRecovery erweitern

**Ziel:** Crash-Recovery ist nicht-destruktiv. RECORDING-Sessions mit
existierenden Segments werden als "fortsetzbar" markiert und beim
nächsten Record-Klick auto-reaktiviert.

**Acceptance Criteria:**
- [ ] Neue SessionStatus-Variante `RECORDING_INTERRUPTED` + Migration
      6→7 (CHECK-Constraint erweitert)
- [ ] `PipelineRecovery.runStatusPromotion` Branch für RECORDING:
      - audio_file_paths nicht-leer + alle Files lesbar → `RECORDING_INTERRUPTED`
      - audio_file_paths leer oder alle unlesbar → `FAILED` (heutiges Verhalten)
- [ ] `ActionResolvers.resolveRecordAction` Continuation-Check: bei
      `Recording.Idle` + jüngste Session=`RECORDING_INTERRUPTED` (innerhalb
      `Pref.ContinuationFreshnessHours`, Default 24h) → reuse sessionId,
      emit `StartRecordingContinuation(sessionId)` statt `StartRecording`
- [ ] Neue Action `RecordingAction.StartRecordingContinuation(sessionId)`
      mit Reducer-Arm und Effect (allocateNext statt allocateFirst)
- [ ] Trash-Btn im Layout (sowohl Keyboard als Overlay) löscht **alle**
      Segments einer `RECORDING_INTERRUPTED`-Session + setzt Status FAILED
- [ ] Recovery-Cleanup älter als 24h: gestale `RECORDING_INTERRUPTED`-Rows
      werden bei Recovery zu FAILED promotiert + Audio gelöscht

**Dependencies:** B1 (braucht `allocateNext` + `PipelineAudioResult`)

### B3 — WidgetState-State + Layout-Refactor

**Ziel:** ViewMode komplett ersetzt durch WidgetState +
`imeViewVisible`-Achse. Layout-Resolver werden migriert.
Pause-Btn-Feature wird im Slot-System integriert.
Host-Commit-Guard für Widget-Modus.

**Acceptance Criteria:**
- [ ] Neues Modul `WidgetModule` (ersetzt `ViewModeModule`), owns
      `widget: WidgetState`-Achse
- [ ] `WidgetState` sealed class + `WidgetOrigin` enum in `DictateUiState`
- [ ] `imeViewVisible: Boolean` als Top-Level-Field in `DictateUiState`
      (heute war es implizit in ViewMode kollabiert)
- [ ] `Action.WidgetAction.*` mit `ToggleWidget`, `CloseWidget`,
      `OnImeViewShown`, `OnImeViewHidden`, `ResetSuppressBit` (ersetzt
      `Action.ViewModeAction.*`)
- [ ] Alle 8 Transitions W1-W8 implementiert + Unit-Tests
- [ ] `LayoutCatalog.forKeyboard` und `LayoutCatalog.forWidget`
      Resolver migriert (kein `ViewMode`-Param mehr)
- [ ] `KeyboardLayoutManager.modeForBackend` migriert — bidirektionales
      Rendering bleibt strukturell intakt
- [ ] `ActionResolvers.resolveOverlayRecordAction`: bei
      `widget=Visible + recording.Active` → `PauseRecording` (NEU
      Pause-Toggle); bei `widget=Visible + recording.Paused` →
      `ResumeRecording`; sonst wie heute
- [ ] Slot-System erweitert um `endIconResolver` — Pause-Symbol im
      Record-Btn rechts neben Timer (im Widget-Modus, egal Origin)
- [ ] `OVERLAY_PAUSE`-Slot wird im Widget-Modus **ausgeblendet**
      (`visibilityPredicate = { widget !is Visible }`) — die
      Pause-Funktion ist in den Senden-Btn integriert, ein separater
      Btn wäre redundant. Im KEYBOARD-Layout (mit Recording-Active)
      bleibt der separate `pause_btn` heute schon sichtbar.
- [ ] `commitTextToInputConnection` Guard: bei `widget is Visible` →
      return false (Host-Block); Helper `canCommitToHost()` für
      Enter-Key-Pfade (`KeyEvent.KEYCODE_ENTER` an Zeilen 3398, 3407,
      3418 in DictateInputMethodService.java)
- [ ] `Action.ViewModeAction.*` und `ViewMode`-Enum entfernt (kein
      Backwards-Compat-Shim)

**Dependencies:** B0 (ADR-0008 ist Vertrag)

### B4 — InfoBar-Producer (nur für Probleme, nicht für Erfolg)

**Ziel:** Recovery ist **seamless** — wenn alles funktioniert, sieht der
User nichts. Nur wenn ein Segment kaputt war (Partial Recovery), wird
informiert. Plus: bestehender Pending-Insert-Producer wird auf
FGS-Recovery erweitert.

**Acceptance Criteria:**
- [ ] **KEIN** Continuation-Hint-Producer — Recovery ist silent.
      Wenn alle Segments lesbar sind, ist das Wiederaufnehmen für den
      User nicht unterscheidbar von einer normalen Aufnahme.
- [ ] Partial-Recovery-Warning-Producer: bei zuletzt-completed Session
      mit `lastErrorMessage` enthielt "partial" Marker → InfoBar
      "Beim Senden wurden N Sekunden Audio übersprungen" (style=ERROR).
      Trigger: `PipelineAudioResult.PartialRecovery` aus B1 wird beim
      Pipeline-Start in die `lastErrorMessage` Session-Spalte persistiert
      und nach Pipeline-Done als InfoBar gezeigt.
- [ ] Pending-Insertion-Producer (heute existent) erweitert:
      - [ ] Zeigt jetzt auch Sessions, deren Pipeline im FGS endete
            während IME tot war (über `inserted_at IS NULL` + freshness
            threshold). Heute schon strukturell vorhanden via SF-4
            `NotifyManualPasteNeeded`. Acceptance: Coverage-Tests, dass
            FGS-Recovery-Pfad korrekt durchläuft.
      - [ ] **Text-Preview im InfoBar-Item**: heutiger Producer zeigt
            generischen Text "Pending text ready to insert" — neu:
            `transcribedText.take(60).trim() + "…"` als Argument in
            der String-Ressource, sodass der User sieht *welcher*
            Text bereitliegt (User-Wunsch 2026-05-21: "Dieser beginnt
            mit den folgenden Buchstaben: …").
      - [ ] Confirm-Action = `AcceptAndInsert(sessionId)` (unverändert,
            heute schon korrekt), Dismiss-Action = `Dismiss(sessionId)`.
- [ ] String-Ressourcen:
      - [ ] `dictate_recovery_partial_msg` (mit `%d`-Placeholder für
            Sekunden) — Partial-Recovery-Warning
      - [ ] `dictate_pending_insert_msg` überarbeitet — Format-String
            mit Text-Preview-Argument `%s`:
            "Text bereit zum Einfügen: »%s« — Tippen zum Einfügen."
- [ ] Unit-Tests für:
      - [ ] Partial-Recovery-Producer (Trigger, Dismiss, String-Argument-
            Substitution)
      - [ ] Pending-Insertion-Producer mit Text-Preview (kurzer Text,
            langer Text → Truncation mit "…", leerer Text → kein
            Item)

**Dependencies:** B2 (RECORDING_INTERRUPTED), B1 (`PipelineAudioResult.
PartialRecovery`)

### B5 — Tests + Cleanup

**Ziel:** Vollständige Test-Coverage. Alle alten ViewMode-Tests sind
migriert oder gelöscht. E2E-Test deckt das Crash-Resume-Continuation-
Cycle ab.

**Acceptance Criteria:**
- [ ] `ViewModeModuleTest` → `WidgetModuleTest` (alle 8 Transitions
      W1-W8 separat asserted)
- [ ] `OverlayModuleTest` Cascade-Tests an WidgetState angepasst
- [ ] Neue Tests: `PipelineRecoveryContinuationTest`,
      `AudioFileRepositoryRollingSegmentTest`,
      `PipelineAudioResultTest`,
      `BootCompletedReceiverTest` (One-Shot-Job-Scheduling +
      Idempotenz mit IME-Bind-Recovery),
      `DictatePipelineServiceLifecycleTest`
      (START_REDELIVER_INTENT, Notification-Re-Derive aus DB)
- [ ] `DictateCutoverE2ETest` erweitert um Crash-Resume-Continuation-
      Zyklus (simuliert Process-Death während Recording)
- [ ] `KeyboardLayoutManagerTest` Bidirectional-Render Tests bleiben
      strukturell intakt
- [ ] Alte `ViewMode`-Imports + Action-Refs gelöscht (`grep` returnt 0)
- [ ] Spec 3 (Floating-Overlay) Decision-History-Entry "Superseded by
      ADR-0008" (oder Spec wird komplett ersetzt durch neue Spec im
      `research/`-Verzeichnis)
- [ ] Build (debug + release) + Lint clean, alle Tests grün

**Dependencies:** B1, B2, B3, B4

## 5 — Cross-Block-Concerns

### 5.1 Reihenfolge-Constraints

```
B0 (Docs)
  │
  ├──► B1 (Audio-Refactor) ──► B2 (Recovery) ──┐
  │                                              │
  ├──► B3 (WidgetState) ────────────────────────┼──► B4 (InfoBar)
  │                                              │              │
  └──────────────────────────────────────────────┴──► B5 (Tests + Cleanup)
```

- B1 muss vor B2 (Recovery braucht `PipelineAudioResult`)
- B3 ist disjoint von B1/B2 (touching disjoint Code-Bereiche)
- B4 nach B2 (braucht `RECORDING_INTERRUPTED`)
- B5 zuletzt — bündelt alle Test-Migrations

### 5.2 Migrations-Strategie

- **DB-Migration 6→7**: `RECORDING_INTERRUPTED` zur `status`-CHECK-Liste
- **DB-Migration 7→8** (optional, B1): `recording_codec_params: String?`
  zur Cache aus `MediaExtractor`-Read (Performance-Optimierung)
- **State-Migration**: keine — `DictateUiState` ist nicht persistiert
- **Pref-Migration**: keine neuen Pref-Reset-Trigger

### 5.3 Backwards-Compatibility

**Keine** — der Refactor ist eine bewusste Bruchstelle. `ViewMode` wird
komplett entfernt, kein Shim. Im Repo gibt es keine externen Konsumenten,
die Stability bräuchten.

### 5.4 Test-Strategy

- **Unit:** Pro Modul (`WidgetModule`, `RecordingModule`-Erweiterungen,
  `PipelineRecovery`, `AudioFileRepository`, `InfoBarSelector`) volle
  Reducer-Coverage
- **Integration:** `KeyboardLayoutManagerTest` (Bidirectional-Render),
  `DictateCutoverE2ETest` (Crash-Resume-Cycle)
- **Manual:** Build APK + Device-Test der UX-Pfade gemäß §6

## 6 — Manual-Test-Runbook

| ID | Szenario | Erwartung |
|---|---|---|
| M1 | Widget aktivieren, Tastatur schließen | Widget bleibt sichtbar (Sticky USER) |
| M2 | Recording starten im Widget, Pause-Btn (= Senden) drücken | Recording.Paused, Pause-Symbol im Senden-Btn |
| M3 | Pause-State + App-Switch | Widget bleibt sichtbar (USER) |
| M4 | Recording starten in Keyboard, App-Switch | Widget öffnet auto (PIPELINE), zeigt Recording |
| M5 | M4 + zurück zur Tastatur | Widget verschwindet (W4), Keyboard übernimmt |
| M6 | Recording starten, App-Kill mid-recording | Recovery: Session→`RECORDING_INTERRUPTED`. Beim nächsten Record-Klick: **silent** Continuation — neue Segment-Allokation ohne UI-Hinweis. User merkt nichts (außer: alle Segments sind beim Senden dabei). |
| M7 | M6 + Senden | Alle Segments concatten, Pipeline läuft normal |
| M8 | Recording + App-Kill ohne Resume innerhalb 24h | Recovery promoviert zu FAILED, Audio gelöscht |
| M9 | Pipeline läuft, App-Kill (vor Pipeline-Done) | FGS-überlebt: Pipeline fertig, Result in DB; bei nächstem IME-Start: InfoBar "Text bereit zum Einfügen" |
| M10 | Crash-during-Rolling-Segment | Verlust ≤ Rolling-Intervall (30s); alle finalisierten Segments verfügbar. Beim Senden: Partial-Recovery-InfoBar zeigt verlorene Sekunden an. |
| M11 | Trash-Btn bei RECORDING_INTERRUPTED | alle Segments gelöscht, Status FAILED, neue Aufnahme = neue Session |
| M12 | Widget(USER) → Close-Btn während Recording.Active | Widget=Hidden, Recording.Paused, kein Cancel; Tastatur sichtbar mit Paused-Indikator |
| M13 | Reboot des Geräts während RECORDING_INTERRUPTED-Row in DB existiert | BootCompletedReceiver führt PipelineRecovery aus; Status bleibt korrekt (24h-frisch → bleibt RECORDING_INTERRUPTED, älter → FAILED). User merkt nichts beim ersten IME-Use. |
| M14 | App-Force-Stop während Pipeline läuft, dann sofort wieder öffnen | FGS-Service wird via START_REDELIVER_INTENT mit Last-Intent neu gestartet; Notification kommt zurück; Pipeline läuft fertig oder wird per Recovery promoviert. |
| M15 | Pending-Insert-InfoBar nach FGS-Pipeline-Done sichtbar | Text-Preview "»Erste 60 Zeichen…«" ist sichtbar; ✓-Tap committet zur Host-App; Dismiss verwirft. |

## 7 — References

- [[ADR-0001]] — Modular Orchestrator (host für `WidgetModule`)
- [[ADR-0002]] — Cross-Module Cascade (für W6/W7/W8-Cascades)
- [[ADR-0003]] — Foreground-Service (Pipeline überlebt IME-Tod)
- [[ADR-0004]] — LayoutCatalog (RenderBackend-Switching pro Surface)
- [[ADR-0005]] — Triangle-FSM (Superseded by ADR-0008)
- [[ADR-0006]] — InfoBar State-Derived Items (für Continuation-Producer)
- [[ADR-0007]] — Audio Multi-File Repository (erweitert für Continuation + L2)
- [[ADR-0008]] — Surface-Axes (WidgetState + ImeView, supersedes 0005)
- Spec 3 (Floating-Overlay) — wird durch ADR-0008 obsolet (Decision-History-Update)
