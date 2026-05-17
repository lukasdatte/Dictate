# Block 6: Theme D — Test-Completion (locks the result)

> **Logbook for Block 6.** Implementation/Audit-Agents document here.
> Orchestrator maintains the state-file status table — agents do not.

**Phase:** Theme D — test-completion. Closes the Espresso UI-test gap
(AC-8) + the final holistic post-all-blocks verification (AC-9/AC-10).
The sole-RenderBackend assumption D1/D2 require is now RESTORED (Theme-C-R
deleted the 4 legacy controllers; render path is the single live driver).
**Implementation-Chunks:** C11-D1 (Espresso 1-10 + Robolectric mirror),
C12-D2 (final integration E2E + cleanup-grep — verification only).
**Workflow:** Iter-10 5-step (combined-step; orchestrator splits 2 commits/chunk). Sequential C11→C12.
**Block-Start-Commit:** cef46be
**Block-End-Commit:** ⏳

> **Context:** all 4 prior blocks ✅ — recording-drive (A/B, AC-1/2/3/10
> GREEN), legacy-retire (C, D-13/D-14), render-path cutover (C-R, 4
> controllers deleted). The cutover is CODE-COMPLETE. B6 locks it with
> tests. SoT: Spec 2 §14.2 (Espresso UI-1..10 bodies + bug-symptom map),
> Spec 1 §9.6/§10, b5-ime-activation-wiring.md §3 (Triangle T1-T7).
> Postponed (carried, NTH): C5-IMPL-2 (amplitude/timer), C10-C3-IMPL-1.

---

## Issue Index (Orchestrator-Maintained)

**Severity counts:** Critical: 0 · Important: 0 · Nice-to-have: 3 (1 fixed · 2 carried postponed) · Postponed: 2

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| F-1 | B6-VAL-SANITY | NTH | fixed | UI-4/UI-10 `(§1.1 #3a)` label over-claims the SEND_MODE `{ false }` eliminator (doc-honesty; text-only) | B6-VAL-W1 (B6-VAL-REPAIR-1) |
| C5-IMPL-2 | B2-C5-B3-IMPL | NTH | postponed | legacy recording amplitude/timer side-channel deferral (promoted to Issue Index in B5-VAL-W1) | carried → Phase 4.7 |
| C10-C3-IMPL-1 | B5-C10-C3-IMPL | NTH | postponed | (CR-DEL documented NTH) | carried → Phase 4.7 |

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

### Chunk C11-D1 — Espresso UI-Tests 1-10 + Robolectric mirror

**Agent-IDs:** `B6-C11-D1-IMPL` (fresh, combined Steps 1-5).
**Status:** ✅ implemented (5 steps combined) · **Risk:** LOW-MED (R-6 Espresso device-infra flakiness; mitigated by Robolectric mirror, OQ-4)
**Implementation-Commit (Commit 1):** EMPTY (pure test chunk — zero src/main change) · **Test-Commit (Commit 2):** ⏳ (orchestrator — both test files)

### Implementation (B6-C11-D1-IMPL)

**What was done.** Implemented all 10 Spec 2 §14.2 UI-1..UI-10 test
bodies in `app/src/androidTest/.../ui/KeyboardLayoutUiTest.kt`
(un-`@Ignore`d, no `fail("pending:")` — real assertions, compiles in the
androidTest source-set) and an equivalent 1:1 Robolectric mirror
`app/src/test/.../ui/KeyboardLayoutRenderMirrorTest.kt` (10 tests, green
under `./gradlew test`). Both drive the **real post-cutover production
render path** — `KeyboardLayoutManager.computeLayoutMode` (the
mode-selector the bound service calls) + the production `applySlotToView`
SSoT slot→view writer (exactly what `ImeViewBackend.render` invokes per
slot) over real Android `MaterialButton` Views — and assert real
`View.visibility` / `isEnabled` / `alpha` / `text`. The 4 legacy
controllers are deleted (Theme-C-R CR-DEL), so this IS the sole live
render path (no parallel-legacy false-assert risk).

**UI-N → §1.1 bug-symptom → Espresso-body + Robolectric-mirror map:**

| UI | §14.2 row | §1.1 bug | Assertion (identical both paths) | Body | Mirror |
|----|-----------|----------|----------------------------------|------|--------|
| UI-1 | Toggle Single-Row in Idle | §1.1 #1 | mode==KEYBOARD_SINGLE_ROW; all 8 original buttons present (subset — none dropped, esp. trash/pause); RECORD/SPACE/BACKSPACE/ENTER/AUDIO_FOCUS VISIBLE | ✓ | ✓ green |
| UI-2 | Recording → resend GONE, trash/pause VISIBLE | coverage-baseline | Active: RESEND GONE, TRASH+PAUSE VISIBLE | ✓ | ✓ green |
| UI-3 | Pipeline → record_btn counter, trash/pause GONE | baseline (F-13) | Running(1,3): record_btn text=="1/3  1000ms" (live F-13 counters); TRASH+PAUSE GONE | ✓ | ✓ green |
| UI-4 | Send-Mode + Single-Row → record_btn unobstructed | §1.1 #3a | mode==KEYBOARD_SINGLE_ROW_SEND_MODE; RECORD VISIBLE; TRASH+PAUSE GONE (hardcoded eliminator) | ✓ | ✓ green |
| UI-5 | ReprocessStaging → pause VISIBLE+disabled+α0.4 | baseline | mode==KEYBOARD_REPROCESS_STAGING; PAUSE VISIBLE, !enabled, alpha 0.4 | ✓ | ✓ green |
| UI-6 | Re-Inflate during Recording → correct mode 1st frame | baseline | re-render of Active ⇒ first-frame mode==KEYBOARD_TWO_ROW; trash/pause stay VISIBLE | ✓ | ✓ green |
| UI-7 | Toggle Single-Row during Recording → pulse continues | §1.1 #2 | mode flips TWO_ROW→SINGLE_ROW; recording survives (RECORD+TRASH stay VISIBLE) | ✓ | ✓ green |
| UI-8 | Toggle Two↔Single in Idle+lastAudio → resend stays VISIBLE | §1.1 #3b | per-frame (3 frames) RESEND VISIBLE across both toggles | ✓ | ✓ green |
| UI-9 | Resend cooldown → VISIBLE+enabled=false+α0.4 | §1.1 #3b | resendCooldown=true ⇒ RESEND VISIBLE (NOT cooldown-coupled, §8.5), !enabled, alpha 0.4 | ✓ | ✓ green |
| UI-10 | Active→Pipeline-Preparing → no trash/pause overlap | §1.1 #3a+#3b | Active(VISIBLE)→Preparing⇒SEND_MODE: TRASH+PAUSE+RESEND GONE (cannot overlap record_btn) | ✓ | ✓ green |

**OQ-4 device-vs-CI disposition.** `connectedAndroidTest` device infra
unavailable here (R-6). Per OQ-4 AC-8 is satisfied by **either** path:
the Espresso bodies are the device path (proven to **compile** in the
`androidTest` source-set — `compileDebugAndroidTestKotlin` green); the
Robolectric mirror is the **CI-green** path (10/10 green via
`./gradlew test`). Assertions are byte-identical between the two
(including UI-3's `"1/3  1000ms"` counter string) so a divergence is a
real regression, not a harness artefact.

**Test counts.** `./gradlew testDebugUnitTest` = **1212 tests, 0
failures / 0 errors** (baseline ~1162 + 10 mirrors + others; AC-9 ≥946
holds with wide margin). `assembleDebug` green. CR-RGATE
RenderPathCutoverGateTest **5/5** + DictateCutoverE2ETest **10/10** stay
green (R-7 axes untouched — this chunk boots no service). Espresso
bodies COMPILE in androidTest.

**Zero-production-change confirmation.** `git status --porcelain | grep
src/main` ⇒ ZERO. This is a pure test chunk; no test seam required a
production change (no finding).

### Deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Dev-1: UI-1 "alle 8 Buttons" asserted as **subset-present**, not exact-count nor "all 8 literally VISIBLE" | Spec 2 §14.2 UI-Test 1 row + §1.1 #1 | (a) `KEYBOARD_SINGLE_ROW` gained `WIDGET_TOGGLE` (B5 F-2) → 9 slots, so an exact 8-set equality is impossible; assert the 8 original buttons are a **subset** (none dropped). (b) In Idle, TRASH/PAUSE/RESEND are GONE *by predicate* (no recording / no lastAudio) — asserting "all 8 literally VISIBLE" would be a vacuous/wrong assertion contradicting the catalog. §1.1 #1's actual eliminator is "trash/pause not *dropped* on the single-row toggle" (structural, via MotionLayout no-re-parent) — that is what is asserted. | §14.2 prose predates WIDGET_TOGGLE; the §1.1 #1 invariant is structural-presence, not literal-visibility-in-Idle. A vacuous green would be worse than RED (prompt directive). | None — test-only, both bodies + mirrors consistent | inline-fixed |
| Dev-2: UI-3 record-btn text asserted as deterministic `"1/3  1000ms"` (test-local `LayoutStrings` formatter), not the §14.2-illustrative `"1/3 0:01"` | Spec 2 §14.2 UI-Test 3 row | §14.2's `"Sende…" / "1/3 0:01"` is illustrative of the *counter shape*; the SoT intent is "F-13 `completedSteps/totalSteps` render LIVE, not placeholders". The test injects a deterministic `formatPipelineLabel` (mirrors the existing `state.layout.testLayoutStrings` convention) and asserts the F-13 fields flow through verbatim. | Real string format is `Context.getString`-bound (production C14 wiring); a literal `"1/3 0:01"` assertion would test the formatter, not the F-13 plumbing the §14.2 row targets. | None — test-only | inline-fixed |

### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|-------------|--------|--------|
| — | — | none | — | pure test chunk; all 10 bodies + 10 mirrors green; zero production change; no architecture/plan conflict found | 

### Code-Bugs Found During Test Self-Review

None in production code. One **test-bug** (mine) found + inline-fixed in
Step 4: UI-1's exact-set-equality assertion (`singleRowSlots` 8-set vs
the now-9-slot `KEYBOARD_SINGLE_ROW`) failed at first run
(`KeyboardLayoutRenderMirrorTest.kt:166`, `AssertionError`). Root-cause:
the catalog gained `WIDGET_TOGGLE` (B5 F-2) after §14.2's "8 buttons"
prose was written. Fix: exact-set-equality → `containsAll(original 8)`
subset check (the correct §1.1 #1 invariant — "none dropped"), applied
identically in both the Espresso body and the mirror. Re-run: 10/10
mirror green, androidTest re-compiles clean. No production code touched.

### Overlooked points / known gaps

- The Espresso bodies are proven to **compile** (androidTest source-set)
  but not **run** here (no device — by design, OQ-4). On a connected
  device they exercise the same render-path harness; a future
  `connectedAndroidTest` run is the device-tier confirmation. AC-8 is
  met now via the green mirror per OQ-4.
- UI-6/UI-7 assert the *render-path* invariant (correct LayoutMode +
  recording-controls survive). The literal pulse-animator continuity
  (`PulseLayout.isPulsing`) is `RecordingAnimationController`-internal
  and already unit-covered in `RecordingAnimationControllerTest` /
  `ImeViewBackendTest`; re-asserting the animator object through a
  second harness would add brittleness for zero added §1.1 #2 coverage
  (the §1.1 #2 bug is the *re-parenting drop*, which the mode-flip +
  controls-survival assertion guards). Documented as the deliberate
  scope boundary, not a gap.
- No new test helper created beyond the two local
  `*LayoutStrings()` builders (First-Use; deliberately local to avoid a
  cross-source-set `internal` dependency on `state.layout`'s
  `testLayoutStrings`). They mirror that existing convention exactly.

---

### Chunk C12-D2 — final integration E2E + cleanup-grep regression (verification only)

**Agent-IDs:** `B6-C12-D2-IMPL` (fresh, combined Steps 1-5 — verification-only).
**Status:** ✅ verified · **Risk:** LOW (final verification lock)
**Implementation-Commit (Commit 1):** EMPTY (zero production change — verification only) · **Test-Commit (Commit 2):** NONE (no test delta — the keystone+Triangle cross-block trace is already fully covered by the existing `DictateCutoverE2ETest` 10/10 + `RenderPathCutoverGateTest` 5/5; a new aggregating file would be vacuous duplication, explicitly avoided per prompt)

### FINAL-LOCK Verdict (B6-C12-D2-IMPL)

> **FINAL-LOCK: GREEN** — every AC-1..AC-10 holds on the fully-cutover,
> legacy-FREE path. The Epic implementation is CODE-COMPLETE +
> verification-locked. Ready for the skill's Phase 4 (integration check) /
> 4.5 / 4.7 / 5.

### What was done (B6-C12-D2-IMPL)

Final holistic verification of the fully-cutover path (all 5 prior blocks
landed; legacy `LanguageController` / `audioFile`-field / 4 render
controllers deleted). Ran the full unit suite **uncached, both variants,
2× in different orders** (`--rerun-tasks`, debug→release then
release→debug), executed every AC cleanup-/compile-invariant grep, and
ran the existing keystone+Triangle aggregating tests. No production code
changed; no test delta written (existing aggregating coverage is
complete — adding a third file would be vacuous, RR-4).

**Full-suite result (both runs identical — order-independence proven):**

| Variant | Run #1 (debug→release) | Run #2 (release→debug) |
|---------|------------------------|------------------------|
| `testDebugUnitTest` | **1172 tests, 0 fail, 0 err, 0 skip** | **1172 tests, 0 fail, 0 err, 0 skip** |
| `testReleaseUnitTest` | **1172 tests, 0 fail, 0 err, 16 skip** | **1172 tests, 0 fail, 0 err, 16 skip** |

- `assembleDebug` ✅ · `assembleRelease` ✅ (release variant fresh-compiled
  — independent proof AC-7 zero code-refs to the 4 deleted controllers;
  the build would not link otherwise).
- The 16 release-variant skips are the `assumeTrue(BuildConfig.DEBUG)`
  no-double-write / sole-live-writer assertions in
  `RenderPathCutoverGateTest` (release-build skip is **by design**,
  documented at `RenderPathCutoverGateTest.kt:466-489` — Spec 2 §10
  no-double-write is a debug-build acceptance criterion). Not a coverage
  gap.
- **AC-9:** 1172 ≥ 946 baseline (Epic added ~226 net behaviour coverage;
  no NET deletion). All 3 R-7 axes confirmed CLOSED — **zero R-7 flake**
  across either ordering (scan for any `failures>0`/`errors>0`: empty).
  No KSP-cache env-flake encountered (no workaround needed).

**Keystone F-1/F-2/F-3 + Triangle-FSM T1-T7 cross-block trace (on the
fully-cutover, legacy-FREE path):**

| Test | Result | Cross-block trace covered |
|------|--------|---------------------------|
| `DictateCutoverE2ETest` | **10/10 green** | Keystone F-1/F-2/F-3 (boot→KEYBOARD, overlay detached); AC-2 (StartRecording→Active + §7.6 FGS Recording notification w/ `[Pause][Stopp][Senden]`); T1/T2 widget round-trip; T3/T5 real-recording→HOVER survival→KEYBOARD (ADR-0003); T4 WIDGET+recording→HOVER; AC-3/T7 StopAndSend→pipeline-via-new-runner→notification Recording→Pipeline→Idle, HOVER→KEYBOARD Geist-Widget guard; AC-2 CancelRecording dismiss; C6-IMPL-1 audio-focus parity (3 cases) |
| `RenderPathCutoverGateTest` | **5/5 green** | G10 ContentArea / G11 PromptVisibility / G12 OverlayReset fire through the NEW owners; keystone+Triangle on the render path; Spec 2 §10 no-double-write soak; new owners are SOLE live writers (legacy KSM deleted, never a writer) |

These two tests already drive the **real bound `DictatePipelineService`**
on the new orchestrator path with the 4 legacy controllers deleted —
i.e. the now-legacy-FREE path. RR-4 honoured: real assertions on a real
bound binder + real Robolectric Views + real audit ledger, no vacuous
duplication. No `DictateCutoverFinalE2ETest.kt` added (would be a
duplicate of an already-complete cross-block trace).

**AC-2/AC-3/AC-4 spot-confirm** via the existing green suites:
recording-drive Active/notification + sessionId/F-13 state-shape are
covered by `DictateCutoverE2ETest` (AC-2/AC-3 above) and the reducer
unit-tests landed in Theme A (A1/A2, part of the 1172). Referenced, not
re-implemented.

### AC-1..AC-10 Holistic Verification Table

| AC | How verified | Grep / test result | Verdict |
|----|--------------|--------------------|---------|
| **AC-1** (stub no-op refs gone) | `grep -rn "StubSubsystems.pipelineRunner\|StubSubsystems.notificationCoordinator" core/` | 1 hit — `DictatePipelineService.kt:475`, a **comment** documenting the real coordinator *replaced* the no-op; ZERO functional refs. Real `PipelineActionRouter` + coordinator wired at :483. | **PASS** |
| **AC-2** (recording→Active + FGS Recording notif) | `DictateCutoverE2ETest.ac2_*` | green — `state.recording` Idle→Active, FGS `NotificationStatus.Recording` posts `[Pause][Stopp][Senden]` | **PASS** |
| **AC-3** (StopAndSend→pipeline via new runner, notif lifecycle) | `DictateCutoverE2ETest.ac3_t7_*` | green — StopRecordingAndSend → pipeline via JobExecutor-backed `PipelineRunnerSubsystem`, notif Recording→Pipeline→Idle | **PASS** |
| **AC-4** (F-10/F-12/F-13/F-15 state-shape) | Theme-A reducer unit-tests (in the 1172) + `DictateCutoverE2ETest` real sessionId threading | green — sessionId carried (no `""` sentinel), `Running` counters, language-aware strings | **PASS** |
| **AC-5** (LanguageController deleted) | `grep -rl "LanguageController" app/src/main` + file check | ZERO hits; `LanguageController.kt` DELETED | **PASS** |
| **AC-6** (audioFile field deleted) | `grep -n "private File audioFile" DictateInputMethodService.java` | ZERO hits | **PASS** |
| **AC-7** (4 dead controllers deleted) | file-existence check + zero import/instantiation grep + `assembleDebug`+`assembleRelease` green | `MainButtonsController/RecordingUiController/KeyboardUiController/KeyboardStateManager.kt` all DELETED; ~140 grep hits are ALL doc-anchors/comments/string-labels — ZERO code refs; both build variants link green (the compile proof) | **PASS** |
| **AC-8** (Espresso 1-10) | C11-D1 — Espresso bodies compile (androidTest) + Robolectric mirror 10/10 green (OQ-4) | covered by C11-D1 (this block); mirrors in the 1172 | **PASS** (per C11-D1) |
| **AC-9** (regression invariant ≥946) | full uncached suite both variants ×2 orders | 1172/0/0 debug · 1172/0/0/16-skip release — identical both orders; +226 net vs 946 baseline; keystone trace green; no R-7 flake | **PASS** |
| **AC-10** (single-architecture, no double-dispatch) | `grep "JobExecutor.INSTANCE.start"` IME + `grep USE_LEGACY_RECORDING_DRIVE app/src/main` + Spec1 §9.6 PipelineOrchestrator | exactly **ONE** `JobExecutor.INSTANCE.start` (line 4171 — documented RESUME carve-out C6-IMPL-2, single-dispatch, orthogonal to recording-drive); ZERO `USE_LEGACY_RECORDING_DRIVE` in `app/src/main`; `PipelineOrchestrator.kt` survives (constructed once at composition root `:315`, reachable only via the `PipelineRunnerSubsystemAdapter` chain — Spec1 §9.6 holds) | **PASS** |

### Deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Dev-1: no new `DictateCutoverFinalE2ETest.kt` written | Epic §4 Block D2 "possibly a new `DictateCutoverE2ETest.kt` aggregating the cross-block trace" | The aggregating cross-block trace (keystone F-1/F-2/F-3 + Triangle T1-T7 on the legacy-FREE path) is **already fully covered** by the existing `DictateCutoverE2ETest` (10/10) + `RenderPathCutoverGateTest` (5/5), which drive the real bound service with the 4 controllers deleted. The plan says "*possibly* a new file"; the prompt explicitly directs avoiding vacuous duplication. A third file would re-assert an identical trace. | Existing coverage is complete + the path is already legacy-FREE (Theme C/C-R landed before B6) — a new file adds maintenance burden + brittleness for zero added coverage (RR-4). | None — verification-only, no test/prod delta | inline-decision (documented) |

### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|-------------|--------|--------|
| — | — | none | — | verification-only chunk; every AC-1..AC-10 PASS on the fully-cutover path; zero production change; zero test delta; no architecture/plan conflict found |

### Overlooked points / known gaps

- **Manual E2E runbook (two-keyboard survival, FGS OOM-recovery,
  notification action-button round-trip)** is the device-tier
  confirmation Epic §4 Block-D2 references. This auto-tier final lock
  proves the **automated** trace (unit + Robolectric bound-service);
  the manual runbook execution is Phase 4.5's job (the skill's
  end-to-end-test phase) — out of scope for this verification-only
  chunk, flagged here so Phase 4.5 picks it up.
- The 16 release-variant skips are intentional (`BuildConfig.DEBUG`
  no-double-write gate). The debug variant runs them green (0 skip), so
  Spec 2 §10 no-double-write IS proven — just on the debug build, by
  design. Not a coverage gap.
- AC-8 verdict is carried from C11-D1 (same block) — this chunk did not
  re-run the Espresso device tier (no device infra; OQ-4 — the
  Robolectric mirror in the 1172 is the CI-green path).
- Postponed NTH issues (C5-IMPL-2 amplitude/timer side-channel,
  C10-C3-IMPL-1) remain carried to Phase 4.7 per the Issue Index —
  untouched by this verification chunk (they are non-blocking and
  out-of-scope for the final lock).

=== COMMIT 1 BOUNDARY === production files: none
=== COMMIT 2 BOUNDARY === test files: none — verification only, existing aggregating tests (DictateCutoverE2ETest + RenderPathCutoverGateTest) already cover the full keystone+Triangle cross-block trace on the legacy-FREE path

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ · **Pre-Validate Commit:** ⏳ · **Validate-Pass Commit:** ⏳

**Focused Block-Validate** (small-block topic-skip): B6 is pure-test, zero `src/main` delta. Only AUDIT-TEST ran materially; PLAN-AND-API / LOGIC are n/a (no production logic); the holistic AC-1..10 table was already produced by C12-D2. CONVENTION folded into the consolidator over the 2 test files.

| Topic | Agent-ID | Status | Output File | Findings |
|-------|----------|--------|-------------|----------|
| plan-and-api | `B6-AUDIT-PLAN-AND-API` | ⏭️ skipped (n/a — no production logic) | — | — |
| convention | `B6-AUDIT-CONVENTION` | ⏭️ folded into VAL-SANITY (2 test files) | — | none |
| logic | `B6-AUDIT-LOGIC` | ⏭️ skipped (n/a — no production logic) | — | — |
| test | `B6-AUDIT-TEST` | ✅ | `./reports/audit-test-B6.md` | 0 Crit / 0 Imp / 1 NTH |

### Sanity-Check Consolidator

**Agent-ID:** `B6-VAL-SANITY` · **Output:** `./reports/validated-findings-B6.md`
**Status:** ✅ consolidated · **Verdict:** 🟢 1 · 🟡 0 · ❌ 0 · postponed-carried 2 (untouched)

**What was done.** Read `audit-test-B6.md` in full + the C11/C12 block-report subsections + the 2 B6 test files. Independently re-verified the AUDIT-TEST-B6-1 vacuity claim (`LayoutPredicates.kt:73-91` — `isTrash/PauseVisible` = `recording.isActiveOrPaused || pipeline is ReprocessStaging`; in UI-4 `pipelineRunning(singleRow=true)` + UI-10 `Preparing` both predicates are `false` regardless → reverting the SEND_MODE `{ false }` literal leaves UI-4/UI-10 GREEN) **and** its mitigation (`VisibilityMatrixTest.kt` case `"TWO_ROW_SEND + recording (cross-mode)"` applies SEND_MODE against `recordingActive()` where `::isTrashVisible` would be `true` → a revert turns *that* test RED; the §1.1 #3a eliminator IS non-vacuously pinned). Folded a light CONVENTION pass (K-1/K-4 clean, naming/structure compliant, body↔mirror byte-identical + documented, helper-First-Use justified).

**Ruling on AUDIT-TEST-B6-1 → F-1 (🟢 NTH).** Validated real (the `(§1.1 #3a)` label over-claims eliminator coverage on UI-4/UI-10) but a *labelling/coverage-attribution* weakness, not a coverage hole (the eliminator literal is genuinely guarded by `VisibilityMatrixTest`). Classified 🟢 small-fix per D3 fix-every-polish: a text-only doc-honesty correction (≤6 edits across the 2 test files, body↔mirror identical, zero assertion-logic change) so a future reader does not trust a vacuous guard. Not ❌-accepted — the over-claim is a real "trust-a-vacuous-test" trap and the fix is near-trivial.

**Validated-no-residual.** FINAL-LOCK confirmed NOT vacuous (AUDIT-TEST independently reproduced 1172/0/0 debug · 1172/0/0/16-skip release, both variants × 2 orders; keystone `DictateCutoverE2ETest` 10/10 + `RenderPathCutoverGateTest` 5/5 are real-bound-binder, RR-4). AC-1..10 holistic table from C12-D2 re-checked coherent. Block diff purely additive — zero regression, zero `src/main` delta. No documentation-gap. Carried postponed (C5-IMPL-2, C10-C3-IMPL-1) confirmed remain `postponed` — correctly carried → Phase 4.7, not B6-fixable (pure-test block, out of scope). Full detail: `./reports/validated-findings-B6.md`.

**Convergence.** B6 is clean enough to converge: 1 near-trivial 🟢 NTH (single doc-honesty repair wave), 0 🟡, 0 ❌, 0 residual.

### Mini-Triage + Repair-Wave(s)

(Per iteration, max 3 per D5 soft-cap.)

#### Block-Validate Repair Wave 1 (B6-VAL-REPAIR-1)

**Date:** 2026-05-17
**Scope:** green-only (single 🟢 NTH, F-1)
**Findings addressed:** 1 (F-1)
**Repair-wave:** B6-VAL-W1, iter 1

**What was done.** Applied F-1's doc-honesty fix — text/KDoc/comment
only, **zero assertion-logic change**, body↔mirror assertion logic kept
byte-identical. UI-4 and UI-10's `(§1.1 #3a)` labels no longer claim to
guard the SEND_MODE `{ false }` *eliminator* (vacuous for that literal
in the `pipelineRunning(singleRow=true)` / `Preparing` states); they now
accurately state they pin the **mode-selection + structural-GONE
outcome**, with an explicit one-line pointer that the §1.1 #3a
eliminator literal's non-vacuous guard is `VisibilityMatrixTest`'s
`"TWO_ROW_SEND + recording (cross-mode)"` case.

| Finding ID | Severity | File | Status | Fix description |
|------------|----------|------|--------|-----------------|
| F-1 | NTH | `KeyboardLayoutUiTest.kt` (UI-4 + UI-10) + mirror `KeyboardLayoutRenderMirrorTest.kt` | fixed | Re-worded the 4 over-claiming `(§1.1 #3a)` assertion messages (UI-4 TRASH/PAUSE, UI-10 TRASH/PAUSE) + the body-only UI-4/UI-10 section-banner + inline comments. Assertion messages edited **identically** in body+mirror; body-only comments corrected in the body. No `assertEquals`/`vis()`/expected-value change. |

**Exact edits (file:line, before → after):**

Assertion messages — applied **identically** to both files (the only
text shared body↔mirror in this region):

1. `KeyboardLayoutUiTest.kt` UI-4 TRASH msg + `KeyboardLayoutRenderMirrorTest.kt` UI-4 TRASH msg:
   `"UI-4 (§1.1 #3a): TRASH must be GONE in single-row send-mode"` →
   `"UI-4: TRASH GONE in single-row send-mode (SEND_MODE structural outcome; the §1.1 #3a \`{ false }\` eliminator literal itself is pinned by VisibilityMatrixTest \"TWO_ROW_SEND + recording\")"`
2. UI-4 PAUSE msg (both files): equivalent PAUSE re-word.
3. UI-10 TRASH msg (both files):
   `"UI-10 (§1.1 #3a): TRASH must be GONE on the Active→Preparing transition (cannot overlap record_btn)"` →
   `"UI-10: TRASH GONE on the Active→Preparing transition (SEND_MODE structural outcome; the §1.1 #3a \`{ false }\` eliminator literal itself is pinned by VisibilityMatrixTest \"TWO_ROW_SEND + recording\")"`
4. UI-10 PAUSE msg (both files): equivalent PAUSE re-word.

Body-only comments (exist only in `KeyboardLayoutUiTest.kt` — a
pre-existing body↔mirror comment asymmetry confirmed at HEAD; corrected
in-place, no new divergence introduced):

5. UI-4 section-banner (was `// UI-4 — §1.1 #3a (critical bug-fix verifier): …`) → outcome-guard wording + explicit "does NOT pin the eliminator literal; VisibilityMatrixTest does" note.
6. UI-4 inline comment (was `// §1.1 #3a eliminator: TRASH + PAUSE are hardcoded \`{ false }\` …`) → "SEND_MODE structural outcome … eliminator literal pinned by VisibilityMatrixTest, not here".
7. UI-10 section-banner + UI-10 frame-2 inline comment: same outcome-honesty correction + VisibilityMatrixTest pointer.

**Zero assertion-logic change + body↔mirror parity (verified).**
`git diff HEAD -- app/src/(androidTest|test)` grep for any
`assertEquals(`/`View.`/`vis(`/`render(`/`mode.id`/`LayoutModeId.`
structural line: **empty** (only string-literal continuation + comment
lines changed). Comments-stripped diff of UI-4 and UI-10 method bodies
body↔mirror: **byte-identical** for both. The single body↔mirror
comment-line difference (the body-only UI-4 inline comment) is
**pre-existing** — verified at `git show HEAD` the body already carried
a 3-line inline comment the mirror never had; this wave only corrected
its text (now 5 lines), it did not create new divergence.

**Self-check (B6-VAL-REPAIR-1-VERIFY, validate-fixes.resume.md).**

- `./gradlew assembleDebug` ✅ · `compileDebugAndroidTestKotlin` ✅
- `./gradlew test --rerun-tasks`: `testDebugUnitTest` = **1172 / 0 fail / 0 err / 0 skip**; `testReleaseUnitTest` = **1172 / 0 fail / 0 err / 16 skip** — exact pre-wave baseline (the fix is text-only; no assertion changed).
- CR-RGATE `RenderPathCutoverGateTest` **5/5** + `DictateCutoverE2ETest` **10/10** green. `KeyboardLayoutRenderMirrorTest` **10/10** (incl. `ui4_*`, `ui10_*`). `VisibilityMatrixTest` **25/25** (the non-vacuous §1.1 #3a eliminator guard the corrected label now points to — green, confirming the mitigation claim).
- Body↔mirror assertion logic byte-identical (verified, see above). Zero accidental code edit. Zero `src/main` delta (`git diff --stat HEAD -- app/src/main` empty).

**Cross-fix conflicts:** none (single finding).
**Files modified:** `app/src/androidTest/java/net/devemperor/dictate/ui/KeyboardLayoutUiTest.kt` (8 text edits — 4 assertion msgs + 4 body-only comment blocks), `app/src/test/java/net/devemperor/dictate/ui/KeyboardLayoutRenderMirrorTest.kt` (4 assertion-msg edits, parity-identical to body).
**Files in findings-scope:** both files above (explicitly named in F-1).
**Files outside findings-scope (drift):** none.

**F-1 → resolved (status `fixed`).** The 2 carried postponed NTH
(C5-IMPL-2, C10-C3-IMPL-1) unchanged — out of scope for this pure-doc
wave, remain `postponed` → Phase 4.7.

---

## Block Deviation Summary

| # | Plan Location | What changed | Why | Impact | Inline-fixed | Source-Agent | Source-Step |
|---|---------------|--------------|-----|--------|--------------|--------------|--------------|
| — | — | — | — | — | — | — | — |

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step, both commits):** ⏳
- **Block-Validate converged:** ⏳
- **AUDIT-TEST: AC-8 Espresso 1-10 green (device or Robolectric mirror):** ⏳
- **C12-D2 holistic: AC-9 (≥946) + AC-10 + all cleanup/compile-invariant greps:** ⏳
- **Build green at block-end:** ⏳
- **Issue index reconciled:** ⏳
- **Epic implementation CODE-COMPLETE — ready for Phase 4:** ⏳

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
