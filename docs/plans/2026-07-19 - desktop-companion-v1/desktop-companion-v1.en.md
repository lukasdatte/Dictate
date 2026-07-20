# Desktop Companion v1 — Dictation on the PC, Entity Model, Peer Catalog

---
date: 2026-07-19
author: Lukas + Claude Code (concept session desktop-concept)
type: Plan
status: Implementation-ready — understanding check fully answered (2026-07-19, see §3 D4)
context: Expansion of the Dictate companion into a standalone desktop dictation host (Compose UI, hotkey, full pipeline) plus a profile/entity model with immediate Android migration and peer-catalog distribution over Tailscale.
related-plan: n/a (top-level plan)
related-adrs: extends/touches ADR-0007, 0009, 0012, 0013, 0015, 0016, 0017, 0020, 0024, 0025, 0027; 8 new plan-scoped ADR drafts (§6)
archive_target: 2026-07-19 - desktop-companion-v1
---

This plan is the implementation template for the desktop expansion defined via
the question catalog (F1–F34, all decided on 2026-07-19). It is structured for
execution with `implement-long-plan-v3` (6 blocks, 16 chunks after the D1a/D1b
split — rationale in §7 and in the Plan Conventions block).

> [!IMPORTANT]
> **SSoT rule since the spec deep-dive (2026-07-20):** For blocks A–E there are
> five implementer-ready specs under
> `docs/plans/2026-07-19 - desktop-companion-v1/research/`:
> `shared-ai-extraktion.md` (A), `secretstore.md` (B),
> `entitaetenmodell-android.md` (C), `desktop-host.md` (D),
> `peer-katalog.md` (E). **The specs are the canonical detail source** —
> the chunk descriptions in §5 are stubs with spec-§ references; content does
> not live twice. The cross-spec decisions are in §3 D5 and are documented in
> the affected specs as decision/change-history addenda.
> Also final: `reports/e2e-runbook.md` (16 manual E2E cases) and two
> groundwork commits (E2E infra versioned, schema assets for androidTest).

Concept preliminary work (inventory, sketch, question catalog): checked in by A1
from `tmp/desktop-concept/` as further `research/` files (D4.1).

## Table of Contents

- [§1 Vision and Motivation](#1-vision-and-motivation)
- [§1a Architecture Walkthrough](#1a-architecture-walkthrough)
- [§2 Acceptance Criteria](#2-acceptance-criteria)
- [§3 Decision Log](#3-decision-log)
- [§4 Directory Layout (Target Topology)](#4-directory-layout-target-topology)
- [§5 Building Blocks](#5-building-blocks)
- [§6 ADR Drafts (plan-scoped)](#6-adr-drafts-plan-scoped)
- [§7 Sequencing and Parallelization](#7-sequencing-and-parallelization)
- [§8 Test Strategy](#8-test-strategy)
- [§9 Risks](#9-risks)
- [§10 Information Gaps](#10-information-gaps)
- [§11 Iteration Log](#11-iteration-log)
- [§12 References](#12-references)
- [Plan Conventions (implement-long-plan-v3)](#plan-conventions-compatibility-block-for-implement-long-plan-v3)

## 1. Vision and Motivation

### 1.1 Why this undertaking exists

Today Dictate dictates exclusively on the phone; the desktop companion is a
passive text receiver (ADR-0017/0027). The user wants to dictate on the PC the
same way as on the phone: defined key combination → warm mini panel → recording →
transcription → post-processing → auto-insert into the focused app — without a
phone in hand. At the same time, configuration data (providers, models, prompts,
API keys) is today pref strings of a single Android installation: not shareable,
not combinable, keys in plaintext. This undertaking solves both in one go,
because the shared root is the same: a shared, platform-neutral AI core plus an
entity-based configuration model.

### 1.2 What the undertaking delivers

1. **Desktop dictation:** The companion records itself (javax.sound), runs the
   AI pipeline itself (shared `:shared-ai` core), inserts via the existing
   `TextInserter`. Hotkey + focus-free Compose mini panel + full review mode
   incl. re-dictate + history in Room-schema parity.
2. **Profile system:** `ProviderConfig`/`ModelRef`/`Prompt`/`Profile` as
   versioned, shareable entities; Android migrates along immediately (Prefs→DB,
   `APISettingsActivity` rebuild).
3. **SecretStore:** project-wide encrypted key storage (Android Keystore /
   Windows DPAPI), migration of the plaintext keys.
4. **Peer catalog:** every companion is a provider AND a consumer in the tailnet;
   hash-based subscription sync (SUBSCRIBE/ONE_SHOT), change notification,
   Peer Explorer, v3 file export with identical serialization; headless
   hub peer as a deployment variant.

### 1.3 Discarded Alternatives

- **Browser UI (TS SPA served by the companion):** discarded per F1 — a second
  language, a UI wire protocol, and TS codegen for zero functional gain in the
  single-user context; Compose Desktop already exists and can do windowing/
  hotkey/warm-keeping natively.
- **KMP/Wasm for code sharing all the way into the browser:** discarded —
  superseding ADR-0015 without need, Kotlin ceiling 2.1.20, the AI SDKs remain
  JVM-only anyway.
- **Zero-knowledge key sharing (share password/sealed box):** discarded per
  F12 in favor of envelope encryption — in the self-hosted context the peer
  operator is trustworthy anyway; zero-knowledge remains a documented later
  hardening option.
- **A central hub server as its own system:** discarded per F25/F8 — the
  "hub" is a `--headless` deployment variant of the companion server, not its
  own module/protocol.
- **A reduced desktop history schema:** discarded per F15 — full Room parity, so
  that regenerate/review/step chains work identically.

## 1a. Architecture Walkthrough

### 1a.0 ASCII Stack Diagram (target architecture)

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

### 1a.1 Layer `:shared` — entities + wire

- **Purpose:** SSoT for all wire formats and the new configuration entities.
- **File:** `shared/src/main/kotlin/net/devemperor/dictate/shared/`
- **Contract:** `ProtocolCodec` remains the single codec door (ADR-0016); the
  canonical entity serialization is simultaneously the v3 file format and the
  hash basis.
- **Detail:** §5 Block C (C1), Block E (E1).

### 1a.2 Layer `:shared-ai` — shared AI core

- **Purpose:** exactly ONE implementation of providers/runners/prompt logic for
  both platforms; platform accesses exclusively via ports.
- **File:** `shared-ai/src/main/kotlin/net/devemperor/dictate/ai/` (the package
  stays `net.devemperor.dictate.ai` → minimal import diffs in `:app`).
- **Contract:** `AiConfig`, `UsageSink`, `ProxyConfig`, `AudioDurationReader`,
  `SecretStore` (ports); `SharedAiPurityTest` analogous to `SharedPurityTest`
  (no Android, no Ktor; okhttp/SDKs allowed — its own dependency policy).
- **Detail:** §5 Block A.

### 1a.3 Platform-host layer

- **`:app`:** implements the ports with SharedPreferences/Room/SecretStore;
  UI rebuild onto the entity model (C2/C3); subscription-sync consumer (E2).
- **`:companion`:** implements the ports with SQLDelight/CompanionSettings/
  SecretStore; new subsystems `capture/`, `pipeline/`, `hotkey/`, `ui/panel/`;
  catalog server + sync engine + Peer Explorer.
- **Detail:** §5 Blocks B–E.

### 1a.4 Read-this-before-implementing Checklist

- [ ] EVERY new wire payload: `@Serializable` DTO + co-located `Validation<T>`
  + exclusively via `ProtocolCodec` (ADR-0016, §5 E1).
- [ ] Kotlin ceiling: no library built with Kotlin > 2.1.20, compiler-wide
  (ADR-0015). Applies to `:shared-ai` and all new dependencies too.
- [ ] New endpoints additive + `HealthResponse` capability flag, no
  protocol-version bump (ADR-0025 pattern, §5 E1).
- [ ] Finite-set columns (Room AND SQLDelight): Double-Enum pattern —
  Kotlin enum + SQL CHECK (docs/DATABASE-PATTERNS.md).
- [ ] Prefs never via raw string keys — only `DictatePrefs.kt`; new secret
  values only via the `SecretStore` port (§5 B1).
- [ ] Reducer purity in the desktop pipeline state (no IO in reducers) —
  pattern from ADR-0001, without porting the Android orchestrator.
- [ ] Extraction chunks (A2/A3) are behavior-neutral: no behavior of the
  Android app changes; existing tests stay green.
- [ ] Every bug fix with a regression test that was red on the unfixed code
  (test-first convention, §8).

## 2. Acceptance Criteria

Global (refined per block in §5):

1. **Build invariant:** `./gradlew build` green across all modules (`:app`,
   `:shared`, `:shared-ai`, `:companion`); `SharedPurityTest` and the new
   `SharedAiPurityTest` green.
2. **Behavior neutrality of the extraction:** After Block A the Android app
   behaves unchanged (existing unit/instrumented tests green, no diff in the
   API-traffic construction — verified by existing runner tests).
3. **Desktop dictation E2E:** Hotkey → panel visible <100 ms → recording
   (start/pause/resume/discard) → transcription+post-processing via the
   configured profile → auto-insert (Windows) resp. clipboard+UI hint (Linux);
   session incl. steps/conversation persisted in the companion DB.
4. **Review-mode parity:** `AmbiguityMode` semantics identical to ADR-0013;
   re-dictate produces a refinement session + conversation continuation; verdict
   via shared `ReviewDecision` (one code path, no fork).
5. **Schema parity:** the SQLDelight schema covers `sessions`/`transcriptions`/
   `processing_steps`/`conversation_messages` with identical enum vocabularies
   + CHECK constraints; automated parity tests (§8) green.
6. **Android migration:** existing installations migrate losslessly
   (Prefs→entities, plaintext-keys→SecretStore); migration tests with
   fixture prefs/DB v11 green; no more plaintext keys in SharedPreferences
   after migration (test checks for absence).
7. **Peer sync:** two companion instances (test harness, in-memory) share a
   prompt/profile/ModelRef/credential; SUBSCRIBE detects a change via
   root-hash comparison and updates + notifies; ONE_SHOT stays put; a
   locally decoupled copy (fork) is never overwritten; a credential lands on
   the receiver exclusively in the SecretStore.
8. **Canonical serialization stable:** snapshot tests fix the byte identity of
   the canonical form (hash basis); v3 file export = catalog wire format
   (one codec, round-trip tests incl. v1/v2 import compatibility).
9. **ADR completeness:** the 8 ADR drafts from §6 exist as plan-scoped drafts
   and are promoted before plan archival (docs/decisions/ + index).

## 3. Decision Log

The 34 fundamental decisions were made by the user + team lead in the question
catalog (`tmp/desktop-concept/fragenkatalog.md`, as of 2026-07-19) and are
binding for this plan. Compact form (Form B; ⚠ = deviation from the original
recommendation):

| # | Decision |
|---|---|
| F1 ⚠ | Compose Desktop UI, NO browser (F3/F13 drop out) |
| F2 | AI pipeline on the companion via `:shared-ai` |
| F4 | Audio recording in the companion (javax.sound) |
| F5 | Native Compose window, frameless, always-on-top, <50 ms toggle |
| F6 | Windows-first for hotkey/insertion, everything behind ports, Linux dogfooding clipboard+button |
| F7/F25 | Providers = companions + optional headless hub peer; Android a pure consumer |
| F8 | Headless peer = `--headless` variant of the companion, not its own module |
| F9 | Pull-only catalog sync |
| F10 | Pairing model reused for peer auth |
| F11 | Project-wide SecretStore port incl. Android plaintext-key migration |
| F12 ⚠ | Envelope encryption: providing peers may decrypt; TLS + SecretStore mandatory; no share password |
| F14 | Fork + update hint (`sourceRef` + provenance hash) |
| F15 ⚠ | FULL Room-schema parity in SQLDelight, with parity tests |
| F16 | Companion DB = shared archive (phone sync + desktop sessions, origin-separated); peers never a dictation store |
| F17 | Profile content: transcription/completion ModelRef, parameter overrides, ordered prompts+autoApply, system-prompt selection, AmbiguityMode; credentials only referenced |
| F18 ⚠ | Full review mode from v1 incl. re-dictate |
| F19 | Recording core 1:1 in Compose; management screens their own layouts with color/shape language |
| F20 | Prompt choice only via the profile (dropdown in the panel) |
| F21 | Focus-free panel + auto-insert; confirmation as a fallback setting |
| F22 ⚠ | Android migrates onto the entity model IMMEDIATELY with v1 |
| F23 | File export stays; v3 = peer wire format, one codec implementation |
| F24 | The unit is called "Profile" (`Profile`) |
| F26 ⚠ | Discovery: manual+QR AND Tailscale-API enumeration (behind a port) |
| F27 | Root hash + per-entity contentHash; canonical serialization = v3 format |
| F28 | Every device polls itself; notification as sync result; interval configurable |
| F29 | Subscribed copies read-only; editing = explicit fork-decouple |
| F30 | Peer = address+pairing credential; `peerId` designed to be public-key-capable |
| F31 | Gateway path: only three seams (AI seam, `GATEWAY` enum reserved, protocol namespace) |
| F32 | Catalog family on the existing wire stack |
| F33 | Tolerate offline peers silently; staleness indicator |
| F34 | Peer Explorer: consumption + offering view; catalog browsing in editor UIs; Android read-only |

Plan-internal follow-up decisions (D numbers, Form A):

### D1 — `:shared-ai` as a fourth module, package stays `net.devemperor.dictate.ai`

**Trigger:** the extraction (F2) needs a target location; `:shared` has the
strict `SharedPurityTest` (jvmTarget 1.8, no okhttp-api leak as a design goal).
**Decision:** new Gradle module `:shared-ai` (pure `kotlin("jvm")`, its own
dependency policy: openai-java/anthropic-java/okhttp allowed, Android/Ktor
forbidden via `SharedAiPurityTest`). The package name stays unchanged, so that
the `:app` diffs affect almost only build files.
**Rationale:** separates wire purity (`:shared`) from SDK heaviness
(`:shared-ai`); minimizes extraction risk. Details/alternatives: ADR draft
`adr-shared-ai-module`.

### D2 — Desktop pipeline as a lean orchestrator of its own, no port of `state/`

**Trigger:** the Android `state/` (19 modules) is tailored to IME axes.
**Decision:** `companion/pipeline/` gets a small state machine (4 axes:
recording, pipeline-queue, review, panel) following the ADR-0001 rules (pure
reducers, one dispatch door, IO in effects), but as a fresh implementation with
ADR-0009 queue semantics (serial, ordered).
**Rationale:** share the pattern, don't drag along code with 15 irrelevant axes.

### D3 — Entities live per platform in the platform DB, serialization in `:shared`

**Trigger:** Room (Android) and SQLDelight (companion) cannot share entities.
**Decision:** `:shared` defines the platform-neutral entity DTOs + canonical
serialization + `contentHash`; Room/SQLDelight each keep their own tables and
map via thin mappers (model: `SessionEntityMapper`). Parity tests enforce
identical enum vocabularies.
**Rationale:** SSoT for the format, native persistence per platform — the same
proven pattern as with session sync (ADR-0020).

### D4 — Understanding-check answers (team lead + user, 2026-07-19)

**Trigger:** the 7 follow-up questions from the plan presentation were fully
decided; all answers confirm the plan assumptions.
**Decision:**
1. Chunk A1 creates the plan folder `docs/plans/2026-07-19 - desktop-companion-v1/`
   with `adrs/` AND `research/` and checks in the three concept documents from
   `tmp/desktop-concept/` as `research/`; the plan file itself stays in
   `~/.claude/plans/` until archival.
2. Desktop audio: WAV 16 kHz mono for v1; Opus/OGG only if needed later.
3. D2 focus spike: the fallback (remember the foreground window at hotkey time,
   restore before insert) is a defined, equivalent path — failure of the
   `WS_EX_NOACTIVATE` spike is NOT an escalation; both paths are documented in
   `adr-desktop-panel-ui`.
4. Android profile UX: NO profile switcher in the keyboard UI — profile choice
   only in the settings (active profile).
5. Android subscription sync: full sync with WorkManager background polling +
   system notifications; only the explorer is read-only.
6. The existing PC-dictation mode (ADR-0027, phone records) stays unchanged in
   parallel; the desktop mode is purely additive.
7. C2 is a hard migration without a coexistence flag; rollback path =
   prefs-backup export before the migration.
**Rationale:** all answers pick the option already anchored in the plan —
no structure or sequencing change needed; §10 Gaps 1–3 thereby closed.

### D5 — Cross-spec decisions of the spec deep-dive (architect, 2026-07-20)

**Trigger:** five Opus-High specs (see SSoT block above) raised or left open
seven cross-block points; team-lead order 2026-07-20, decision by the plan
architect.

**Decision:**

a) **Enum layering A↔C — wire enums in `:shared` CONFIRMED, no move of
   `AIProvider` to `:shared`, no `:shared-ai`→`:shared` edge.**
   `:shared` defines `ProviderType`/`ProviderKind`/`ModelFunction`/
   `AmbiguityModeValue`/`PromptSelectionMode` itself (entitaetenmodell §4.8);
   mapper + parity tests live in `:app` (which sees both modules) and are a
   mandatory gate. Rationale: this is exactly the repo's existing
   wire-vs-domain doctrine (ADR-0016: `SessionOriginWire` ↔ `SessionOrigin` +
   `SessionEntityMapper` + parity tests) — the domain enums carry behavior
   (`AIProvider` capabilities/base URLs, `AmbiguityMode.forcesTurn`), which does
   not belong in the wire module; a move would additionally introduce the
   module coupling deliberately avoided by Block A (shared-ai spec §1a.0: "both
   are independent leaves") and break the package-preserving move concept.
   Drift is test-prevented, not convention-prevented — identical to the
   existing baseline. Closes entitaetenmodell §14 Gap 1; the §4.8 TIP
   (alternative) is thereby discarded.

b) **Companion entity tables: D3 creates them COMPLETELY (incl. provenance
   columns per the DDL from peer-katalog §5.2); E1 creates only
   `peers`/`subscriptions`/`catalog_access_log`. New edge E1→D3.**
   Rationale: one migration owner instead of a two-stage schema (the spec option
   "whoever lands first" is not deterministically plannable); the opposite
   direction (D3→E1) would produce a block cycle D↔E, because E3 integrates into
   the D3 editor screens anyway. Provenance columns stay NULL until E2 —
   harmless. Closes peer-katalog §15 Gap 1.

c) **D1 sub-cut ADOPTED as BINDING: D1a (SQLDelight full parity +
   `received_texts` replacement + sync rebuild) before D1b (capture + pipeline).**
   Rationale: the replacement, keeping five existing tests green without an
   assertion change (`SyncE2ETest`, `CompanionE2ETest`, `MultiConnectorE2ETest`,
   `TruncatedResponseE2ETest`, `SqlDelightHistoryRepositoryTest`; desktop-host
   §3.5), is the block's highest regression risk — as its own chunk it gets
   focused audit and decouples the risk from the pipeline construction
   (desktop-host §14 D2, "optional" there — binding here).

d) **`PromptTypeClassifier` stays in `:app` — confirmed** (deviation from the
   original Plan-A2 list). Rationale: it hangs on `PromptType` (16 pill files,
   ADR-0024); pills are deliberately desktop-alien (F6 of the requirements
   catalog / shared-ai §9). Plan-A2 stub corrected.

e) **`AmplitudeProcessor`: MOVE to `:shared-ai` (package-preserving,
   `net.devemperor.dictate.core`), executed in A2 — instead of a copy in
   `:companion`** (overrides desktop-host §15 Gap 4 "v1: copy").
   Rationale: F19 requires 1:1 design parity of the recording core — the
   amplitude-curve parameters ARE the design spec; a copy drifts invisibly. The
   class is pure `kotlin.math`, the move costs a `git mv` in the already-running
   A2.

f) **WorkManager confirmed** (`androidx.work:work-runtime-ktx` as a new `:app`
   dependency; peer-katalog §6.5). Verification order to the E2 agent: check the
   candidate version BEFORE incorporation against Kotlin metadata ≤ 2.1.20 and
   document it in the chunk (R4); fallback `AlarmManager`+`JobScheduler`
   in-house build only after proven incompatibility. Covers D4.5 (full
   background polling).

g) **`received_texts` replacement confirmed as a plan decision** (replaces
   §10 Gap 5): replacement instead of coexistence; sync fields move into the
   1:1 companion table `dispatch_state`; backfill `received_texts`→`sessions`
   (+`dispatch_state`) in the SQLDelight migration, then DROP
   (desktop-host §3.4/§14 D1). Behavior-neutrality proof = the five existing
   tests from (c).

**Additional determination (consequence of b/c/g) — SQLDelight migration
numbers:** D1a and E1 both claimed `2.sqm`. Binding assignment by sequencing:
**D1a = `2.sqm`** (parity + dispatch_state + backfill/drop),
**D3 = `3.sqm`** (entity tables), **E1 = `4.sqm`**
(peers/subscriptions/catalog_access_log).

**Overall rationale:** all seven points follow the same criteria: existing
repo doctrine before reinvention (a), one owner per schema surface (b, g),
risk isolation into its own audit units (c), DRY across module boundaries only
where behavior MUST stay identical (e), documented ceiling check instead of a
ban (f).

## 4. Directory Layout (Target Topology)

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

**File delta, rough:** 1 new module, ~6 new companion subsystems, ~4 new app
packages, ~25 moved files, 8 ADR drafts.

## 5. Building Blocks

> Format per chunk: **stub** — goal + spec reference + sharpened acceptance.
> The domain detail SSoT is the respective spec under `research/` (see the
> SSoT block at the top of the file); this plan does not repeat it. One chunk =
> one big focus area for one agent (v3 convention). The implementation agent
> reads the spec §§ of its chunk COMPLETELY.

### Block A — Foundation: ADRs + `:shared-ai` extraction

**Goal:** the AI core exists exactly once, pure JVM, behind ports; Android
behaves unchanged. All fundamental ADRs are fixed as drafts.

**Chunk A1 — ADR drafts (all 8) + check in concept research.** Author chunk
with no production code: creates `docs/plans/2026-07-19 - desktop-companion-v1/`
with `adrs/` and `research/`; checks in the three concept documents from
`tmp/desktop-concept/` (bestandsaufnahme, konzept-skizze, fragenkatalog) as
`research/` (D4.1); writes the 8 drafts per the §6 specification (format:
`knowledge-adr-format` + `~/.claude/templates/adr.md`; status `Proposed
(plan-scoped — pending promotion)`; filename without a number). The five specs +
`reports/e2e-runbook.md` already lie in the plan folder and are checked in
along. Acceptance: 8 ADR files, each with all mandatory sections incl.
Alternatives + Decision History initial entry; decision contents congruent with
§3 (incl. D5!); `research/` contains concept documents + specs,
`tmp/desktop-concept/` obsolete afterwards (delete).

**Chunk A2 — Pure moves.** → **Spec `shared-ai-extraktion.md`**: inventory
§3.1–3.4, Gradle scaffold + purity §5, move steps A2.0–A2.3 (§6),
directory §7. Enum moves package-preserving (split-package pattern, §3.4);
**per D5.d `PromptTypeClassifier` stays in `:app`** (spec §9 footgun);
**per D5.e additionally move `core/AmplitudeProcessor.kt` package-preserving to
`:shared-ai`** (not in the spec inventory — addendum spec §11).
Acceptance: spec §2 criteria 1–3, 5 (module compiles on jvmTarget 1.8;
build green; `SharedAiPurityTest` green incl. negative self-test;
`git log --follow` intact).

**Chunk A3 — Ports + runner/orchestrator.** → **Spec
`shared-ai-extraktion.md`**: port signatures §4.1–4.5 (`AiConfig`,
`UsageSink`, `ProxyConfig`, `AudioDurationReader`), app couplings §3.5,
move steps A3.1–A3.7 (§6), characterization tests §8.1–8.2
(written BEFORE the move). `org.json` → kotlinx-serialization (A3.4).
Acceptance: spec §2 criteria 2, 4, 6 (behavior neutrality: all `:app` tests
green without an assertion change, no diff in the API traffic; no AI-core path
reads SharedPreferences/UsageDao/MediaMetadataRetriever directly anymore —
grep check).

**Risks:** spec §9 (footgun table) + §10 (Gaps 1–4; Gap 4 SDK bytecode target
= escalation case, see §9 R9 here).

### Block B — SecretStore (project-wide)

**Goal:** no more plaintext key on any platform; one port, two impls.

**Chunk B1 — Port + impls.** → **Spec `secretstore.md`**: port design §4
(`SecretStore` in `:shared-ai`, package `net.devemperor.dictate.ai.secrets`,
no new dependency), Android Keystore impl §5 (incl. Robolectric cipher seam
§5.4, backup exclusion §5.3), desktop impl §6 (DPAPI via the existing
jna-platform `Crypt32Util` + file fallback, `available` flag).
Acceptance: spec §2 criteria 1–3 (port shared + build/purity green;
round-trip per impl byte-identical, DPAPI as pending; error semantics
`DecryptionFailed` instead of empty string).

**Chunk B2 — Android key migration.** → **Spec `secretstore.md`**:
migration design §7 — binding **11 slots** (all `Pref.*ApiKey*` PLUS
`WindowsDeviceSecret`, §7.1/Gap 1), legacy namespace `SecretRef("legacy",…)`
with C2 re-mapping (§7.2 — B2 runs BEFORE C2), ordering invariant
backup→put→remove→flag (§7.3), rollback export §7.6.
Acceptance: spec §2 criteria 4–6 (11-slot fixture lossless; idempotent +
fresh installation ok; no code path reads old pref keys — grep test).

**Risks:** spec §11 (footguns: IV reuse, pref-before-put deletion, backup blob
without key) + §4.3/§5.3 (KEK device-bound — re-entry after restore, held in
`adr-secret-store`).

### Block C — Entity model + Android rebuild (F22)

**Goal:** ProviderConfig/ModelRef/Prompt/Profile as shareable entities with
canonical serialization; Android runs fully on it.

**Chunk C1 — Entities + v3 codec (`:shared`).** → **Spec
`entitaetenmodell-android.md`**: entity DTOs + envelope §4.1–4.7,
wire enums §4.8 (**per D5.a confirmed** — `ProviderType`/`ProviderKind`/
`ModelFunction`/`AmbiguityModeValue`/`PromptSelectionMode` in `:shared`,
parity tests + mapper in `:app`; the §4.8 TIP is discarded), canonical
serialization + contentHash + v3 format §5 (incl. `keyFingerprint` rule
§4.4 and recompute-on-write §5.3). `GATEWAY` reserved (F31).
Acceptance: spec §2 AC1–AC3 (codec + purity; canonical stability incl.
key reordering; v3 round-trip v1/v2/v3) + enum parity tests green.

**Chunk C2 — Android persistence + profile resolver.** → **Spec
`entitaetenmodell-android.md`**: Room v11→v12 §7 (5 new tables +
`prompts` recreate, CHECKs), Prefs→entities migration §8 (backup §8.4 BEFORE
everything; default profile §8.5 in ONE transaction; deterministic UUIDs
§8.6; SecretStore re-mapping legacy→credential per secretstore §7.2),
`ProfileResolver` as `AiConfig` §9 (+ characterization test §9.4).
Acceptance: spec §2 AC4–AC7 (MigrationTest CHECK acceptance/rejection;
byte-equal runner configuration; grep key-freeness; backup + idempotence).

**Chunk C3 — Android UI rebuild.** → **Spec `entitaetenmodell-android.md`**:
settings rebuild §10 (provider/model/profile management §10.1–10.3 — ONLY
settings, no keyboard switcher per D4.4; import dispatcher §10.4;
v3 export §10.5; PromptsOverview badge §10.6). ADR-0024/pills untouched.
Acceptance: spec §2 AC8–AC9 + manual E2E checklist (create profile →
dictate → model switch takes effect; cases in `reports/e2e-runbook.md`).

**Risks:** the largest existing-code intervention (783-line activity); hard
migration without a coexistence flag (D4.7) with prefs backup as rollback;
spec §14 Gaps 2–5 (Anthropic free text, SecretRef format, backup cleanup,
custom dedup) with named owners.

### Block D — Desktop dictation (companion host)

**Goal:** full dictation on the PC: recording → pipeline → insert/review,
with a warm panel and hotkey.

**Chunk D1a — Schema full parity + `received_texts` replacement (per D5.c).**
→ **Spec `desktop-host.md`**: enum vocabularies §3.2, table translation
§3.3, replacement + `dispatch_state` + backfill/drop §3.4 (**migration
`2.sqm`**, D5 number assignment), sync/repo rebuild §3.5, parity-test design
§3.6. Acceptance: spec §2 criteria 1–4 — in particular: the five existing
tests (`SyncE2ETest`, `CompanionE2ETest`, `MultiConnectorE2ETest`,
`TruncatedResponseE2ETest`, `SqlDelightHistoryRepositoryTest`) stay green
**without an assertion change**; parity suite red on artificial drift;
`received_texts` MigrationTest lossless.

**Chunk D1b — Recording + desktop pipeline.** → **Spec `desktop-host.md`**:
capture §4 (WAV 16 kHz mono §4.1, devices §4.2, rolling segments §4.3,
amplitude feed §4.4 — uses the `AmplitudeProcessor` moved to `:shared-ai` per
D5.e; upload-limit verification §4.5), pipeline §5
(controller §5.1, phase model §5.2, `DesktopUiState` §5.3,
reducer purity §5.4, steps §5.5, queue §5.6; transitional `AiConfig`
from `CompanionSettings` until D3). Acceptance: spec §2 criteria 5–6
(headless dictation E2E with fake runners: WAV fixture → session +
transcription + step + conversation persisted; reducer transitions +
enqueue unit-tested).

**Chunk D2 — Hotkey + panel + recording UI + insert.** → **Spec
`desktop-host.md`**: `GlobalHotkey` port §6.1, `PanelWindowControl` §6.2,
focus spike (time-box ~1 day) + `FocusRestorationPolicy` fallback §6.3
(D4.3: failure not an escalation; do not "fake green" the pending test),
recording-UI reconstruction §7 (parameter tables §7.1–7.3, Compose §7.4),
insert/auto-insert §8.5. Acceptance: spec §2 criteria 8–10 (focus policy
both paths unit-tested; design-parameter adoption; manual Windows sign-off
per e2e-runbook).

**Chunk D3 — Review + profile/model/prompt UI + entity tables.**
→ **Spec `desktop-host.md`**: review §8 (AmbiguityMode §8.1,
`ReviewDecision` verbatim §8.2, re-dictate §8.3, states §8.4,
insert/discard §8.5), management/history UI §9 (panel entry point +
profile dropdown §9.1, screens §9.2, history §9.3). **Per D5.b D3
additionally creates the companion entity tables COMPLETELY** —
`provider_configs`/`model_refs`/`prompts`/`profiles`/`profile_prompts`
per the DDL from peer-katalog §5.2 incl. provenance columns, as
**migration `3.sqm`**, enum parity with C1 per parity test. Acceptance:
spec §2 criterion 7 (verdict matrix + `REVIEW_REFINEMENT` session) +
review E2E with fake runner + entity-table migration with test.

**Risks:** spec §13 (footgun table: ReviewDecision reconstruction,
sync-cursor ordering, reducer IO, companion-local enum copies) + §15
(Gap 2 upload limits, Gap 3 cold resume deliberately v1-out, Gap 5
C1-stub coordination).

### Block E — Peer catalog + subscription sync + Peer Explorer

**Goal:** share and consume entities between peers in the tailnet; provenance
visible; headless hub peer possible.

**Chunk E1 — Protocol + server side.** → **Spec `peer-katalog.md`**:
wire family §3 (DTOs/validations/endpoints/`supportsCatalog`,
`CatalogClient`), server §4 (`CatalogService`/routes, root hash §4.2,
credential call + audit line §4.3), schema §5 — **per D5.b only**
`peers`/`subscriptions`/`catalog_access_log` (**migration `4.sqm`**); the
entity tables come from D3 (new edge E1→D3). Acceptance: spec §2
AC1–AC5 (additivity without a version bump; Konform completeness;
root-hash determinism; auth parity 401; credential isolation +
audit line per delivery).

**Chunk E2 — Sync engine + notification + Android consumer.** → **Spec
`peer-katalog.md`**: engine §6 (no-op path §6.1, verify-before-write §6.3,
fork protection §6.4, offline §6.5 staleness), scheduler §6.5 (companion timer;
Android `CatalogSyncWorker` under WorkManager — **per D5.f confirmed**,
document the ceiling-check order BEFORE incorporation), notification §7 (AWT
`SystemTray` per spec D6; Android system notification). Acceptance: spec §2
AC6–AC10 (idempotence with exactly one GET; update detection; fork protection
`subscription_mode=NULL`; staleness instead of an error; two-peer E2E §11).

**Chunk E3 — Peer Explorer + discovery + headless.** → **Spec
`peer-katalog.md`**: explorer §8 (consumption/offering view, state matrix
§8.1; Android read-only), discovery + headless §9 (`PeerDiscovery` port,
Tailscale-CLI impl + Noop §9.2; `--headless` §9.3), editor integration into
the D3 screens. Acceptance: spec §2 AC11–AC13 (discovery fallback empty
list; headless boot without Compose; explorer state matrix).

**Risks:** spec §15 (Gap 3 tray-coexistence spike Compose `Tray` vs. AWT
`TrayIcon` — owner E2; Gap 4 `peerId` formation v1 = opaque UUID; Gap 5
Tailscale CLI vs. LocalAPI; Gap 6 credential `contentHash` basis, coupled
to secretstore §12 Gap 4 — owner E1 + `adr-peer-catalog`).

### Block F — Closure: ADR promotion + docs + E2E

**Goal:** decisions promoted, documentation consistent, overall sign-off.

**Chunk F1 — Promotion + docs + sign-off.** Promote the ADR drafts
(next free numbers from 0028, index rows, cross-links plan↔ADR
bidirectional); ADR-0015 decision-history entry (fourth module),
ADR-0017 extension references; `CLAUDE.md` (modules, `:shared-ai`, new
conventions SecretStore/entities), `docs/DATABASE-PATTERNS.md`
(SQLDelight parity section), companion README; manual
E2E sign-off checklist (Windows device: §2 criteria 3/4/7 played through) as
a runbook-like checklist in the plan folder. Acceptance: §2 criterion 9;
docs references without dead links; sign-off checklist checked off by the user.

## 6. ADR Drafts (plan-scoped)

Location: `docs/plans/2026-07-19 - desktop-companion-v1/adrs/adr-{slug}.md`,
status `Proposed (plan-scoped — pending promotion)`, format per
`knowledge-adr-format` (all mandatory sections; Research cites the
`tmp/desktop-concept/` research + question-catalog decisions). Contents:

| Slug | Core decision | Relationship to existing ADRs |
|---|---|---|
| `adr-shared-ai-module` | Fourth pure-JVM module `:shared-ai` for the AI core, ports (AiConfig/UsageSink/ProxyConfig/AudioDurationReader), own purity policy, package unchanged | extends ADR-0015 (decision-history entry there on promotion) |
| `adr-secret-store` | Project-wide `SecretStore` port (Keystore/DPAPI/fallback), migration of the plaintext keys, keys device-bound | resolves the explicit defer from ADR-0017 §F-3 |
| `adr-config-entity-model` | ProviderConfig/ModelRef/Prompt/Profile as entities; canonical serialization + contentHash = v3 format; `GATEWAY` reserved; Android migration Prefs→DB | new foundation; touches ADR-0024 (prompt fields), ADR-0012 (model resolution via profile) |
| `adr-desktop-dictation-host` | Companion becomes a recording+pipeline host (javax.sound, serial queue per ADR-0009 semantics, `:shared-ai` pipeline); phone path stays unchanged | extends the ADR-0017 role model (companion no longer only a receiver); ADR-0007 pattern adopted |
| `adr-desktop-panel-ui` | Compose mini panel: frameless, always-on-top, focus-free (spike + fallback), global hotkey behind a port, auto-insert policy F21 | analogue of the render-host pattern ADR-0004/0027 on the desktop side |
| `adr-desktop-review` | Full review mode on the desktop host incl. re-dictate; shared `ReviewDecision` authority | **revises** the "review is IME-only" determination from ADR-0013/ADR-0027-F8 (supersede sub-aspect, both reference) |
| `adr-peer-catalog` | Peer-catalog family on the wire stack: pull-only, root hash + contentHash, SUBSCRIBE/ONE_SHOT, fork rule, envelope credential delivery, discovery port, headless peer, `/v1/ai/*` namespace reserved | extends ADR-0016/0025 (additive family), extends ADR-0017/0020 (new authority direction only for configuration entities, dictations excluded per F16) |
| `adr-companion-history-parity` | Full session-schema parity in SQLDelight + parity-test obligation; `received_texts` replacement; companion DB as shared archive (F16) | extends ADR-0014/0020 (filter-definition equality also applies to desktop sessions) |

## 7. Sequencing and Parallelization

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

Rationale:

- **A first** — every further piece of work builds on `:shared-ai` (D, C2)
  resp. the ADR fixations (all). A1 is independent of A2/A3 and runs in parallel.
- **B early** — C2 (key storage in the entity model) and E2 (credential
  consumption) need the SecretStore; B1 hangs only on the A2 scaffold.
- **C1 parallel to A3/B1** — pure `:shared` work with no dependency on the AI
  core. C2/C3 afterward serial (migration before UI).
- **D parallel to C** from A2/A3: **D1a** (schema/sync, its own chunk per D5.c)
  needs only the A2 enum moves; **D1b** (capture/pipeline) needs A3 and
  runs against a transitional `AiConfig` from `CompanionSettings`; only D3
  gates on the C1 types and additionally creates the companion entity tables
  (D5.b, migration `3.sqm`).
- **E after C1 + D3 + B1** (D5.b: E1 needs the D3 entity tables; E1 itself
  creates only `peers`/`subscriptions`/`catalog_access_log` as `4.sqm`). E
  thereby loses some parallelism with D — deliberately accepted, because E3
  integrates into the D3 editor screens anyway and the alternative (D3→E1)
  would have produced a block cycle D↔E.
- **F strictly last** (promotion only once the decisions are implemented
  "active" — lifecycle-adr rule).

Block-count rationale (v3 recommends 1 block/plan): the undertaking comprises
five domain-disjoint programs (extraction, security, configuration model,
desktop host, distribution) with real parallelization gains and different audit
lenses — a monoblock would devalue the audit cycles. 6 blocks / 16 chunks
(after D5.c) is the coarsest cut that still maps the dependency gates from the
diagram.

## 8. Test Strategy

Conventions: `~/.claude/snippets/test-first-patterns.md` (TDD for new
construction, pending for documented gaps, regression tests for every bug fix —
red before green).

1. **Characterization tests BEFORE extraction (A3, C2):** the existing
   behavior (runner configuration from prefs, proxy application) is fixed as a
   test BEFORE anything is moved/migrated; the same tests then run against the
   port adapters — that is the behavior-neutrality proof.
2. **Parity tests (D1, ongoing):** Room schema (exported JSON schemas in
   `app/schemas/`) ↔ SQLDelight (`verifyMigrations` + schema snapshot) —
   automated comparison of the enum vocabularies and CHECK constraints
   (model `OriginCheckConstraintParityTest`); mandatory test on every
   schema change (anchored in `adr-companion-history-parity`).
3. **Canonical snapshots (C1):** byte snapshots of the canonical serialization
   per entity type; hash determinism (same content ⇒ same hash, field
   reordering ⇒ same hash, value change ⇒ new hash).
4. **Migration tests (B2, C2):** Room `MigrationTestHelper` v11→v12 with a
   populated fixture; prefs fixtures for key and provider migration;
   idempotence (double run = no-op).
5. **E2E tests in the companion (D1, E1, E2):** extend the existing pattern
   (`CompanionE2ETest`, in-memory container, real Ktor server):
   dictation pipeline with fake runners; two-peer catalog scenarios
   (change/fork/offline/one-shot); truncated/malformed payloads against the
   catalog routes (`TruncatedResponseE2ETest` model).
6. **Review matrix (D3):** ADR-0013 verdict matrix (3 modes × needsClarification
   × message-blank) as a parameterized suite against the desktop caller.
7. **UI-near tests:** ViewModel tests following the existing companion pattern
   (`HistoryViewModelTest` …) for panel, review, explorer ViewModels;
   Android: Robolectric smoke for the new settings navigation. No
   screenshot diffing (no existing basis for it).
8. **Pending tests:** `GATEWAY` enum rejection (reserved, not selectable) as an
   active test; focus-free window as pending, until the D2 spike is
   decided (`pending: D2-focus-spike`).

## 9. Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | Focus-free Compose window under Windows not cleanly feasible | medium | Spike as the first D2 task; decided fallback (focus restoration) per F21; ADR draft documents both |
| R2 | C2/C3 migration damages real user data | high | Fixture-based MigrationTests, prefs-backup export before migration, superset mapping documented |
| R3 | Double-schema drift Room↔SQLDelight | medium | Parity tests as a mandatory gate (ADR-anchored), shared enum sources in `:shared` where possible |
| R4 | Kotlin ceiling 2.1.20 blocks a desired new dependency (audio encoder, DPAPI wrapper) | medium | Check dependency candidates per chunk BEFORE incorporation against the ceiling; preference: JNA in-house build instead of new libs (DPAPI, RegisterHotKey are small surfaces) |
| R5 | javax.sound device zoo (wrong default mixer, sample rates) | medium | Device-selection UI + persisted choice; WAV 16-bit/16-kHz downsample as a robust default (§10 Gap 2) |
| R6 | Scope size (5 programs in one plan) | high | Strict block gates (§7), audit per block (v3), understanding-check follow-ups before start (§10) |
| R7 | ADR-0017/0020 extension is assessed in review as supersede-mandatory | low | `adr-peer-catalog`/`adr-desktop-dictation-host` are explicitly cut as extension ADRs; conflict surfaces named in the draft |
| R8 | Credential consumption opens unwanted key exfiltration in the peer net | medium | Secret delivery only via an explicit, individually authorized call; never in the index; audit-log line per delivery (E1) |
| R9 | AI SDKs carry >Java-8 bytecode → `:shared-ai` with jvmTarget 1.8 not buildable (shared-ai §10 Gap 4) | low | Verify the assumption early in A2; if violated: real blocker → escalation (would force a `:app` jvmTarget bump, outside Block A) |
| R10 | `api` visibility of the SDKs in `:shared-ai` leaks SDK types more broadly than needed (shared-ai §5.2/§10 Gap 2) | low | Check in the A2/A3 compile whether the runner surface really carries SDK types; if not: downgrade to `implementation` (narrower is better) |
| R11 | Tray coexistence Compose `Tray` vs. AWT `TrayIcon` (one slot, two owners; peer-katalog §15 Gap 3) | low | E2 spike; fallback: AWT `TrayIcon` becomes the sole owner, Compose `Tray` drops out (spec D6) |
| R12 | Focus spike eats unbounded time | low | Time-box ~1 day (desktop-host §6.3); after that a binding switch to the `FocusRestorationPolicy` fallback (D4.3, no escalation) |
| R13 | Split package (`database.entity`/`preferences`/`core` across the module boundary) as a latent smell (shared-ai §10 Gap 1) | low | Deliberate trade-off for zero import diffs; documented in the ADR draft; later consolidation possible |

## 10. Information Gaps

1. ~~**Location of the concept documents**~~ — **closed 2026-07-19 (D4.1):**
   A1 checks the three documents in as `research/`, the plan folder is created
   at A1, the plan file stays in `~/.claude/plans/` until archival.
2. ~~**Audio format desktop**~~ — **closed 2026-07-19 (D4.2):** WAV
   16 kHz mono for v1; Opus/OGG only if needed later. Remaining task for the
   D1 agent: verify the providers' upload limits against ~2 MB/min and document
   it in the chunk (pure verification, no open decision).
3. ~~**Focus-free window**~~ — **decision-side closed 2026-07-19
   (D4.3):** the spike remains a technical D2 task; both outcomes are
   defined paths (no escalation case), documentation in
   `adr-desktop-panel-ui`.
4. ~~**Anthropic model list**~~ — **relocated 2026-07-20:** detailed in the spec
   `entitaetenmodell-android.md` §14 Gap 2 (free text + ModelRef curation,
   owner C3). Closed at the plan level.
5. ~~**`received_texts` existing data**~~ — **closed 2026-07-20
   (D5.g):** replacement instead of coexistence; `dispatch_state` companion
   table, backfill + DROP in migration `2.sqm` (desktop-host §3.4/§14 D1); the
   five existing tests are the behavior-neutrality proof (D1a acceptance).
6. ~~**Notification mechanics Windows tray**~~ — **decision-side closed
   2026-07-20:** AWT `SystemTray`/`TrayIcon.displayMessage`
   (peer-katalog §7.1/§14 D6); remaining fuzziness = coexistence spike with
   Compose `Tray` (peer-katalog §15 Gap 3, owner E2 — R11 here).

Detail gaps have lived, since the spec deep-dive, in the specs themselves
(shared-ai §10: Gaps 1–4 · secretstore §12: Gaps 2–4 · entitaetenmodell §14:
Gaps 2–5 · desktop-host §15: Gaps 2/3/5 · peer-katalog §15: Gaps 2–6) —
each with a named chunk owner and fallback. The spec gaps closed by D5
(entitaetenmodell Gap 1, desktop-host Gap 4, peer-katalog Gap 1) are added
there.

## 11. Iteration Log

### 2026-07-19 — Initial version

- **Trigger:** all 34 question-catalog decisions are in (team-lead message
  2026-07-19); order for an implementation plan.
- **Reasoning:** plan set up directly on the decision state;
  concept sketch (`tmp/desktop-concept/konzept-skizze.md`) brought in parallel
  to the decision state (F1 pivot to Compose, F12/F15/F18/F22).
- **What changed:** first version with 6 blocks / 15 chunks, 8 ADR-draft specs,
  sequencing DAG, test strategy, risks, gaps.

### 2026-07-19 — Understanding check incorporated, status implementation-ready

- **Trigger:** team-lead answer to all 7 follow-up questions (all confirm the
  plan assumptions).
- **Reasoning:** none of the answers changes structure, blocks, chunks, or
  sequencing — incorporation as a decision-log entry D4 + gap closure.
- **What changed:** frontmatter status → implementation-ready; new §3 D4
  (7 answers); A1 extended with research/ check-in + tmp cleanup; C3
  clarified (no keyboard profile switcher, D4.4); D2 fallback marked as an
  equivalent path (D4.3); §10 Gaps 1–3 closed.

### 2026-07-20 — Spec integration + cross-spec decisions (D5)

- **Trigger:** five implementer-ready specs (~300 KB, Opus-High research)
  lie under `research/`; team-lead order: integration + decision of the
  seven cross-spec points.
- **Reasoning:** SSoT rule — detail lives in the specs, the plan becomes the
  stub level with sharpened acceptance references; the seven points (a–g)
  decided by the criteria repo-doctrine fidelity, one schema owner,
  risk isolation, DRY, ceiling discipline.
- **What changed:** §5 fully switched to spec stubs; new §3 D5
  (a–g + migration number assignment 2/3/4.sqm); D1 split into D1a/D1b
  (16 chunks); D3 takes over the companion entity tables, E1→D3 edge;
  §7 DAG updated; §9 extended with R9–R13; §10 Gaps 4–6 closed/
  relocated + reference to the spec gaps; §12 supplemented with specs/runbook;
  Plan Conventions block updated. Specs received decision addenda
  (change-history/decision-log entries).

## 12. References

- **Specs (SSoT per block):** `docs/plans/2026-07-19 - desktop-companion-v1/
  research/{shared-ai-extraktion,secretstore,entitaetenmodell-android,
  desktop-host,peer-katalog}.md` · E2E: `reports/e2e-runbook.md` (16 cases) ·
  Orchestration: `chunks.json`, `desktop-companion-v1.state.md` (in the
  plan folder)
- Concept preliminary work (in the plan folder after A1):
  `research/bestandsaufnahme.md`, `research/konzept-skizze.md`,
  `research/fragenkatalog.md` (F1–F34, decided 2026-07-19)
- Existing ADRs (binding): `docs/decisions/0009` (queue), `0012`
  (conversation), `0013` (review), `0014` (history), `0015` (monorepo/ceiling),
  `0016` (wire SSoT), `0017` (roles/pairing), `0018` (TextInserter),
  `0020` (sync), `0023` (bind), `0024` (prompt types), `0025` (additive
  endpoints), `0027` (PC dictation)
- Key code: `shared/src/main/kotlin/net/devemperor/dictate/shared/protocol/`
  (ProtocolCodec/Dtos/Validations), `companion/src/main/kotlin/net/devemperor/
  dictate/companion/` (Container/Server/Domain/Data),
  `app/src/main/java/net/devemperor/dictate/ai/` (extraction source),
  `app/.../preferences/DictatePrefs.kt`, `app/.../database/`
- Conventions: `docs/DATABASE-PATTERNS.md`,
  `~/.claude/snippets/test-first-patterns.md`,
  `~/.claude/snippets/docs/lifecycle-adr.md`
- ADRs of this plan (promoted after F1, `docs/decisions/`): `0028` (`:shared-ai`
  module), `0029` (SecretStore), `0030` (entity model/v3), `0031`
  (desktop dictation host), `0032` (desktop panel/hotkey), `0033` (desktop review),
  `0034` (peer catalog), `0035` (companion history parity). They extend/revise
  `0012`/`0013`/`0014`/`0015`/`0017`/`0020`/`0027` (decision-history entries there).
  Draft history via `git log --follow` (in F1 promoted from the former plan
  folder `adrs/` to `docs/decisions/`).

## Plan Conventions (Compatibility block for implement-long-plan-v3)

- **Blocks/chunks:** A(A1,A2,A3) → B(B1,B2) · C(C1,C2,C3) ·
  D(D1a,D1b,D2,D3) · E(E1,E2,E3) → F(F1). 16 chunks.
- **depends_on:** A2→A1(no, independent); A3→A2; B1→A2; B2→{B1,A3};
  C1→(—); C2→{C1,B2,A3}; C3→C2; **D1a→A2; D1b→{D1a,A3}; D2→D1b;
  D3→{D2,C1}; E1→{C1,D3}**; E2→{E1,B1}; E3→E2; F1→all.
- **SQLDelight migration assignment (D5):** D1a=`2.sqm`, D3=`3.sqm`,
  E1=`4.sqm`.
- **Commit prefix:** `[<Block>.<Chunk>] <Title> (desktop-companion-v1)`.
- **Audit-lens hint:** Block C additionally with a migration/data-loss
  lens; Block E additionally with a security lens (credential paths).
- **Custom trigger:** every schema change in D/E triggers the parity-test
  suite; every new wire DTO triggers Konform rejection tests.
- **E2E strategy:** §8 points 5–6; manual Windows sign-off in F1.
