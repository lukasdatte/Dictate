# Block 3: Theme C — Legacy-Retire (point of no return)

> **Logbook for Block 3.** Implementation/Audit-Agents document here.
> Orchestrator maintains the state-file status table — agents do not.

**Phase:** Theme C — legacy-retire (D-13 LanguageController, D-14 audioFile field, dead-controller retire). The point of no return — authorised by C6-D2pre GREEN; the new recording path is proven, legacy is now deleted (forward-fix, not revert-to-legacy).
**Implementation-Chunks:** C8-C1, C9-C2, C10-C3
**Workflow:** Iter-10 5-step (combined-step pattern — SendMessage/resume unavailable; orchestrator splits 2 commits/chunk). Sequential C8→C9→C10 (deps + git-index safety, Epic §4.1).
**Block-Start-Commit:** d39891a
**Block-End-Commit:** ⏳

> **Gate context:** C6-D2pre RE-GATE returned GREEN — C7 deleted the
> guarded fallback; the new orchestrator is the sole recording driver
> (only RESUME legacy `JobExecutor.start` survives, C6-IMPL-2 carve-out).
> Theme C is now safe: AC-5/AC-6/AC-7 deletions of legacy
> LanguageController/audioFile/dead-controllers.

> **Cross-block context (from B1/B2):**
> - F-15 (B1 C2-A2): `LayoutStrings.dictateButtonText` reads
>   `LanguageState.effective` read-only. After C8 there is NO legacy
>   writer behind it — C8 makes the new path the sole language source.
> - AC-10 GREEN (B2): only RESUME `JobExecutor.start` survives. C10's
>   OQ-1: `PipelineOrchestrator` is NOT deleted — it survives as the
>   `PipelineRunnerSubsystem` adaptee (C3's adapter delegates to it).
> - C5-IMPL-2 (B2, Important, was deferred to Theme-C): legacy recording
>   UI/animation sites read Idle on the new path — C10's RenderBackend-
>   sole-render-path scope addresses the controller side.

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

### Chunk C8-C1 — LanguageController full removal (D-13)

**Agent-IDs:** `B3-C8-C1-IMPL` (fresh, combined Steps 1-5).
**Status:** ✅ done (Steps 1-5, both commit-boundaries) · **Risk:** MED-HIGH (R-3 Application-singleton boot-before-bind) — **R-3 verdict: mitigated, see below**
**Implementation-Commit (Commit 1):** ⏳ (orchestrator) · **Test-Commit (Commit 2):** ⏳ (orchestrator)

#### What was done (Steps 1-5)

`LanguageController.kt` + `LanguageControllerTest.kt` **deleted**. The
permanent language SoT was extracted into a new stateless object
`preferences/LanguageResolver.kt` (effective-resolution + auto-curation +
curated-list-replace + pos-resync — exactly the deleted controller's
pref-only algorithm, delegating to the existing
`persistInputLanguagesAndPos` SoT so the versioned-envelope pattern is
respected, not reimplemented). `Action.LanguageAction.RefreshFromPref`
was promoted from a no-op `data object` to a payload-bearing
`data class RefreshFromPref(val effective: String)`; the `LanguageModule`
reducer now writes `LanguageState.effective` (idempotent on no-change).

All consumers migrated:
- `DictateApplication.java` — process-global singleton + its no-op
  `PipelineUiStateReader` **removed**; settings path now uses the static
  `LanguageResolver`.
- `PreferencesFragment.java` — reads/writes via `LanguageResolver.INSTANCE`.
- `DictateInputMethodService.java` — field deleted; new helpers
  `resolveEffectiveLanguage()` (override-or-permanent, exact legacy
  `computeEffective` semantics → preserves R-1 transcription-config
  fidelity), `pushPermanentLanguageToOrchestrator()` (Pre-Dispatch-
  Resolution → guarded `RefreshFromPref(code)` dispatch + chip/label
  refresh), `setLanguageFromPicker()` (staging → `SetOverride` dispatch
  + legacy `updateReprocessLanguage`; else permanent write). Chip-refresh-
  on-pipeline-state moved into `servicePipelineCallback` (the deleted
  controller was a `PipelineUiCallback`).
- `PipelineUiStateReader.kt` / `KeyboardUiController.kt` — KDoc only
  (both survive until C10; LC reference removed).
- KDoc/comment-only references in `TextResolvers.kt`, `DictatePipelineService.kt`,
  `ImePipelineConfigResolver.kt`, `PipelineRunnerSubsystemAdapter.kt`,
  `LanguageModule.kt`, `Action.kt`, `InputLanguagesPlugin.kt`,
  `InputLanguagesLegacyMigration.kt`, `VersionedPrefs.kt` — all scrubbed
  (AC-5 `grep -rl` is literal-zero, so even doc tokens removed).

#### BEFORE grep (`grep -rn LanguageController app/src/main`)

39 hits across 14 files (`LanguageController.kt` decl + IME field/dispose/
transcription/chip/picker sites + `DictateApplication` singleton +
`PreferencesFragment` + 9 KDoc-only refs).

#### AFTER grep

| Command | Result |
|---|---|
| `grep -rl "LanguageController" app/src/main` | **ZERO hits — AC-5 PASS** (verified exit-1) |
| `grep -rn "LanguageController" app/src/main` | (empty) |

#### R-3 — DictateApplication boot-before-bind migration design + ordering rationale (load-bearing)

**Problem.** The legacy `DictateApplication` singleton `LanguageController`
had non-service-bound lifetime; consumers (Settings `PreferencesFragment`,
IME render-tick before `pipelineBinder != null`) ran before/without a
bound orchestrator. A naive removal risks a silent stale/`"system"`
language (the subtle R-3 risk — worse than a compile error).

**Design.** The permanent SoT is moved to the **prefs file itself** via
the stateless `LanguageResolver` (reads/writes the same
`input_languages` versioned-envelope + `Pref.InputLanguagePos`). Because
the SoT is not an object tied to either lifetime:
- **Unbound path** (Settings UI, pre-bind IME): resolve/write directly
  through `LanguageResolver` — every read re-reads prefs, so there is
  **no cache** and therefore **no cross-instance staleness** (this
  structurally eliminates the old `LanguageController.lastEffective`
  bug the `inputLanguagesListener` bridge existed to patch).
- **Bound path**: IME resolves via `LanguageResolver.effectiveLanguage`
  **before** dispatch (Pre-Dispatch-Resolution, Spec 1 §4.11) and
  dispatches `RefreshFromPref(code)`, guarded by `pipelineBinder != null`
  (mirrors the parent plan's guard discipline).

**Ordering decision (explicit).** `onCreateInputView`'s
`pushPermanentLanguageToOrchestrator()` runs **before** the async
`bindService` callback in the common race, so its `RefreshFromPref`
dispatch is skipped (binder null) and `state.language.effective` would
remain the `"system"` boot sentinel. **Fix:** `onServiceConnected`
re-calls `pushPermanentLanguageToOrchestrator()` once the binder exists
(idempotent — reducer reduces a no-change refresh to null). The
unbound→bound transition is the only place this matters; subsequent pref
changes re-push via `inputLanguagesListener`. This closes the silent-
stale-language risk and is documented inline at the
`onServiceConnected` call-site.

**R-3 verdict:** mitigated. Application-singleton removed safely
(SoT is the prefs file, not an instance); boot-before-bind ordering
documented + closed via the `onServiceConnected` re-push. As a
side-effect this also **fixes a latent F-15 bug**: the RenderBackend
`state.language.effective` read was *always* `"system"` pre-C8 (the
`RefreshFromPref` reducer was a no-op AND `PipelinePrefMirror` does not
mirror language) → the language-suffixed "Record" label never rendered;
C8's payload-bearing dispatch makes it live.

#### Settings-propagation test (AC-5)

`LanguageModuleTest::settings language change propagates to
LanguageState_effective via dispatch` — builds a real `DictateOrchestrator`
with the production `LanguageModule`, asserts boot sentinel `"system"`,
dispatches `RefreshFromPref("de")`, asserts `Applied` +
`store.snapshot.language.effective == "de"` (the exact path the IME takes
after a Settings write fires `inputLanguagesListener`). The prefs-
resolution half is covered by `LanguageResolverTest` (13 cases).

#### Disjoint commit-boundary file lists

**=== COMMIT 1 (production) ===**
- `app/src/main/java/net/devemperor/dictate/preferences/LanguageResolver.kt` (new)
- `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- `app/src/main/java/net/devemperor/dictate/state/modules/LanguageModule.kt`
- `app/src/main/java/net/devemperor/dictate/DictateApplication.java`
- `app/src/main/java/net/devemperor/dictate/settings/PreferencesFragment.java`
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/core/PipelineUiStateReader.kt`
- `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt`
- `app/src/main/java/net/devemperor/dictate/core/ImePipelineConfigResolver.kt`
- `app/src/main/java/net/devemperor/dictate/core/PipelineRunnerSubsystemAdapter.kt`
- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt`
- `app/src/main/java/net/devemperor/dictate/state/layout/TextResolvers.kt`
- `app/src/main/java/net/devemperor/dictate/preferences/InputLanguagesPlugin.kt`
- `app/src/main/java/net/devemperor/dictate/preferences/InputLanguagesLegacyMigration.kt`
- `app/src/main/java/net/devemperor/dictate/preferences/versioned/VersionedPrefs.kt`
- `app/src/main/java/net/devemperor/dictate/core/LanguageController.kt` (DELETED)

**=== COMMIT 2 (tests) ===**
- `app/src/test/java/net/devemperor/dictate/preferences/LanguageResolverTest.kt` (new)
- `app/src/test/java/net/devemperor/dictate/state/LanguageModuleTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/DictateOrchestratorTest.kt`
- `app/src/test/java/net/devemperor/dictate/state/ModuleServicesTest.kt`
- `app/src/test/java/net/devemperor/dictate/testutil/FakePipelineUiStateReader.kt`
- `app/src/test/java/net/devemperor/dictate/core/MultiCallbackForwardingTest.kt`
- `app/src/test/java/net/devemperor/dictate/preferences/versioned/VersionedPluginRegistryTest.kt`
- `app/src/test/java/net/devemperor/dictate/core/LanguageControllerTest.kt` (DELETED)

(lists are disjoint — production vs test)

#### Deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Dev-1 | Epic §4 Block C1 ("extend the existing `RefreshFromPref` dispatch pattern :874") | `RefreshFromPref` promoted `data object` → `data class RefreshFromPref(val effective: String)`; reducer writes `effective` | The Phase-1 reducer was a no-op AND `PipelinePrefMirror` does not mirror language → without a payload `LanguageState.effective` stays `"system"` forever (latent F-15 bug). The `LanguageModule`/`Action` KDoc + Spec 1 §4.11 explicitly anticipated this promotion ("Once B3 wires the dispatcher to carry the resolved code, this will become a typed-payload action"). | Any future `LanguageAction` consumer; required for F-15 to be real. Existing placeholder usages in `DictateOrchestratorTest`/`ModuleServicesTest` updated (payload `"en"`). | inline-fixed (mid-size, solution clear from plan knowledge — `plan-deviation-resolved`) |
| Dev-2 | Epic §4 Block C1 (caller graph) | Added `onServiceConnected` re-push of `pushPermanentLanguageToOrchestrator()` | Boot-before-bind race: `onCreateInputView` push runs before binder arrives → `state.language.effective` would stay `"system"`. Plan mandates "document the boot-before-bind ordering" + "replicate `pipelineBinder != null` guard discipline". | None (IME-internal, idempotent). Closes R-3 silent-stale risk. | inline-fixed (small + locally decidable) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-1 | Important | `LegacyAudioFileMigrationTest` (Robolectric, `migration/`) flakes in the **full `testReleaseUnitTest` run** — fails non-deterministically at a *different test method/line each run* (kt:220 `openai_rate_limit` vs kt:183 `promotes recoverable…`). Passes in isolation (uncached) + full `testDebugUnitTest` (uncached) all green. ZERO code-path overlap with C8-C1 (language only); the error is a `DurationHealingJob` "Audio file not found during healing" artifact = R-7-class DB-singleton test-pollution on the audioFile/healing axis (C9-C2 territory), NOT covered by the B2-VAL-W1 `ActiveJobRegistry.resetForTest()` fix (different shared-state axis). | delegated-to-orchestrator | Cross-chunk test-infra (R-7 family, audioFile-healing DB-singleton). Out of C8-C1 scope; not finder-fixer (external test-infra). Non-deterministic + method-varying ⇒ pollution flake, not a C8-C1 regression. Recommend block-validate / B2-VAL-W1-follow-up adds a DB-singleton/`DurationHealingJob` `tearDown` reset for the `migration/` Robolectric tests. |

#### Code-Bugs Found While Writing Tests (Step 4)

| File:Line | Bug-Symptom | Root-Cause | Fix (before → after) | Research |
|---|---|---|---|---|
| `DictateOrchestratorTest.kt` (14 sites) + `ModuleServicesTest.kt` (2 sites) | Test compile failure: `RefreshFromPref does not have a companion object` | These tests used `Action.LanguageAction.RefreshFromPref` as a no-arg placeholder action; my Dev-1 API change (`data object` → `data class`) broke them. | `Action.LanguageAction.RefreshFromPref` → `Action.LanguageAction.RefreshFromPref("en")`; stale "data object leaf" comment → "data-class leaves". Pure placeholder usages — no language-semantic assertion affected. | Step-4 Fall-1 (bug in own chunk-diff via API change); D5 (plan re-read confirms payload promotion is the intended Spec §4.11 design). |

#### Test-Review (Step 5)

`LanguageResolverTest` (13 cases — defaults, effective resolution,
pos-coerce on corrupt envelope, auto-curation, present/unknown code,
curated dedup/sort/pos-anchor, preferActive fallback paths, R-3 cross-
instance freshness). `LanguageModuleTest` (reducer SetOverride/
RefreshFromPref incl. idempotence + override-survival + lens/id/initial
+ the AC-5 real-dispatch propagation). All C8-C1-touched tests pass
deterministically in isolation (35s). Coverage of the new
`LanguageResolver` public surface + `LanguageModule.reduce` branches is
complete. No quality issues found.

#### Files modified — drift classification

- **In plan-prescribed scope:** `LanguageController.kt`(+test, deleted),
  `DictateApplication.java`, `DictateInputMethodService.java`,
  `PreferencesFragment.java`, `KeyboardUiController.kt`,
  `PipelineUiStateReader.kt`, `DictatePipelineService.kt`,
  `FakePipelineUiStateReader.kt`, `MultiCallbackForwardingTest.kt` (all
  named in Epic §4 Block C1 "Files").
- **Drift (touched, not directly named):** `LanguageResolver.kt` (new —
  the extraction target the plan implies by "migrate every consumer";
  Spec §9.6 "wandert direkt in den Modul-Reducer" + the unbound-path SoT
  R-3 mitigation); `Action.kt` + `LanguageModule.kt` (Dev-1 payload
  promotion); `TextResolvers.kt`, `ImePipelineConfigResolver.kt`,
  `PipelineRunnerSubsystemAdapter.kt`, `InputLanguagesPlugin.kt`,
  `InputLanguagesLegacyMigration.kt`, `VersionedPrefs.kt` (KDoc-only
  scrub to satisfy AC-5 literal `grep -rl` zero);
  `DictateOrchestratorTest.kt`, `ModuleServicesTest.kt`,
  `VersionedPluginRegistryTest.kt` (Step-4 own-API-change fix /
  KDoc scrub). All drift is mechanically forced by AC-5's literal
  grep-zero + the Dev-1 API change; documented above.

#### Overlooked points / known gaps

- The ReprocessStaging override now has **two** carriers during the
  C8→C10 window: the legacy `PipelineUiState.ReprocessStaging.selectedLanguage`
  (via `KeyboardUiController`, read by `resolveEffectiveLanguage()` /
  transcription) **and** the new `LanguageState.override` (via the new
  `SetOverride` dispatch in `setLanguageFromPicker`). This is intentional
  transitional duplication (KeyboardUiController is retired in C10/C3);
  the transcription path still reads the legacy carrier so R-1 fidelity
  is unchanged. C10 must collapse the read onto `LanguageState.override`.
- `PipelinePrefMirror` still does not mirror the language prefs (by
  design — language uses Pre-Dispatch-Resolution, not the mirror). Noted
  so a future reader doesn't "fix" it by adding a mirror entry that would
  double-write `effective`.
- IMPL-1 (`LegacyAudioFileMigrationTest` release-suite flake) is
  delegated — not investigated beyond establishing it is pre-existing
  R-7-class pollution unrelated to C8-C1.

---

### Chunk C9-C2 — audioFile field removal (D-14)

**Agent-IDs:** `B3-C9-C2-IMPL` · **Status:** ⏳ pending · **Risk:** MED (R-5 non-recording reads)
(subsections filled when chunk runs)

---

### Chunk C10-C3 — dead-controller retire + PipelineOrchestrator disposition

**Agent-IDs:** `B3-C10-C3-IMPL` · **Status:** ⏳ pending · **Risk:** MED
(subsections filled when chunk runs)

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ · **Pre-Validate Commit:** ⏳ · **Validate-Pass Commit:** ⏳

| Topic | Agent-ID | Status | Output File | Findings |
|-------|----------|--------|-------------|----------|
| plan-and-api | `B3-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B3.md` | — |
| convention | `B3-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B3.md` | — |
| logic | `B3-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B3.md` | — |
| test | `B3-AUDIT-TEST` | ⏳ | `./reports/audit-test-B3.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B3-VAL-SANITY` · **Output:** `./reports/validated-findings-B3.md`

### Mini-Triage + Repair-Wave(s)

(Per iteration, max 3 per D5 soft-cap.)

---

## Block Deviation Summary

| # | Plan Location | What changed | Why | Impact | Inline-fixed | Source-Agent | Source-Step |
|---|---------------|--------------|-----|--------|--------------|--------------|--------------|
| Dev-1 | Epic §4 Block C1 (RefreshFromPref dispatch pattern) | `RefreshFromPref` `data object` → `data class(effective)`; reducer writes `effective` | Phase-1 reducer was a no-op + PrefMirror does not mirror language ⇒ `LanguageState.effective` would stay `"system"` (latent F-15 bug); Spec 1 §4.11 + module KDoc anticipated the payload promotion | Any future LanguageAction consumer; placeholder test usages updated; F-15 now live | yes (`plan-deviation-resolved`) | `B3-C8-C1-IMPL` | Step 2 |
| Dev-2 | Epic §4 Block C1 (caller graph / R-3) | `onServiceConnected` re-pushes `pushPermanentLanguageToOrchestrator()` | Boot-before-bind race: onCreateInputView push runs before binder arrives → `effective` stays `"system"`; plan mandates documenting the ordering + `pipelineBinder != null` discipline | IME-internal, idempotent; closes R-3 silent-stale risk | yes | `B3-C8-C1-IMPL` | Step 2 |

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step, both commits):** ⏳
- **Block-Validate converged:** ⏳
- **AUDIT-TEST: coverage + no cross-chunk regressions:** ⏳
- **Build green at block-end (cleanup-greps pass per Spec 1 §9.6):** ⏳
- **Issue index reconciled:** ⏳
- **Cross-block-API consumer info forwarded to B4:** ⏳

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
