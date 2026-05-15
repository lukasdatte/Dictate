# Block 1: Theme A — State-Shape (non-destructive foundation)

> **This file is the logbook for Block 1.** Implementation-Agents and
> Audit-Agents document their work here. The orchestrator maintains the
> status table in the main state file
> (`../dictate-cutover-completion.state.md`) — agents do **not** write to
> the state file.

**Phase:** Theme A — State-Shape (F-12/F-13/F-10/F-15; non-destructive, additive)
**Implementation-Chunks:** C1-A1, C2-A2
**Workflow:** Iter-10 — 5-step chunks (IMPL → IMPL-PLAN-FIX → IMPL-CODE-FIX → [Commit 1] → IMPL-TEST → IMPL-TEST-FIX → [Commit 2]) → Block-Validate (4-topic audit + repair-sub-phase). SendMessage/resume unavailable → combined-step pattern: one IMPL agent runs all 5 steps, orchestrator splits into 2 commits.
**Block-Start-Commit:** 58bb9a1
**Block-End-Commit:** ⏳ (set by orchestrator at block completion)

---

## Issue Index (Orchestrator-Maintained)

**Severity counts:** Critical: 0 · Important: 3 · Nice-to-have: 0 · Postponed: 0

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| IMPL-PLAN-FIX-1 | B1-C1-A1-IMPL | Important | delegated-to-orchestrator | Dev-2: SendStaging keeps existing →Preparing edge instead of literal `copy(isStarting=true)` (literal would break runner handshake); guard satisfied via isStarting→null branch. Block-Validate (AUDIT-PLAN-AND-API/LOGIC) must confirm this reading vs Epic §4-A1 + Spec 1 §3/§15.2 | step-2-plan-fix |
| IMPL-PLAN-FIX-1 (C2-A2) | B1-C2-A2-IMPL | Important | delegated-to-orchestrator | Dev-1: `sessionId` added to `RecordingState.Preparing/Active/Paused` — Spec 1 §15.2/§3 show these without it; resolved per Epic §4 Block A2's explicit authorisation. FSM graph unchanged (payload-only). Block-Validate (AUDIT-PLAN-AND-API/LOGIC) must confirm spec-faithfulness vs §15.2 | step-2-plan-fix |
| IMPL-PLAN-FIX-2 (C2-A2) | B1-C2-A2-IMPL | Important | delegated-to-orchestrator | Dev-2: `StopRecordingAndSend` payload removed (now `data object`) — A2-vs-B3 plan-internal contract inconsistency; resolved per A2's authoritative seam (id flows via `StartRecording`). Cross-block: B3 must dispatch `StartRecording(…,preAllocatedId)` + payload-less `StopRecordingAndSend`. Orchestrator forward to B3. | step-2-plan-fix |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|
| — | — | — |

---

## Mandatory Format Reminder for All Agents

Shared sub-agent directives live in
`~/.claude/skills/implement-long-plan-v2/prompts/agent-prompts.md` — read it
before starting. Each agent documents: **What was done**, **Plan deviations**
(table), **Issues** (table with severity + 5-status), **Overlooked points**.

### Deviation Format

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Inline-fixed? |
|-----------|---------------|--------------|-----|------------------------|----------------|

### Issue Format

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|

5-status: `open` / `delegated-to-orchestrator` / `postponed` / `fixed` / `closed`.

---

## Implementation Logs

### Chunk C1-A1 — State-shape: F-12 isStarting + F-13 Running counters

**Agent-IDs:** Step 1 `B1-C1-A1-IMPL` (fresh, runs combined Steps 1-5) →
orchestrator splits Commit 1 (prod, after Step 3) + Commit 2 (tests, after Step 5).

**Status:** ⏳ in_progress
**Chunks file:** `../dictate-cutover-completion.chunks.json` Chunk C1-A1-state-shape-counters
**Implementation-Commit (Commit 1, production):** ⏳
**Test-Commit (Commit 2, tests):** ⏳

**What was done:** F-12 — added `PipelineUiState.ReprocessStaging.isStarting:
Boolean = false` and wired the `SendStaging` reducer arm with a
double-click guard (`isStarting == true` → reducer returns `null`, so the
reprocess job submits exactly once). F-13 — added
`PipelineUiState.Running.completedSteps:Int=0`, `totalSteps:Int=0`,
`startedAtMs:Long=0L`, `elapsedMs:Long=0L`; wired `StartPipeline`
(stamps `totalSteps` from payload + `startedAtMs`/`elapsedMs` from
`ctx.now`), `StepStarted` (restamps `elapsedMs`), `StepCompleted`
(increments `completedSteps` + restamps `elapsedMs`). Replaced the
B4-resolver placeholder in `resolveRecordButtonTextPipeline`
(`0, 0, …, 0L` → real `Running` fields). All additive defaulted fields —
source-compatible; `assembleDebug` + full 959-test suite green.

**Files created/modified (production, Commit 1):**

- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt` — added
  `isStarting` to `ReprocessStaging`; added `completedSteps`/`totalSteps`/
  `startedAtMs`/`elapsedMs` to `Running` (all defaulted) + KDoc.
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`
  — wired `SendStaging` guard; wired `StartPipeline`/`StepStarted`/
  `StepCompleted` counter arms; added private `elapsedSince()` helper.
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
  — replaced B4 placeholder with real `Running` field reads + updated KDoc.

**Files outside chunk-scope (drift):** none. (`startedAtMs` is an extra
`Running` field not literally enumerated in the Epic F-13 list — see
Dev-3 — but it lives in the same in-scope `Running` data class; not file
drift.)

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact | Inline-fixed? |
|-----------|---------------|--------------|-----|--------|----------------|
| Dev-1: `StepStarted` does **not** set `totalSteps` | Epic §4 Block A1 (`dictate-cutover-completion.md:316-317`) "`StartPipeline`/`StepStarted` sets `totalSteps`" | Only `StartPipeline` sets `totalSteps` (from its payload). `StepStarted` restamps `elapsedMs` only. | `Action.PipelineAction.StepStarted(sessionId, stepName)` carries **no** `totalSteps` field — it is structurally impossible for the `StepStarted` arm to set a total it does not receive. `StartPipeline.totalSteps` is the sole authoritative source. | None — no downstream chunk relies on `StepStarted` mutating `totalSteps`; the live label reads `totalSteps` off `Running` which `StartPipeline` already populated. | inline-fixed (most plan-compatible reading) |
| Dev-2: `SendStaging` first tap transitions `ReprocessStaging → Preparing` (existing contract) rather than `copy(isStarting=true)` staying in `ReprocessStaging` | Epic §4 Block A1 (`dictate-cutover-completion.md:314-315`) `SendStaging` arm pseudo-code `if (state.isStarting) null else copy(isStarting=true)` | First `SendStaging` keeps the existing `→ Preparing` + `SubmitReprocess` transition; the `isStarting` guard returns `null` when `ReprocessStaging.isStarting == true`. | Following the pseudo-code literally (`copy(isStarting=true)`, stay in `ReprocessStaging`) would break the runner handshake: `StartPipeline` only transitions from `PipelineUiState.Preparing`, and the existing `SendStaging transitions ReprocessStaging to Preparing` test + documented FSM contract require the `→ Preparing` edge. AC-4's testable guard ("SendStaging-while-starting → no-op") is satisfied by the `isStarting`-true → `null` branch. | The guard is reachable when the UI/resolver optimistically marks `isStarting` before the FSM flips, or in a same-tick re-dispatch. No downstream chunk depends on `SendStaging` leaving the FSM in `ReprocessStaging`. | inline-fixed + flagged `plan-deviation-resolved` (IMPL-PLAN-FIX-1) |
| Dev-3: added `Running.startedAtMs:Long=0L` (not in the literal F-13 field list) | Epic §4 Block A1 (`dictate-cutover-completion.md:313`) F-13 lists `completedSteps/totalSteps/elapsedMs` | Added a 4th defaulted field `startedAtMs` as the elapsed-timer origin. | The Epic says "`elapsedMs` derived from `ReducerContext.now`". A reducer is pure and has no memory across calls, so deriving a *running* elapsed value requires storing the baseline timestamp in-state. `elapsedMs` alone cannot be incrementally maintained without `startedAtMs`. Additive defaulted field — source-compatible. | B2 (notification) / B4 (record-button label) read `elapsedMs`, not `startedAtMs`; `startedAtMs` is an internal reducer-baseline. No consumer-API change. | inline-fixed (small, locally decidable) |

**Issues (Step 1 — IMPL):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | none — implementation matched plan intent; deviations handled per D22 |

**Issues (Step 2 — IMPL-PLAN-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-PLAN-FIX-1 | Important | Dev-2: `SendStaging` arm keeps existing `→ Preparing` transition instead of literal Epic pseudo-code `copy(isStarting=true)`; resolved in favour of preserving the runner-handshake FSM contract + existing test. Block-validate should confirm this call. | delegated-to-orchestrator | marker `plan-deviation-resolved` — mid-size deviation, solution clear from plan knowledge (runner handshake requires `Preparing`); implemented + flagged per D22 |

Plan-requirement check: F-12 `isStarting` field ✓ · F-12 `SendStaging`
guard △ (Dev-2, resolved) · F-13 `completedSteps` ✓ · F-13 `totalSteps`
✓ (StartPipeline) / △ (StepStarted, Dev-1) · F-13 `elapsedMs` ✓ ·
B4-placeholder replaced ✓. Files in plan-prescribed scope:
`DictateUiState.kt`, `PipelineModule.kt`, `TextResolvers.kt` (all three
named in Epic §4 Block A1 "Files"). Files outside plan-scope (drift): none.

**Issues (Step 3 — IMPL-CODE-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | none delegated |

Knowledge skills consulted: `knowledge-reference` (plugin-system /
versioned-envelope — neither applies to a pure-Kotlin reducer field
addition; no realignment needed). No `knowledge-kotlin`/`-android`
exists — checked against surrounding reducer-module conventions
instead. Aspects: DRY ✓ (the `now - startedAtMs` expression hit 2 sites
→ extracted to private `elapsedSince()` helper, which also folds in the
non-negative-floor invariant; 3-use rule satisfied because the floor
logic is itself a reused concern). Naming ✓ (matches Epic spec + camelCase
surroundings). Type-safety ✓ (tight `Int`/`Long`/`Boolean`, defaulted).
Comments ✓ (KDoc explains the WHY: double-click guard, pure-reducer
time-source invariant). Inline fix applied:
`PipelineModule.elapsedSince(startedAtMs, now)` private helper +
`coerceAtLeast(0L)` defensive floor (file `PipelineModule.kt`).
Files modified this step: `PipelineModule.kt`. Drift: none.

**Test-Files created (Step 4 — Commit 2):** none created — extended two
existing suites:

- `app/src/test/java/net/devemperor/dictate/state/PipelineModuleTest.kt`
  — 24 → 37 `@Test` (+13 net new: F-12 guard ×4, F-13 counters/floor ×9)
  and 1 existing test updated in place (`StepStarted … restamps elapsedMs
  (F-13)` — see Code-Bugs note below; it is a plan-driven contract
  update, not a code-bug).
- `app/src/test/java/net/devemperor/dictate/state/layout/ActionResolversTest.kt`
  — +2 new tests (F-13 resolver renders real `Running` counters, not the
  old `0,0,0L` placeholder; autoEnter-false label variant).

Plan-AC mapping (AC-4):

| AC-4 clause | Test |
|-------------|------|
| `ReprocessStaging` has `isStarting: Boolean` | `F-12 ReprocessStaging defaults isStarting to false` |
| SendStaging double-click guard (`!isStarting`) | `F-12 SendStaging while isStarting true is a no-op`, `… false still submits once`, `… mismatched sessionId rejected` |
| `Running` has `completedSteps/totalSteps/elapsedMs` | `F-13 Running defaults all counters to zero` |
| `StepCompleted` increments `completedSteps` | `F-13 StepCompleted increments completedSteps and restamps elapsedMs` (+ mismatch / non-Running no-op) |
| `StartPipeline` sets `totalSteps`; `elapsedMs` from `ReducerContext.now` | `F-13 StartPipeline stamps totalSteps + startedAtMs from ctx-now`, `… StepStarted restamps elapsedMs …`, `… elapsedMs floored at zero …` |
| `Running` counter labels render real values not placeholders | `F-13 resolveRecordButtonTextPipeline renders real Running counters not placeholders` (+ autoEnter-false variant) |

Helper-Decisions: reused `testLayoutStrings()` + `stubAudioFile()` from
`LayoutCatalogTest.kt` (already committed); reused the existing
`PipelineModuleTest.ctx(now=5_000L)` fixture. No new helpers (K-1/K-4
satisfied — pure reducers, no fakes/Robolectric needed).

**Test-Run-Result (Step 4):** `./gradlew testDebugUnitTest` — 959 pass /
0 fail. `PipelineModuleTest` 35→37, `ActionResolversTest` 31.

**Issues (Step 4 — IMPL-TEST):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | no production code-bugs surfaced (see Code-Bugs note: the one red existing test was a plan-driven contract change, not a bug) |

#### Code-Bugs Found While Writing Tests *(Step 4 — only if any)*

No production code-bugs. One **existing test** went red and was updated
to reflect the plan-mandated F-13 behavior change (documented here for
the audit trail — this is a test-update, not a code-bug fix):

| File:Line | Symptom | Root-Cause | Fix (before → after) | Research |
|-----------|---------|-----------|----------------------|----------|
| `PipelineModuleTest.kt:86` (pre-edit) `StepStarted in Running emits UpdateNotification but keeps state` | `assertEquals(state, result.nextState)` failed: `elapsedMs` changed `0 → 5000` | The test encoded the *old* contract ("StepStarted is a pure no-op state pass-through"). F-13 (Epic §4 Block A1: "`elapsedMs` derived from `ReducerContext.now`") makes `StepStarted` a progress tick that restamps `elapsedMs` — the old assertion is now stale by design, not a regression. | Renamed to `StepStarted in Running emits UpdateNotification and restamps elapsedMs (F-13)`; assertion → `assertEquals(state.copy(elapsedMs = 3_500L), next)` with `startedAtMs = 1_500L`, `ctx.now = 5_000L`. Side-effect assertion unchanged. | Epic plan §4 Block A1 (`dictate-cutover-completion.md:316-318`); existing reducer contract in `PipelineModule.kt`. |

**Test-Review-Result (Step 5):** all green; the 2 Step-5 branch-coverage
fills (`StepStarted` mismatch-sessionId rejected, `StepStarted`
outside-Running no-op) are included in the final
`PipelineModuleTest` = 37 `@Test` total. Coverage assessment (branch-level,
reasoned — project has no Jacoco wiring; `coverage_threshold_branches:
70`): every new/changed reducer branch is exercised — `StartPipeline`
(match/mismatch/non-Preparing), `StepStarted` (match/mismatch/non-Running),
`StepCompleted` (match/mismatch/Idle/Preparing), `SendStaging`
(sessionId-mismatch / isStarting-true / isStarting-false / non-staging),
`elapsedSince` (positive / floored-at-zero), resolver (real fields /
autoEnter-false). Estimated branch coverage of the touched lines: 100%
of new branches, well above the 70% threshold. Test quality: names
describe behavior; assertions concrete (state + side-effect counts);
no weak assertions; no mocks (K-1/K-4 — pure reducers).

**Issues (Step 5 — IMPL-TEST-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | none |

#### Code-Bugs Found During Test Self-Review *(Step 5 — only if any)*

| File:Line | Bug-Symptom | Root-Cause | Fix (vorher → nachher) | Recherche |
|-----------|-------------|-----------|------------------------|-----------|
| — | — | — | — | none — no code-bugs; only added 2 branch-coverage tests |

**Mid-Chunk-Triage** *(only if Critical-blocker)*: none — no
architecture-conflict / blocks-following-chunks issue.

**Overlooked / Known Gaps:**
- The `ReprocessStaging` staging-**duration** placeholder in
  `resolveRecordButtonTextStaging` (`TextResolvers.kt:~121` —
  `formatStagingLabel(0)`) is **intentionally left** — it depends on a
  staging audio-duration field NOT in F-12/F-13 scope (the chunk only
  adds `isStarting` to `ReprocessStaging`). Out of scope; flagged here
  so a later block owns it.
- `startedAtMs` is reducer-internal; no consumer reads it. If a future
  block wants a "pause-aware" elapsed timer, `startedAtMs` would need a
  pause-offset companion — not required for Phase 1.
- The legacy `core.PipelineUiState.Running` (separate class, has its own
  `completedSteps`/`totalSteps`) is untouched — it is legacy-retire
  surface owned by other blocks, not the new `state/` module.

---

### Chunk C2-A2 — F-10 sessionId source + F-15 language-aware strings

**Agent-IDs:** Step 1 `B1-C2-A2-IMPL` (fresh, combined Steps 1-5).

**Status:** ✅ implemented (combined 5-step, awaiting orchestrator 2-commit split)
**Chunks file:** `../dictate-cutover-completion.chunks.json` Chunk C2-A2-sessionid-langstrings
**Implementation-Commit (Commit 1):** ⏳ (orchestrator — production file list below)
**Test-Commit (Commit 2):** ⏳ (orchestrator — test file list below)

**What was done:**

- **F-10 — real sessionId source.** `RecordingState.Preparing/Active/Paused`
  gained a `sessionId: String` field (non-defaulted — a default would
  silently re-introduce a sentinel, defeating F-10). `RecordingAction.StartRecording`
  gained `sessionId: String`; the caller-minted UUID is carried into
  `Preparing` and propagated verbatim through every FSM transition
  (`MediaRecorderReady`, `PauseRecording`, `ResumeRecording`).
  `StopRecordingAndSend` became a `data object` (payload removed); its
  reducer arms (Active + Paused) now read `state.sessionId` for the
  `EmitPipelineTrigger` effect instead of `action.sessionId`. The two
  click resolvers (`resolveRecordAction`, `resolveOverlayRecordAction`)
  + the `LayoutCatalog` OVERLAY_SEND slot now mint a fresh UUID via a
  shared private `newSessionId()` helper instead of passing `""`. The
  empty-string sentinel is gone everywhere: `grep -rn 'sessionId = ""'
  app/src/main` → **ZERO** (also zero in `app/src/test`).
- **F-15 — language-aware dictateButtonText.** `LayoutStrings.dictateButtonText`
  signature changed from `() -> CharSequence` to
  `(effectiveLanguage: String) -> CharSequence`; `resolveRecordButtonText`
  feeds `state.language.effective` (the `LanguageModule` axis, read-only
  — no legacy writer added, D-13 untouched). The production wiring in
  `DictatePipelineService.buildLayoutStrings()` produces a
  language-suffixed label (`"Record (en)"`); `"system"`/empty renders
  the plain `"Record"`.
- FSM transition graph **unchanged** — only the `Preparing/Active/Paused`
  payload widened with `sessionId` (spec-faithful to Spec 1 §15.2; the
  §15.2/§3 reference predates F-10, see Dev-1). `assembleDebug` +
  `testDebugUnitTest` (964 pass / 0 fail) green.

**Files created/modified (production, Commit 1 — disjoint from test list):**

- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt` —
  `sessionId` field on `RecordingState.Preparing/Active/Paused` + KDoc.
- `app/src/main/java/net/devemperor/dictate/state/Action.kt` —
  `StartRecording.sessionId` field; `StopRecordingAndSend` → `data object`
  (payload removed) + KDoc.
- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt`
  — sessionId propagation on all transitions; `StopRecordingAndSend`
  arms read `state.sessionId`; updated `EmitPipelineTrigger` KDoc.
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`
  — stale `StopRecordingAndSend(sessionId)` comment corrected (no behavior).
- `app/src/main/java/net/devemperor/dictate/state/layout/ActionResolvers.kt`
  — `newSessionId()` helper; `StartRecording` call-sites mint a UUID;
  `StopRecordingAndSend` payload-less; KDoc table updated.
- `app/src/main/java/net/devemperor/dictate/state/layout/LayoutCatalog.kt`
  — OVERLAY_SEND actionResolver payload-less `StopRecordingAndSend`.
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
  — `dictateButtonText` signature `(String) -> CharSequence`;
  `resolveRecordButtonText` passes `state.language.effective` + KDoc.
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
  — `buildLayoutStrings()` `dictateButtonText` lambda is language-aware.

**Files in plan-prescribed scope:** all 8 above. Epic §4 Block A2 "Files"
names `state/Action.kt`, `state/RecordingState` payload (= `DictateUiState.kt`),
`state/modules/RecordingModule.kt`, `LayoutStrings`/record-button resolver
(= `TextResolvers.kt`). `ActionResolvers.kt` + `LayoutCatalog.kt` +
`PipelineModule.kt` + `DictatePipelineService.kt` are **drift** (call-sites
the contract change forced): `ActionResolvers.kt`/`LayoutCatalog.kt` are the
`StopRecordingAndSend`/`StartRecording` call-sites the F-10 sentinel removal
required; `DictatePipelineService.kt` is the F-15 `LayoutStrings` production
wiring; `PipelineModule.kt` is a one-line stale-comment correction. All four
are mechanically necessitated by the two plan-named contract changes — no
out-of-scope behavior added.

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Inline-fixed? |
|-----------|---------------|--------------|-----|------------------------|----------------|
| Dev-1: `RecordingState.Preparing/Active/Paused` gain a `sessionId` field; `Spec 1 §15.2`/`§3` reference impl shows these variants WITHOUT `sessionId` | Spec 1 §3 (`1-pipeline-service.reviewed.md:251-253`) + §15.2 reducer (`:6360-6429`) | Added `sessionId: String` to the 3 non-Idle `RecordingState` variants and threaded it on every transition | Spec 1 §3/§15.2 predate F-10; Epic §4 Block A2 (`dictate-cutover-completion.md:343-346`) **explicitly authorises** this: "`RecordingState.Active` currently carries `audioFile`+`useBluetooth`; adding `sessionId` here is the clean source". The FSM transition graph is unchanged — only the payload widens, so §15.2's documented transitions stay faithful. | B3 (recording-trigger cutover) must supply `sessionId` to `StartRecording` (it has the IME's `preAllocatedId` already). No PipelineModule/other-module API change — `EmitPipelineTrigger`/`TriggerPipeline` shapes unchanged. | inline-fixed + flagged `plan-deviation-resolved` (IMPL-PLAN-FIX-1) |
| Dev-2: `StopRecordingAndSend` is now a `data object` (no payload); Epic §4 Block B3 sketch says `dispatch(...StopRecordingAndSend(realSessionId))` | Epic §4 Block B3 (`dictate-cutover-completion.md:438-439`) | Removed the `sessionId` payload from `StopRecordingAndSend` | Epic §4 Block A2 (the chunk I own) prescribes the clean design: "thread into `StartRecording` → carried in `RecordingState` → read on `StopRecordingAndSend`". B3's `StopRecordingAndSend(realSessionId)` phrasing is a forward-reference sketch that A2's chosen approach refines — the id flows via `StartRecording`, not the stop action. Plan-internal inconsistency between Block A2 (authoritative design for this seam) and Block B3 (downstream sketch), resolved in favour of A2. | B3 implementer: dispatch `StartRecording(target, audioFile, preAllocatedId)` then `StopRecordingAndSend` (no arg). The IME's `preAllocatedId` (`DictateInputMethodService.java:2213`) flows in at `StartRecording`, not at stop. | inline-fixed + flagged `plan-deviation-resolved` (IMPL-PLAN-FIX-2) |
| Dev-3: existing tests across 13 files mechanically updated for the F-10/F-15 contract change | n/a (test-side contract follow-through) | ~70 `RecordingState.*`/`StartRecording` call-sites gained `sessionId = "sid-test"`; `StopRecordingAndSend(sessionId=…)` → `StopRecordingAndSend`; `testLayoutStrings().dictateButtonText` is now `(lang) -> …` | Required follow-through of two plan-named contract changes (not a code-bug — see Step-4 Code-Bugs note). The non-recording tests' intent is unchanged (the `sid-test` value is irrelevant to audio/overlay/viewmode assertions). | None — these are test-only mechanical edits. | inline-fixed (test-contract update) |

**Issues (Step 1 — IMPL):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | none — implementation matched plan intent; deviations handled per D22 |

**Issues (Step 2 — IMPL-PLAN-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-PLAN-FIX-1 | Important | Dev-1: `sessionId` added to `RecordingState.Preparing/Active/Paused` — Spec 1 §15.2/§3 show these without `sessionId`; resolved in favour of Epic §4 Block A2's explicit authorisation. FSM graph unchanged (payload-only widening). Block-Validate (AUDIT-PLAN-AND-API/LOGIC) should confirm spec-faithfulness vs §15.2. | delegated-to-orchestrator | marker `plan-deviation-resolved` — mid-size deviation, Epic §4 Block A2 explicitly prescribes "adding sessionId here is the clean source"; implemented + flagged per D22 |
| IMPL-PLAN-FIX-2 | Important | Dev-2: `StopRecordingAndSend` payload removed (now `data object`) — Epic §4 Block B3 sketch (`:438-439`) shows `StopRecordingAndSend(realSessionId)`. Plan-internal A2-vs-B3 inconsistency; resolved per A2's authoritative seam design (id flows via `StartRecording`). B3 implementer must dispatch `StartRecording(...,preAllocatedId)` + payload-less `StopRecordingAndSend`. | delegated-to-orchestrator | marker `plan-deviation-resolved` — cross-block API contract clarification; A2 owns this seam's design, B3 phrasing is a refinable forward-reference. Block-Validate + B3 must adopt this contract. |

Plan-requirement check: F-10 `StartRecording` carries real sessionId ✓ ·
F-10 sessionId in `RecordingState` (Preparing/Active/Paused) ✓ (Dev-1) ·
F-10 `StopRecordingAndSend` reads FSM sessionId ✓ · F-10 sentinel removed
(`grep` zero) ✓ · F-10 `Action.kt` KDoc updated ✓ · F-15
`dictateButtonText` reads `LanguageState.effective` ✓ · F-15 read-only,
no legacy writer ✓ (D-13 untouched) · F-15 label differs across
languages ✓. Files in plan-prescribed scope vs drift: see "Files" block
above (drift = forced call-sites + F-15 production wiring + 1 stale comment).

**Issues (Step 3 — IMPL-CODE-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | none delegated |

Knowledge skills consulted: `knowledge-reference` (plugin-system /
versioned-envelope — neither applies to a pure-Kotlin reducer payload
widening + a label-provider signature change). No `knowledge-kotlin`/
`-android` skill exists — grounded against surrounding state-module
conventions (mirrors C1-A1's approach). Aspects: DRY ✓ (the UUID-mint
hit 2 resolver call-sites → extracted private `newSessionId()` helper,
same rationale class as C1-A1's `elapsedSince()` — the B3-migration-seam
intent is a single reused concern). Naming ✓ (`sessionId` matches the
existing `PipelineUiState.*.sessionId` convention; `newSessionId()`
camelCase). Type-safety ✓ (non-nullable `sessionId: String`, R.15;
`StopRecordingAndSend` `data object` strictly tighter than the old
nullable-sentinel payload). Comments ✓ (KDoc captures the WHY: F-10
single-source rationale, spec-predates-F-10 note, B3-migration seam).
Inline fix applied: `ActionResolvers.newSessionId()` private helper +
cleaned an accidental dead `.let { _ -> }` in the
`DictatePipelineService` `dictateButtonText` lambda. Verified the legacy
`core.RecordingState` / `core.KeyboardUiController.dictateButtonTextProvider`
are **separate classes** (different package) — untouched, no drift into
legacy-retire surface. Files modified this step: `ActionResolvers.kt`,
`DictatePipelineService.kt`. Drift: none beyond the contract-forced set
already documented.

**Test-Files created (Step 4 — Commit 2):** none newly created — extended
existing suites + mechanically updated 13 contract-affected suites:

- `app/src/test/java/net/devemperor/dictate/state/RecordingModuleTest.kt`
  — rewrote the 2 `StopRecordingAndSend` tests as F-10 FSM-sessionId
  tests; added `F-10 sessionId minted at StartRecording survives the
  full FSM round-trip`; strengthened `StartRecording from Idle` to
  assert the carried sessionId. Net 24 → 26 `@Test`.
- `app/src/test/java/net/devemperor/dictate/state/layout/ActionResolversTest.kt`
  — added `F-10 resolveRecordAction mints a fresh sessionId` + `F-15
  resolveRecordButtonText label differs across two effective languages`;
  fixed the `dictateButtonText in Idle` test for the new signature. Net
  31 → 33 `@Test`.
- `app/src/test/java/net/devemperor/dictate/state/layout/LayoutCatalogTest.kt`
  — `testLayoutStrings()` helper `dictateButtonText` is now `(lang) ->
  "Dictate ($lang)"` (F-15 language-aware fixture; used by both
  ActionResolversTest + LayoutCatalogTest).
- Mechanical contract-update (no behavior change, `sessionId = "sid-test"`
  / payload-less `StopRecordingAndSend` / language-arg fixture) in:
  `AudioModuleTest.kt`, `ViewModeModuleTest.kt`, `OverlayModuleTest.kt`,
  `DictateUiStateTest.kt`, `ActionHierarchyTest.kt`,
  `render/overlay/OverlayBackendTest.kt`,
  `render/PromptVisibilityControllerTest.kt`,
  `render/RecordingAnimationControllerTest.kt`,
  `render/ImeViewBackendTest.kt`, `layout/VisibilityMatrixTest.kt`,
  `core/DictatePipelineServiceOverlayTransitionTest.kt`.

Plan-AC mapping (AC-4 F-10/F-15 part):

| AC-4 clause | Test |
|-------------|------|
| `StopRecordingAndSend` carries the same sessionId minted at `StartRecording` | `F-10 sessionId minted at StartRecording survives the full FSM round-trip`, `F-10 StopRecordingAndSend from Active uses the FSM sessionId not an action payload`, `F-10 StopRecordingAndSend from Paused uses the FSM sessionId`, `StartRecording from Idle … carried into the FSM` |
| no `sessionId = ""` literal anywhere (`grep`) | grep verification (zero in `app/src/main` AND `app/src/test`) + `F-10 resolveRecordAction mints a fresh sessionId on each StartRecording` (UUID-shaped, non-empty) |
| `dictateButtonText` differs across two `LanguageState.effective` values | `F-15 resolveRecordButtonText label differs across two effective languages` (asserts `"Dictate (en)"` ≠ `"Dictate (de)"`) |

Helper-Decisions: reused `testLayoutStrings()` + `stubAudioFile()` from
`LayoutCatalogTest.kt` (already committed; `dictateButtonText` fixture
field updated for F-15 — shared by 2 suites). Reused
`fakeModuleServices()` / `FixedAudioFileFactory` from existing
`testutil`. No new helpers (K-1/K-4 satisfied — pure reducers + existing
handwritten fakes; no Mockito/Robolectric).

**Test-Run-Result (Step 4):** `./gradlew testDebugUnitTest` — **964 pass /
0 fail / 0 error / 0 skipped** (up from 959; +5 net new tests).
`RecordingModuleTest` 26, `ActionResolversTest` 33. `./gradlew
assembleDebug` green.

**Issues (Step 4 — IMPL-TEST):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | no production code-bugs surfaced (see Code-Bugs note: all red tests were the expected plan-driven contract change, not bugs) |

#### Code-Bugs Found While Writing Tests *(Step 4)*

No production code-bugs. ~70 existing test call-sites + the 2
`StopRecordingAndSend` tests went red **by design** — the F-10/F-15
contract changes (sessionId now required on `RecordingState`/`StartRecording`;
`StopRecordingAndSend` payload removed; `dictateButtonText` signature)
are plan-mandated. Documented here for the audit trail as a
test-contract update, **not** a code-bug fix:

| File:Line | Symptom | Root-Cause | Fix (before → after) | Research |
|-----------|---------|-----------|----------------------|----------|
| 13 test files, ~70 sites | `No value passed for parameter 'sessionId'` / `Unresolved reference 'StopRecordingAndSend'` / `No value passed for parameter 'effectiveLanguage'` | Tests encoded the *pre-F-10/F-15* contract (no `sessionId` on FSM, payload on `StopRecordingAndSend`, no-arg `dictateButtonText`) | Mechanical: `RecordingState.*`/`StartRecording` ctors gain `sessionId = "sid-test"`; `StopRecordingAndSend(sessionId=…)` → `StopRecordingAndSend`; `testLayoutStrings().dictateButtonText` → `(lang) -> "Dictate ($lang)"`. Bracket-balanced script for the bulk; RecordingModuleTest/ActionResolversTest hand-edited with real F-10/F-15 assertions. | Epic §4 Block A2 (`dictate-cutover-completion.md:330-357`); Spec 1 §15.2; this chunk's production diff. |

**Test-Review-Result (Step 5):** all green; AC-4 F-10/F-15 fully mapped
(table above). Coverage assessment (branch-level, reasoned — project has
no Jacoco wiring, `coverage_threshold_branches: 70`): every new/changed
production branch is exercised — `StartRecording` (sessionId→Preparing),
`MediaRecorderReady`/`Pause`/`Resume` (sessionId propagation),
`StopRecordingAndSend` Active+Paused (reads `state.sessionId`),
`resolveRecordAction` Idle (mints UUID) / Active / Paused,
`resolveRecordButtonText` Idle (two language values). The full FSM
round-trip test covers the Preparing→Active→Paused→Active→Idle path
end-to-end. Estimated branch coverage of the touched lines: ~100% of
new branches, well above the 70% threshold. Test quality: names describe
behavior (F-10/F-15 prefixed); assertions concrete (id equality, label
inequality, UUID regex); no weak assertions; no mocks (K-1/K-4 — pure
reducers + existing handwritten fakes).

**Issues (Step 5 — IMPL-TEST-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | — | — | none |

#### Code-Bugs Found During Test Self-Review *(Step 5)*

| File:Line | Bug-Symptom | Root-Cause | Fix (vorher → nachher) | Recherche |
|-----------|-------------|-----------|------------------------|-----------|
| — | — | — | — | none — no code-bugs; coverage + quality already adequate |

**Mid-Chunk-Triage** *(only if Critical-blocker)*: none — no
architecture-conflict. Both deviations (Dev-1 spec-§15.2 payload
widening, Dev-2 A2-vs-B3 contract) are Epic-authorised / plan-internal
clarifications resolved per D22, flagged Important (not Critical) for
Block-Validate confirmation — they do not block following chunks (B3
adopts the documented `StartRecording`-carries-sessionId contract).

**Overlooked / Known Gaps:**
- The `ReprocessStaging` staging-**duration** placeholder in
  `resolveRecordButtonTextStaging` (`TextResolvers.kt` —
  `formatStagingLabel(0)`) is still intentionally left (C1-A1 already
  flagged it; out of F-10/F-15 scope).
- `DictatePipelineService.buildLayoutStrings().dictateButtonText` uses a
  simple `"Record ($lang)"` format as the new-render-path baseline. The
  legacy `MainButtonsController.updateRecordButtonText` path still owns
  the live label in Phase 1 (D-13 removes the legacy writer later); the
  exact production label string is a UI-polish concern a later
  Theme-C/D block can refine — F-15's testable contract ("label differs
  across two effective languages") is met.
- B3 contract dependency (Dev-2): B3 must dispatch
  `StartRecording(target, audioFile, preAllocatedId)` then payload-less
  `StopRecordingAndSend`. Flagged as IMPL-PLAN-FIX-2 so the orchestrator
  forwards the cross-block-API contract to B3.
- The legacy `core.RecordingState` / `core.RecordingStateController` /
  `core.KeyboardUiController` are untouched (separate `core`-package
  classes — legacy-retire surface owned by Theme-C, not the new
  `state/` module).

---

## Block-Validate (Phase 3.2)

**Status:** ⏳
**Pre-Validate Commit:** ⏳ (block's last chunk Commit 2)
**Validate-Pass Commit:** ⏳

### Audit-Topic Outputs

| Topic | Agent-ID | Status | Output File | Findings |
|-------|----------|--------|-------------|----------|
| plan-and-api | `B1-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B1.md` | — |
| convention | `B1-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B1.md` | — |
| logic | `B1-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B1.md` | — |
| test | `B1-AUDIT-TEST` | ⏳ | `./reports/audit-test-B1.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B1-VAL-SANITY` · **Output:** `./reports/validated-findings-B1.md`

| Issue-ID | Verdict | Severity | Routing |
|----------|---------|----------|---------|
| — | — | — | — |

### Mini-Triage + Repair-Wave(s)

(Per iteration, max 3 per D5 soft-cap.)

---

## Block Deviation Summary

| # | Plan Location | What changed | Why | Impact | Inline-fixed | Source-Agent | Source-Step |
|---|---------------|--------------|-----|--------|--------------|--------------|--------------|
| — | — | — | — | — | — | — | — |

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step, both commits):** ⏳
- **Block-Validate converged:** ⏳
- **AUDIT-TEST: coverage + no cross-chunk regressions:** ⏳
- **Build green at block-end:** ⏳
- **Issue index reconciled:** ⏳
- **Conventions section filled:** ⏳
- **Cross-block-API consumer info forwarded to B2:** ⏳

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
