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

**Severity counts:** Critical: 1 · Important: 0 (1 fixed) · Nice-to-have: 1 · Postponed: 0 · B5-carry-over: 1 (F-6)

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| C8-IMPL-1 (F-1) | B3-C8-C1-IMPL → B3-AUDIT-TEST → B3-VAL-REPAIR-1 | Important | **fixed** (B3-VAL-W1) | R-7-variant `LegacyAudioFileMigrationTest` flake — root-caused as the `DurationHealingJob`/DB-singleton async axis (`DictateApplication.onCreate` spawned heal thread). **Corrected (F-2):** manifested in **BOTH** `testDebug`+`testReleaseUnitTest` non-deterministically (not release-only). Fixed via production-owned `DurationHealingScheduler` graceful-drain `resetForTest()` seam; verified 1048/1048 green ×3 uncached both variants. | step-1-impl (C8-C1) → B3-VAL-W1 |
| F-6 | B3-VAL-SANITY (merged PLAN-AND-API-B3-1 + LOGIC-B3-1 + conv-1 footnote) | Nice-to-have (info) | **deferred-with-tracking → B5** | Dual-carrier ReprocessStaging override (legacy `PipelineUiState.ReprocessStaging.selectedLanguage` vs new `LanguageState.override`). Behaviour-safe today; the cross-carrier *collapse* is the missing render-path-cutover block's job — **inherited by the B5/Theme-C-R render-path-cutover scope under the C10-IMPL-2 umbrella** so the transitional dual-carrier does not become permanent when `KeyboardUiController` is retired. NOT a B3 defect (F-3 already de-dups the B3-local legacy-carrier read; disjoint from F-6). | block-validate (B3-VAL-SANITY) |
| C10-IMPL-2 | B3-C10-C3-IMPL | **Critical** | delegated-to-orchestrator → **mid-chunk-triage / Epic re-scope** | **Architecture-conflict: the render-path cutover block does not exist.** C10-C3's premise ("Theme B + the parent RenderBackend path made the 4 controllers dead") is false — Theme B was recording-drive only; `ImeViewBackend` runs *parallel* to the legacy controllers (`staticHandlerInstaller=null`, new render controllers not IME-attached); RECORD/BACKSPACE long-press + touch handlers + theming + key-press-anim + QWERTZ + pipeline-progress UI have no ported owner (parent B4-VAL F-1/F-2/F-33 deferred the IME render-attach to a never-created follow-up). Per-class responsibility-trace caught it before any deletion (R-mitigation). Needs a new render-path-cutover block before the 4 deletions are safe. Subsumes C5-IMPL-2. Blocks D1/D2. | step-1-impl (C10-C3) |
| C10-IMPL-3 | B3-C10-C3-IMPL | Nice-to-have | open | Optional architecture-test guarding the AC-10 "PipelineOrchestrator has no caller outside {adapter, JobExecutor, standalone/cancel/RESUME}" invariant. Noted for D1/D2 or follow-up; not done this (blocked) chunk. | step-1-impl (C10-C3) |

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
| IMPL-1 | Important | `LegacyAudioFileMigrationTest` (Robolectric, `migration/`) flakes non-deterministically at a *method-varying* line. **CORRECTION (B3-VAL F-2, AUDIT-TEST-verified):** the original "release-only / `testDebugUnitTest` always green" framing was **inaccurate** — the flake manifests in **BOTH** `testDebugUnitTest` and `testReleaseUnitTest` non-deterministically (1047/1048 green every uncached run, the single failure always the C8-IMPL-1 pollution flake; AUDIT-TEST reproduced 3/3 uncached runs incl. a `testDebugUnitTest` failure at kt:244). ZERO code-path overlap with C8-C1 (language only); the error is a `DurationHealingJob` "Audio file not found during healing" artifact = R-7-class DB-singleton test-pollution on the audioFile/healing axis, NOT covered by the B2-VAL-W1 `ActiveJobRegistry.resetForTest()` fix (different shared-state axis — the `DurationHealingJob`/DB-singleton async axis). | **fixed** (B3-VAL-REPAIR-1, F-1) | Root-caused + fixed: production-owned `DurationHealingScheduler` holder with a `@JvmStatic @VisibleForTesting resetForTest()` graceful-drain seam; called before `DictateDatabase.resetForTest()` in `LegacyAudioFileMigrationTest` @Before/@After + the `DictatePipelineService*` boot-test teardowns. Test-infra only, no production behaviour change. Verified 1048/1048 green across 3 uncached runs both variants. |

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

> **§9.6 reconciliation (B3-VAL F-5):** Spec 1 §9.6 says
> `LanguageController` "wandert direkt in den Modul-Reducer; keine
> Adapter-Phase nötig". This is satisfied by the pref-only
> resolution/write algorithm living in the stateless
> `preferences/LanguageResolver` object, invoked **Pre-Dispatch** per
> Spec 1 §4.11 — *not* literally inside `LanguageModule.reduce`, because
> the reducer must stay I/O-free (ADR-0002 pure-reducer invariant;
> putting `SharedPreferences` I/O inside the reducer would violate it).
> The chosen placement is therefore the *more* spec-faithful reading of
> Spec 1 as a whole, not unplanned drift. (Spec-file edit is out of B3
> scope; this block-report note is the SSoT-correct location.)

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

**Agent-IDs:** `B3-C9-C2-IMPL` (fresh, combined Steps 1-5).
**Status:** ✅ done (Steps 1-5, both commit-boundaries) · **Risk:** MED (R-5 non-recording reads) — **R-5 verdict: GREEN (every site provably correctly sourced; resend/migration never touched the field)**
**Implementation-Commit (Commit 1):** ⏳ (orchestrator) · **Test-Commit (Commit 2):** ⏳ (orchestrator)

#### What was done (Steps 1-5)

Deleted the `DictateInputMethodService.audioFile` field. Verified the
**current** line via grep (decl was `:240`, not Epic's stale `:222` —
C8 shifted the file as warned). All field use-sites re-sourced from
their correct post-cutover source: recording-active reads from the
orchestrator `state.recording` payload (Spec 1 §15.2), scratch-handle
uses kept as method-locals (`CacheDirAudioFileFactory.allocate()` /
imported-file `new File(...)`), and the shared
`captureFreshConfigSnapshot` / `transcribeImportedAudioFileViaOrchestrator`
helpers now take the file as a parameter instead of reading a field.

A canonical Kotlin accessor `RecordingState.audioFileOrNull` was added
to `state/DictateUiState.kt` next to the sibling `isActiveOrPaused`
extension (same documented DRY rationale — the sealed-interface payload
extraction would otherwise be an `is`-cascade at the IME read site).
This is the documented Dev-1 deviation (plan said "DictateInputMethodService.java
only", but the plan *also* mandates sourcing recording-active reads
"from orchestrator state" — the accessor is the minimal, plan-faithful
way to do that from Java).

#### MANDATORY per-site source-analysis table (R-5 evidence)

Field decl + 8 field use-sites traced individually. (Line numbers are
pre-edit; the field was at `:240`, NOT the Epic's stale `:222`/`:1374`/
… — C8 shifted the IME file, re-grepped as the prompt directed.)

| Pre-edit line | Usage (read/write, context) | old: `this.audioFile` | new source | why this source is correct | risk |
|---|---|---|---|---|---|
| `:240` | field declaration | `private File audioFile;` | **deleted** | the field is the removal target (AC-6) | — |
| `:1397` | write — dead `onRecordingCompleted(File file)` legacy callback (dead post-C7: legacy controller never started on new path) | `audioFile = file;` then read at `:2655` | pass `file` → `transcribeImportedAudioFileViaOrchestrator(file)` param | the callback already receives the file as a param; the field was only a bridge to the downstream read. Param threads the exact handle. Dead path but kept behaviour-equivalent. | LOW (dead path) |
| `:2003` | write — Settings audio-import path in `onStartInputView` | `audioFile = new File(cacheDir/audio, Pref.TranscriptionAudioFile)` | method-local `File importedAudio = new File(...)` | an import has **no orchestrator recording session** → not in `state.recording`; it is a scratch handle local to this import flow. Traced: this is the *import* non-recording read, distinct from resend. | MED→LOW (non-recording, traced) |
| `:2004` | read `.getName()` — `Pref.LastFileName` write | `audioFile.getName()` | `importedAudio.getName()` (same local, same instant) | identical value; LastFileName semantics unchanged (b3-cleanup research §6: LastFileName is the persistent last-recording filename, independent of the live handle) | LOW |
| `:2345` | write — `startRecording()` | `audioFile = pipelineBinder.getAudioFileFactory().allocate()` | method-local `File audioFile` | the just-allocated file is handed to the orchestrator via `StartRecording` and becomes `RecordingState`'s authoritative payload (Spec 1 §15.2; B2 C5-B3 report confirms FSM carries it). The IME field was a redundant mirror. | MED→LOW (recording path; orchestrator is post-cutover SoT) |
| `:2357` | read `.getName()` — `Pref.LastFileName` write | `audioFile.getName()` | local `audioFile.getName()` (same local, same instant) | identical value, same allocation instant | LOW |
| `:2371` | read — `StartRecording(target, audioFile, id)` dispatch | field | local `audioFile` | passes the just-allocated file *into* the FSM where it becomes the SoT | LOW |
| `:2481` | read `.getAbsolutePath()` — `captureFreshConfigSnapshot()` | field | **parameter** `File audioFile` | `stopRecording()` caller sources it from `RecordingState.Active/Paused.audioFile` via `pipelineBinder.getState()` + `audioFileOrNull` (post-cutover authoritative, Spec 1 §15.2). Guarded: `stopRecording()` early-returns if `pipelineBinder == null` (`:2393`); the Active/Paused guard (`:2422`) guarantees non-null, plus an explicit defensive null-bail added. Import caller passes its local. | **MED (R-5 core)** — recording: state; import: param. **Both traced.** |
| `:2655` | read — `TriggerPipeline(sessionId, audioFile)` dispatch in `transcribeImportedAudioFileViaOrchestrator()` | field | **parameter** `File audioFile` | the two callers thread the correct file explicitly: `:1397` dead-legacy → its `file` param; `:2007` import → `importedAudio` local. No field. | LOW (param from traced callers) |
| `:2787`/`:2792` | `onAudioPersisted(File audioFile, …)` | **method parameter** (shadowed the field) | unchanged | already a parameter — never referenced the field (Epic confirmed) | NONE |
| `:2641` | comment "it reads the IME `audioFile` field" | comment | rewritten to describe the param | doc-only | NONE |

**R-5 trap analysis — resend + legacy-migration (the load-bearing risk):**

- **Resend** (`handleReprocessSend`, `:3431`): sources the audio file
  from the **Room DB** — `session.getAudioFilePath()` for the target
  session. It **never referenced the `audioFile` field** (verified by
  grep + reading the full method). R-5 resend concern: **not
  applicable** — the field was structurally uninvolved in resend.
- **Legacy-migration / resend-button visibility** (`:877`, `:1934`,
  `:2474`, `:2515`): all source from `new File(getCacheDir(),
  Pref.LastFileName)` **directly**, never the field. The Epic's
  `:1880`/`:2104` "non-recording read" line numbers are stale; the
  *actual* non-recording reads that exist source from `Pref.LastFileName`
  / DB, not the field. The field's only roles were: (a) fresh-recording
  scratch handle (now `RecordingState`), (b) import scratch handle (now
  a local), (c) bridge into the two shared helpers (now params).

**Verdict:** all 9 sites provably correctly sourced. **Zero sites
flagged** — no guessed source. R-5 GREEN: the field never participated
in resend or migration, so removing it cannot break them; the only
behaviour-bearing re-source (the fresh-recording send-tap) reads the
orchestrator's authoritative `state.recording` payload, the documented
post-cutover SoT.

#### BEFORE / AFTER grep (AC-6)

| Command | BEFORE | AFTER |
|---|---|---|
| `grep -n "private File audioFile" …/DictateInputMethodService.java` | `240: private File audioFile;` (1 hit) | **ZERO hits — AC-6 PASS** (exit 1 verified) |
| `grep -n "audioFile" …/DictateInputMethodService.java` | 11 lines (field + 8 field-use + onAudioPersisted param + comment) | 14 lines — all method-locals (`:2350/2352/2364/2378`), the `captureFreshConfigSnapshot(String,File)` + `transcribeImportedAudioFileViaOrchestrator(File)` params + their uses, the `onAudioPersisted` param, a log string, and updated comments. **No field, no `this.audioFile`, no bare-field ref.** |

#### Resend + migration still work (acceptance)

- **Resend:** unchanged — `handleReprocessSend` reads `session.getAudioFilePath()`
  (DB), code path not touched by this chunk (zero edits in/around `:3431`).
- **Legacy-migration:** unchanged — sources from `Pref.LastFileName`,
  code path not touched. `LegacyAudioFileMigrationTest` passes
  **isolated** (8 tests, 0 fail). The full-suite flake is the
  **pre-existing C8-IMPL-1 pollution flake**
  (DB-singleton/`DurationHealingJob` shared-state axis) — NOT a
  regression: my change does not touch the audioFile/migration axis
  (resend/migration source from `Pref.LastFileName`/DB, never the
  deleted field). **CORRECTION (B3-VAL F-2):** the original claim that
  "full `testDebugUnitTest` is green (1048/0/0)" was **inaccurate** —
  AUDIT-TEST verified the C8-IMPL-1 flake manifests in **BOTH**
  `testDebugUnitTest` and `testReleaseUnitTest` non-deterministically
  (1047/1048 green every uncached run, the single failure always the
  C8-IMPL-1 pollution flake; never debug-always-green). My re-sourcing
  does not worsen the C8-IMPL-1 axis; the axis itself is now fixed by
  B3-VAL-REPAIR-1 (F-1 `DurationHealingScheduler` seam — 1048/1048
  green across 3 uncached runs both variants).

#### Build + test (AC-9)

- `./gradlew assembleDebug` → **BUILD SUCCESSFUL**.
- `./gradlew testDebugUnitTest` → **1048 tests** (baseline ~1043 + 5
  new `audioFileOrNull` tests).
  **CORRECTION (B3-VAL F-2):** the original "**0 failures, 0 skipped**"
  claim for the uncached full `testDebugUnitTest` was **inaccurate** —
  AUDIT-TEST verified that pre-fix, the forwarded C8-IMPL-1 pollution
  flake manifested in **BOTH** `testDebugUnitTest` and
  `testReleaseUnitTest` non-deterministically (1047/1048 green every
  uncached run, the single failure always the C8-IMPL-1 flake at a
  method-varying line; debug was *not* always-green). This is **not** a
  C9-C2 regression (zero audioFile/migration-axis edit; isolated test
  green). The C8-IMPL-1 axis is now fixed by B3-VAL-REPAIR-1 (F-1):
  full `testDebugUnitTest` AND `testReleaseUnitTest` are **1048/1048,
  0 failures, 0 errors** across 3 uncached `--rerun-tasks` runs in
  varying order.

#### Disjoint commit-boundary file lists

**=== COMMIT 1 (production) ===**
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java`
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt` (new `audioFileOrNull` accessor — Dev-1)

**=== COMMIT 2 (tests) ===**
- `app/src/test/java/net/devemperor/dictate/state/DictateUiStateTest.kt` (5 new `audioFileOrNull` cases)

(lists are disjoint — production vs test)

#### Deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Dev-1 | Epic §4 Block C2 ("Files: `DictateInputMethodService.java` only"); chunks.json `files_estimate: 1` | Added `RecordingState.audioFileOrNull` extension to `state/DictateUiState.kt` | The plan *also* mandates "recording-active reads → orchestrator state (`state.recording`)". Extracting `audioFile` from the sealed `RecordingState` interface in Java needs either an `is`-cascade at the read site or a canonical accessor. The project's own documented convention (`DictateUiState.kt:187` `isActiveOrPaused`) is a centralised extension next to the FSM definition — the most plan-compatible, DRY, sustainable solution. One extra production file, additive (no behaviour change to existing callers). | C10-C3 (dead-controller retire) may consume the same accessor when collapsing legacy recording-UI reads; it is the canonical sealed-payload accessor going forward. | inline-fixed (small + locally decidable — the plan's own "source from state.recording" requirement forces a state-side accessor; project convention dictates its form) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| (none) | — | All 9 sites provably correctly sourced; no guessed source, no architecture conflict, no state-shape change needed. The pre-existing `LegacyAudioFileMigrationTest` release-suite flake is C8-IMPL-1 (already delegated to B3 AUDIT-TEST) — confirmed unrelated to this chunk (no audioFile/migration-axis edit; isolated test green). | — | — |

#### Code-Bugs Found While Writing Tests (Step 4)

(none — the new `audioFileOrNull` accessor and the IME re-sourcing are
covered by the 5 new tests; all green first run. No production bug
surfaced.)

#### Test-Review (Step 5)

5 new tests in `DictateUiStateTest.kt` cover all 4 `when`-arms of
`audioFileOrNull` (Preparing/Active/Paused → file; Idle → null) plus
the R-5 handle-identity invariant (`assertSame` proves the *exact*
minted handle is returned across Preparing→Active→Paused, not a
re-derived path — this is the precise R-5 risk under test). Branch
coverage of the new accessor is 100% (exhaustive sealed `when`, all
arms exercised). Test names describe behaviour; assertions use
`assertSame`/`assertNull` (identity, the load-bearing property), no
weak assertions. No quality issues; no fixes needed.

#### Files modified — drift classification

- **In plan-prescribed scope:** `DictateInputMethodService.java` (Epic
  §4 Block C2 "Files: …only"); `DictateUiStateTest.kt` (test for the
  diff).
- **Drift (touched, not directly named):** `DictateUiState.kt` —
  Dev-1; the plan's "source from `state.recording`" mandate forces a
  state-side accessor, project convention (`isActiveOrPaused` sibling)
  dictates its form. Additive, no behaviour change to existing callers.

#### Overlooked points / known gaps

- The Epic's site line numbers (`:222/:1374/:1880/:2104/…`) were stale
  pre-C8; the prompt anticipated this and directed a re-grep — done.
  The Epic's "`:1880` legacy-migration / `:2104` resend" *field* reads
  do not exist as field reads in the current code: those paths source
  from `Pref.LastFileName`/DB independently of the field. This is a
  *favourable* finding (less R-5 surface than the Epic feared), but
  noting it so a reviewer does not look for field-based migration/resend
  reads that were never there.
- `onAudioPersisted(File audioFile, …)` keeps a *parameter* named
  `audioFile` — intentionally not renamed (it shadowed nothing after
  the field deletion; renaming would be churn with no clarity gain;
  it is unambiguously a method param).
- C8-IMPL-1 (`LegacyAudioFileMigrationTest` release-suite pollution
  flake) remains delegated to B3 AUDIT-TEST — confirmed here as
  unrelated to C9-C2 (no audioFile/migration-axis code edit; the test
  passes isolated).

---

### Chunk C10-C3 — dead-controller retire + PipelineOrchestrator disposition

**Agent-IDs:** `B3-C10-C3-IMPL` (fresh, combined Steps 1-5).
**Status:** ⚠️ **BLOCKED — Critical architecture-conflict (IMPL-2, → mid-chunk-triage)**. OQ-1 done; KeyboardLayoutModeController already-gone confirmed; the 4 controller deletions **NOT performed** (premise false — see trace). · **Risk:** MED → **the subtle R-risk materialised: the controllers' behaviour is NOT ported. Per-class responsibility-trace (R-mitigation) caught it before any deletion (D4 correctness over speed).**
**Implementation-Commit (Commit 1):** ⏳ (orchestrator) · **Test-Commit (Commit 2):** ⏳ (n/a — no test-bearing change this chunk; see below)

#### What was done (Steps 1-5, combined)

1. **OQ-1 — `PipelineOrchestrator` disposition: CONFIRMED + annotated (no deletion).**
   The C3-B1 `PipelineRunnerSubsystemAdapter` provably **delegates** (does
   not reimplement): `PipelineRunnerSubsystemAdapter.submit/cancel` →
   `JobExecutor.INSTANCE.start/cancel` → `JobExecutor.initialize(orchestrator)`
   → `PipelineOrchestratorRunner(orchestrator)` → `PipelineOrchestrator`
   (the unchanged 1383-LOC runner body). `grep` of every non-doc
   `PipelineOrchestrator` reference: the only direct callers besides the
   adapter chain are the standalone-prompt path (`StandaloneConfig`,
   IME `:2720`), the cancel path (`CancelInfo`, IME `:3587/:3591/:3598`),
   and the RESUME carve-out (C6-IMPL-2) — **no second state-router, no
   double-dispatch (AC-10 holds)**. A KDoc boundary header was added to
   `core/PipelineOrchestrator.kt` stating it is the `PipelineRunnerSubsystem`
   adaptee with the full delegation chain + the Spec 1 §9.6 / §1.x "never
   deleted" citation. **`PipelineOrchestrator` NOT deleted/rewritten** (per
   Spec 1 §9.6 + Epic OQ-1).
2. **`KeyboardLayoutModeController` — already deleted (Spec 2 §9.1 / parent
   C15): CONFIRMED.** No `.kt` source exists (`find` empty). The 5 residual
   refs are comment/KDoc-only anchors documenting the C15 removal (IME
   `:835/:3657`, KSM `:63/:105`, layout XML `:153`) — not code. Spec 2 §9.1
   "entfällt vollständig" already satisfied; nothing to delete.
3. **The 4 controller deletions (`MainButtonsController`,
   `RecordingUiController`, `KeyboardStateManager`, `KeyboardUiController`)
   — NOT performed.** The chunk premise ("Theme B + the parent RenderBackend
   path made these dead") is **false**. The per-class responsibility-trace
   (mandatory R-mitigation, below) proves every one of these controllers is
   still the **sole production owner** of behaviour with **no ported new
   owner**. Deleting them would erase core user features (RECORD long-press
   2-mode handler, BACKSPACE accelerated-delete, SPACE cursor-swipe, ENTER
   overlay, all keyboard visibility logic, the entire pipeline-progress UI).
   Flagged as **Critical `architecture-conflict` (IMPL-2)** → mid-chunk-triage.

#### MANDATORY per-class responsibility-trace (AC-7 evidence + R-mitigation)

The architecture reality (verified by reading every controller + the new
render path + the IME wiring + parent-plan B4-VAL findings F-6/F-33/F-1/F-2):

> **The render-path cutover never happened.** Theme B of this Epic was the
> *recording-drive* cutover (JobExecutor → PipelineRunnerSubsystem); it did
> **not** wire the IME render path onto `RenderBackend`. `ImeViewBackend` is
> attached in *parallel* to the legacy controllers (IME `:1023`
> `attachImeViewBackendIfReady`), explicitly documented at IME `:1014-1017`:
> *"the record path still works through the legacy MainButtonsController +
> KeyboardUiController + RecordingUiController flow"*. `ImeViewBackend` is
> only 287 LOC, wires **plain click + the RESEND long-press only**, and its
> `staticHandlerInstaller` is wired **`null`** at IME `:1113`. The new
> `ContentAreaController`/`PromptVisibilityController`/`OverlayResetHandler`
> are **not attached in the IME** (`grep` empty). Parent-plan B4-VAL F-33 +
> F-1/F-2 explicitly deferred the IME-side render-path attach + the
> RECORD/BACKSPACE long-press port to a "D-13 / B5/B7 follow-up" — **a block
> that never existed** (the exact INT-1 pattern this Epic was created to
> fix, recurring at the render layer).

| Class | Spec disposition (§ref) | Per-responsibility → new owner (verified present?) | Safe to delete? |
|---|---|---|---|
| `KeyboardLayoutModeController` | Spec 2 §9.1 "entfällt vollständig" | Already gone (parent C15 → MotionScene XML + `ImeViewBackend.render` transitionToState). VERIFIED present (declarative scene-states). | ✅ already deleted — confirmed, no action |
| `MainButtonsController` | Spec 2 §9.2 → `ImeViewBackend.wireStaticHandlers` + `staticHandlerInstaller` | `registerMainButtonListeners` (9 click/long/touch handlers) → `ImeViewBackend.wireStaticHandlers` wires plain clicks only; **RECORD long-press** (Idle→Settings+file-picker / Active→autoSwitch+stop) **NOT ported** — `ImeViewBackend` KDoc `:233-242` explicitly says wiring it "would silently overwrite the legacy listener… erasing both user features", deferred to "B5/B7 follow-up". **BACKSPACE long-press** (accel-delete cascade) **NOT ported** (KDoc `:243-246`, "F-1 drop the wire"). `BackspaceSwipeHandler`/`CursorSwipeTouchHandler`/`EnterOverlayHandler` → `staticHandlerInstaller` hook, but it is **`null`** at IME `:1113` → **NOT installed on the new path**. `applyTheme`/`initializeKeyPressAnimations`/`initializeOverlayCharacters`/`updateOverlayCharacters`/`animateSmallModeToggle`/`animateEditNumbersBounce`/`refreshAudioFocusIcon`/`updateRecordButtonText`/`setResendEnabled` → **no new owner exists** (Spec 2 §9.2 maps them to ImeViewBackend methods that were never written). | ❌ **NO — STOP.** Multiple side-effecting responsibilities with zero ported owner. Deleting strands RECORD/BACKSPACE long-press, all touch handlers, theming, all key-press animation, overlay chars. |
| `KeyboardStateManager` | Spec 2 §9.3 → `ContentAreaController` + `PromptVisibilityController` + `OverlayResetHandler` (R.10 split) | `applyContentAreaVisibility` → `ContentAreaController` (class exists, **not IME-attached** — KSM KDoc `:149-159` *"TODO(D-13 follow-up): delete … once ContentAreaController attaches in production (B4-VAL F-33)"*; the new path is "a parallel RenderBackend ready to take over once the IME-side attach lands"). `applyPromptsVisibility`/`applyPromptsLayout` → `PromptVisibilityController` (same — not attached). `overlayCharactersLl.visibility=GONE` reset → `OverlayResetHandler` (not attached). `contentArea`/`isSmallMode` state + `setContentArea`/`setSmallMode`/`refresh` → `LayoutModule`/`LayoutState` (state ported; **but the IME still calls `stateManager.setContentArea(...)`/`refresh()` directly** at `:1169/:1172/:1184/:1185` — the dispatch-side migration was not done). | ❌ **NO — STOP.** The new owner classes exist but are **not wired into production**; KSM is the sole live visibility owner. Deleting blanks every keyboard visibility axis. |
| `RecordingUiController` | Spec 2 §9.4 → `KeyboardUiController.applyRecordButtonForRecording` + `RecordingAnimationController` + LayoutCatalog predicates | Main-button mutations → already moved to `KeyboardUiController.applyRecordButtonForRecording` (but that class is *also* on the kill-list — see next row). `recordingAnimation` lifecycle → `RecordingAnimationController` (exists; wired into `ImeViewBackend` `recordingAnimationCtrlForBackend`) — **but the IME also still drives `recordingUiController.onStateChanged/onAmplitudeUpdate/onTimerTick`** at `:1369/:1376/:1381/:1437`. `updateQwertzRecButton`/`enterPipelineDisplay`/`updatePipelineTimer` (QWERTZ rec-button) + `setupPromptsVisualizer`/prompt-rec/prompt-pause buttons → **no new owner** (Spec 2 §9.4 says "bleibt — QWERTZ-spezifisch"; never migrated). IME `servicePipelineCallback` `:940/:979/:981/:986` calls these live. | ❌ **NO — STOP.** QWERTZ rec-button + prompts visualizer + prompt-bar controls have no ported owner; the IME drives the controller on every recording state change. |
| `KeyboardUiController` | Spec 2 §9.5 (state+view → record_btn-Resolver) / Spec 1 §9.2 (state-Teil → PipelineModule) | `PipelineUiState` state + `preparePipeline`/`startPipeline`/`stopPipeline`/`toggleAutoEnter`/`enterReprocessStaging`/`cancelReprocessStaging`/`updateReprocessQueue`/`updateReprocessLanguage` → Spec says → `PipelineModule.reduce` via `Action.PipelineAction.*`; **the IME still calls `uiController.startPipeline/addRunningStep/completeStep/failStep/stopPipeline/enterReprocessStaging/getAutoEnterConfig/getState` directly** (`:1295/:1301/:1450/:1470/:1476/:2559/:2713/:2717/:3528` + KSM/RecordingUiController lambdas `:786/:788/:878/:881`). `refreshRecordButtonFromState`/`applyRecordButtonForRecording` (Spec 2 §9.5 → record_btn resolver) — resolver exists in LayoutCatalog but the IME-side flip never happened. `stepRows` pipeline-progress view rendering (inflates `item_pipeline_step_row`, live per-step timers) → **no new owner** (Spec 1 §9.2 says "stepRows bleibt im KeyboardUiController View-side"). It is still a `PipelineUiStateReader` consumed by `PipelineUiStateReader.kt` + `FakePipelineUiStateReader`. | ❌ **NO — STOP.** Pipeline-progress UI + step-row rendering + the entire IME pipeline-UI driver path is unported. `PipelineUiStateReader` consumers would dangle. |

**Verdict:** `KeyboardLayoutModeController` deletion = already done (confirmed,
no-op). The **4 remaining controllers cannot be deleted** — every one is the
sole production owner of behaviour the spec maps to new owners that **were
never wired into the IME** (the render-path cutover block the Epic does not
contain). This is the exact subtle R-risk the prompt + plan §4 Block C3
"Risk" warned about ("a controller method with a side-effect not yet ported");
the mandatory per-class trace caught it **before** any deletion. **Zero
controllers deleted; zero residual-ref edits made** (none are needed — no
deletion). AC-7 cleanup-grep deliberately **not** run for the 4 classes
(they still exist by design; running it would falsely "fail").

#### Spec 1 §9.6 End-of-Block-Cleanup-Check (AC-7)

| Class (Spec 1 §9.6 / Spec 2 §9.x) | "Final gelöscht" target | grep `app/src` result | Verdict |
|---|---|---|---|
| `KeyboardLayoutModeController` | Spec 2 §9.1 (parent C15) | source absent; 5 comment-only anchors remain | ✅ PASS (already deleted; anchors are intentional C15 doc-trail, not code) |
| `MainButtonsController` | Spec 2 §9.2 (this block, intended) | source present + ~12 live IME/render refs | ⛔ **cannot run as PASS** — class must survive (architecture-conflict) |
| `KeyboardStateManager` | Spec 2 §9.3 (this block, intended) | source present + ~15 live refs | ⛔ same |
| `RecordingUiController` | Spec 2 §9.4 (this block, intended) | source present + ~20 live refs | ⛔ same |
| `KeyboardUiController` | Spec 2 §9.5 / Spec 1 §9.2 (this block, intended) | source present + ~25 live refs | ⛔ same |
| `PipelineOrchestrator` | Spec 1 §9.6 **NEVER deleted** | present, reachable only via adapter+carve-out, KDoc-annotated | ✅ PASS (OQ-1 — correctly kept) |

#### OQ-1 PipelineOrchestrator boundary confirmation + KDoc

- **Delegation proven (not reimplementation):**
  `PipelineRunnerSubsystemAdapter` (`core/PipelineRunnerSubsystemAdapter.kt:82-118`)
  → `JobExecutor.INSTANCE.start/cancel` + `ActiveJobRegistry`. `JobExecutor`
  bound to the body via `JobExecutor.initialize(orchestrator: PipelineOrchestrator)`
  → `PipelineOrchestratorRunner(orchestrator)` (`core/JobExecutor.kt:56-57,347-348`).
- **Sole-caller grep:** no state-router other than `DictateOrchestrator`
  reaches `PipelineOrchestrator`; the direct IME callers are only
  standalone-prompt + cancel + RESUME carve-out. AC-10 (no double-dispatch)
  holds.
- **KDoc added:** `core/PipelineOrchestrator.kt` class header now carries the
  "Cutover boundary — `PipelineRunnerSubsystem` adaptee (OQ-1, Spec 1 §9.6)"
  section with the full delegation diagram + `@see` anchors.

#### C5-IMPL-2 resolution note

C5-IMPL-2 (B2, Important, deferred to Theme-C): *"legacy recording-UI /
animation sites read Idle on the new path — C10's RenderBackend-sole-render-
path scope addresses the controller side."* **Resolution: NOT resolvable in
this chunk — it is a symptom of the same root cause as IMPL-2.** The legacy
recording-UI/animation sites (`RecordingUiController` / `KeyboardUiController`)
still *exist and are still IME-driven* precisely because the render path is
**not** solely RenderBackend (the controllers were never retired — premise
false). C5-IMPL-2 cannot close until the render-path cutover (the missing
block) wires the new render controllers into the IME and removes the legacy
driver calls. It is **folded into IMPL-2** (same architecture-conflict; same
mid-chunk-triage). It is **not** independently fixable here without the
controller deletions, which are blocked.

#### Disjoint commit-boundary lists

**=== COMMIT 1 BOUNDARY === production files:**
- `app/src/main/java/net/devemperor/dictate/core/PipelineOrchestrator.kt` (OQ-1 KDoc boundary annotation only — additive doc, no behaviour change)

**=== COMMIT 2 BOUNDARY === test files:**
- (none — no production behaviour changed, so no test added/changed. The 4 controller deletions that would have driven test changes are blocked.)

(lists are disjoint — production vs test; test list intentionally empty)

#### Deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Dev-1 (chunk-local) | Epic §4 Block C3 / chunks.json `id: C10-C3` ("Delete … MainButtonsController, RecordingUiController, KeyboardUiController, KeyboardStateManager") | The 4 controller deletions were **not performed**; only OQ-1 (KDoc) + the already-gone confirmation done | The chunk's stated precondition ("Theme B + the parent RenderBackend path made these dead") is factually false: the render-path cutover never happened (Theme B = recording-drive only); the new render owners exist but are **not IME-wired** (`staticHandlerInstaller=null`, no `ContentAreaController`/`PromptVisibilityController` attach); RECORD/BACKSPACE long-press explicitly deferred by parent B4-VAL F-1/F-2 to a never-created follow-up. Deleting now erases unported core features (subtle R-risk). D4 correctness-over-speed + the prompt's explicit "do NOT delete a class whose behaviour isn't provably ported". | **All of D1, D2** (Espresso UI-tests + integration E2E assume the render path is solely RenderBackend post-C3 — e2e-runbook TC-21 explicitly states "C3 deleted the legacy controllers"; that assumption is now invalid until the render-path cutover lands). Block-validate + Phase-4 must treat the render-path-cutover as missing scope. | **delegated — flagged Critical `architecture-conflict` (IMPL-2)**; NOT inline-fixed (larger + needs new module work, not research) |

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-2 | **Critical** | **Architecture-conflict: the render-path cutover block does not exist.** Chunk C10-C3 requires deleting `MainButtonsController`/`RecordingUiController`/`KeyboardStateManager`/`KeyboardUiController` on the premise that Theme B + the parent RenderBackend path made them dead. Per-class responsibility-trace (this report) proves the premise false: `ImeViewBackend` is attached *in parallel* to the legacy controllers (IME `:1023`), wires plain-click + RESEND-long-press only, `staticHandlerInstaller=null` (IME `:1113`); the new `ContentAreaController`/`PromptVisibilityController`/`OverlayResetHandler` are **not IME-attached**; RECORD long-press (2-mode) + BACKSPACE accel-delete + SPACE/ENTER touch handlers + theming + key-press-anim + QWERTZ rec-button + prompts-visualizer + the entire pipeline-progress/step-row UI have **no ported new owner** (parent B4-VAL F-1/F-2/F-33 deferred the IME-side render attach + long-press port to a "B5/B7 / D-13 follow-up" that was never created — the exact INT-1 pattern, recurring at the render layer). Deleting strands core user features. **Needs a new render-path-cutover block** (wire `ImeViewBackend.staticHandlerInstaller`, model RECORD/BACKSPACE long-press as Actions, attach `ContentAreaController`/`PromptVisibilityController`/`OverlayResetHandler`, port QWERTZ + pipeline-progress + prompts-visualizer, then remove the IME legacy-driver calls) **before** C10-C3's deletions are safe. Subsumes C5-IMPL-2 (same root cause). | delegated-to-orchestrator | `architecture-conflict`, blocks-following-chunks (D1/D2 assume sole-RenderBackend post-C3). New module work, not research → mid-chunk-triage / Epic re-scope decision required (mirrors INT-1's "the cutover was forwarded to a block that never existed"). Not inline-fixed (D7: architecture conflict → delegate Critical; the prompt's R-mitigation explicitly forbids deleting unported behaviour). |
| IMPL-3 | Nice-to-have | OQ-1 KDoc boundary added to `PipelineOrchestrator.kt`; consider a lightweight architecture-test asserting `PipelineOrchestrator` has no caller outside `{PipelineRunnerSubsystemAdapter, JobExecutor, standalone/cancel/RESUME carve-out}` so the AC-10 "no second state-router" invariant is regression-guarded. Not done here (no test commit this chunk); noted for D1/D2 or a follow-up. | open | Documentation/guard nicety; AC-10 currently holds by inspection. Out of this (blocked) chunk's deliverable scope. |

#### Code-Bugs Found While Writing Tests (Step 4)

(none — no tests written. No production behaviour changed: the only edit is
the additive OQ-1 KDoc on `PipelineOrchestrator.kt`. The 4 controller
deletions that would have necessitated test changes are blocked by IMPL-2.)

#### Test-Review (Step 5)

(n/a — no test diff. The OQ-1 KDoc is a non-behavioural doc annotation;
existing `PipelineOrchestrator`/adapter/JobExecutor tests already cover the
delegation chain it documents. No coverage gap introduced.)

#### Files modified — drift classification

- **In plan-prescribed scope:** `core/PipelineOrchestrator.kt` (Epic §4
  Block C3 + chunks.json: "keep `core/PipelineOrchestrator.kt` (annotate it
  as the `PipelineRunnerSubsystem` adaptee per Spec 1 §9.6)" — the KDoc
  annotation is exactly the prescribed OQ-1 deliverable).
- **Files in plan-prescribed scope NOT modified (blocked):**
  `core/MainButtonsController.kt`, `core/RecordingUiController.kt`,
  `core/KeyboardUiController.kt`, `core/KeyboardStateManager.kt` (+ their
  tests + residual IME refs) — deletion blocked by IMPL-2 (architecture-
  conflict; premise false).
- **Files outside plan-prescribed scope (drift):** none.

#### Overlooked points / known gaps

- **Block-Validate / Phase-4 must re-scope.** The Epic's dependency graph
  (§4.1) and e2e-runbook (TC-21 "C3 deleted the legacy controllers") assume
  C10-C3 retires the render controllers. That assumption is invalid. D1
  (Espresso UI-tests "test the real path not the dead one") and D2
  (integration E2E "render path solely RenderBackend") inherit the blocker.
  Recommend the orchestrator treat IMPL-2 like INT-1: a missing-block
  escalation, not a chunk-local fix. The honest options are (a) author the
  render-path-cutover block and run it before C10-C3's deletions, or
  (b) accept the legacy render path as the permanent render owner and
  rewrite AC-7 + the e2e-runbook to match (the D7 anti-pattern — flagged,
  not chosen by me).
- **OQ-1 is fully closed** independently of IMPL-2 — it was a confirmation
  + annotation, not a deletion, so it is unaffected by the controller
  blocker. `PipelineOrchestrator` correctly survives as the adaptee.
- **C8-IMPL-1** (`LegacyAudioFileMigrationTest` release-suite flake) remains
  delegated to B3 AUDIT-TEST — untouched by this chunk (no test/DB-axis
  edit; only an additive KDoc).
- I did **not** run `./gradlew assembleDebug` / `test` as a chunk gate: the
  only change is an additive KDoc comment on `PipelineOrchestrator.kt`
  (cannot affect compilation or tests). The build/test acceptance (AC-7/AC-9
  baseline ~1048) is unmet *by design* this chunk because the deletions that
  would exercise it are blocked; Block-Validate / the re-scoped block owns
  the green-build gate once the render-path cutover lands.

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ · **Pre-Validate Commit:** ⏳ · **Validate-Pass Commit:** ⏳

| Topic | Agent-ID | Status | Output File | Findings |
|-------|----------|--------|-------------|----------|
| plan-and-api | `B3-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B3.md` | — |
| convention | `B3-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B3.md` | — |
| logic | `B3-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B3.md` | — |
| test | `B3-AUDIT-TEST` | ✅ done | `./reports/audit-test-B3.md` | 1 Important (C8-IMPL-1 root-caused: `DurationHealingJob` async axis — precise fix specified, → repair-sub-phase per D3) + 2 Important (doc-gap: C8/C9 "debug green" claim falsified) + 1 Nice-to-have (R-3 onServiceConnected coverage gap). **NO non-C8-IMPL-1 regression** — 1047/1048 green every run; the single failure is the forwarded C8-IMPL-1 pollution flake in BOTH variants (3/3 uncached runs). |

### Block-Validate Sanity-Check (B3-VAL-SANITY)

**Agent-ID:** `B3-VAL-SANITY` · **Output:** `./reports/validated-findings-B3.md`
**Date:** 2026-05-16 · **Source audits:** 4 (plan-and-api / convention / logic / test), read in full.

**What was done:** Consolidated all 4 B3 audit outputs. Raw input: 0 Critical / 2 Important / 7 Nice-to-have-class observations + 1 doc-gap, across the 4 audits — a clean block (the only failing test is the forwarded C8-IMPL-1 pollution flake, 1047/1048 green every uncached run; zero real C8/C9 regressions). De-duplicated (the dual-carrier ReprocessStaging override was flagged from 3 angles → merged into one), validated each finding, classified 🟢/🟡/❌ per D3 (every real finding incl. NTH repaired in this block; only genuine intentional-deferred → ❌/tracked).

**Classified findings:**

| Issue-ID | Was | Verdict | Severity | Routing |
|----------|-----|---------|----------|---------|
| F-1 | AUDIT-TEST C8-IMPL-1 | 🟢 valid+auto-fixable | Important | repair-sub-phase, B3 (forwarded Postponed test-debt **actioned now per D3**, NOT re-postponed) |
| F-2 | AUDIT-TEST-B3-1 | 🟢 valid+auto-fixable | Important | repair-sub-phase, B3 (doc-correction; co-commit with F-1) |
| F-3 | AUDIT-CONVENTION-B3-1 | 🟢 valid+auto-fixable | Nice-to-have | repair-sub-phase, B3 (dedup + **blank-guard-drift normalise** — B3-local, behaviour-preserving) |
| F-4 | AUDIT-LOGIC-B3-2 | 🟢 valid+auto-fixable | Nice-to-have | repair-sub-phase, B3 (one clarifying comment at `inputLanguagesListener`) |
| F-5 | AUDIT-PLAN-AND-API-B3-2 | 🟢 valid+auto-fixable | Nice-to-have | repair-sub-phase, B3 (one-line §9.6-vs-LanguageResolver reconciliation doc-note) |
| F-6 | AUDIT-PLAN-AND-API-B3-1 + AUDIT-LOGIC-B3-1 + conv-1 footnote (merged) | ❌ deferred-with-rationale | Nice-to-have (informational) | **NOT B3** — genuine intentional transitional duplication; the *collapse* is the missing B5/render-path-cutover block's job (folded into C10-IMPL-2). **Tracked-for-B5** (see Issue Index). |
| (none) | AUDIT-CONVENTION-B3-2 | ❌ false-positive-as-finding | — | No action — the broad `catch (Throwable t)` is **byte-consistent with the pre-existing in-file convention**; auditor flagged only so it is not re-raised as new drift. |
| (none) | AUDIT-LOGIC-B3-3 + AUDIT-TEST R-3 coverage row | ❌ deferred-with-tracking | Nice-to-have | **Known inherent gap** — IME-service binder-lifecycle untestability predates B3; reducer end *is* covered (real-orchestrator AC-5 test). NOT B3-introduced; recorded as known intentional gap. |

**Counts:** 🟢 5 (Important 2, NTH 3) · 🟡 0 · ❌ 3 (1 tracked-for-B5, 1 false-positive, 1 known-inherent-gap).

**C8-IMPL-1 classification (the priority item):** 🟢 valid + auto-fixable, Important, **fixed in this block per D3** (it is the forwarded Postponed test-debt being actioned now, NOT re-postponed). AUDIT-TEST fully root-caused it (`DurationHealingJob` async axis spawned by `DictateApplication.onCreate()`; smoking-gun: the `"Audio file not found during healing"` string exists in exactly one place, `DurationHealingJob.kt:61`) and fully specified the fix (extract a production-owned `DurationHealingScheduler` holder with `@JvmStatic @VisibleForTesting resetForTest()` mirroring the `DictateDatabase`/`JobExecutor`/`ActiveJobRegistry.resetForTest()` convention; call it from `LegacyAudioFileMigrationTest` @Before/@After before `DictateDatabase.resetForTest()`, + the `DictatePipelineService*` boot-test teardowns). Test-infra only, no behavioural production change. Mechanical + fully determined → 🟢.

**NTH defer-to-B5 vs fix-now split:**
- **Fix-now in B3:** F-3 (dedup + blank-guard-drift normalise — the duplicated read lives entirely inside C8-C1's *own new* helpers; the blank-guard drift is a latent correctness smell; B3-local + behaviour-preserving), F-4 (one comment), F-5 (one-line doc note).
- **Defer-to-B5 (genuine intentional, tracked):** F-6 — the *cross-carrier collapse* (unify `resolveEffectiveLanguage()` + `handleReprocessSend` + `PipelineModule` onto `LanguageState.override`, remove the legacy `PipelineUiState.ReprocessStaging.selectedLanguage` carrier read). It depends on the legacy `KeyboardUiController`/`PipelineUiStateReader` retirement and is the work the new B5/render-path-cutover block owns (C10-IMPL-2). Fixing it in B3 would pre-empt the blocked cutover (D7 anti-pattern). **F-3 and F-6 do not overlap** — F-3 de-duplicates the legacy-carrier *read* within B3 code; F-6 is the larger cross-carrier *collapse*.

**Tracking obligation (orchestrator):** the new B5/render-path-cutover block scope must explicitly inherit the F-6 "collapse the ReprocessStaging override read onto `LanguageState.override`; remove the legacy carrier read" item that the C8-C1 "Overlooked points" hands off (folded into the C10-IMPL-2 render-path-cutover umbrella) so the transitional dual-carrier does not become permanent when `KeyboardUiController` is retired.

**Validated-no-residual:** AC-5 PASS, AC-6 PASS, R-3 SOUND, override-vs-rebind clobber CANNOT-HAPPEN, F-15 side-effect REAL+CORRECT+COMPLETE, R-5 GREEN, C10-C3-only-OQ-1-landed CORRECT — all independently confirmed across ≥2 audits; no action. All C8/C9 documented deviations (Dev-1 RefreshFromPref payload, Dev-2 onServiceConnected re-push, Dev-3 audioFileOrNull accessor) re-scrutinised spec-faithful with no logic hole.

> Do NOT fix (consolidator phase). The orchestrator may resume this agent as `B3-VAL-REPAIR` with `implement-fixes.md` to apply F-1…F-5.

### Block-Validate Repair Wave 1 (B3-VAL-REPAIR-1)

**Agent-ID:** `B3-VAL-REPAIR-1` → `B3-VAL-REPAIR-1-VERIFY` · **Wave:** B3-VAL-W1, iter 1
**Date:** 2026-05-16 · **Scope:** green-only (F-1…F-5) · **Findings addressed:** 5 🟢 (2 Important, 3 NTH). F-6 + 2 ❌ recorded-not-fixed (see below).

| Finding | Sev | What was done | Status |
|---------|-----|---------------|--------|
| F-1 | Important | New production-owned holder `database/DurationHealingScheduler.kt` (Kotlin sibling-convention) owning the `ExecutorService`; `schedule(dao, repo)` keeps the exact prior `DictateApplication.onCreate` async-single-shot semantics; `@JvmStatic @VisibleForTesting internal fun resetForTest()` drains the in-flight heal. `DictateApplication.onCreate()` (Java, in place) now calls `DurationHealingScheduler.schedule(...)` instead of inlining `Executors.newSingleThreadExecutor()`. `LegacyAudioFileMigrationTest` @Before+@After call `DurationHealingScheduler.resetForTest()` **before** `DictateDatabase.resetForTest()` (mandatory ordering). Belt-and-suspenders: same call added before `DictateDatabase.resetForTest(...)` in 5 `DictatePipelineService*`/cutover boot-test teardowns. | **fixed** |
| F-2 | Important | Corrected 3 block-report subsections (C8-C1 IMPL-1 Issues row; C9-C2 "Resend + migration still work"; C9-C2 "Build + test (AC-9)") — the false "release-only / `testDebugUnitTest` always green (1048/0/0)" claims replaced with the verified truth: the C8-IMPL-1 flake manifested in **BOTH** variants non-deterministically, 1047/1048 every uncached run, now fixed. | **fixed** |
| F-3 | NTH | De-duplicated the byte-identical `uiController != null && state instanceof ReprocessStaging` detection in `resolveEffectiveLanguage()` + `setLanguageFromPicker()` into one private `reprocessStagingOrNull()`; added `reprocessStagingOverrideOrNull()` as the **single guarded reader** (trimmed-non-blank). `resolveEffectiveLanguage()` uses the guarded reader; `setLanguageFromPicker()` routes on `reprocessStagingOrNull() != null` — **routing semantics unchanged** (in-staging → transient regardless of override blankness; picker `code` is resource-array-derived, never blank). Blank-guard drift normalised into the one reader. | **fixed** |
| F-4 | NTH | Added the clarifying comment at the `inputLanguagesListener` site (override still wins for display via `resolveEffectiveLanguage()`; this listener only moves the orthogonal permanent `effective` axis — not a clobber; cross-refs `LanguageModuleTest:78`). | **fixed** |
| F-5 | NTH | Added the one-line §9.6-vs-`LanguageResolver` reconciliation note to the C8-C1 "Files modified — drift classification" section (the pref-only algorithm in `preferences/LanguageResolver` invoked Pre-Dispatch per §4.11 satisfies §9.6 without violating the ADR-0002 pure-reducer invariant). | **fixed** |
| F-6 | NTH (info) | **NOT fixed — deferred-with-tracking to B5** per validated-findings (genuine intentional transitional dual-carrier; the cross-carrier *collapse* is the missing render-path-cutover block's job, folded into C10-IMPL-2). F-3 de-dups only the *legacy-carrier read* within B3 code and does **not** touch the cross-carrier collapse — disjoint from F-6. Recorded in Issue Index as a B5 carry-over. | recorded-not-fixed |

**DurationHealingScheduler design.** Production-owned `object` in `database/` (sibling to `DurationHealingJob`, mirroring the `DictateDatabase`/`JobExecutor`/`ActiveJobRegistry.resetForTest()` convention — K-1, no Mockito). `schedule()` creates the single-thread executor, submits `DurationHealingJob.heal`, `shutdown()`s — byte-identical production semantics to the old inline code (still async, still single-shot, still shut down after enqueue; no behavioural production change). `resetForTest()` does a **graceful** `shutdown()` + `awaitTermination(10s)`.

**Deviation D22 (F-1 drain mechanic).** AUDIT-TEST's spec said `shutdownNow()` + `awaitTermination()`. First implementation followed that literally and **regressed**: `shutdownNow()` interrupts the heal thread mid-Room/SQLite-native-call, which corrupts Robolectric's process-wide native SQLite runtime → a release-suite-wide `UnsatisfiedLinkError: SQLiteConnectionNatives.nativeOpen` cascade (10 failures), *and* failed to actually stop the JNI-blocked heal (debug `LegacyAudioFileMigrationTest` still polluted: `non-legacy-path row … expected:<RECORDING> but was:<FAILED>`). Switched to a **graceful** `shutdown()` + `awaitTermination(10s)` (no interrupt): the in-flight heal runs to completion uninterrupted against the *old* DB (harmless — it is dropped immediately after), `awaitTermination` blocks the caller until done, so the heal can never reach the next test's rebuilt DB. Small + locally-decidable correction of a mechanically-wrong spec detail; same seam shape + same call sites; documented in the holder's KDoc. → inline-fixed, `plan-deviation-resolved`.

**Self-check (B3-VAL-REPAIR-1-VERIFY).** `./gradlew assembleDebug` BUILD SUCCESSFUL. `./gradlew test --rerun-tasks` for `testDebugUnitTest` + `testReleaseUnitTest`, **3 uncached runs in varying order** (run1 both-in-one-invocation; run2 release-then-debug separate invocations; run3 both-in-one): **every run 1048/1048, 0 failures, 0 errors, both variants.** `LegacyAudioFileMigrationTest` 8/8 green both variants every run — the C8-IMPL-1 flake is **GONE**. F-3 behaviour-preservation confirmed: `LanguageModuleTest` 10/10 (incl. AC-5 real-dispatch propagation + `RefreshFromPref does not clear an active override`:78), `LanguageResolverTest` 11/11, both variants. No new issues; no self-corrections needed beyond the D22 drain-mechanic fix folded into this wave. Convergence: ✓ converged.

**Files modified (wave-commit — DISJOINT production vs test):**

*Production (Commit-wave):*
- `app/src/main/java/net/devemperor/dictate/database/DurationHealingScheduler.kt` (new — F-1)
- `app/src/main/java/net/devemperor/dictate/DictateApplication.java` (F-1 — use holder; removed inline executor + unused imports)
- `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (F-3 dedup + guarded-reader; F-4 comment)

*Test:*
- `app/src/test/java/net/devemperor/dictate/migration/LegacyAudioFileMigrationTest.kt` (F-1 — @Before/@After scheduler drain before DB reset)
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceRecordingDriveTest.kt` (F-1 belt-and-suspenders teardown)
- `app/src/test/java/net/devemperor/dictate/core/ImeRecordingDriveCutoverTest.kt` (F-1 belt-and-suspenders teardown)
- `app/src/test/java/net/devemperor/dictate/core/DictateCutoverE2ETest.kt` (F-1 belt-and-suspenders teardown)
- `app/src/test/java/net/devemperor/dictate/core/PipelineRunnerSubsystemAdapterTest.kt` (F-1 belt-and-suspenders teardown)
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceOverlayTransitionTest.kt` (F-1 belt-and-suspenders teardown)

*Documentation (this block-report — F-2, F-5, this subsection, Issue Index, Deviation Summary):*
- `docs/plans/2026-05-15 - dictate-cutover-completion/reports/B3-theme-c-legacy-retire.md`

**Files outside findings-scope (drift):** none — the wave-diff stays within the loci named in F-1…F-5 (the 5 belt-and-suspenders boot-tests are explicitly named in F-1's "Files" + suggested-fix step 3).
**Cross-fix conflicts:** none (F-3 and F-6 are disjoint by construction; F-1 prod/test split is clean).

### Block-Validate Repair Wave (B3-VAL-REPAIR)

(superseded — applied as B3-VAL-REPAIR-1 above, wave B3-VAL-W1 iter 1)

### Mini-Triage + Repair-Wave(s)

(Per iteration, max 3 per D5 soft-cap.)

---

## Block Deviation Summary

| # | Plan Location | What changed | Why | Impact | Inline-fixed | Source-Agent | Source-Step |
|---|---------------|--------------|-----|--------|--------------|--------------|--------------|
| Dev-1 | Epic §4 Block C1 (RefreshFromPref dispatch pattern) | `RefreshFromPref` `data object` → `data class(effective)`; reducer writes `effective` | Phase-1 reducer was a no-op + PrefMirror does not mirror language ⇒ `LanguageState.effective` would stay `"system"` (latent F-15 bug); Spec 1 §4.11 + module KDoc anticipated the payload promotion | Any future LanguageAction consumer; placeholder test usages updated; F-15 now live | yes (`plan-deviation-resolved`) | `B3-C8-C1-IMPL` | Step 2 |
| Dev-2 | Epic §4 Block C1 (caller graph / R-3) | `onServiceConnected` re-pushes `pushPermanentLanguageToOrchestrator()` | Boot-before-bind race: onCreateInputView push runs before binder arrives → `effective` stays `"system"`; plan mandates documenting the ordering + `pipelineBinder != null` discipline | IME-internal, idempotent; closes R-3 silent-stale risk | yes | `B3-C8-C1-IMPL` | Step 2 |
| Dev-3 (chunk-local Dev-1) | Epic §4 Block C2 ("Files: `DictateInputMethodService.java` only") | Added `RecordingState.audioFileOrNull` extension to `state/DictateUiState.kt` | The plan also mandates "recording-active reads → orchestrator state (`state.recording`)"; sourcing the sealed-`RecordingState` `audioFile` from Java needs a canonical accessor — project convention is a centralised extension next to the FSM def (`isActiveOrPaused` sibling). Most plan-compatible/DRY. Additive, no behaviour change. | C10-C3 may consume the same canonical accessor when collapsing legacy recording-UI reads | yes (small + locally decidable — plan's own state-source mandate forces it) | `B3-C9-C2-IMPL` | Step 2 |
| Dev-4 (chunk-local Dev-1) | Epic §4 Block C3 / chunks.json `C10-C3` ("Delete MainButtonsController/RecordingUiController/KeyboardUiController/KeyboardStateManager") | The 4 controller deletions **not performed**; only OQ-1 KDoc + already-gone confirmation done | Chunk premise false — render-path cutover never happened (Theme B = recording-drive only); new render owners exist but are not IME-wired; RECORD/BACKSPACE long-press deferred by parent B4-VAL F-1/F-2 to a never-created block. Deleting strands unported core features (subtle R-risk; prompt forbids deleting unported behaviour; D4). | All of D1/D2 (assume sole-RenderBackend post-C3 — e2e-runbook TC-21). Render-path cutover is missing scope. | **delegated — Critical `architecture-conflict` (C10-IMPL-2)**; NOT inline-fixed (needs new module work) | `B3-C10-C3-IMPL` | Step 1 |
| Dev-5 (B3-VAL-W1, F-1) | AUDIT-TEST C8-IMPL-1 suggested-fix step 1 (`shutdownNow()` + `awaitTermination()`) | `DurationHealingScheduler.resetForTest()` uses **graceful** `shutdown()` + `awaitTermination(10s)` instead of `shutdownNow()` | `shutdownNow()` interrupts the heal mid-Room/SQLite-native-call → corrupts Robolectric's process-wide native SQLite runtime (release-suite-wide `UnsatisfiedLinkError` cascade, 10 fails) **and** fails to stop the JNI-blocked heal (debug pollution still fired). Graceful drain lets the in-flight heal complete uninterrupted against the old DB (dropped right after) — no native corruption, deterministic. Empirically reproduced both failure modes before switching. | None beyond F-1's own seam — same call sites, same seam shape; documented in the holder KDoc + the B3-VAL-REPAIR-1 subsection. Verified 1048/1048 ×3 uncached both variants. | yes (`plan-deviation-resolved` — small + locally-decidable correction of a mechanically-wrong spec detail) | `B3-VAL-REPAIR-1` | Block-Validate repair wave B3-VAL-W1 |

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step, both commits):** ✅ C8-C1 (6de54b1+93f86d6) · C9-C2 (bd82070+ccb38c2). C10-C3 MOVED to B5 per C10-IMPL-2 — only its OQ-1 PipelineOrchestrator KDoc landed here (185f3f6).
- **Block-Validate converged:** ✅ 1 wave (B3-VAL-W1 80cdda2) — clean block, 0 Crit
- **AUDIT-TEST: coverage + no cross-chunk regressions:** ✅ C8-IMPL-1 DurationHealing flake CLOSED; 1048/1048 both variants ×3 uncached; LanguageControllerTest coverage preserved
- **Build green at block-end:** ✅ assembleDebug + test (AC-9 ≥946 holds)
- **Issue index reconciled:** ✅ C8-IMPL-1 + F-2..F-5 fixed; F-6 deferred→B5; 2 ❌ recorded
- **Cross-block consumer info forwarded:** ✅ AC-5 + AC-6 met. **F-6 cross-carrier collapse forwarded to B5 Theme-C-R** (depends on KeyboardUiController/PipelineUiStateReader retirement = render-cutover scope; folded into C10-IMPL-2). Latent F-15 RenderBackend "system" bug fixed as C8 side-effect.

**Note (Phase 4.7):** B3 narrowed mid-flight to {C8-C1,C9-C2} when C10's
per-class trace surfaced C10-IMPL-2 (render-path cutover never happened —
INT-1 pattern at the render layer). C10-C3 correctly moved to new B5
Theme-C-R (gated on a render verification gate). The R-mitigation
(mandatory per-class trace) prevented deleting controllers whose
behaviour was unported. Dev-5 = graceful-shutdown correction of
AUDIT-TEST's mechanically-wrong shutdownNow() spec detail.

**Block completed at:** 2026-05-16
**Block-End-Commit:** 80cdda2
**Cross-reference set in state file:** ✅
**Postponed issues forwarded:** F-6 → B5/C10-IMPL-2; 2 ❌ recorded-not-fixed
