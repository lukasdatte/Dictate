# Block 2: Theme B — Recording-Drive Cutover + D2-pre Gate

> **Logbook for Block 2.** Implementation/Audit-Agents document here.
> Orchestrator maintains the state-file status table — agents do not.

**Phase:** Theme B — recording-drive (the live cutover) + the D2-pre verification GATE
**Implementation-Chunks:** C3-B1, C4-B2, C5-B3, C6-D2pre, C7-B3
**Workflow:** Iter-10 5-step (combined-step pattern — SendMessage/resume unavailable; orchestrator splits 2 commits/chunk). **mid-chunk-triage ARMED for C3/C4/C5** (architecture-conflict / blocks-following-chunks markers likely on the recording-drive flip).
**Block-Start-Commit:** 17085ca
**Block-End-Commit:** ⏳

> **⚠ GATE (Epic §6.2, load-bearing):** C6-D2pre is a verification GATE.
> C7-B3 (legacy-call-site deletion) MUST NOT start until C6 signs off
> GREEN. All of Block B3 (Theme C, the next block) is also hard-gated on
> green C6. Until C6: the legacy `JobExecutor.start` path stays reachable
> behind `USE_LEGACY_RECORDING_DRIVE`. The new path must be **proven**,
> not assumed.

> **Cross-block forwarding notes in effect (from state-file Orchestrator
> Forwarding Notes — agents must honour):**
> - **FN-1:** AC-10 has **3** `JobExecutor.start` call-sites in
>   `DictateInputMethodService.java` (`:2236`, `:2897`, `:3053`), not the
>   2 the Epic §4-B3 names. C5 guards all three; C7 deletes all three;
>   C6 double-dispatch grep covers all three.
> - **FN-2:** notification-action strings `[Pause][Stopp][Senden]` likely
>   absent in `values/strings.xml` — C4 adds them (de/en, mirrors F-5).
> - **FN-3:** OQ-2 default — `USE_LEGACY_RECORDING_DRIVE` removed
>   immediately after C6 green (in C7), per D7.
> - **FN-4:** `StopRecordingAndSend` is now a **payload-less data
>   object**. C5 dispatches `StartRecording(target, audioFile,
>   preAllocatedId)` (IME `:2213` UUID) then payload-less
>   `StopRecordingAndSend()`. Supersedes Epic §4-B3 / §3 literal
>   `StopRecordingAndSend(realSessionId)` wording.
> - **F-7 (B1):** `StartRecording.sessionId` now has a
>   `require(isNotBlank())` fail-fast. C5 MUST mint a real non-blank UUID
>   (the IME's `preAllocatedId`) — passing `""` will crash.

---

## Issue Index (Orchestrator-Maintained)

**Severity counts:** Critical: 0 · Important: 0 · Nice-to-have: 0 · Postponed: 0

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| — | — | — | — | — | — |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|
| — | — | — |

---

## Mandatory Format Reminder for All Agents

Shared directives: `~/.claude/skills/implement-long-plan-v2/prompts/agent-prompts.md`.
Each agent documents: What was done · Plan deviations (table) · Issues
(table, severity + 5-status) · Overlooked points. 5-status: `open` /
`delegated-to-orchestrator` / `postponed` / `fixed` / `closed`.

---

## Implementation Logs

### Chunk C3-B1 — real PipelineRunnerSubsystemAdapter (JobExecutor-backed)

**Agent-IDs:** `B2-C3-B1-IMPL` (fresh, combined Steps 1-5).
**Status:** ✅ complete · **Risk:** HIGH (R-1 JobRequest field-by-field fidelity)
**Implementation-Commit (Commit 1):** ⏳ (orchestrator) · **Test-Commit (Commit 2):** ⏳ (orchestrator)

**What was done:** Replaced the no-op `PipelineServiceStubSubsystems.pipelineRunner`
stub with a real production `PipelineRunnerSubsystemAdapter` — a **thin
delegation to `JobExecutor.INSTANCE`** (OQ-1 thin-delegation; no
`PipelineOrchestrator` rewrite). `submit`/`submitReprocess` build a
`JobRequest.TranscriptionPipeline` via an injected `PipelineConfigResolver`
seam and call `JobExecutor.start`; `cancel`→`JobExecutor.cancel`;
`isRunning`/`activeJobCount`→`ActiveJobRegistry`. Wired into
`DictatePipelineService.onCreate` Step 4 (replaced the `:419`
stub line); `pipelineRunner` stub demoted to `@Deprecated` test-only
(mirrors `sessionRepo`/`audioFileFactory`).

**OQ-1 verdict:** thin delegation is **feasible — NO architecture-conflict**.
`JobExecutor.start(context, request)` + `cancel(sessionId)` and
`ActiveJobRegistry.isActive`/`.state.value.size` give the adapter a clean
submit/cancel/isRunning surface. Adopted the Epic §7 OQ-1 path; no escalation.

**R-1 verdict:** reprocess path is mapped **1:1** (table below, all asserted
field-by-field in tests). Fresh-recording path: ~8 IME-runtime-only fields are
**NOT on the orchestrator path until C5** — the `DefaultPipelineConfigResolver`
**throws** for them rather than silently defaulting (surfacing beats guessing;
the throw is caught by the orchestrator's `runEffect`→`EffectFailure` wrap, so
it fails loud, never silent-wrong). The legacy IME path is untouched and stays
authoritative for fresh recordings (Epic §6.2). Fresh-field delegation flagged
as `IMPL-1` (delegated to C5, the prescribed owner per Epic §4 B1/B3).

**R-1 JobRequest field-by-field fidelity table (mandatory evidence):**

*Fresh recording — IME `DictateInputMethodService.java:2214-2230`:*

| IME field (line) | Adapter source | Status |
|---|---|---|
| `preAllocatedId` (:2215) | submit `sessionId` (from `Effect.SubmitPipeline`, IME `:2213` UUID via C5) | ✅ on-path |
| `audioFilePath` (:2218) | submit `audioFile.absolutePath` | ✅ on-path |
| `kind=RECORDING` (:2217) | constant in resolver | ✅ trivial |
| `recordingsDir` (:2223) | `File(filesDir,"recordings")` | ✅ on-path |
| `origin=KEYBOARD` (:2226) | constant | ✅ trivial |
| `reuseSessionId=null` (:2224) | constant (fresh) | ✅ trivial |
| `totalSteps` (:2187-2189) | IME: 1 + autoFormatting + promptQueue | ❌ **C5** (AutoFormattingService + PromptQueueManager are IME-runtime) |
| `language` (:2198-2201) | IME: `LanguageController.getEffectiveLanguage()` | ❌ **C5** (LanguageController IME-runtime; D-13 removes it) |
| `stylePrompt` (:2202) | IME: `promptService.resolveWhisperStylePrompt(...)` | ❌ **C5** (depends on resolved language) |
| `queuedPromptIds` (:2221) | IME: `promptQueueManager.getQueuedIds()` | ❌ **C5** (PromptQueueManager IME-runtime) |
| `targetAppPackage` (:2222) | IME: `EditorInfo.packageName` | ❌ **C5** (EditorInfo is IME-view runtime) |
| `livePrompt` (:2227) | IME instance flag `livePrompt` | ❌ **C5** (IME instance state) |
| `autoSwitchKeyboard` (:2228) | IME instance flag `autoSwitchKeyboard` | ❌ **C5** (IME instance state) |
| `showResendButton` (:2229) | IME: prefs `LastFileName.exists() && ResendButton` | ❌ **C5** (resolvable but threaded with the above; not split to avoid partial-config) |

*Reprocess staging — IME `DictateInputMethodService.java:3038-3051` (fully on-path, asserted 1:1):*

| IME field (line) | Adapter source | Status |
|---|---|---|
| `targetSessionId` (:3039) | `submitReprocess` `sessionId` | ✅ 1:1 |
| `totalSteps` (:3034-3036) | `1 + queue.size` (AutoFormatting +1 = `IMPL-2` delegated) | ⚠ near-1:1 |
| `kind=REPROCESS_STAGING` (:3041) | constant | ✅ 1:1 |
| `audioFilePath` (:3042) | `audioFile?.absolutePath` (F-19 null = DB-lookup) | ✅ 1:1 |
| `language` (:3043) | `submitReprocess` `language` arg | ✅ 1:1 |
| `modelOverride` (:3044) | `null` (staging-FSM does not carry it on the new path) | ⚠ `IMPL-2` |
| `queuedPromptIds` (:3045) | `submitReprocess` `queue` arg | ✅ 1:1 |
| `targetAppPackage` (:3046) | `null` (IME-view runtime) | ⚠ `IMPL-2` |
| `recordingsDir` (:3047) | `File(filesDir,"recordings")` | ✅ 1:1 |
| `reuseSessionId` (:3048) | `sessionId` | ✅ 1:1 |
| `stylePrompt` (:3049) | `null` (matches IME — reprocess passes null) | ✅ 1:1 |
| `origin=KEYBOARD` (:3050) | constant | ✅ 1:1 |

**Files created/modified (production, Commit 1):**
- `app/src/main/java/net/devemperor/dictate/core/PipelineRunnerSubsystemAdapter.kt` (NEW — adapter + `PipelineConfigResolver` seam + `DefaultPipelineConfigResolver`)
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` (field decl + onCreate Step 4 wiring, replaced `:419`)
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt` (demote `pipelineRunner` `@Deprecated` + header KDoc refresh)

**Files in chunk-scope:** all 3 above are named in the chunk spec/Epic §4 B1.
**Files outside chunk-scope (drift):** none.

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact | Inline-fixed? |
|-----------|---------------|--------------|-----|--------|----------------|
| Config-resolver seam instead of in-adapter `JobRequest` construction | Epic §4 B1 ("adapter's `submit` builds a `JobRequest.TranscriptionPipeline` mirroring IME `:2214-2236`") | Adapter delegates `JobRequest` construction to an injected `PipelineConfigResolver`; C3 ships `DefaultPipelineConfigResolver` (reprocess 1:1, fresh throws) | The new path's `Effect.SubmitPipeline` carries only `sessionId`+`audioFile`; the ~8 fresh fields are IME-runtime sources not on the orchestrator path until C5. Inventing defaults = the exact R-1 silent-data-loss the Epic forbids. Seam mirrors the established `emitAction:(Action)->Unit` provider pattern and gives C5 one typed insertion point. | C5 (IME-trigger flip) MUST inject an IME-faithful `PipelineConfigResolver` for fresh recordings. Until then fresh new-path submit fails loud (guarded; legacy IME path authoritative). C4/C7 unaffected (submit-direction + reprocess work today). | inline-fixed (mid-size, solution clear from Epic §4 B1/B3 + R-1 directive) → marker `plan-deviation-resolved` |

**Issues (Steps 1-3 — IMPL / PLAN-FIX / CODE-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-1 | Important | Fresh-recording `JobRequest` IME-runtime-only fields (`totalSteps`, `language`, `stylePrompt`, `queuedPromptIds`, `targetAppPackage`, `livePrompt`, `autoSwitchKeyboard`, `showResendButton`) are NOT on the orchestrator path; `DefaultPipelineConfigResolver.resolveFresh` throws to surface this. C5 must inject an IME-faithful resolver. `PipelineRunnerSubsystemAdapter.kt` resolveFresh + table above. | delegated-to-orchestrator | Prescribed owner is C5 (Epic §4 B1/B3 "this block is submit-direction only … C5 threads the IME trigger"). NOT an architecture-conflict — the path simply does not exist yet by design. Marker `plan-deviation-resolved`. |
| IMPL-2 | Nice-to-have | Reprocess `modelOverride`/`targetAppPackage` defaulted to `null` and AutoFormatting `+1` step omitted (IME `:3035`/`:3044`/`:3046`) — these are IME-runtime sources not on the staging-FSM path. Minor vs the fresh path (reprocess re-uses an existing session). `PipelineRunnerSubsystemAdapter.kt` resolveReprocess. | delegated-to-orchestrator | Same C5 ownership; lower severity (reprocess operates on an already-persisted session, model/targetApp less load-bearing than fresh language/prompts). |

**Test-Files created (Step 4-5 — Commit 2):**
- `app/src/test/java/net/devemperor/dictate/core/PipelineRunnerSubsystemAdapterTest.kt` (NEW, 7 tests)

**Test-Run-Result:** ✅ 7/7 pass. Full suite: **971 tests, 0 failures, 0 errors**
(≥946 baseline — AC-9 regression invariant holds, no cross-chunk regression).
`./gradlew assembleDebug` green.

Test → AC mapping:
| Test | Asserts |
|---|---|
| `resolveReprocess maps every JobRequest field 1-to-1...` | R-1 reprocess fidelity table (field-by-field) |
| `resolveReprocess passes a null audio path through...` | F-19 nullable contract |
| `resolveFresh throws rather than silently defaulting...` | R-1 fresh-guard (surfacing > silent) |
| `submitReprocess starts JobExecutor with the resolved JobRequest` | thin delegation → `JobExecutor.start` |
| `isRunning and activeJobCount reflect ActiveJobRegistry` | AC-1 registry reflection |
| `cancel delegates to JobExecutor cancel` | `cancel`→cooperative token |
| `binder TriggerPipeline reaches the real adapter and the R-1 guard surfaces...` | AC-1: production wiring reaches real adapter (NOT stub), no silent `JobExecutor.start` |

**Issues (Steps 4-5 — IMPL-TEST / IMPL-TEST-FIX):**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| — | — | none | — | — |

#### Code-Bugs Found While Writing/Reviewing Tests *(only if any)*

None. (The one Step-4 test failure was a **test-fake design issue**, not a
production bug — see below.)

| File:Line | Bug-Symptom | Root-Cause | Fix (vorher → nachher) | Recherche |
|-----------|-------------|-----------|------------------------|-----------|
| `PipelineRunnerSubsystemAdapterTest.kt` cancellableRunner (test-only) | `cancel delegates...` test flaked first run | `JobExecutor.cancel` ALSO `Thread.interrupt()`s the runner thread (last-resort fallback, `JobExecutor.kt:186`); the fake's `Thread.sleep` threw `InterruptedException` and exited the spin before re-checking `token.isCancelled` | wrapped the fake's `Thread.sleep` in `try/catch(InterruptedException)` so it keeps polling the cooperative token (the actual contract surface) | `JobExecutor.kt:170-187` `cancel()` doc — confirms token-flip + interrupt are both intentional; production `cancel` delegation was always correct |

**Mid-Chunk-Triage** *(only if Critical-blocker — ARMED for this chunk)*:

Not triggered. OQ-1 resolved in-chunk (thin delegation feasible, no
architecture-conflict). IMPL-1 is an Important `delegated-to-orchestrator`
with prescribed owner C5 — explicitly NOT a `architecture-conflict` /
`blocks-following-chunks` marker (C4-B2 notification + reprocess work today;
only the *fresh* new-path submit is C5-gated, which is C5's own scope).

| Triggering Issue | Step | Research Topic | Repair Agent-ID | Wave-Commit | Outcome |
|------------------|------|----------------|------------------|-------------|---------|
| — | — | — | — | — | — |

**Overlooked / Known Gaps:**
- Fresh-recording `PipelineConfigResolver` is the C5 insertion point (IMPL-1) —
  by design, not a gap to fix here.
- `LocalBinder` accessor for the adapter not added (no consumer yet; the
  field is held for C5 to reach without reconstruction).
- The binder integration test asserts the R-1 guard surfaces (registry stays
  empty) rather than asserting a successful fresh submit — a successful
  fresh-path submit is only possible post-C5 (when the IME-faithful resolver
  is injected); a green fresh-submit assertion belongs in C5's tests.
- Reprocess `modelOverride`/`targetAppPackage`/AutoFormatting-step (IMPL-2)
  delegated to C5 — low-severity, reprocess operates on a persisted session.

---

### Chunk C4-B2 — real PipelineNotificationCoordinator + PipelineActionRouter

**Agent-IDs:** `B2-C4-B2-IMPL` · **Status:** ⏳ pending · **Risk:** HIGH (R-2 FGS crash)
(subsections filled when chunk runs — same structure as C3-B1)

---

### Chunk C5-B3 — IME recording-trigger flip (guarded fallback)

**Agent-IDs:** `B2-C5-B3-IMPL` · **Status:** ⏳ pending · **Risk:** HIGHEST (R-1/R-4)
(subsections filled when chunk runs)

---

### Chunk C6-D2pre — VERIFICATION GATE (authorises C7 + Theme C)

**Agent-IDs:** `B2-C6-D2pre-IMPL` · **Status:** ⏳ pending · **Risk:** Gate
**GATE OUTPUT:** green → orchestrator authorises C7 + Block B3; red → mid-chunk-triage, NO deletion.
(subsections filled when chunk runs)

---

### Chunk C7-B3 — legacy call-site deletion (GATED on green C6)

**Agent-IDs:** `B2-C7-B3-IMPL` · **Status:** ⏳ blocked-on-C6 · **Risk:** Med (pure delete of proven-dead code)
(subsections filled when chunk runs — separately committed for git-revert isolation, Epic §6.2)

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ · **Pre-Validate Commit:** ⏳ · **Validate-Pass Commit:** ⏳

| Topic | Agent-ID | Status | Output File | Findings |
|-------|----------|--------|-------------|----------|
| plan-and-api | `B2-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B2.md` | — |
| convention | `B2-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B2.md` | — |
| logic | `B2-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B2.md` | — |
| test | `B2-AUDIT-TEST` | ⏳ | `./reports/audit-test-B2.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B2-VAL-SANITY` · **Output:** `./reports/validated-findings-B2.md`

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
- **C6-D2pre gate GREEN (authorises C7 + Theme C):** ⏳
- **Block-Validate converged:** ⏳
- **AUDIT-TEST: coverage + no cross-chunk regressions:** ⏳
- **Build green at block-end:** ⏳
- **Issue index reconciled:** ⏳
- **Cross-block-API consumer info forwarded to B3:** ⏳

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
