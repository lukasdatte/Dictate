# Phase-4.5 End-to-End-Test Report — dictate-cutover-completion

**Agent-ID:** E2E
**Run at:** 2026-05-17
**Runbook:** [→ e2e-runbook.md](./e2e-runbook.md) (incl. the `## Phase-4.5 Refresh` TC-R1..TC-R6 appended by this agent)
**Profile executed:** C12-FULL (post-all-blocks holistic re-run)
**Integration-check input:** [→ integration-check.md](./integration-check.md) — INT-1 RESOLVED, 1180/0/0, 0 Crit / 0 Imp / 4 NTH
**Mode-distribution:** auto: 4 + 1 (TC-A1..A4 + TC-R5 auto-half) · manual: 24 + 6 refresh (TC-1..TC-24 + TC-R1..TC-R6) — `manual-pending` (no device in env)

---

## ⭐ Verdict — auto-tier GREEN, device-tier manual-pending (env constraint)

**The Phase-4.5 auto-tier is fully GREEN. The full unit suite re-confirms
1180/0/0 holistically post-INTEGRATION-W1, both variants, uncached
(`--rerun-tasks`). All 4 runbook auto-TCs PASS. Zero E2E-{N} issues.**

The ~24 manual device-tier TCs + 6 Phase-4.5-Refresh device TCs are
`manual-pending` — **this environment has no Android device/emulator**
(every implementation agent confirmed device-infra unavailable;
`connectedAndroidTest` cannot run here). This is an **environment
constraint, not a test failure**. Each device-tier TC has an
**auto-surrogate that has already run GREEN** in the in-plan verification
gates (C6-D2pre RE-GATE, CR-RGATE, C12-D2 FINAL-LOCK) + Phase-4
INTEGRATION-W1 — the achievable verification surrogate for this env, and
it is green. The user runs the device-tier on their phone per the
runbook's Q1-Q7 "ohne Walkthrough" defaults.

---

## Pre-Flight re-verify

| # | Item | Result | Note |
|---|------|--------|------|
| 1 | gradle-wrapper (`test -x ./gradlew`) | **PASS** | executable in worktree |
| 2 | jvm (JDK ≥17) | **PASS** | OpenJDK 21.0.10 (≥17, AGP-8.x-compatible) |
| 3 | android-sdk | **PASS (adjusted)** | Runbook names `android-35`; project now `compileSdkVersion 36` (`app/build.gradle:9`), platform `android-36` installed → build prereq satisfied against SDK 36. The `-35` literal is parent-baseline-stale, not a failure. `assembleDebug`+`assembleRelease` link green = proof. |
| 4-15 | device / adb / ime-enabled / mic-perm / notif-perm / network / target-app | **blocked: no-device-in-env** | Environment constraint (no Android device/emulator in this CI-like env). NOT a failure — the user runs the device-tier per Q1-Q7 defaults. |
| 16 / E-6 | parent-baseline ≥946 green | **PASS (exceeded)** | Parent baseline 946 → now **1180** (+234; no behaviour-coverage deletion) |
| 17 / E-8 | guard-state (`USE_LEGACY_RECORDING_DRIVE`) | **PASS** | C12-FULL profile: grep-zero in `app/src/main/` functional code (C7 deleted it; `CutoverArchitectureInvariantTest` invariant (b) green) |
| 18 / E-7 | room-no-migration (schema stays v4) | **PASS** | No `@Database(version=5)` — code-only blast radius confirmed (no schema change this Epic) |
| 19 / E-9 | recording-path consent | **defaulted** | "ohne Walkthrough" — Q2 recommended default (Yes); blast-radius lower than parent (no DB migration) |

No prerequisite **failed**. The device-tier items are env-blocked
(constraint), the headless invariants all PASS.

---

## Auto-tier results (mode: auto) — all PASS

| TC | Scope | Steps run | Result | In-plan-gate auto-surrogate that already proved it |
|----|-------|-----------|--------|----------------------------------------------------|
| **TC-A1** | AC-1 stub-subsystem dereference | `grep StubSubsystems.pipelineRunner\|.notificationCoordinator core/` → 1 hit, a **comment** at `DictatePipelineService.kt:475` (doc trail of the replacement), ZERO functional refs. Real `PipelineRunnerSubsystemAdapter` (`:463`) + `PipelineNotificationCoordinator` (`:486`) wired in `onCreate`. | **PASS** | C12-D2 FINAL-LOCK AC-1 (comment-only) · `CutoverArchitectureInvariantTest` invariant (d) (stubs not wired + real adapters are) · INT-1 Central Verdict §1 |
| **TC-A2** | AC-5 LanguageController deletion (D-13) | `test ! -f LanguageController.kt` → gone. `grep -rl LanguageController app/src/main/` → ZERO. Test-side: 1 hit = a **KDoc historical anchor** in `LanguageResolverTest.kt:20` (`* LanguageControllerTest …` — documents the superseded test), not a symbol/import. | **PASS** | C12-D2 FINAL-LOCK AC-5 (zero) · INT-1 Central Verdict §4 (replaced by `preferences/LanguageResolver.kt`) |
| **TC-A3** | AC-6 audioFile-field deletion (D-14) | `grep "private File audioFile" DictateInputMethodService.java` → ZERO. Remaining `audioFile` tokens are all **method-params / locals** (`File audioFile;` at `:3121` local, `captureFreshConfigSnapshot(String, File audioFile)` `:3256`, `transcribeImportedAudioFileViaOrchestrator(File audioFile)` `:3420`, `onAudioPersisted(File audioFile, …)` `:3602`) — no field declaration, no field reads. | **PASS** | C12-D2 FINAL-LOCK AC-6 (zero) · INT-1 Central Verdict §4 (sourced from `RecordingState`, Spec 1 §15.2) |
| **TC-A4** | AC-7 dead-controller deletion + AC-9 regression | All 4 `.kt` files (`MainButtonsController`/`RecordingUiController`/`KeyboardUiController`/`KeyboardStateManager`) **deleted**. Bare `grep -rl` hits the 4 names in ~33 files — verified (per INT-1 + `CutoverArchitectureInvariantTest` invariant (c)) to be **all doc-anchors/`@see`/KDoc/XML-comments/string-labels, ZERO code refs**; the decisive proof is `assembleDebug`+`assembleRelease` link green (would not link with a real code ref). `PipelineOrchestrator.kt` **retained** (Spec 1 §9.6 adaptee, reachable only via `PipelineRunnerSubsystemAdapter`/`JobExecutor` chain). `./gradlew test --rerun-tasks`: **testDebugUnitTest 1180/0/0/0** · **testReleaseUnitTest 1180/0/0/16-skip** (uncached, both variants). `./gradlew assembleDebug` BUILD SUCCESSFUL. | **PASS** | C12-D2 FINAL-LOCK AC-7+AC-9 (1172/0 both variants ×2 orders) · INTEGRATION-W1 (1180/0 post INT-3) · `CutoverArchitectureInvariantTest` invariant (c) + INT-1 §3 |

### Cutover-invariant test suites (the runbook's named auto-surrogates) — all GREEN

| Test suite | Result | What it proves (runbook TC mapping) |
|------------|--------|--------------------------------------|
| `CutoverArchitectureInvariantTest` | **8/8 green** (4 invariant + 4 stripper-soundness self-tests) | The INT-3 D4 regression-lock. (a) exactly one `JobExecutor.INSTANCE.start` in the IME = the RESUME carve-out (`startResumeJob`) → AC-10/TC-C1. (b) zero functional `USE_LEGACY_RECORDING_DRIVE` → AC-10/TC-C1 C12-FULL. (c) zero functional refs to the 4 deleted controllers → AC-7/TC-A4. (d) stubs not wired + real adapter/coordinator wired → AC-1/TC-A1. Each paired with a comment-stripper-soundness self-test (non-vacuity); empirically RED-proven in INTEGRATION-W1. **This IS TC-R5's auto-tier.** |
| `DictateCutoverE2ETest` | **10/10 green** | Keystone F-1/F-2/F-3 IME-activation chain (TC-11); Triangle T1-T7 on the LIVE path (TC-4/TC-6/TC-10); AC-2 StartRecording→Active + §7.6 FGS `[Pause][Stopp][Senden]` (TC-1/TC-C4/TC-22); AC-3/T7 StopAndSend→pipeline-via-new-runner→notif Recording→Pipeline→Idle, Geist-Widget guard (TC-2/TC-10); two-keyboard-switch survival ADR-0003 (TC-1); C6-IMPL-1 audio-focus/BT-SCO parity 3 cases (TC-R1). Drives the **real bound `DictatePipelineService`** on the new orchestrator path, legacy-FREE. |
| `RenderPathCutoverGateTest` | **5/5 green** | G2-G16 + ContentArea/PromptVisibility/OverlayReset fire through the **new owners**; Spec 2 §10 no-double-write soak (`doubleWriteCount==0`); new owners are SOLE `live=true` writers (legacy KSM/controllers deleted). Maps TC-21 (MotionLayout/sole-render-path), TC-R2 (SPACE no double-commit). |

KSP-cache env-flake: **not encountered** (no `kspDebugKotlin FileNotFound`;
no `rm -rf app/build/kspCaches` workaround needed; clean `--rerun-tasks`).

---

## Manual device-tier — `manual-pending` (no device in env; auto-surrogate green)

These are **not failed** — they require a physical Android device which
this environment does not have. They are produced as a runnable ordered
checklist for the user (see `## Manual device-tier checklist for the
user` below). Each TC's auto-surrogate has already run GREEN in the
in-plan gates / INTEGRATION-W1, which is the achievable verification
surrogate for this environment.

Status for every one of TC-1, TC-2, TC-3, TC-C1, TC-C2, TC-C3, TC-C4,
TC-C5(manual half), TC-4..TC-24, and the Phase-4.5-Refresh TC-R1, TC-R2,
TC-R3, TC-R4, TC-R5(manual spot-check half), TC-R6:
**`manual-pending — requires device (env has none); auto-surrogate green`**.

(TC-C5 grep-half and TC-R5 architecture-test-half are the **auto** parts
and ran green above; only their on-device locale/behaviour spot-checks
are manual-pending.)

### Periodic Visits

| Visit | Auto-evaluable here? | Result |
|-------|----------------------|--------|
| logcat-error-scan | No (device) | manual-pending — surrogate: `DictateCutoverE2ETest` asserts no crash on the bound service |
| stub-leak-scan | Partially (static) | **GREEN (static)** — `CutoverArchitectureInvariantTest` (d) proves stubs not wired (no stub `Log.w` path reachable in production); runtime confirmation manual-pending |
| fgs-crash-scan | No (device) | manual-pending — surrogate: B2 R-2 GREEN (`PipelineNotificationCoordinator` never crashes FGS; `DictateCutoverE2ETest` AC-2 FGS notif posts) |
| double-dispatch-scan | **Yes (static)** | **GREEN** — `CutoverArchitectureInvariantTest` (a)+(b): exactly one IME `JobExecutor.start` (RESUME), zero `USE_LEGACY_RECORDING_DRIVE` → AC-10 holds |
| memory-profile | No (device) | manual-pending |
| fgs-notification-presence | No (device) | manual-pending — surrogate: `DictateCutoverE2ETest` AC-2 `[Pause][Stopp][Senden]` |
| db-version-sanity | **Yes (static)** | **GREEN** — Pre-Flight #18/E-7: schema still v4, no `@Database(version=5)` |

---

## Manual device-tier checklist for the user

> **Ordered runnable checklist.** Each entry: precondition · steps ·
> expected · the §1.1/AC it guards · its in-plan auto-surrogate (already
> GREEN). Run on a personal phone per the runbook Q1-Q7 "ohne
> Walkthrough" defaults (USB cable, configured AI provider, Google
> Keep/Notes target, leave installed). Full steps are in `e2e-runbook.md`.

**Group 1 — Survival on the live path (the central goal):**

1. **TC-1** (keystone) — Precondition: Dictate IME selected, Notes open.
   Steps: tap record → verify FGS notif `[Pause][Stopp][Senden]` →
   switch to Gboard, wait 30 s → switch back → stop-and-send. Expected:
   recording survives the round-trip on the **new** path, one continuous
   utterance transcribed, FGS never crashes, **zero stub `Log.w`**.
   Guards: AC-2/AC-3 + ADR-0003. Surrogate: `DictateCutoverE2ETest` 10/10.
2. **TC-2** — real non-empty sessionId start→stop continuity; pipeline
   via new JobExecutor-backed runner. Guards AC-3/F-10. Surrogate:
   `DictateCutoverE2ETest` real-sessionId threading.
3. **TC-3** — process-survival: force-close target app mid-recording,
   reopen → IME shows recording-active (state restored from store).
   Guards Spec 1 §15. Surrogate: `DictateCutoverE2ETest` T3/T5.

**Group 2 — Cutover-specific (the live flip + guard):**

4. **TC-C1** — C12-FULL: `grep` confirms the IME no longer calls
   `JobExecutor.INSTANCE.start` directly (only the RESUME carve-out +
   the C3 adapter); zero `USE_LEGACY_RECORDING_DRIVE`. Single recording
   driver per user action across all trigger sites. Guards AC-10/R-4.
   Surrogate: `CutoverArchitectureInvariantTest` (a)+(b) (auto, GREEN).
5. **TC-C2** — *C6-SUBSET-only; N/A on C12-FULL (boolean deleted)* —
   listed for completeness; the guarded-fallback proof was the C6-D2pre
   in-plan gate (GREEN before C7 deletion).
6. **TC-C3** — full `JobRequest` config (language, prompt-queue,
   style-prompt, autoSwitch, recordingsDir) survives the adapter
   translation; behaviour: German transcription + queued reword +
   keyboard auto-switch. Guards R-1/AC-2. Surrogate: B2 R-1 GREEN
   (field-for-field spy) + `DictateCutoverE2ETest`.
7. **TC-C4** — FGS notification action-button round-trip:
   Pause/resume/Senden/Stopp each round-trip through
   `PipelineActionRouter` → `orchestrator.dispatch`; notif lifecycle
   Recording→Pipeline→Idle; single NOTIF_ID, no `startForeground`
   crash. Guards AC-2/R-2. Surrogate: `DictateCutoverE2ETest` AC-2 +
   B2 R-2 GREEN.
8. **TC-C5** (locale-visual half) — switch device to German, record →
   notification buttons render German labels. Guards OQ-3. Surrogate:
   the grep half is auto-GREEN (strings present de/en).

**Group 3 — Triangle-FSM on the live path:**

9. **TC-4** T1 KEYBOARD→WIDGET · 10. **TC-5** T2 WIDGET→KEYBOARD
   SmallMode · 11. **TC-6** T3 KEYBOARD→HOVER (real pipeline active) ·
   12. **TC-7** T4 WIDGET→HOVER · 13. **TC-8** T5 HOVER→KEYBOARD ·
   14. **TC-9** T6 HOVER→WIDGET · 15. **TC-10** T7 HOVER→KEYBOARD via
   Pipeline-Done cascade (Geist-Widget-Bug regression — Critical).
   Guards §3.1 T1-T7. Surrogate: `DictateCutoverE2ETest` T1-T7 +
   `RenderPathCutoverGateTest` Triangle-on-render-path.

**Group 4 — Keystone + visibility + legacy-retire regression:**

16. **TC-11** keystone F-1/F-2/F-3 IME-activation chain on the live
    path (AC-9). Surrogate: `DictateCutoverE2ETest` keystone (10/10).
17. **TC-12** resend_btn predicate-driven visibility · 18. **TC-13**
    resend + audio-migration after audioFile-field removal (R-5) ·
    19. **TC-14** settings language-change propagates after
    LanguageController removal (R-3, bound + unbound paths, no NPE).
    Surrogate: B3-VAL-W1 AC-5/AC-6 PASS, R-3 SOUND, R-5 GREEN.

**Group 5 — Persistence + overlay + cascade regression:**

20. **TC-15** OOM-recovery → user-resume from DB (no schema change) ·
    21. **TC-16** orphan FAILED audio cleanup · 22. **TC-17**
    first-time overlay-permission ask · 23. **TC-18** overlay denied →
    notification fallback · 24. **TC-19** overlay drag + position
    persistence (portrait/landscape) · 25. **TC-20** overlay survives
    rotation while recording · 26. **TC-21** MotionLayout transitions
    no flicker (sole RenderBackend path) · 27. **TC-22** F-13 running
    counters render real progress (AC-4) · 28. **TC-23** SendStaging
    double-click guard (F-12/AC-4) · 29. **TC-24** cross-module-cascade
    depth ≤8 + Mode-3 prohibition (ADR-0002).
    Surrogate: C12-D2 FINAL-LOCK AC-1..10 + `RenderPathCutoverGateTest`
    5/5 + reducer unit-tests (in the 1180).

**Group 6 — Phase-4.5 Refresh (edge-of-the-blade from implementation):**

30. **TC-R1** BT-SCO already-connected does NOT hang recording
    (B2-VAL-W1 F-1, was Critical). Surrogate: `DictateCutoverE2ETest`
    C6-IMPL-1 BT-SCO parity + B2-C6-W1-REGATE.
31. **TC-R2** SPACE — exactly one space per tap, no double-commit
    (B5-VAL-W1 F-1, was Critical). Surrogate: `RenderPathCutoverGateTest`
    + B5 render unit-tests.
32. **TC-R3** reword-staging language override seeded-on-entry +
    cleared-on-exit, no cross-session leak (B5-VAL-W1 F-2/F-6).
    Surrogate: B5 language-override lifecycle unit-tests.
33. **TC-R4** F-6 staging-override does not corrupt the reprocess job
    config. Surrogate: B5 reprocess-config unit-tests.
34. **TC-R5** INT-1-pattern non-recurrence (auto half GREEN:
    `CutoverArchitectureInvariantTest` 8/8; manual spot-check: zero
    stub `Log.w` at runtime).
35. **TC-R6** *awareness only, non-blocking* — HistoryDetailActivity
    re-process is single-dispatch (INT-2, out-of-scope-recorded). A
    defect here = Nice-to-have Phase-5 follow-up, NOT a blocker.

---

## Issues

| ID | Severity | Description | Status | Routing |
|----|----------|-------------|--------|---------|
| — | — | **None.** The auto-tier is fully GREEN (1180/0/0 both variants uncached; TC-A1..A4 PASS; all 3 cutover-invariant suites green). No auto-TC revealed a regression or unexpected behaviour. The device-tier is `manual-pending` due to the no-device env constraint (not a failure). | — | — |

No E2E-{N} issue raised. Expected Phase-4.5 outcome for this
environment: auto-tier green + device-tier manual-pending with green
auto-surrogates.

---

## Summary

| Metric | Value |
|--------|-------|
| Test-case count (incl. Phase-4.5 Refresh) | 34 (28 base + 6 refresh) |
| Auto-tier executed | TC-A1, TC-A2, TC-A3, TC-A4 + TC-R5(auto half) + TC-C5(grep half) + 3 cutover-invariant suites |
| Auto pass | **all PASS** (4/4 runbook auto-TCs + `CutoverArchitectureInvariantTest` 8/8 + `DictateCutoverE2ETest` 10/10 + `RenderPathCutoverGateTest` 5/5) |
| Auto fail | **0** |
| Manual-pending (device, env has none; auto-surrogate green) | 30 (TC-1..TC-24 minus auto-halves + TC-R1..TC-R6 manual halves) |
| Blocked (env constraint, not failure) | Pre-Flight device items #4-#15 / E-1..E-5/E-9 |
| Regression vs ≥946 baseline | **none** — 1180/0/0 (+234, no coverage deletion) |
| E2E issue severity counts | 0 Critical / 0 Important / 0 Nice-to-have |
| **Recommended next step** | **ready_for_closure** |

**Rationale for `ready_for_closure`:** the auto-tier is fully green with
zero Critical/Important E2E issues; the manual device-tier is
`manual-pending` purely because this environment has no Android device —
an environment constraint, not a blocker. The in-plan C6-D2pre RE-GATE
(GREEN, authorised C7+Theme-C), CR-RGATE (GREEN, authorised CR-DEL),
C12-D2 FINAL-LOCK (GREEN, all AC-1..10), and Phase-4 INTEGRATION-W1
(INT-1 RESOLVED, 1180/0) are the achievable holistic verification
surrogates for this env, and they are all GREEN. The user runs the
device-tier on their phone per the documented Q1-Q7 defaults; the
runbook (with the Phase-4.5 Refresh TC-R1..TC-R6) is the runnable
artefact for that.
