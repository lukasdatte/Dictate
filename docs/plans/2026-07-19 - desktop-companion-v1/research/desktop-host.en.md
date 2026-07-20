# Desktop Dictation Host — Implementer Spec for Block D

---
date: 2026-07-19
author: Lukas + Claude Code
type: Spec
status: Spec — programmer-ready
context: Binding build instructions for Block D of the desktop-companion-v1 plan — the companion becomes a standalone desktop dictation host (SQLDelight full parity, javax.sound capture, desktop pipeline, global hotkey, focus-free Compose panel with recording-UI reconstruction, full review mode incl. re-dictate, management/history UI).
related-plan: ../../../../.claude/plans/desktop-companion-v1.md
related-adrs: 0007, 0009, 0011, 0012, 0013, 0016, 0018, 0020, 0023, 0027
---

This spec concretizes **Chunk D1 (data model + capture + pipeline)**, **D2
(hotkey + panel + recording-UI + insert)** and **D3 (review + management UI +
history)** from the `desktop-companion-v1` plan. It describes _what gets built_:
the SQLDelight schema replacement incl. `received_texts` migration, the
javax.sound capture with rolling segments, the newly-implemented
desktop orchestrator (D2 plan decision: no port of `state/`), hotkey and
window ports, the 1:1 Compose reconstruction of the Android recording look and the
full review mode with the shared `ReviewDecision`.

It is **not** a substitute for the ADR drafts (Chunk A1). The foundational decisions
live in `adr-desktop-dictation-host`, `adr-desktop-panel-ui`, `adr-desktop-review`
and `adr-companion-history-parity`; this spec implements them. It builds on the
sister spec **`shared-ai-extraktion.md`**: the `:shared-ai` core extracted there
(runners, `AIOrchestrator`, `ai/conversation/*`, `ReviewDecision`,
`ParameterRegistry`) and its ports (`AiConfig`, `UsageSink`, `ProxyConfig`,
`AudioDurationReader`) are a precondition here — Block D **consumes** them, it
extracts nothing. Credential storage comes from **`secretstore.md`** (Block B).

## Table of Contents

- [Glossary](#glossary)
- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Data Model — SQLDelight Full Parity (D1)](#3-data-model--sqldelight-full-parity-d1)
- [§4 Audio Capture — javax.sound (D1)](#4-audio-capture--javaxsound-d1)
- [§5 Desktop Pipeline / Orchestrator (D1)](#5-desktop-pipeline--orchestrator-d1)
- [§6 Hotkey + Focus-Free Panel (D2)](#6-hotkey--focus-free-panel-d2)
- [§7 Recording-UI Reconstruction (D2)](#7-recording-ui-reconstruction-d2)
- [§8 Review Panel + Re-dictate (D3)](#8-review-panel--re-dictate-d3)
- [§9 Management + History UI (D3)](#9-management--history-ui-d3)
- [§10 Directory Layout](#10-directory-layout)
- [§11 Chunk Boundaries D1 / D2 / D3](#11-chunk-boundaries-d1--d2--d3)
- [§12 Testing Approach](#12-testing-approach)
- [§13 Footguns / Anti-Patterns](#13-footguns--anti-patterns)
- [§14 Decision Log](#14-decision-log)
- [§15 Information Gaps](#15-information-gaps)
- [§16 References](#16-references)

## Glossary

### Modules & Components

- **`:companion`** — the JVM desktop module (Compose Desktop + Ktor + SQLDelight).
  Today it is a passive text receiver (ADR-0017/0027); Block D turns it into an
  active dictation host. Package `net.devemperor.dictate.companion`.
- **`capture/`** — NEW subsystem: javax.sound capture, device selection,
  rolling WAV segments, amplitude feed (§4).
- **`pipeline/`** — NEW subsystem: desktop orchestrator, a lean
  state machine + serial job queue (§5).
- **`hotkey/`** — NEW subsystem: `GlobalHotkey` port + Win32 impl + Noop (§6).
- **`ui/panel/`** — NEW subsystem: undecorated, always-on-top, focus-free
  Compose window with recording, review and profile UI (§6–§8).
- **`DesktopDictationController`** — the entry-point orchestrator in the
  companion process that wires hotkey → capture → pipeline → panel → insert
  (§5.1). Analogous to the Android god-class, but small.

### State & Domain Terms

- **`DictationPhase`** — the four pipeline phases `RECORDING → TRANSCRIBING →
  POST_PROCESSING → (REVIEW | INSERTED | CANCELLED | FAILED)` (§5.2).
- **`DesktopUiState`** — the pure Compose state of the panel (recording, pipeline,
  review, panelVisible); produced by the reducer, no wire protocol (§5.3).
- **Review mode / Review** — the full post-processing dialog (ADR-0013), where
  the model can ask follow-up questions and the user answers by voice. Verdict via the
  shared `ReviewDecision.decide` (§8).
- **Re-dictate** — a transcription-only follow-up recording in review that is
  appended as a `ConversationContinuation` turn to the reviewed session
  (origin `REVIEW_REFINEMENT`, §8.3).
- **`AmbiguityMode`** — the tri-state policy `ALWAYS_INSERT | AUTO |
  ALWAYS_REVIEW` (shared from `:shared-ai`), chosen per profile (§8.1).
- **`ReviewDecision`** — the shared, pure verdict function `decide(mode,
  needsClarification, message): Verdict{INSERT, REVIEW}` — **one** code path for
  phone and desktop (§8.2).

### Persistence & Parity

- **Room parity** — the SQLDelight schema covers `sessions` / `transcriptions` /
  `processing_steps` / `conversation_messages` with **identical
  enum vocabularies and CHECK constraints** as the Android Room schema v11 (§3).
- **Double-Enum pattern** — every finite-set column is modelled TWICE:
  Kotlin `enum` (`AS ...` in `.sq`) **and** SQL `CHECK` (docs/DATABASE-PATTERNS.md).
- **`host_origin`** — companion-OWN axis `PHONE_SYNC | DESKTOP_DICTATION`
  that separates phone-mirrored from locally-dictated sessions (F16, §3.3).
  Orthogonal to `sessions.origin` (the pipeline origin).
- **Parity test** — the programmatic reconciliation of the SQLDelight CHECK vocabularies
  against the Room reference sets (model `OriginCheckConstraintParityTest`, §3.6).
- **Rolling segments** — the ADR-0007 idea "always-one-ahead": the capture
  periodically writes new WAV segments, so a crash loses at most one interval
  (§4.3).

### Platform Ports

- **`GlobalHotkey`** — port for the global key combination; Win32
  `RegisterHotKey` via JNA, Linux Noop (§6.1).
- **`PanelWindowControl`** — port for window visibility + focus-freedom
  (`WS_EX_NOACTIVATE` spike) + focus-restoration fallback (§6.2/§6.3).
- **`TextInserter`** — the EXISTING insertion port (ADR-0018), reused verbatim
  for auto-insert (§8.5).

> **`sessions.origin` ≠ `host_origin` ≠ `AmbiguityMode`.** `sessions.origin`
> (KEYBOARD/HISTORY_REPROCESS/POST_PROCESSING/REVIEW_REFINEMENT) is the
> **pipeline** origin of a session, Room-parity. `host_origin`
> (PHONE_SYNC/DESKTOP_DICTATION) is the companion-own **provenance** axis
> (device). `AmbiguityMode` is the **review policy**, none of which persists
> on the session (transient verdict, ADR-0013).

## 1. Vision and Motivation

### 1.1 Why this desktop host exists

Today Dictate dictates only on the phone; the companion is a passive
text receiver that types phone dictations into the foreground window via
`/v1/dispatch` (ADR-0018/0027) and mirrors phone history over the sync (ADR-0020). The
user wants to dictate on the PC **like on the phone** — hotkey → warm mini panel →
capture → transcription → post-processing → auto-insert — without the phone in
hand. Block D delivers exactly this dictation loop in the companion process: own
capture (javax.sound), own pipeline (shared `:shared-ai` core), own
history (Room parity) and the full review mode.

### 1.2 What Block D solves

1. **No second AI core.** The pipeline runs against the `:shared-ai` core extracted
   in Block A behind ports — exactly ONE implementation of runners,
   prompt logic, conversation and `ReviewDecision` for phone and desktop
   (CLAUDE.md convention "never SDKs directly", ADR-0013 verdict shared).
2. **No schema drift.** The desktop history uses the same session model as
   Android (regenerate/review/step chains work identically), secured
   by parity tests instead of by discipline (F15, ADR-0007/0012/0013 schemas).
3. **No focus theft.** The panel is warm and focus-free — it does not steal the
   focus from the target window, otherwise auto-insert would go nowhere (F21).
4. **Additive coexistence.** The existing PC dictation mode (ADR-0027, phone
   records, companion types) stays unchanged in parallel (D4.6). The
   desktop mode is purely additive.

### 1.3 Discarded Alternatives

- **Port of the Android `state/` orchestrator (19 modules).** Discarded per
  plan-D2: `state/` is tailored to IME axes (layout, MotionScene,
  widget, InfoBar, hover …). The desktop needs ~4 axes. Reimplementation
  following the ADR-0001 rules (pure reducers, one dispatch door, IO in effects)
  is cheaper and more maintainable than dragging along 15 irrelevant axes (§5).
- **Keep `received_texts` as a second history table (coexistence).**
  Discarded (§14 D1): two tables for "which texts exist" = double
  truth, exactly the SSoT anti-pattern. Instead of coexistence: a one-off
  backfill migration into the `sessions` model, `received_texts` goes away, the
  sync henceforth lands directly in the session archive (§3.4).
- **`AudioRecord`-like raw PCM without a WAV container.** Not needed: javax.sound
  `TargetDataLine` yields PCM that we write directly as WAV (RIFF header + PCM) without
  an encoder dependency — no AAC/Opus encoder in the Kotlin-ceiling risk
  (F4/D4.2, ADR-0015). Opus/OGG remains a later option (§15 Gap 2).
- **Review only on the phone (ADR-0013 surface constraint).** The desktop host
  **revises** the "review is IME-only" ruling for its own surface
  (`adr-desktop-review`); the verdict logic itself stays shared and unchanged.

### 1.4 What Block D concretely delivers

1. Dictation on the PC in <100 ms panel latency, with start/pause/resume/discard.
2. History parity → regenerate, review, conversation chains like on the phone.
3. Full review mode incl. iterative re-dictate — from v1, no staging (F18).
4. Windows auto-insert via the proven `TextInserter`; Linux dogfooding via
   clipboard + tray/panel button (F6).

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

### 1a.1 Layer `ui/panel/` — the warm window surface

- **Purpose:** renders `DesktopUiState` declaratively; the window stays
  hidden-warm from process start and toggles in <100 ms.
- **File:** `companion/.../companion/ui/panel/PanelWindow.kt` (NEW).
- **Contract:** `PanelWindowControl` (port) for `show()/hide()` + focus-freedom;
  ViewModel provides `StateFlow<DesktopUiState>`.
- **Detail:** §6.2, §7, §8.

### 1a.2 Layer `pipeline/` — the desktop orchestrator

- **Purpose:** the single dispatch door; pure reducers map the four axes,
  effects perform IO (start capture, `converse`, insert).
- **File:** `companion/.../companion/pipeline/DesktopDictationController.kt` +
  `DictationReducer.kt` (NEW).
- **Contract:** `dispatch(DictationIntent)`; `state: StateFlow<DesktopUiState>`;
  serial `JobQueue` (ADR-0009 semantics).
- **Detail:** §5.

### 1a.3 Layer `capture/` — the capture

- **Purpose:** javax.sound `TargetDataLine` → 16 kHz mono 16-bit WAV segments;
  RMS amplitude feed for the UI.
- **File:** `companion/.../companion/capture/AudioCaptureService.kt` (NEW).
- **Contract:** `start(deviceRef)/pause()/resume()/stop(): CaptureResult`;
  `amplitudes: Flow<Float>`.
- **Detail:** §4.

### 1a.4 Layer `data/` — the Room-parity persistence

- **Purpose:** full session archive; `received_texts` replaced; sync lands in
  `sessions` + `dispatch_state`.
- **File:** `companion/src/main/sqldelight/.../db/Companion.sq` (EDIT) +
  `2.sqm` migration (NEW).
- **Contract:** SQLDelight repos + ColumnAdapter (Double-Enum); parity tests.
- **Detail:** §3.

### 1a.5 Read-this-before-implementing checklist

- [ ] **Every finite-set column** in `.sq`: Kotlin `enum` (`AS`) **and** SQL `CHECK`
  (Double-Enum, §3.2). Missing CHECK → parity test red.
- [ ] **`verifyMigrations = true` is on** (`companion/build.gradle:14`): every
  `.sq` change needs an `N.sqm` migration + new `databases/N.db` snapshot,
  otherwise build red (§3.5).
- [ ] **No behavior of the Android app** changes in Block D — Block D does not touch
  `:app` (except the enum reference list in the parity test, read-only).
- [ ] **Reducers are pure** — no IO, no `System.currentTimeMillis()`; time +
  UUID + capture + `converse` + insert live in effects (`ClockPort` exists,
  §5.4).
- [ ] **`ReviewDecision.decide` is NOT reimplemented** — the shared
  `:shared-ai` call is the only verdict authority (§8.2).
- [ ] **The `converse` system prompt** comes from the persisted `SYSTEM` row
  of the conversation, not from a live template (ADR-0012, §8.3).
- [ ] **Audio format is fixed** 16 kHz / mono / 16-bit PCM-WAV (D4.2); verify
  provider upload limits against ~2 MB/min (§4.5, §15 Gap-Rest).

## 2. Acceptance Criteria

1. **Build invariant.** `./gradlew :companion:build` green; `verifyMigrations`
   green (new `2.db`/`3.db` snapshot consistent); `:app` unchanged green.
2. **Schema parity.** SQLDelight covers `sessions` / `transcriptions` /
   `processing_steps` / `conversation_messages` with identical enum vocabularies
   + CHECK constraints as Room v11; the parity test suite (§3.6) is green and
   fails red as soon as a CHECK vocabulary diverges from the Room reference.
3. **`received_texts` replacement.** The migration `2.sqm` (resp. `3.sqm`, §3.4)
   transfers all `received_texts` rows losslessly into `sessions` (host_origin
   `PHONE_SYNC`) + `dispatch_state`; a MigrationTest with a populated fixture DB
   verifies row count, origin mapping and cursor equivalence; `received_texts`
   no longer exists after the migration.
4. **Sync regression.** All existing sync E2E tests (`SyncE2ETest`,
   `CompanionE2ETest`, `MultiConnectorE2ETest`, `TruncatedResponseE2ETest`) are
   green after the cursor switch to `sessions`+`dispatch_state`; the
   sync cursor pages byte-identically as before (ADR-0020 order `created_at,
   session_id`).
5. **Desktop dictation E2E (headless).** Fake runner + WAV fixture: hotkey intent →
   capture → transcription + post-processing over a profile → auto-insert
   (fake `TextInserter`); the session incl. `transcription` + `CONVERSATION_TURN`
   step + `conversation_messages` (SYSTEM + USER) is persisted in the companion DB
   (§5.5, §12).
6. **Pipeline state transitions.** Start/pause/resume/discard and the
   phase transitions `RECORDING → TRANSCRIBING → POST_PROCESSING → (REVIEW |
   INSERTED | CANCELLED | FAILED)` are reducer-unit-tested; a second
   dictation trigger during a running pipeline enqueues (ADR-0009), does not discard.
7. **Review-mode parity.** The desktop caller uses `ReviewDecision.decide`
   verbatim; the 5-row verdict matrix (§8.2) runs green as a parametrized suite;
   re-dictate produces a `REVIEW_REFINEMENT` session + a
   `ConversationContinuation` turn, and the panel updates non-terminally.
8. **Focus policy.** Either the `WS_EX_NOACTIVATE` spike succeeds (panel
   never steals focus) OR the fallback kicks in (foreground window remembered at
   the hotkey, restored before insert); both paths are unit-tested
   (fake keyboard/fake WindowControl), a spike failure is **not** an
   escalation (D4.3). The focus-free window is, until the spike decision, a
   `pending: D2-focus-spike` test.
9. **Recording UI.** The Compose-canvas recording look uses the extracted
   design parameters (§7): 30-slot ring buffer, log amplitude, age fade 0.4→1.0,
   HSV glow, breathing pulse 1500 ms; ViewModel tests cover the states.
10. **Manual Windows acceptance.** Hotkey → panel → dictation → insert into an editor
    (Notepad/browser field); Linux: panel via tray, result in clipboard +
    hint. Checklist in F1 checked off.

## 3. Data Model — SQLDelight Full Parity (D1)

### 3.1 Current State + Goal

Today `Companion.sq` (v2) holds only `devices`, `received_texts`, `settings`,
`key_command_chords` (`companion/src/main/sqldelight/.../db/Companion.sq:1-63`).
The companion DB wrapper registers ColumnAdapters centrally in
`data/CompanionDatabase.kt:38-47` (`EnumColumnAdapter()` per enum column);
`SchemaMigrator` bumps `PRAGMA user_version`
(`data/SchemaMigrator.kt:31-56`); `verifyMigrations = true`
(`companion/build.gradle:14`) replays every `.sqm` against the `databases/N.db`
snapshot on every build.

Goal (F15): the four session tables of the Room schema v11 in **full parity**
— identical columns, identical enum vocabularies as CHECK, identical indexes.
The Room schema is the SSoT of the structure (`app/schemas/.../11.json`); the
enum values come from the Room migration CHECKs (Room JSON carries no CHECKs).

### 3.2 Enum Vocabularies (SSoT for the CHECK Constraints)

These values are the persisted `.name` strings of the Room enums (verified in the
`:app` codebase). **Every** of these columns is modelled in the companion as
`TEXT AS <KotlinEnum>` + `CHECK (col IN (...))` (Double-Enum). The
Kotlin enums are redefined on the companion side (package
`net.devemperor.dictate.companion.domain.session`), because `:companion` cannot access
`:app` (§3.6 solves the parity via a test).

| Column | Kotlin enum | Values (exact) | Room source (file:line) | CHECK? |
|---|---|---|---|---|
| `sessions.type` | `SessionType` | `RECORDING, REWORDING, POST_PROCESSING` | `entity/SessionType.kt:3`; Mig `MigrationTo9.kt:46` | yes |
| `sessions.status` | `SessionStatus` | `RECORDING, RECORDING_INTERRUPTED, RECORDED, TRANSCRIBING, COMPLETED, FAILED, CANCELLED` | `entity/SessionStatus.kt:27`; Mig `MigrationTo9.kt:55` | yes |
| `sessions.origin` | `SessionOrigin` | `KEYBOARD, HISTORY_REPROCESS, POST_PROCESSING, REVIEW_REFINEMENT` | `entity/SessionOrigin.kt:22`; Mig `MigrationTo9.kt:60` | yes |
| `sessions.last_error_type` | `AiErrorType` | `INVALID_API_KEY, RATE_LIMITED, MODEL_NOT_FOUND, BAD_REQUEST, SERVER_ERROR, NETWORK_ERROR, CANCELLED, UNKNOWN` | `ai/AIProviderException.kt:18`; Mig `MigrationTo9.kt:66` | yes (nullable) |
| `processing_steps.step_type` | `StepType` | `AUTO_FORMAT, REWORDING, QUEUED_PROMPT, CONVERSATION_TURN` | `entity/StepType.kt:3`; Mig `MigrationTo8.kt:47` | yes |
| `processing_steps.status` | `StepStatus` | `SUCCESS, ERROR` | `entity/StepStatus.kt:3` | **no in Room** (only `TEXT NOT NULL`, `MigrationTo8.kt:63`) — Companion adds CHECK **anew** (improvement, §14 D3) |
| `processing_steps.response_format` | `ResponseFormatKind` | `JSON_SCHEMA, TOOL_USE, TEXT_FALLBACK` | `entity/ResponseFormatKind.kt:13`; Mig `MigrationTo8.kt:68` | yes (nullable) |
| `conversation_messages.role` | `MessageRole` | `SYSTEM, USER, ASSISTANT` | `entity/MessageRole.kt:19`; Mig `MigrationTo8.kt:108` | yes |
| `text_insertions.insertion_method` | `InsertionMethod` | `COMMIT, PASTE, WINDOWS_DISPATCH` | `entity/InsertionMethod.kt:10`; Mig `MigrationTo10.kt:50` | yes |

**Open vocabularies (NO CHECK, as in Room):** `transcriptions.provider`,
`processing_steps.provider`, `usage.model_provider`, `completion_log.type`. These
carry `AIProvider.name` strings resp. free text and are deliberately not Double-Enum
(model/provider is open). Adopt as `TEXT NOT NULL` without CHECK.

> [!IMPORTANT]
> `AiErrorType` and `AIProvider` live after Block A in `:shared-ai`
> (`shared-ai-extraktion.md §3.4`). The companion imports them from there — do NOT
> redefine them companion-locally, otherwise the error/provider vocabularies drift
> against the shared core. Only the pure session-structure enums
> (`SessionType/Status/Origin`, `StepType/Status`, `ResponseFormatKind`,
> `MessageRole`, `InsertionMethod`) are defined companion-locally, because they
> live Room-only in `:app` today (§15 Gap 1 documents the SSoT wish).

### 3.3 Table Translation (`Companion.sq`)

The four Room tables are translated 1:1 (column names, affinities, nullability,
FKs, indexes from `11.json`). SQLDelight specifics: `INTEGER AS kotlin.Boolean`
for flags (primitive-adapters, as existing); `value`→`value_` rename does not apply
here. Companion extension: **`sessions.host_origin`** (Double-Enum
`PHONE_SYNC | DESKTOP_DICTATION`) separates the phone mirror from desktop dictation (F16).

Excerpt `sessions` (the other three tables analogously, full column list from
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
> `text_insertions`, `completion_log`, `usage`, `prompts` from Room v11 are **not**
> parity-mandatory for Block D: `usage` is served by the `UsageSink` port
> (its own small `usage` table, §5.4), `prompts`/profiles come from Block C
> (entity model), `text_insertions`/`completion_log` are Android audit detail.
> Block D translates the **four core session tables** + the companion-needed
> `usage`. `text_insertions` is optional (only if the history needs re-insert audit
> — §9.3 uses `dispatch_state` instead).

### 3.4 `received_texts` Replacement — Migration (solves Gap 5)

**Decision (§14 D1):** `received_texts` is **replaced**, not coexisted.
The `sessions` model is the only history archive; the sync-specific fields
(device binding, dispatch watermark, insertion outcome) move into a lean
1:1 companion table `dispatch_state`, so that `sessions` stays **purely Room-parity**
(otherwise device_id/dispatched/last_outcome would pollute the parity diff).

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

**Semantic mapping `received_texts` → `sessions` + `dispatch_state`:**

| `received_texts` | Target | Rule |
|---|---|---|
| `session_id` | `sessions.id` **and** `dispatch_state.session_id` | 1:1 |
| `text` | `sessions.final_output_text` | the received text = final output |
| `created_at` | `sessions.created_at` | cursor axis stays identical |
| `origin` (SessionOriginWire) | `sessions.origin` | mapping below |
| `device_id` | `dispatch_state.device_id` | 1:1 |
| `received_at` | `dispatch_state.received_at` | 1:1 |
| `dispatched` | `dispatch_state.dispatched` | 1:1 |
| `last_outcome` | `dispatch_state.last_outcome` | 1:1 |
| — | `sessions.type` | `'RECORDING'` (phone dictation) |
| — | `sessions.status` | `'COMPLETED'` |
| — | `sessions.host_origin` | `'PHONE_SYNC'` |
| — | `sessions.inserted_at` | `dispatched=1 ? received_at : NULL` |
| — | `sessions.audio_file_paths` | `'[]'` (phone audio never lives here) |

Origin mapping: `received_texts.origin` uses `SessionOriginWire` with **five**
values incl. `UNKNOWN` (`shared/.../Dtos.kt:64`); `sessions.origin` allows only
the four Room values. `UNKNOWN` → `'KEYBOARD'` (the landing default; ADR-0016 names
`UNKNOWN` the "landing value for values the protocol doesn't map"). The other
four are same-named → direct.

`2.sqm` (v2→v3) — pure SQL migration (idempotent, verifyMigrations-capable):

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
> The FK `dispatch_state.device_id → devices` requires that every
> `received_texts.device_id` exists in `devices` — that is already guaranteed by the
> existing FK `received_texts.device_id → devices`, but remains to be checked
> in the MigrationTest (fixture with ≥1 device + ≥2 received_texts,
> one of them `origin='UNKNOWN'` and one `dispatched=0`).

### 3.5 SyncService and Repo Rework (Blast Radius)

The replacement touches the existing phone sync (ADR-0020). The
`received_texts` queries in `Companion.sq:83-141` must be rewritten to
`sessions`+`dispatch_state` — **behavior-equivalent**:

- `upsertReceivedText` → writes `sessions` (INSERT OR REPLACE of the parity fields,
  `host_origin='PHONE_SYNC'`) **and** `dispatch_state` with the unchanged
  "never downgrade dispatched" invariant (`MAX(dispatch_state.dispatched,
  excluded.dispatched)`, `Companion.sq:99-108`). The `last_outcome` DO-UPDATE
  exclusion stays (only `recordDispatch` writes it).
- `selectCursor` / `pageHistory` / `countHistory` → `FROM sessions JOIN
  dispatch_state` resp. `WHERE host_origin='PHONE_SYNC'`, order `created_at DESC,
  id DESC` unchanged (ADR-0020 parity contract).
- `recordDispatch` → `UPDATE dispatch_state`.

Affected files: `data/SqlDelightHistoryRepository.kt`, `domain/SyncService.kt`,
`domain/DispatchService.kt` (writes `recordDispatch`), plus the E2E tests
(`SyncE2ETest`, `CompanionE2ETest`, `MultiConnectorE2ETest`,
`TruncatedResponseE2ETest`, `SqlDelightHistoryRepositoryTest`). These tests are
the behavior-neutrality proof — they must stay green **without an assertion change**
(only the internal repo wiring changes). This is the main effort
and the main risk of D1 (§13, R-Sync).

### 3.6 Parity Test Design (programmatic)

Model `OriginCheckConstraintParityTest`
(`companion/.../data/OriginCheckConstraintParityTest.kt`). Two test families:

**(a) CHECK acceptance/rejection** (per Double-Enum column, like the model):
- `everyEnumValue_isAccepted`: every companion `enum` value is insertable.
- `aValueTheEnumCannotProduce_isRejected`: a fantasy value (`'TELEPATHY'`) throws
  `CHECK constraint failed`. This is the half that makes the first one valuable.

**(b) Room-parity reconciliation** (the new, cross-schema test — solves R3): The
companion test cannot import Room enums (`:app` is Android). Therefore
`RoomParityReference.kt` (in the companion test) holds **hardcoded** reference sets per
column, each with a `// SSoT: app/.../entity/<Enum>.kt:<line>` comment. The
test asserts: `SessionType.entries.map { it.name }.toSet() ==
RoomParityReference.SESSION_TYPE`. If the companion `enum` drifts **or** someone
forgets to update the reference after a Room change, the test goes red with
a diff message pointing at the Room source.

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
> The real cross-module SSoT (define the enums once in `:shared` and map Room
> **and** SQLDelight onto them) is desirable, but is an `:app` Room refactor
> outside Block D (§15 Gap 1). Until then the tested reference list is the
> pragmatic, maintainable interim solution — anchored in
> `adr-companion-history-parity`.

## 4. Audio Capture — javax.sound (D1)

### 4.1 Format + Line Setup

Fixed (F4/D4.2): **16 kHz, mono, 16-bit signed little-endian PCM**, written as
WAV (RIFF/`WAVE`, `fmt ` chunk + `data` chunk). No encoder → no new
dependency (ADR-0015 ceiling). javax.sound is in the JDK, no libs.versions change.

```kotlin
val format = AudioFormat(16_000f, 16, 1, /*signed*/ true, /*bigEndian*/ false)
val info = DataLine.Info(TargetDataLine::class.java, format)
val line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
line.open(format); line.start()
```

The WAV header is written back with the final `data` length after `stop()`
(seekable `RandomAccessFile`) — standard RIFF pattern; an `AudioSystem.write(
AudioInputStream, WAVE, file)` over the buffered stream is the simpler,
preferred variant (writes the header correctly).

### 4.2 Device Enumeration + Selection Persistence

`AudioSystem.getMixerInfo()` → filter mixers that support a `TargetDataLine` with the
format. The selection (mixer name) persists via the existing
`CompanionSettings` facade (`domain/CompanionSettings.kt`), key
`audio.inputDevice` (typed getter/setter analogous to `port`/`bindSelection`,
`CompanionSettings.kt:23-56`). Default: `null` → system-default mixer. If the
remembered mixer is gone → fallback to default + settings hint (R5 mitigation).

### 4.3 Rolling Segments (ADR-0007 idea, desktop adaptation)

Android uses `MediaRecorder.setNextOutputFile()` (ADR-0007
Decision-History 2026-05-21). javax.sound has no equivalent — the read loop runs
itself. Adaptation: the capture thread reads `line.read(buffer)` in a loop and
**rolls** every `Pref audio.rollingSegmentSec` (default 30 s) onto a new segment,
by finalizing the current WAV (writing the header) and
opening `{sessionId}_N.wav`. Storage in the companion data dir under a NEW
`recordings/` subdirectory of `AppPaths.dataDirectory()`
(`platform/AppPaths.kt:19` today only provides `databaseFile()`; add a
`recordingsDirectory()`).

On pause: read loop pauses (`line.stop()`), finalize segment. Resume:
`line.start()` into the same or a new segment. Cold-resume is **not** a
must for v1 (the desktop process is long-lived, no FGS teardown like Android) — the
segment list lands as JSON in `sessions.audio_file_paths` (parity with ADR-0007),
so recovery can be retrofitted later (§15 Gap 3).

Merge for the pipeline upload: WAV segments of the same config concat = header of
the first + raw `data` chunks of the following. A `WavConcat.merge(segments): File`
(pure byte work, unit-testable) yields the single upload file. With exactly one
segment: zero-copy (file directly).

### 4.4 Amplitude Feed (RMS) for the UI

Android reads `MediaRecorder.getMaxAmplitude()` (peak 0–32767) every 100 ms and
pushes it through `AmplitudeProcessor.process(Int): Float`
(`core/AmplitudeProcessor.kt:34-44`, log normalization). javax.sound yields PCM —
we compute the **peak** (or RMS) ourselves from the buffer just read and
feed the same ported `AmplitudeProcessor`:

```kotlin
// pro gelesenem 16-bit-PCM-Buffer: Peak-Betrag als 0..32767-Äquivalent
var peak = 0
for (i in 0 until n step 2) {
    val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)   // LE int16
    val a = kotlin.math.abs(s); if (a > peak) peak = a
}
val level: Float = amplitudeProcessor.process(peak)   // Log-Norm, effectiveMax=12000
```

`AmplitudeProcessor` is pure `kotlin.math` (ln/min/max) and moves with the
recording-UI code into `:companion` (§7 clarifies the exact storage location — copy vs.
`:shared-ai`). Emission as `Flow<Float>` at ~10 Hz (choose buffer size so that
`line.read` returns roughly every 100 ms: `16000 * 2 * 0.1 ≈ 3200` bytes).

### 4.5 Provider Upload Limit (residual task D4.2)

16 kHz mono 16-bit PCM-WAV ≈ **1.92 MB/min**. The D1 agent verifies the
upload limits of the configurable transcription providers (OpenAI 25 MB → ~13 min;
Groq; ElevenLabs) and documents them in the chunk (pure verification, no open
decision). At tight limits, Opus/OGG is the documented later option
(§15 Gap 2).

## 5. Desktop Pipeline / Orchestrator (D1)

### 5.1 `DesktopDictationController` — the single dispatch door

Reimplementation following the ADR-0001 rules (plan-D2), **no** port of the
Android `state/`. The controller is the desktop analogue to the
`DictateInputMethodService` god-class, but small: it holds the pure
`DesktopUiState`, accepts `DictationIntent`s, calls the reducer, executes the
resulting effects and exposes `state: StateFlow<DesktopUiState>` to the
panel ViewModel.

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
> D1/D2 initially run against a **transitional `AiConfig`** from `CompanionSettings`
> (plan §7: "the pipeline initially runs against a transitional `AiConfig` from
> CompanionSettings"); only D3 gates on the C1 profile types (`ActiveProfileSource`
> then delivers a real profile). `AiConfig` is the port from
> `shared-ai-extraktion.md §4.1`.

### 5.2 Phase Model (`DictationPhase`)

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

Phases map onto the persisted `sessions.status` values: RECORDING→`RECORDING`,
TRANSCRIBING→`TRANSCRIBING`, POST_PROCESSING→`TRANSCRIBING` (no own
Room status; the turn is part of the processing), INSERTED/REVIEW→`COMPLETED`,
FAILED→`FAILED`, CANCELLED→`CANCELLED`. That is Room-parity — no new
status values.

### 5.3 `DesktopUiState` (pure Compose state)

```kotlin
data class DesktopUiState(
    val panelVisible: Boolean = false,
    val recording: RecordingUi = RecordingUi.Idle,          // Active/Paused + elapsed + level-buffer
    val pipeline: PipelineUi = PipelineUi.Idle,             // Transcribing/PostProcessing/Failed
    val review: ReviewUi? = null,                           // §8: message,output,refining,refinementRecording
    val queued: List<QueuedRun> = emptyList(),              // ADR-0009-Payload
)
```

No wire protocol, no WebSocket — the Compose renderer reads the state directly
(F1/F5 advantage). The `review` sub-axis mirrors exactly the ADR-0013 states
(`refining`, `refinementRecording`, §8).

### 5.4 Reducer Purity + Effects

Reducer: `(DesktopUiState, DictationIntent) -> Pair<DesktopUiState, List<Effect>>`.
**No IO in reducers** (checklist §1a.5). Effects encapsulate: `StartCapture`,
`StopCapture`, `RunTranscription`, `RunPostProcessing`, `RunContinuation`,
`InsertText`, `PersistSession`, `ShowPanel`/`HidePanel`. Time/UUID via `clock` +
`java.util.UUID` **in the effect handler**. Usage tracking via the `UsageSink` port
(`shared-ai-extraktion.md §4.2`), which on the companion side writes into a small
`usage` table (`SqlDelightUsageSink`).

### 5.5 Pipeline Steps (the three `:shared-ai` calls)

1. **Transcription:** `ai.transcribe(mergedWav, language, stylePrompt):
   TranscriptionResult` (`ai/AIOrchestrator.kt:41`). Persist a
   `transcriptions` row (`is_current=1`, `version=1`).
2. **Post-processing (conversation):** build `PostProcessingInputs` (transcript +
   profile instructions + `includeAmbiguityTask = forceTurn`), first user message
   via `ConversationTurnBuilder.buildFirstUserMessage(inputs)`
   (`ai/conversation/ConversationTurnBuilder.kt:36`), then `ai.converse(messages,
   systemPrompt): ConversationResult` (`ai/AIOrchestrator.kt:130`). Persist
   a `CONVERSATION_TURN` step (`chain_index=0`, `assistant_message=message`,
   `response_format=result.responseFormat`) + `conversation_messages`: one
   `SYSTEM` row (turn-0 system prompt) + one `USER` row (the built message).
   `final_output_text = result.output` uniformly in the same transaction (ADR-0013
   §3 crash resilience).
   `hasWork(inputs)==false` (pure transcript, no forceTurn) → no turn, insert the
   bare transcript (ADR-0012 §1).
3. **Verdict:** `ReviewDecision.decide(mode, result.needsClarification,
   result.message)` (§8.2) → INSERT or REVIEW.

### 5.6 Serial Job Queue (ADR-0009 semantics, simplified)

A `JobQueue` with a worker thread, FIFO, one job at a time. A second
`StartHotkey`/`StopRecording` during a running pipeline **enqueues** (dedup per
sessionId), does not discard — capture order = insertion order =
insert order (ADR-0009 "Positive"). Unlike Android: **no** parallel
second recording in v1 (the desktop has one recording; the user starts the next
hotkey only after `stop`). The `ConversationContinuation` job (re-dictate, §8.3)
runs over the same queue.

## 6. Hotkey + Focus-Free Panel (D2)

### 6.1 `GlobalHotkey` Port

```kotlin
interface GlobalHotkey {
    val available: Boolean
    fun register(combo: HotkeyCombo, onTrigger: () -> Unit): Boolean
    fun unregister()
}
```

- **Win32 impl** (`platform/windows/Win32GlobalHotkey.kt`): `User32.RegisterHotKey`
  on its own message-loop thread (`GetMessage`/`PeekMessage` → `WM_HOTKEY`).
  JNA `com.sun.jna.platform.win32.User32` is already a dependency (JNA `5.19.1`,
  `libs.versions.toml:66`). Pattern analogous to `Win32Keyboard.kt:105-131` (INPUT/SendInput
  uses the same User32). `HotkeyCombo` (modifier + VK) configurable in settings
  (`CompanionSettings` key `hotkey.combo`), default e.g. Ctrl+Alt+Space.
- **Noop impl** (`platform/fallback/NoopGlobalHotkey.kt`): `available=false`,
  `register` no-op (Linux/macOS, F6). On Linux the user triggers the capture via
  tray menu or panel button (§9.1).

Fake for tests: `FakeGlobalHotkey` with manual `trigger()` — model
`FakeInputCommandPerformer.kt`.

### 6.2 `PanelWindowControl` — Window Warmth + Visibility

```kotlin
interface PanelWindowControl {
    fun show()          // Panel sichtbar, positioniert
    fun hide()          // Panel unsichtbar, Prozess bleibt warm
    val focusFree: Boolean   // true, wenn WS_EX_NOACTIVATE-Spike erfolgreich
}
```

The Compose `Window` is created from process start and **kept hidden**
(`visible=false` / off-screen), so that the toggle is <100 ms (F5). No
re-creation per hotkey. Undecorated (`undecorated=true`), always-on-top
(`alwaysOnTop=true`) — both available directly in Compose-Desktop `Window(...)`
(the companion already uses `Window` today in `Main.kt:153`). Positioning: near the
cursor or centered-bottom (v1: fixed centered-bottom, configurable later).

### 6.3 The Focus Spike (D2 core risk, R1) + Fallback

Goal: the panel **never** takes focus from the target window, otherwise `SendInput`
(Ctrl+V) goes into the panel instead of the editor.

**Spike (first D2 task, timebox ~1 day):**
1. Create Compose `Window`, obtain the AWT `Window` handle via
   `Native.getComponentPointer(window)` resp. `Native.getWindowPointer` (JNA).
2. `HWND` from it; read `User32.GetWindowLong(hwnd, GWL_EXSTYLE)`,
   OR in `WS_EX_NOACTIVATE (0x08000000)`, write back with `SetWindowLong`.
   Additionally consider `WS_EX_TOOLWINDOW` (no alt-tab entry).
3. **Success criterion:** focus stays on an open Notepad while the panel
   becomes visible and the user clicks a panel button; a `TextInserter.insert`
   triggered right after lands in Notepad. Verification manually on
   Windows + one instrumented marker.
4. `focusFree = true` on success.

**Fallback (D4.3, equivalent path — failure is NOT an escalation):** Before the
hotkey trigger, remember the foreground window (`User32.GetForegroundWindow()`), the
panel may take focus; **before** the insert, restore the remembered window
(`SetForegroundWindow(savedHwnd)` + a short settle delay), then `insert`. Policy in
a pure, testable class `FocusRestorationPolicy` (fake `WindowControl` +
fake keyboard) — what is remembered/restored is unit-tested; the raw
`SetForegroundWindow` API sits behind the port. Both paths documented in
`adr-desktop-panel-ui`.

> [!CAUTION]
> The focus-free-window test remains a `pending: D2-focus-spike` test until the
> spike decision (test-first convention §8 of the plan). Do not
> "fake it green" — only the spike decision arms it.

## 7. Recording-UI Reconstruction (D2)

Goal (F19): reconstruct the Android recording-widget look 1:1 as a Compose canvas.
All design parameters are pure numbers/curves and therefore directly portable; the
Android `Drawable`/`Paint` APIs are replaced by Compose `Canvas`/`drawRoundRect`.

### 7.1 Amplitude Processing (port)

`AmplitudeProcessor` (`core/AmplitudeProcessor.kt`) is pure `kotlin.math` and
is taken over into `:companion` (copy into `capture/` or — if the
recording-UI rendering should also be shared — into `:shared-ai`; for v1 a copy,
§15 Gap 4). Parameters: `effectiveMax=12000`, log normalization
`ln(1+clamp)/ln(1+12000)`, output 0..1
(`AmplitudeProcessor.kt:36-44`). Production instances use `attack=decay=1.0`
(EMA effectively passthrough) — adopt identically for the desktop.

### 7.2 Waveform Bars (canvas)

| Parameter | Value | Android source |
|---|---|---|
| Ring buffer / visible bars | **30** | `AmplitudeVisualizerDrawable.kt:66`, `RecordGlowFactory.kt:41` |
| Push | shift-left, newest on the right, `coerceIn(0,1)` | `:96-103` |
| Bar spacing | 2% of the bar area | `:220` |
| max/min bar height | `0.55*h` / `0.06*h` | `:225-226` |
| Height mapping | `minH + (maxH-minH)*amplitude`, centered around the middle | `:235,:244` |
| **Age fade** | `alpha = 0.4 + 0.6*(i/(n-1))` (left α=0.4 … right α=1.0) | `:241-242` |
| Cap shape | Pill, `cornerRadius = barWidth/2` | `:228` |
| Update frequency | 100 ms / 10 Hz | Ticker `:326` |

### 7.3 Color + Shape Language

- **Bar color:** accent → HSV, `sat*=0.4`, `val=1.0` (pastel)
  (`VisualizerUtils.kt:11-16`).
- **Glow (brightness couples to amplitude):** `hsv[2] = baseV + 0.35*level`
  (`BorderGlowAnimation.kt:154-156`, `RecordGlowFactory.kt:43`); pause baseline
  `+0.12` (`:38`).
- **Breathing pulse:** ArgbEvaluator peak↔dim, **1500 ms**, INFINITE REVERSE, dim =
  `darken(peak, 0.18)` (`RecordingAnimationController.kt:320-326`). In Compose:
  `rememberInfiniteTransition` + `animateColor`. **No ripple** — the earlier
  red ripple was removed 2026-05-23 (`RecordingAnimationController.kt:20-27`).
- **Layout** (left→right): `[Send-Icon] [Amplitude-Bars] [MM:SS] ["PC"-Badge]`
  (`AmplitudeVisualizerDrawable.kt:16-24`); icon size `0.45*h`, H padding `0.35*h`,
  timer `%02d:%02d` bold white right-aligned.

### 7.4 Compose Implementation Sketch

A `@Composable RecordingBar(state: RecordingUi)` with `Canvas` that reads the
30-slot level buffer from `state`, draws the bars via `drawRoundRect`
(age fade as `alpha`), the glow as a background tint via
`animateColorAsState` and the pulse via `InfiniteTransition`. The buffer is pushed
from the `amplitudes: Flow<Float>` (§4.4) via the ViewModel into the state.
ViewModel test covers: buffer shift, Idle/Active/Paused transitions, timer format.

## 8. Review Panel + Re-dictate (D3)

### 8.1 `AmbiguityMode` Comes from the Profile (F20)

The policy is the shared `AmbiguityMode` (`ALWAYS_INSERT | AUTO |
ALWAYS_REVIEW`), from `:shared-ai` (today
`preferences/AmbiguityMode.kt`, moves with the conversation core). On the desktop
it is **not** read live, but taken from the active **profile** and
snapshotted at capture start (parity to ADR-0013 K11: "one consistent
mode snapshot per run"). `forceTurn = mode.forcesTurn` (AUTO+ALWAYS_REVIEW,
`AmbiguityMode.kt:27`) → `includeAmbiguityTask = forceTurn`.

### 8.2 Verdict via the Shared `ReviewDecision` (one code path)

`ReviewDecision.decide(mode, needsClarification, message): Verdict{INSERT, REVIEW}`
(`ai/conversation/ReviewDecision.kt:28-34`) is called **verbatim** — not
reimplemented. On top of it, the desktop places its own visibility gate (instead of
Android's `canShowReviewPanel()`): the panel is always visible in desktop mode, so
the verdict takes effect directly. The full matrix as a parametrized test suite (Acc. 7):

| Mode | needsClarification | message | Verdict |
|---|---|---|---|
| ALWAYS_INSERT | any | any | **INSERT** |
| AUTO | false | any | **INSERT** |
| AUTO | true | blank/null | **INSERT** |
| AUTO | true | non-blank | **REVIEW** |
| ALWAYS_REVIEW | any | any | **REVIEW** |

`needsClarification` is the transient wire field from `StructuredResponse`
(`ai/conversation/StructuredResponse.kt:19`) — never persisted, never replayed
(`encode()` stays two-field `{message, output}`, ADR-0012 §3).

### 8.3 Re-dictate = `ConversationContinuation` (ADR-0013 §6, surface=desktop)

Model: `PipelineOrchestrator.continueConversationBlocking`
(`core/PipelineOrchestrator.kt:788-873`). Desktop reconstruction as an effect handler
`RunContinuation`:

1. Panel button "Re-dictate" → `dispatch(StartRefinement)`: review axis sets
   `refinementRecording=true` (Insert/Discard buttons disabled, ADR-0013 K1),
   recording S2 starts **transcription-only**, `sessions.origin =
   REVIEW_REFINEMENT`.
2. S2 transcript done → **no** insert; `dispatch(RefinementTranscribed(text))`
   sets `refining=true`, enqueues a `ConversationContinuation` job.
3. Effect `RunContinuation(reviewSessionId, followUpText)`:
   - `followUpMsg = ConversationTurnBuilder.buildFollowUpUserMessage(followUpText)`
     (`ConversationTurnBuilder.kt:78`, wraps as `<user-reply>` — instruction,
     not transcript).
   - `snapshot = sessions.loadConversation(reviewSessionId)` (persisted turns +
     `systemContent`).
   - `messages = ConversationReconstructor.toApiMessages(snapshot.turns,
     followUpMsg)` (`ConversationReconstructor.kt:49`).
   - `result = ai.converse(messages, snapshot.systemContent)` — system prompt from the
     persisted `SYSTEM` row, not from a live template (ADR-0012 §3).
   - Persist as a **new** turn (`chain_index = maxChainIndex+1`,
     `appendConversationTurn` equivalent), `final_output_text = result.output`
     uniformly in-transaction.
   - Non-terminal `dispatch(ReviewTurnCompleted(output, message,
     needsClarification))` → again `ReviewDecision.decide` → update panel
     or insert+close. Iterative re-dictate is thus possible.
   - Error path: persist the follow-up as an ERROR turn (auditable; `loadConversation`
     skips ERROR turns on replay), panel shows the error.

### 8.4 Review-Panel States (Compose)

`ReviewUi(message: String?, output: String, refining: Boolean,
refinementRecording: Boolean)`. Rendering: output always; `message` only when
non-blank (otherwise output-only, ADR-0013 §5); "Refining…" hint on `refining`;
recording hint + bars on `refinementRecording`. Buttons: **Insert**,
**Re-dictate**, **Discard**. Insert/Discard disabled during `refinementRecording
|| refining` (ADR-0013 K1). Discard doubles as cancel of the running refinement
(`JobQueue.cancel` + `CancelRefinement` intent).

### 8.5 Insert + Discard (one acknowledge channel)

- **Insert:** `TextInserter.insert(output)` (ADR-0018, `domain/port/TextInserter.kt`)
  — Windows Ctrl+V into the (restored, §6.3) foreground window; Linux clipboard +
  hint (`available=false`). Afterwards mark the session as "acknowledged"
  (`inserted_at = now`) and close the panel. Auto-insert policy F21: on
  `Verdict.INSERT` without review insert directly; setting "confirm before insert"
  (`CompanionSettings` key `insertion.confirmBeforeInsert`, default false) puts
  a confirmation before it.
- **Discard:** `inserted_at = now` as acknowledge (no insert), close panel —
  one channel for both (ADR-0013 §4).

## 9. Management + History UI (D3)

### 9.1 Panel Entry + Profile Dropdown (F20)

The panel carries a profile dropdown at the top (active profile from C1 types; D1/D2:
transitional selection from settings). Linux trigger (no hotkey): tray menu entry
"Start dictation" + panel button. The tray already exists
(`Main.kt:129-151`) — add an entry.

### 9.2 Management Screens (Compose, existing NavigationRail style)

New screens in the existing `ui/` pattern (plain-class ViewModel + `MutableStateFlow`,
model `SettingsViewModel.kt:52-59`; injected `CoroutineScope` on IO like
`HistoryViewModel.kt:51-55`):

- **Profile editor:** list, create/duplicate/move, active profile
  (data source from Block C; D3 consumes the C1 `Profile` types).
- **Model switcher:** two-stage (provider → model from `ModelFetcher`
  (`ai/model/ModelFetcher.kt:42`) ∪ `ModelRef`s ∪ free text), same
  `:shared-ai` data source as Android C3 — parameter UI from `ParameterRegistry`
  (`ai/model/ParameterRegistry.kt`).
- **Prompt editor:** local (peer source comes in E3).

These screens hang on Block C (`depends_on: D3→{D2,C1}`, plan §7). Where C1 types
are missing, the screen is a stub against the transitional settings (§15 Gap 5).

### 9.3 History Screen Expansion

The existing `HistoryScreen`/`HistoryViewModel` today reads `received_texts`; after
the replacement (§3.4) it reads `sessions` (+ `dispatch_state` for phone-sync outcome).
Expansion to the session model: detail view transcript vs. final output
(`transcriptions.text` vs. `sessions.final_output_text`), `host_origin` filter
(phone/desktop), re-insert (`TextInserter.insert(final_output_text)`). The
`pageHistory`/`countHistory` query pattern (`Companion.sq:131-141`, `instr()` search)
stays, rewritten to `sessions`.

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

**File delta roughly:** 4 new subsystems (`capture`, `pipeline`, `hotkey`,
`ui/panel`), ~9 companion-local session enums, 1 schema migration + replacement,
~5 EDIT files in the sync path. Block D does **not touch `:app`** (only read-only as
a parity reference).

## 11. Chunk Boundaries D1 / D2 / D3

The plan cuts D1 (data model+capture+pipeline) / D2 (hotkey+panel+
recording-UI+insert) / D3 (review+management+history). Assessment: **the cut is
viable and file-disjoint**, with **one** recommended refinement.

- **D1** (`data/` + `capture/` + `pipeline/` + `domain/session/` + sync rework):
  self-contained, headless testable (fake runner, WAV fixture), no
  UI dependency. The `received_texts` sync rework (§3.5) clearly belongs to D1
  (schema owner). **Largest chunk** — defensible (one big focus area).
- **D2** (`hotkey/` + `ui/panel/` PanelWindow/RecordingBar + insert): hangs on D1
  (`pipeline/` + `DesktopUiState`), file-disjoint to D1. The focus spike is the
  first D2 task (R1). Recording-UI canvas is purely additive.
- **D3** (`ui/panel/ReviewPanel` + `ui/{profiles,models,prompts}` + history):
  hangs on D2 (panel scaffold) + C1 (profile types), file-disjoint.

**Recommended refinement (§14 D2):** The **enum definitions + SQLDelight schema +
parity tests** (pure `data/` + `domain/session/`) form a clean,
early-completable *lead* within D1. If D1 becomes too large, the
natural sub-cut is: **D1a schema+parity+sync replacement** / **D1b
capture+pipeline**. D1a is the precondition for everything persisting and has the
highest regression risk (sync) — having it green first decouples the risk from the
pipeline build-out. No forced split, but the recommended break if the v3 audit
rates D1 as too broad.

## 12. Testing Approach

Conventions: `~/.claude/snippets/test-first-patterns.md` (TDD for new build,
regression tests red-before-green, pending for documented gaps).

- **Parity tests** (`data/`, §3.6): CHECK acceptance/rejection per Double-Enum column
  (model `OriginCheckConstraintParityTest`) + `RoomParityReference` reconciliation.
  Mandatory gate at every schema change (`adr-companion-history-parity`).
- **MigrationTest** (`2.sqm`): stamp an in-memory DB to v2, feed a fixture
  `received_texts` (≥1 device, rows with `origin='UNKNOWN'`, `dispatched=0/1`),
  migrate, verify `sessions`+`dispatch_state` (row count,
  origin mapping, `inserted_at` derivation, `received_texts` gone). `verifyMigrations`
  covers schema consistency separately.
- **Sync regression** (existing E2E, §2 crit. 4): `SyncE2ETest`,
  `CompanionE2ETest`, `MultiConnectorE2ETest`, `TruncatedResponseE2ETest`,
  `SqlDelightHistoryRepositoryTest` — green **without an assertion change** after the
  cursor rework. This is the behavior-neutrality proof.
- **Pipeline reducer tests**: phase transitions, pause/resume/discard, ADR-0009 enqueue
  (second trigger enqueues). Pure JVM tests (reducers are IO-free).
- **Pipeline E2E (headless)**: fake `TranscriptionRunner`/`CompletionRunner`
  (model `RunnerFactory` open seam, `shared-ai-extraktion.md §8`), WAV fixture →
  session+transcription+step+conversation persisted.
- **Review matrix** (§8.2): parametrized suite (3 modes × needsClarification ×
  message-blank) against the desktop caller of `ReviewDecision`.
- **Re-dictate E2E**: `needsClarification=true` → panel holds; re-dictate fixture →
  `REVIEW_REFINEMENT` session + continuation turn persisted + panel updated.
- **WAV unit tests**: `WavWriter` (header correct), `WavConcat` (merge of two
  fixtures = valid RIFF length), `AmplitudeProcessor` (log-norm curve).
- **Focus policy**: `FocusRestorationPolicy` with fake `WindowControl`+fake keyboard
  (remember/restore order). Focus-free window as
  `pending: D2-focus-spike`, until the spike is decided.
- **ViewModel tests** (companion pattern `HistoryViewModelTest`): panel, review,
  recording-buffer states.
- **Manual Windows acceptance** (F1): hotkey→panel→dictation→insert; Linux
  tray→clipboard.

## 13. Footguns / Anti-Patterns

- **Reimplement `ReviewDecision`.** Don't copy, don't "adapt" — the shared
  `:shared-ai` call is the only verdict authority (one code path, Acc. 7). A
  second decision branch drifts guaranteed.
- **Forget CHECK.** A finite-set column without a CHECK is only half the
  Double-Enum rule; the parity test catches it, but only if the rejection test
  (`'TELEPATHY'` is rejected) exists — otherwise the test greens despite a missing
  CHECK (see `OriginCheckConstraintParityTest` comment).
- **Bypass `verifyMigrations`.** A `.sq` change without `.sqm` + snapshot
  breaks the build. Always deliver both (§3.5, `companion/build.gradle:14`).
- **Change the sync cursor order.** `created_at DESC, id DESC` is the
  ADR-0020 parity contract to the phone; any deviation skips/duplicates rows
  in the paging. The rework is purely mechanical (table changes, order does not).
- **Steal focus.** A normal Compose window takes focus when shown →
  Ctrl+V goes into the panel. Without a proven spike **always** use the fallback (restore
  focus before insert), never "it'll be fine".
- **System prompt from a live template.** On the continuation `converse`, take the system prompt
  from the persisted `SYSTEM` row (`snapshot.systemContent`), don't rebuild
  it — otherwise the dialog drifts after template changes (ADR-0012 §3).
- **Reducer with IO/time.** `System.currentTimeMillis()`/UUID/capture in the reducer =
  untestable. Everything in effects, time via `ClockPort`.
- **Copy `AiErrorType`/`AIProvider` companion-locally.** Import from `:shared-ai`,
  otherwise the error/provider vocabularies drift against the core (§3.2).

## 14. Decision Log

### D1 — `received_texts` is replaced, not coexisted (closes plan Gap 5)

**Trigger:** Plan D1 + §10 Gap 5 demand the clarification "replacement/coexistence" of the
`received_texts` table in the new session model.

**Decision:** Replacement. `sessions` is the only history archive (Room-parity);
the sync-specific fields (device_id, dispatched, last_outcome, received_at)
move into a 1:1 companion table `dispatch_state`; `received_texts` is dropped after
backfill (`2.sqm`, §3.4). The phone sync henceforth writes/reads
`sessions`+`dispatch_state` with unchanged order and "never-downgrade-
dispatched" invariant.

**Rationale:** Two tables for "which texts exist" = double truth
(SSoT anti-pattern, `knowledge-doc-format`). A one-off migration is cheaper
than permanent union reads + coherence maintenance. `dispatch_state` instead of columns-in-
`sessions` keeps the Room parity clean (device/dispatched would pollute the parity diff).

**Alternatives Considered:** (a) Coexistence + union read — discarded (permanent
double truth). (b) Sync fields directly in `sessions` — discarded (pollutes the
parity). (c) Keep `received_texts`, only desktop in `sessions` — discarded
(the history UI would have to mix two sources).

### D2 — D1 optionally splittable into D1a (schema+parity+sync) / D1b (capture+pipeline)

**Trigger:** D1 is the broadest chunk; the sync rework carries the highest
regression risk.

**Decision:** Recommendation, no obligation: if the audit rates D1 as too broad, the
file-disjoint break is D1a (`data/` + `domain/session/` + sync replacement) before D1b
(`capture/` + `pipeline/`). D1a green first decouples the sync risk from the
pipeline build-out.

**Rationale:** Risk isolation; D1a is the precondition for everything persisting.

### D3 — `processing_steps.status` gets a CHECK in the companion (beyond Room)

**Trigger:** Room has **no** CHECK for `processing_steps.status` (only
`TEXT NOT NULL`, `MigrationTo8.kt:63`), although `StepStatus{SUCCESS,ERROR}` is a
finite set.

**Decision:** The companion sets the CHECK `status IN ('SUCCESS','ERROR')` — a
strict improvement, no parity break (every Room value stays valid). The
parity test documents the deliberate asymmetry.

**Rationale:** Double-Enum completeness on the greenfield; the
companion schema definition is new, so there is no migration obligation to the
Room gap.

### D4 — Cross-spec decisions of the plan architect (plan §3 D5, 2026-07-20)

**Trigger:** Plan refinement after all five block specs were available; four
decisions concern Block D.

**Decision:**
1. **D1a/D1b split is BINDING** (no longer "optional" as in D2):
   D1a = §3 complete (schema + parity + `received_texts` replacement +
   sync rework), D1b = §4 + §5 (capture + pipeline). The five existing tests
   from §3.5 are D1a acceptance.
2. **Companion entity tables: D3 creates them COMPLETELY** (plan D5.b) —
   `provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts`
   per the DDL from `peer-katalog.md` §5.2 **incl. provenance columns**;
   E1 creates only `peers`/`subscriptions`/`catalog_access_log` and gets
   the new edge E1→D3. This precises §9.2/"data source from Block C":
   the C1 **types** come from `:shared`, the companion
   **tables** from D3.
3. **SQLDelight migration numbers:** D1a = `2.sqm` (§3.4), D3 = `3.sqm`
   (entity tables), E1 = `4.sqm` — resolves the number collision between
   §3.4 and peer-katalog §5.
4. **`AmplitudeProcessor`: MOVE into `:shared-ai` instead of v1 copy** (plan
   D5.e, overrides §15 Gap 4 and the copy references in §4.4/§7.1/§10):
   package-preserving move in Chunk A2 (shared-ai spec §11 addendum);
   `capture/` imports the class from `:shared-ai`. Rationale: the
   curve parameters are the F19 design spec — a copy drifts invisibly.

**Rationale:** Risk isolation (1), one schema owner per surface + no
D↔E block cycle (2, 3), DRY where behavior MUST stay identical (4).

### D5 — Freshness pass 2026-07-20 (post-implementation, before archival)

**Trigger:** Integration check after completion of Block A–E (finding `integ-1`,
green) — reconciliation against the as-built state. D4 had already set the migration numbers
(D1a=`2.sqm`, D3=`3.sqm`, E1=`4.sqm`) as-built; the following
two points had not yet been executed at the D4 point in time.
**As-built vs. spec:**
1. **The `usage` table lives in `3.sqm` (D3), not in the D1a migration `2.sqm`.**
   The §3.3 note/§5.4 read the `usage` table conceptually as a D1 companion of the
   `UsageSink` port; physically it was folded into the D3 migration
   `companion/.../db/migrations/3.sqm` (the inline header there documents
   "Folded into this migration"). Behavior unchanged — only the
   migration assignment moved, to keep the table-owner boundary clean
   (D3 owns the additional companion tables).
2. **Management UI consolidated.** §9.2 sketches separate profile/model/
   prompt editor screens (implicitly `ui/profiles`/`ui/models`/`ui/prompts`).
   What was built is ONE consolidated screen
   `companion/.../ui/config/ManagementScreen.kt` + `ConfigViewModel.kt`
   (provider/model/prompt/profile in one data-driven surface) — DRY over the
   shared entity editing, instead of four almost identical screens.
**Assessment:** No code impact; both points are
characterization/parity-tested and noted in the impl reports. Body
unchanged; this entry is the normative as-built correction.

## 15. Information Gaps

1. **Cross-module enum SSoT.** The session-structure enums are companion-locally
   duplicated (Room original in `:app`), secured only via a tested
   `RoomParityReference`. The real SSoT (enums in `:shared`, Room+SQLDelight
   map onto them) requires an `:app` Room refactor outside Block D. **Owner:**
   later consolidation / `adr-companion-history-parity`. **Fallback:** tested
   reference list (§3.6).
2. **Audio format vs. provider limits.** WAV 16 kHz mono ≈ 1.92 MB/min; the
   concrete upload limits per transcription provider are to be verified by the D1 agent
   (pure verification, D4.2). **Fallback:** Opus/OGG as a later
   option if a limit breaks.
3. **Cold-resume of the capture.** Not implemented for v1 (desktop process
   long-lived); the segment list in `audio_file_paths` keeps the door open. **Owner:**
   follow-up iteration. **Fallback:** no resume after a companion crash (re-record).
4. ~~**`AmplitudeProcessor` storage location**~~ — **closed 2026-07-20 (§14 D4.4
   / plan D5.e):** move into `:shared-ai` (package-preserving, in Chunk A2)
   instead of a copy; `capture/` imports from `:shared-ai`.
5. **Management screens ↔ Block C.** Profile/model/prompt editor (§9.2) hang on
   the C1 `Profile` types; as long as C1 has not landed, they are stubs against the
   transitional `CompanionSettings` `AiConfig`. **Owner:** the D3 agent coordinates with the
   C strand (`depends_on: D3→{D2,C1}`). **Fallback:** dictation runs in D1/D2 against the
   transitional config.
6. **Windows tray notification mechanics.** For sync/error hints (AWT
   `TrayIcon.displayMessage` vs. toast) — the owner per plan is the E2 agent (§10
   Gap 6 of the plan); Block D uses only the panel hint (Linux clipboard).

## 16. References

- **Plan:** `~/.claude/plans/desktop-companion-v1.md` — §5 Block D (chunks
  D1/D2/D3), §3 decisions F4/F15/F16/F18/F19/F20/F21 + D2/D4, §7
  sequencing, §10 gaps 2/5.
- **Sister specs:** `shared-ai-extraktion.md` (Block A — `:shared-ai` core +
  ports, precondition), `secretstore.md` (Block B — credential storage).
- **ADR drafts (Block A1, plan-scoped):** `adr-desktop-dictation-host`,
  `adr-desktop-panel-ui`, `adr-desktop-review`, `adr-companion-history-parity`.
- **Binding ADRs:** `docs/decisions/0007` (rolling segments/multi-file audio),
  `0009` (serial run queue), `0011` (getFinalOutput/pending/terminal-guard),
  `0012` (post-processing conversation + `{message,output}`), `0013`
  (review panel + AmbiguityMode verdict matrix), `0016` (wire SSoT), `0018`
  (TextInserter port), `0020` (lazy-cursor sync — cursor parity contract), `0023`
  (bind catalog), `0027` (PC dictation, stays additive in parallel).
- **Conventions:** `docs/DATABASE-PATTERNS.md` (Double-Enum),
  `~/.claude/snippets/test-first-patterns.md`.
- **Key code (current state):**
  - Room schema: `app/schemas/net.devemperor.dictate.database.DictateDatabase/11.json`;
    enums `app/.../database/entity/{SessionType,SessionStatus,SessionOrigin,StepType,
    StepStatus,ResponseFormatKind,MessageRole,InsertionMethod}.kt`; CHECKs in
    `app/.../database/migration/MigrationTo{8,9,10,11}.kt`.
  - Companion schema + migration: `companion/.../db/Companion.sq`,
    `data/SchemaMigrator.kt:31-56`, `data/CompanionDatabase.kt:38-47`,
    `companion/build.gradle:9-22`; parity model
    `.../data/OriginCheckConstraintParityTest.kt`.
  - AI core (after Block A in `:shared-ai`): `ai/AIOrchestrator.kt:41,130`,
    `ai/factory/RunnerFactory.kt`, `ai/conversation/{ReviewDecision:28,
    ConversationTurnBuilder:36,78, ConversationReconstructor:49, StructuredResponse:19}.kt`,
    `preferences/AmbiguityMode.kt:27`, `ai/model/{ModelFetcher:42,ParameterRegistry}.kt`.
  - Re-dictate model: `core/PipelineOrchestrator.kt:788-873`.
  - Recording look: `core/AmplitudeProcessor.kt:34-44`,
    `widget/AmplitudeVisualizerDrawable.kt`, `widget/VisualizerUtils.kt`,
    `widget/BorderGlowAnimation.kt:154`, `state/render/RecordingAnimationController.kt:320`.
  - Companion infra: `Main.kt` (Window/Tray :129-174), `CompanionContainer.kt`,
    `platform/AppPaths.kt:19`, `platform/windows/{Win32Keyboard,Win32TextInserter}.kt`,
    `domain/{CompanionSettings,SyncService,DispatchService}.kt`,
    `domain/port/TextInserter.kt`.
```
