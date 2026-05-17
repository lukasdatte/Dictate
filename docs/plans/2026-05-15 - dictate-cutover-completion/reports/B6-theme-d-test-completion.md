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

**Severity counts:** Critical: 0 · Important: 0 · Nice-to-have: 2 (carried postponed) · Postponed: 2

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
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

**Agent-IDs:** `B6-C12-D2-IMPL` · **Status:** ⏳ pending · **Risk:** LOW (verification gate)
(subsections filled when chunk runs)

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ · **Pre-Validate Commit:** ⏳ · **Validate-Pass Commit:** ⏳

| Topic | Agent-ID | Status | Output File | Findings |
|-------|----------|--------|-------------|----------|
| plan-and-api | `B6-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B6.md` | — |
| convention | `B6-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B6.md` | — |
| logic | `B6-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B6.md` | — |
| test | `B6-AUDIT-TEST` | ⏳ | `./reports/audit-test-B6.md` | — |

### Sanity-Check Consolidator

**Agent-ID:** `B6-VAL-SANITY` · **Output:** `./reports/validated-findings-B6.md`

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
- **AUDIT-TEST: AC-8 Espresso 1-10 green (device or Robolectric mirror):** ⏳
- **C12-D2 holistic: AC-9 (≥946) + AC-10 + all cleanup/compile-invariant greps:** ⏳
- **Build green at block-end:** ⏳
- **Issue index reconciled:** ⏳
- **Epic implementation CODE-COMPLETE — ready for Phase 4:** ⏳

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
