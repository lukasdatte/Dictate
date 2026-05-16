# State: dictate-cutover-completion

> 🛑 **ORCHESTRATOR-MANDATORY — FIRST ACTION on every resume:**
>
> If you opened this state file as part of a resume, do these four
> reads **before any other action** — before any `Task(...)`, before
> any commit, before any `AskUserQuestion`:
>
> 1. **Re-read `SKILL.md`** in full
>    (`~/.claude/skills/implement-long-plan-v2/SKILL.md`). The skill
>    is intentionally complex; a compacted summary cannot losslessly
>    preserve every directive, phase boundary, and edge-case rule.
>    Acting on compacted memory of this skill is unsafe.
> 2. **Re-read `AGENT-CONTEXT.md`** (same folder) — sub-agent setup +
>    role schema.
> 3. **Read this state file completely** — chunk table, repair-sub-
>    phase log, postponed issues, mid-chunk-triage log, Phase-4.7
>    implementation-report status.
> 4. **Read current block-reports** under `./reports/` for the active
>    block (and the immediately previous block, for cross-block
>    context).
>
> Only after these four reads: identify the in-progress task, decide
> resume-vs-fresh per the agent-ID in the state, and continue.
>
> The orchestrator-agent is YOU. The skill describes how you proceed.

**Plan:** [→ dictate-cutover-completion.md](dictate-cutover-completion.md)
**Worktree:** /home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor
**Branch:** feature/dictate-keyboard-layout-refactor
**Plan-Reader-Script:** ~/.claude/skills/implement-long-plan-v2/plan-reader.ts
**Block-Reports:** ./reports/
**Started:** 2026-05-15

**Context:** This Epic is the INT-1-escalation follow-up to the parent plan
`dictate-keyboard-layout-refactor` (state-file in the sibling
`2026-05-07 - …` folder). It runs on the **same branch + worktree** because
it builds directly on the parent plan's 52 commits (HEAD at Epic-start:
`65bb303`). The 3 specs in the parent plan's `research/` remain the SoT —
this Epic references §-sections, does not duplicate.

**Active Modes:**
- Plan-Reader-Mode: ⏳ (Epic 874 lines < 1500 → Standard for the Epic itself;
  but specs Spec1=6984 / Spec2=2601 / Spec3=2857 lines are SoT → Phase-1a
  agent decides per-spec Plan-Reader; the parent plan's spec chunks.json
  files already exist and are reusable)
- Block-Mode: ✓ (always — see Skill Invariants)
- Validation+Tests Closeouts: ✓ (default; user invoked "ohne Walkthrough"
  for this session — auto-accept skill-recommended defaults; carries to this
  Epic per the continuous "setze ihn um" instruction)
- Modular Plan Pattern: ✓ (Epic points at the parent plan's 3 specs as SoT)

**Phase-2 Briefing:** (pending — briefing-only, no `AskUserQuestion` per
"ohne Walkthrough")
**Pre-Phase-3 Git-State Check:** clean (verified 2026-05-15 after Epic-commit
65bb303 — only the new Epic dir was untracked, now committed)

---

## plan_lifecycle

```yaml
current_path: docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md
move_to: docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md   # already in target location
status: in_place
note: Epic was authored directly in docs/plans/ — no Phase-0 mv needed
```

---

## Documentation Plan

```yaml
phase_4_6_activation: full
phase_4_6_decided_at: 2026-05-15
phase_4_6_decided_by: skill-default (user invoked "ohne Walkthrough" — accepts default `full`)

doc_landscape:
  architecture_dir: docs/architecture/state-architecture/   # EXISTS (created by parent plan B0)
  decisions_dir: docs/decisions/                            # EXISTS — 5 ADRs + README (parent plan B0)
  api_dir: null
  runbooks_dir: null
  module_readmes: 0
  nested_claude_md: []                                      # only root CLAUDE.md
  other_docs:
    - docs/DATABASE-PATTERNS.md
  knowledge_skills_relevant:
    - knowledge-adr-format        # ADR Decision-History appends likely (0001/0003/0005 — recording-drive cutover)
    - knowledge-doc-format        # state-architecture/ doc updates for the cutover seam
    - knowledge-reference         # general code-pattern references
  knowledge_skills_missing:
    - knowledge-kotlin / knowledge-android   # codebase is Kotlin+Java+Android — gap carried from parent plan

doc_plan_sketch:
  - "ADR-0001 (single-dispatch) — Decision-History append likely: pipelineRunner/notificationCoordinator stubs → real adapters (the cutover collapses the two-orchestrator coexistence)"
  - "ADR-0003 (FGS pipeline) — Decision-History append likely: PipelineNotificationCoordinator becomes real; NOTIF_ID single-source"
  - "ADR-0005 (Triangle-FSM) — Decision-History append possible: IME recording-trigger flips to dispatch path"
  - "docs/architecture/state-architecture/ — the dormant-seam doc(s) updated to reflect the live cutover (no more STUB boxes)"
  - "Phase 4.6c inline-worker: module headers + @see anchors on new PipelineRunnerSubsystemAdapter / PipelineNotificationCoordinator / PipelineActionRouter; @see updates on DictateInputMethodService recording-trigger + DictatePipelineService.onCreate"
  - "spec-files remain Spec status SoT — do NOT convert to architecture/ (D21 SSoT — parent-plan doc-set is the architecture source)"

re_evaluations: []
```

---

## Chunks Files

(Populated by Phase 1a. The parent plan's spec chunks.json files exist and
are reusable as targeted-sub-section references.)

| Source File | Chunks File | Status |
|-------------|-------------|--------|
| dictate-cutover-completion.md | dictate-cutover-completion.chunks.json | ✅ Phase 1a — validated (11 headings, 12 chunks, 4 skill-blocks, D2-pre gate encoded) |
| ../2026-05-07 - …/research/1-pipeline-service/1-pipeline-service.reviewed.md | (parent chunks.json — reused as targeted-sub-section refs) | SoT — referenced by Theme A/B/C |
| ../2026-05-07 - …/research/2-keyboard-layout/2-keyboard-layout.reviewed.md | (parent chunks.json — reused) | SoT — referenced by Theme C/D |
| ../2026-05-07 - …/research/3-floating-overlay/3-floating-overlay.reviewed.md | (parent chunks.json — reused) | SoT — context only |

---

## Execution Strategy

### Skill-Conventions

```yaml
build_command: ./gradlew assembleDebug
test_command: ./gradlew test
lint_command: null                              # no linter configured per CLAUDE.md
coverage_command: ./gradlew test
instrumented_test_command: ./gradlew connectedAndroidTest
plan_reader_cmd: npx tsx ~/.claude/skills/implement-long-plan-v2/plan-reader.ts
test_file_pattern: "app/src/test/**/*.kt"
android_test_file_pattern: "app/src/androidTest/**/*.kt"
mock_factory: handwritten-fakes              # Quality-Gate K-1 — no Mockito/MockK; FakeXxx pattern (see FakeAudioFocusGate.kt). K-4 — no Android Context (Robolectric = justified opt-out only)
source_extension_main: .kt
source_extension_legacy: .java
test_helpers_location_hint: "app/src/test/java/net/devemperor/dictate/"
coverage_threshold_branches: 70                # provisional; AUDIT-TEST refines per block
```

### Pre-Flight

(Phase 1a — initial population. Phase 1b adds E2E-specific items.)

| # | Kind | Target | Programmatic check | Why |
|---|------|--------|--------------------|-----|
| 1 | gradle-wrapper | `./gradlew` in worktree | `test -x ./gradlew` | All build/test commands route through it |
| 2 | jvm | JDK 17+ for AGP 8.x | `java -version` ≥ 17 | Required for AGP |
| 3 | android-sdk | Android SDK API 35 | `ls $ANDROID_HOME/platforms/android-35` | targetSdk=35 |
| 4 | git-state | working tree clean OR user-acknowledged | `git status --porcelain` | Clean block-end-commits |
| 5 | parent-baseline | parent plan's 946-test baseline green | `./gradlew test` green at HEAD 65bb303 | AC-9 regression invariant baseline |

**E2E-specific Pre-Flight (Phase 1b — B0-E2E-STRATEGY, 2026-05-15).** Reuses
the parent runbook's 9 E2E items, **adjusted for the Epic**: the parent's
DB-migration-consent + Room-v3→v4-backup items are DROPPED (this Epic adds no
schema change — invariant E-7 enforces it); items E-8/E-9 are Epic-new.
Manual-TC items gate the manual run only; E-6/E-7/E-8 are hard invariants.

| # | Kind | Target | Programmatic check | Blocking | Profile |
|---|------|--------|--------------------|----------|---------|
| E-1 | device | Android device/emulator API 26-35 via ADB | `adb devices` shows ≥1 in state `device` | manual-TCs | both |
| E-2 | adb-connection | **USB cable — NOT Wireless** (`user_dev_setup.md`) | `adb shell ip route` ok 5× in 60 s no disconnect | manual-TCs | both |
| E-3 | apk-installed | APK from `./gradlew assembleDebug` at the profile's HEAD | `adb shell pm list packages \| grep net.devemperor.dictate` | manual-TCs | both |
| E-4 | ime-selected | Dictate IME enabled + currently selected | `adb shell settings get secure default_input_method` = `net.devemperor.dictate/...` | manual-TCs | both |
| E-5 | mic+notif-perm | `RECORD_AUDIO` granted; `POST_NOTIFICATIONS` granted on API ≥ 33 (Epic-new FGS action-buttons, R-2) | `adb shell dumpsys package net.devemperor.dictate \| grep -E "RECORD_AUDIO\|POST_NOTIFICATIONS"` granted | manual-TCs | both |
| E-6 | parent-baseline | parent ≥946-test baseline green at Epic-start | `./gradlew test` green at HEAD `65bb303` (= Pre-Flight #5) | yes | both |
| E-7 | room-no-migration | NO `@Database(version=…)` bump — schema stays v4 (blast-radius is code-only; this is WHY the parent's DB-consent items are dropped) | `grep -rn "version = 5\|version=5" app/src/main/java/.../database/` → zero | yes (safety invariant) | both |
| E-8 | guard-state | C6-SUBSET: `USE_LEGACY_RECORDING_DRIVE` present + default-new. C12-FULL: boolean + legacy IME call-site deleted (C7 done) | `grep -rn "USE_LEGACY_RECORDING_DRIVE" app/src/main/` — C6: ≥1 default-new; C12: zero | yes | both (profile-dependent) |
| E-9 | recording-path-consent | If personal device: user OK that recording-trigger is the new path + new FGS notification appears. Blast-radius LOWER than parent (no DB migration). | manual: User-Question Q2 (recommended default = Yes) | manual-TCs | both |

(Phase 1b appends E2E-specific items before Phase 4.5.)

### Test-Strategy-Completeness

```yaml
mocking_strategy_documented: true     # K-1 handwritten fakes; carried from parent plan
test_approach_documented: true        # JVM unit primary; Robolectric justified opt-out; androidTest for Espresso 1-10 (Theme D)
tier_coverage_documented: full
gaps:
  - "Espresso device-infra (AC-8 / D1) — OQ-4: D1 ships Espresso body + Robolectric mirror so AC-8 green either way"
```

### Test-Strategy (per skill-block)

(Populated by Phase 1a. Theme A = pure JVM reducer-tests; Theme B = Robolectric
binder-harness + JobRequest spy; Theme C = compile-invariant greps + regression;
Theme D = Espresso + Robolectric mirror.)

### End-to-End-Test-Plan

(Populated by Phase 1b — agent B0-E2E-STRATEGY, 2026-05-15. The parent plan's
runbook in the sibling folder's `reports/e2e-runbook.md` was the reuse base;
the Epic runbook inherits the relevant parent TCs and adds cutover-specific +
auto-tier invariant TCs, re-traced on the LIVE path.)

```yaml
scope: "Staged destructive recording-drive cutover: new DictateOrchestrator drives production recording + real FGS notification; legacy LanguageController/audioFile-field/dead-controllers retired. Same two-keyboard survival + Triangle-FSM trace as parent, now on the LIVE path, plus cutover-specific (guarded-fallback mutual-exclusion, full JobRequest config survival, notification action-button round-trip) + grep/regression invariants."
runbook: ./reports/e2e-runbook.md
relevant_knowledge:
  - test-orchestrator        # auto-tier: ./gradlew test / assembleDebug / connectedAndroidTest, grep invariants
  # knowledge-gap (carried from parent): NO test-knowledge-android/-mobile/-ime skill — runbook is hand-rolled self-contained adb (matches parent runbook style)
test_case_count: 28          # 4 auto (AC-1/5/6/7/9 grep+regression) + 24 manual
auto_count: 4                # TC-A1..TC-A4 (headless grep + ./gradlew test ≥946 + assembleDebug)
manual_count: 24             # TC-1..TC-24 (device-attached IME flows)
prerequisites_count: 19      # parent's 17 adapted: dropped DB-migration-consent (#14/#15), added notif-perm (#10), guard-state (#17), room-no-migration (#18), recording-path consent (#19)
blocking_user_questions: 0   # "ohne Walkthrough" — orchestrator applies the Recommended-default column; Q1-Q7 parent-answered, Q8 (OQ-2 guard-lifetime) Epic-new but non-blocking (Epic §7: surfaces at B3 mid-Epic with documented fallback)
fresh_fallback_used: true    # Phase-1b ran as a fresh-spawn (not a Phase-1a resume — SendMessage/resume unavailable in this environment)
two_profile_structure: true  # runbook is structured for C6-SUBSET (in-plan D2-pre gate, authorises C7+Theme-C) + C12-FULL (final gate) + post-all-blocks Phase-4.5 (= C12-FULL)
c6_subset_gate: "TC-1,TC-2,TC-C1,TC-C2,TC-C3,TC-C4,TC-6,TC-10,TC-11,TC-22 + 3 cutover Periodic-Visits — PASS authorises C7 legacy-deletion + Theme C (C8/C9/C10); FAIL keeps them gated (guarded fallback keeps app shippable on legacy)"
skip_recommendation: "NOT skip — staged destructive cutover on the product's core feature (recording) with major user-visible behaviour impact; the C6 gate is an in-plan BLOCKING authorisation, not optional"
```

### Plan-Consistency-Check

```yaml
status: pass
checked_at: 2026-05-15
broken_links: 0
spec_existence: all_present       # Spec 1/2/3 in sibling 2026-05-07 folder all resolve
external_unreachable: 0
proceed_decision: yes
```

**Phase 2.6 mechanical findings (orchestrator-run 2026-05-15):**
- All Epic §8 references resolve: parent plan, parent integration-check.md +
  state.md, 3 research files, 3 specs, 5 ADRs (0001-0005) — all present.
- All Epic §8 "Key code seams" exist at baseline HEAD 74217cf:
  `PipelineServiceStubSubsystems.kt`, `DictatePipelineService.kt` (stub wiring
  confirmed at `:419 pipelineRunner` / `:421 notificationCoordinator`),
  `DictateInputMethodService.java`, `PipelineOrchestrator.kt`,
  `DictateUiState.kt`, `Action.kt`, `ModuleServices.kt`,
  `LanguageController.kt`, 4 dead controllers, `KeyboardLayoutUiTest.kt`.
- Spec heading-refs in chunks.json validated by Phase 1a (one Spec-2 §9
  heading-text mismatch fixed during 1a). No blocking issues — Phase 3 starts.

---

## Spec Inventory

(Modular-plan-pattern active — the Epic points at the parent plan's 3 specs
as SoT. Specs live in the sibling `2026-05-07 - …/research/` folder.)

| Spec File | Status | Lines | Used by Epic Block |
|-----------|--------|-------|--------------------|
| ../2026-05-07 - …/research/1-pipeline-service/1-pipeline-service.reviewed.md | Spec — programmer-ready | 6984 | B1 (§9.6,§13.3.11) · B2 (§7.4/7.5/7.6,§11.1.2,§10) · A2/B3 (§15.2) · C1/C3 (§9.6) |
| ../2026-05-07 - …/research/2-keyboard-layout/2-keyboard-layout.reviewed.md | Spec — programmer-ready | 2601 | C3 (§9.1–§9.6) · D1 (§14.2) |
| ../2026-05-07 - …/research/3-floating-overlay/3-floating-overlay.reviewed.md | Spec — programmer-ready | 2857 | context only |

---

## Chunks

Status legend: ⏳ pending, 🔄 in progress, ✅ done, ⚠️ blocked.

(Populated by Phase 1a chunking-agent. Epic §4 proposes 9-10 blocks across
4 themes: A1,A2 (state-shape) · B1,B2,B3 (recording-drive) · C1,C2,C3
(legacy-retire) · D1,D2 (test-completion). D2 is a verification GATE that
authorises B3's final deletion chunk + the C-blocks — see Epic EXECUTION-PLAN
ORDERING NOTE.)

| # | ID | Block | Chunk-Name | Theme | Risk | Status | Agent-IDs | Impl-Commit | Test-Commit |
|---|-----|-------|------------|-------|------|--------|-----------|-------------|-------------|
| 1 | C1-A1 | B1 | state-shape F-12 isStarting + F-13 Running counters | A | Low | ✅ | B1-C1-A1-IMPL | 9bacace | ca5dbed |
| 2 | C2-A2 | B1 | F-10 sessionId source + F-15 language-aware strings | A | Med | ✅ | B1-C2-A2-IMPL | d236ab2 | 015b616 |
| 3 | C3-B1 | B2 | real PipelineRunnerSubsystemAdapter (JobExecutor) | B | **HIGH** (R-1) | ✅ | B2-C3-B1-IMPL | 7967306 | b6e2011 |
| 4 | C4-B2 | B2 | real PipelineNotificationCoordinator + ActionRouter | B | **HIGH** (R-2) | ✅ | B2-C4-B2-IMPL | 715c452 | 0cc96b6 |
| 5 | C5-B3 | B2 | IME recording-trigger flip (guarded fallback) | B | **HIGHEST** (R-1/R-4) | ✅ | B2-C5-B3-IMPL | bf62eee | 987432a |
| 6 | C6-D2pre | B2 | **D2-pre VERIFICATION GATE** (authorises C7 + Theme C) | D-gate | Gate | ✅ GREEN (after W1 repair + indep. re-gate) | B2-C6-D2pre-IMPL / -REGATE | 397a148 | 13c273c (W1) |
| 7 | C7-B3 | B2 | legacy call-site deletion (was gated on C6 — done) | B | Med | ✅ | B2-C7-B3-IMPL + MID-W1 | 799f3af | (MID-W1 6159d4c) |
| 8 | C8-C1 | B3 | LanguageController full removal (D-13) | C | Med-High (R-3) | ✅ | B3-C8-C1-IMPL | 6de54b1 | 93f86d6 |
| 9 | C9-C2 | B3 | audioFile field removal (D-14) | C | Med (R-5) | ✅ | B3-C9-C2-IMPL | bd82070 | ccb38c2 |
| 10 | C10-C3 | B3 | dead-controller retire + PipelineOrchestrator disposition | C | Med | ⚠️ BLOCKED | B3-C10-C3-IMPL | 185f3f6 (OQ-1 only) | — |

> **⚠️ C10-IMPL-2 — Critical architecture-conflict (ESCALATED, INT-1-class).**
> The C10 per-class responsibility-trace proved the chunk premise FALSE:
> Theme B was the *recording-drive* cutover ONLY; the **render-path cutover
> never happened**. MainButtonsController / RecordingUiController /
> KeyboardStateManager / KeyboardUiController remain the sole owners of
> RECORD-long-press(2-mode), BACKSPACE-accel-delete, SPACE/ENTER touch,
> theming, key-press-animation, QWERTZ rec-button, prompts-visualizer,
> pipeline-progress/step-row UI — ImeViewBackend runs in PARALLEL,
> incomplete. Parent B4-VAL F-1/F-2/F-33 deferred this to a "B5/B7
> follow-up" block NEVER created — the exact INT-1 anti-pattern this Epic
> exists to cure, recurring at the render layer. C5-IMPL-2 folds in. D1/D2
> render-path assumptions invalid. OQ-1 (PipelineOrchestrator kept+
> annotated) was completed cleanly (185f3f6). C8-C1/C9-C2 (LanguageController
> D-13, audioFile D-14) remain ✅.
>
> **RESOLUTION (orchestrator-autonomous, 2026-05-16): Option 1 — author +
> implement the render-path cutover NOW.** User was asked (INT-1-class
> escalation) and answered "Weitermachen" (proceed autonomously, no
> walkthrough). Per D4 + the Epic's raison d'être (kill the
> parallel-dormant anti-pattern; a dormant render layer repeats the exact
> INT-1 mistake) + the user's INT-1 precedent ("implement the cutover
> now"): the Epic is extended with a **Theme C-R (render-path cutover)**
> — port the ~8 controller UI-behaviour groups to the RenderBackend, then
> delete the 4 controllers. New blocks authored by a planning agent
> (chunking is delegated, not orchestrator-done). C10-C3's deletion
> becomes the FINAL chunk of Theme C-R (gated on the render-port being
> proven). D1/D2 re-scoped accordingly.
| 11 | C11-D1 | B4 | Espresso UI-Tests 1-10 + Robolectric mirror | D | Low-Med (R-6) | ⏳ | | | |
| 12 | C12-D2 | B4 | final integration E2E + cleanup-grep regression | D | Low | ⏳ | | | |

**Gate rule (load-bearing, Epic §6.2):** C7 (B3 legacy-deletion) and ALL of
Block B3 (C8/C9/C10 — Theme C, point of no return) are **hard-gated on a green
C6 (D2-pre)**. C5 lands the flip behind `USE_LEGACY_RECORDING_DRIVE`; the
legacy call-site is only deleted in C7 after C6 proves the new path. mid-chunk-
triage armed for C3/C4/C5 (architecture-conflict / blocks-following-chunks).

### Orchestrator Forwarding Notes (inject into the relevant chunk-IMPL prompts)

| Note-ID | For chunk(s) | Finding (Phase-1b research) | Action required |
|---------|--------------|------------------------------|-----------------|
| FN-1 | C5-B3, C7-B3, C6-D2pre | **AC-10 scope is wider than Epic §4 states.** Epic §4-B3 names only `JobExecutor.INSTANCE.start` at `:2236` + standalone path `:2251-2290`. Phase-1b verified **3** call-sites: `DictateInputMethodService.java:2236`, `:2897`, `:3053`. | C5 must guard **all three** behind `USE_LEGACY_RECORDING_DRIVE`; C7 deletes all three; C6 double-dispatch grep-audit (TC-C1) covers all three. Treat as a documented plan-deviation (mid-size, solution clear → inline per D22), not an architecture-conflict. |
| FN-2 | C4-B2 | **OQ-3 confirmed:** `values/strings.xml` has `dictate_history_pause` but NO dedicated `[Pause][Stopp][Senden]` pipeline-notification-action strings. | C4 adds them (de/en locales) mirroring parent F-5's locale-file discipline. Additive — not a blocker (Epic §7 OQ-3 default). |
| FN-4 | C5-B3, C7-B3 | **C2-A2 changed the StopRecordingAndSend contract.** `Action.RecordingAction.StopRecordingAndSend` is now a payload-less `data object` (no `sessionId` arg); the sessionId flows via `RecordingAction.StartRecording(target, audioFile, sessionId)` → `RecordingState` → reducer reads `state.sessionId` on stop. (B1 IMPL-PLAN-FIX-2, plan-deviation-resolved.) | C5/C7 IME recording-trigger flip must dispatch `StartRecording(target, audioFile, preAllocatedId)` (the IME's `:2213` `preAllocatedId` UUID) THEN payload-less `StopRecordingAndSend()`. Do NOT pass a sessionId to StopRecordingAndSend — it no longer takes one. Supersedes the Epic §4-B3 / §3 literal `StopRecordingAndSend(realSessionId)` wording. |
| FN-3 | C5-B3 / C7-B3 | **OQ-2 default applied** (Phase-1b Q8, "ohne Walkthrough"): `USE_LEGACY_RECORDING_DRIVE` is removed **immediately after C6 green** (in C7), per D7 — no lingering dead switch. No one-dogfood-release hold. | C7 = the switch+legacy-call-site deletion chunk, gated on green C6. |

---

## Repair-Sub-Phase Log (Iter 10)

| Wave-ID | Caller | Iter | Findings (🟢/🟡/❌) | Outcome | Wave-commit |
|---------|--------|------|---------------------|---------|-------------|
| B3-VAL-W1 | Block-Validate B3 (4 audits) — C8-C1 + C9-C2 (clean block) | 1 | 0 Crit / 1 Imp / ~7 NTH raw → 8 unique → 🟢5 / 🟡0 / ❌3. F-1 (Imp, C8-IMPL-1) DurationHealingJob async pollution → new DurationHealingScheduler holder + resetForTest() (Dev-5 D22: AUDIT-TEST's shutdownNow() regressed via Robolectric SQLite-native interrupt → switched to graceful shutdown()+awaitTermination(10s)). F-2 doc-trail-accuracy. F-3 dedup ReprocessStaging-override + guard-normalise. F-4/F-5 NTH doc. F-6 (cross-carrier collapse) deferred-with-tracking → B5/C10-IMPL-2. 2 ❌ (conv broad-catch FP, R-3 inherent IME gap). AC-5/AC-6 PASS, R-3 SOUND, R-5 GREEN, F-15-side-effect REAL+CORRECT, C10-only-OQ-1 CORRECT. | ✓ converged 1 wave; flake GONE (3× uncached both variants 1048/1048); assembleDebug green | 80cdda2 |
| B2-VAL-W1 | Block-Validate B2 (4 audits: PLAN-AND-API + CONVENTION + LOGIC + TEST) — the architectural core, ~5 chunks+W1+MID-W1 diff | 1 | 1 Crit / 6 Imp / 7 NTH raw → 9 unique → 🟢6 / 🟡2 / ❌5. F-1 (🔴 Crit, blocks-following) BT-SCO already-connected hang → research recording-preparing-sco-focus-lifecycle → option (ii) prime bluetoothSco=Waiting on BT-mic start (Waiting→Connected becomes a real edge; Spec1 §15.2/15.3-faithful, no Mode-3, stale-resolve-after-cancel still defeated). F-2 focus-only ReacquireAudioFocus on awaitingSco true→false (legacy timing). F-3 IME sendable-guard. F-4 NUL was LOAD-BEARING (char-literal separator) → ' ' escape + clean re-audit. F-5 §15.x doc reconcile. F-6 R-7 ActiveJobRegistry.resetForTest() + reference-test fix → R-7 GONE (2× uncached 1050/1050). AC-1/2/3/10 GREEN; all deviations spec-faithful. | ✓ converged 1 wave (soft-cap 3 not approached); E2E 10/10; assembleDebug+suite green | a3ca1e3 |
| B2-C6-W1-REGATE | C6-D2pre independent RE-GATE (post-W1) | 1 (verify) | C6-IMPL-1 independently verified closed (code-trace vs real legacy source): audio-focus cascade legacy-equivalent every edge, BT-SCO handshake exact (Connected/Failed/timeout/duplicate tested), AC-10 holds (3 boolean-fenced sites), E2E 10/10. Forced uncached full-run: 5 fails = confirmed pre-existing R-7 pollution flake (PipelineRunnerSubsystemAdapterTest×4 + LegacyAudioFileMigrationTest×1; 100% green isolated; W1 diff touches zero JobExecutor/DB files → not causal). | ✅ **RE-GATE: GREEN — C7 + ALL Theme C (B3) AUTHORISED** | (verification — no commit) |
| B2-C6-W1 | C6-D2pre GATE (RED verdict — gate-repair) | 1 | C6-IMPL-1 (1 Important blocker: legacy-parity regression — new path emitted no audio-focus/BT-SCO; ~100% users). Combined research+repair+self-check. Audio-focus: restored Spec1 §15.1-row-3 AudioModule observer arm (S-4 KDoc had wrongly removed it, stale-comment class; gated Pref.AudioFocus). BT-SCO: new ScoRouteResolved action + Preparing arm + awaitingSco discriminator, 2500ms subsystem timeout, SCO→VOICE_COMMUNICATION / fail→MIC. ADR-0002 Mode-2→Mode-1. Engagement-edge correctness fix (Dispatchers.Main.immediate re-entrancy). D22 Dev-W1-1/2/3. | ✓ converged (assembleDebug+suite green; R-7 flake pre-existing not regression). C6-IMPL-1 fixed, C5-IMPL-1 fixed-via-W1, C6-IMPL-2 documented. **Re-gate C6-D2pre pending** | 13c273c |
| B1-VAL-W1 | Block-Validate B1 (4 audits: PLAN-AND-API + CONVENTION + LOGIC + TEST) | 1 | 🟢5 / 🟡1 / ❌1 (9 raw → 7 unique; 0 Crit / 2 Imp / 5 NTH). 🟡 F-1 = isStarting inert dead-code; research `sendstaging-isstarting-guard-semantics` → **Option (b)** (delete inert trio, FSM-edge is canonical guard, amend Epic AC-4/§4-A1 + F-12 tests — code+spec+Epic agree, D4). 3 C1-A1/C2-A2 plan-deviation-resolved issues all CONFIRMED-JUSTIFIED. F-5 ❌ (F-15 raw lang code intentional-deferred). | ✓ converged 1 wave (soft-cap 3 not approached); 964 tests green; assembleDebug green | 48e3be5 |

## Postponed Issues

| Block | Postponed-Issue | Severity | Why postponed | Tracking |
|-------|-----------------|----------|---------------|----------|
| ~~B2~~ | ~~R-7 test-pollution flake~~ → **RESOLVED in B2-VAL-W1 F-6** (ActiveJobRegistry.resetForTest() seam + reference-test drain; 2× uncached 1050/1050 green) | ~~Important~~ → closed | (was) | B2-VAL-W1 ✓ |
| B2 | R-7 test-pollution flake amplifying: order-dependent shared-singleton (JobExecutor/ActiveJobRegistry) pollution now fails PipelineRunnerSubsystemAdapterTest×4 + LegacyAudioFileMigrationTest×1 in non-cached full-suite runs (100% green isolated). Pre-existing pattern, not a cutover regression — but the new C3-B1 PipelineRunnerSubsystemAdapterTest lacks the full DictatePipelineServiceOverlayTransitionTest tearDown discipline (Epic §6.3 R-7 mitigation). | Important (test-quality) | B2 Block-Validate AUDIT-TEST must address (add JobExecutor/ActiveJobRegistry reset tearDown to PipelineRunnerSubsystemAdapterTest + any new boot-test); not gate-blocking | B2 AUDIT-TEST |

## Mid-Chunk-Triage Trigger Log

(Epic §9 ESTIMATE: mid-chunk-triage armed for B1/B2/B3 — architecture-conflict
/ blocks-following-chunks markers likely on the recording-drive flip.)

| Chunk | Step | Issue-ID | Severity | Wave-ID | Resolution |
|-------|------|----------|----------|---------|------------|
| C7-B3 | step-1-impl | C7-IMPL-1 | Critical (architecture-conflict, blocks-following-chunks) | B2-C7-MID-W1 (iter 1/cap 2) | research+repair: imported-audio-file transcription was a 2nd overlooked legacy JobExecutor.start consumer; routed through orchestrator via existing TriggerPipeline action (Spec1 §3) + C3 submit seam; config field-identical (C5 captureFreshConfigSnapshot reused). grep → only RESUME legacy survives. AC-10 fully GREEN. Theme-C unblocked. ✓ converged. Wave-commit 6159d4c |

---

## End-to-End-Test-Result

(Populated by Phase-4.5 agent. Note: Epic D2 is itself an in-plan E2E
verification GATE — Phase 4.5 is the post-all-blocks holistic re-run.)

## Phase 4.6 Documentation Update

(Populated by Phase-4.6 final agent.)

## Block-End Commits

| Block | Block-Start-Commit | Block-End-Commit | Status |
|-------|--------------------|--------------------|--------|
| (Epic-start baseline) | 65bb303 | — | — |
| B3 (Theme C — legacy-retire C8+C9) | d39891a | 80cdda2 | ✅ (C8-C1 D-13 + C9-C2 D-14; VAL-W1 1 wave; C8-IMPL-1 DurationHealing flake CLOSED; C10-C3 moved to B5; 1048/1048 both variants; AC-5/AC-6 PASS) |
| B1 (Theme A — state-shape) | 58bb9a1 | 48e3be5 | ✅ (C1-A1 + C2-A2; VAL-W1 1 wave; 964 tests green; Epic AC-4/§4-A1 amended per F-1 option-b — documented plan-deviation) |
| B2 (Theme B — recording-drive + D2-pre gate) | 17085ca | a3ca1e3 | ✅ (C3/C4/C5/C6-GREEN/C7; C6 RED→W1-repair→indep.RE-GATE GREEN; C7-IMPL-1 mid-triage MID-W1 imported-file→orchestrator; VAL-W1 1 wave incl. Critical BT-SCO-hang fix + R-7 closed; AC-1/2/3/10 GREEN; 1050/1050 tests; only RESUME legacy survives) |

---

## Implementation Report (Phase 4.7)

(Populated by Phase-4.7 aggregator-agent.)

---

## Run Log

| Timestamp | Phase | Action | Outcome |
|-----------|-------|--------|---------|
| 2026-05-15 | Phase 0 | Epic state-file created in worktree feature/dictate-keyboard-layout-refactor; reports/ dir created; Epic file committed (65bb303). Plan in-place (no mv). Doc-landscape probed: docs/decisions/ (5 ADRs) + docs/architecture/state-architecture/ exist (parent plan B0). Phase-4.6 activation `full` (skill-default, "ohne Walkthrough"). | ✅ |
| 2026-05-15 | Phase 3.1 B2-C6-D2pre | C6-D2pre VERIFICATION GATE. Auto-tier FULLY GREEN: 1016 tests 0 fail (AC-9 holds), assembleDebug green, AC-10 no-double-dispatch verified, new DictateCutoverE2ETest.kt 7 green (keystone F-1/F-2/F-3 + Triangle T1-T7 on NEW LIVE path; recording survives keyboard-switch ADR-0003). **GATE: 🔴 RED** — blocker C6-IMPL-1 (=C5-IMPL-1, code-traced): legacy requests audio-focus (Pref.AudioFocus default-true, 100% users) + BT-SCO; new path emits neither (AudioModule.reduce no arm; effects defined-never-emitted; KDoc factually wrong). C7 would delete the masking fallback → shipped core-feature regression. C5-IMPL-2 → 🟢 non-blocking (lost keyboard-hide-pause IS the intended ADR-0003 improvement; FGS notif acceptable interim). C5-IMPL-3/C4-IMPL-2 → 🟢. C7 + ALL Theme C (B3) HARD-GATED. Commit 397a148. | ✅ (gate did its job) |
| 2026-05-16 | Phase 3.2 B3 | Block-Validate B3 (C8+C9, clean block): 4-topic audit → 0 Crit / 1 Imp / ~7 NTH → VAL-SANITY 8 unique → 🟢5/🟡0/❌3. B3-VAL-W1: F-1 C8-IMPL-1 DurationHealingJob async pollution closed (new DurationHealingScheduler holder+resetForTest(); Dev-5 D22 graceful-shutdown over AUDIT-TEST's regressing shutdownNow()). F-2 doc-trail. F-3 dedup+guard. F-4/F-5 doc. F-6 cross-carrier-collapse deferred-with-tracking → B5/C10-IMPL-2. AC-5/AC-6 PASS. Flake GONE (3× uncached both variants 1048/1048). Wave-commit 80cdda2. **Block B3 ✅ COMPLETE** (D2 — converged 1 wave; C10-C3 moved to B5). | ✅ |
| 2026-05-16 | Phase 3.1 B3-C9-C2 | B3-C9-C2-IMPL combined Steps 1-5 (MED R-5): audioFile field removed (D-14/AC-6). All 9 sites per-site-traced + provably correctly sourced (new RecordingState.audioFileOrNull accessor for the recording-active read, guarded). R-5 trap GREEN — resend uses Room DB session.getAudioFilePath(), migration uses Pref.LastFileName, NEITHER ever referenced the field (Epic's feared :1880/:2104 field-reads don't exist → less surface). grep "private File audioFile" → zero. 1048 tests green. testReleaseUnitTest fail = the already-indexed C8-IMPL-1 pollution flake (not a regression — C9 doesn't touch that axis). Dev-1 audioFileOrNull. No Critical/arch-conflict. Commit 1 bd82070 + Commit 2 ccb38c2. | ✅ |
| 2026-05-16 | Phase 3.1 B3-C8-C1 | B3-C8-C1-IMPL combined Steps 1-5 (MED-HIGH R-3): LanguageController fully removed (D-13/AC-5). New stateless preferences/LanguageResolver.kt (prefs-file SoT, no lifetime-bound singleton). RefreshFromPref data object→data class(effective); LanguageModule writes effective. All consumers migrated (DictateApplication singleton+no-op-reader removed, IME, PreferencesFragment, +KDoc scrub 9 files). R-3 mitigated structurally (no cache→no staleness; onServiceConnected idempotent re-push closes boot-before-bind). **Side-effect: fixed latent F-15 bug** (RenderBackend effective was always "system" pre-C8). grep -rl LanguageController app/src/main → ZERO (was 39/14). ~1043 tests green. Dev-1/Dev-2 inline. IMPL-1 delegated (R-7-variant: LegacyAudioFileMigrationTest flakes in testReleaseUnitTest only — DB-singleton/DurationHealingJob axis, C9 territory, not a regression → B3 AUDIT-TEST). No Critical/arch-conflict → no mid-chunk-triage. Commit 1 6de54b1 + Commit 2 93f86d6. | ✅ |
| 2026-05-15 | Phase 3.2 B2 | Block-Validate B2 (architectural core): 4-topic audit → 1 Crit / 6 Imp / 7 NTH → VAL-SANITY 9 unique → 🟢6/🟡2/❌5. B2-VAL-W1 (combined research+repair+self-check): F-1 Critical BT-SCO already-connected hang fixed (prime bluetoothSco=Waiting, option ii — Spec1 §15.2/15.3-faithful); F-2 focus-during-Preparing; F-3 IME sendable-guard; F-4 NUL byte was LOAD-BEARING char-separator → ' ' escape + clean re-audit; F-5 §15.x doc reconcile; F-6 R-7 ActiveJobRegistry.resetForTest() → R-7 GONE (2× uncached 1050/1050). AC-1/2/3/10 GREEN. Wave-commit a3ca1e3. **Block B2 ✅ COMPLETE** (D2 — converged 1 wave). | ✅ |
| 2026-05-15 | Phase 3.1 B2-C7-B3 | C7-B3 legacy-deletion (authorised by C6 GREEN). Commit 1 799f3af (revert-isolated §6.2): deleted USE_LEGACY_RECORDING_DRIVE + fresh/reprocess legacy branches; RESUME carve-out collapsed-not-deleted (C6-IMPL-2 fixed). Surfaced **C7-IMPL-1 Critical** (architecture-conflict, blocks-following): a 2nd overlooked legacy JobExecutor.start — Settings imported-audio-file transcription. Agent correctly kept reachable code + flagged. mid-chunk-triage B2-C7-MID-W1 (iter 1): routed imported-file through orchestrator (TriggerPipeline/Spec1 §3 + C3 seam; config field-identical via C5 snapshot), deleted legacy :2554. grep → only RESUME survives; AC-10 fully GREEN; Theme-C unblocked. 2082 tests (debug+release) 0 fail. Wave-commit 6159d4c. C7-B3 ✅. **All B2 impl chunks done.** | ✅ |
| 2026-05-15 | Phase 3.1 B2-C6 re-gate | C6-D2pre independent RE-GATE (fresh agent, not the repair agent). Code-traced vs real legacy: audio-focus + BT-SCO genuinely closed, legacy-parity exact. **RE-GATE: GREEN — C7 + ALL Theme C (B3) AUTHORISED.** Uncached full-run surfaced 5 R-7 pollution-flake fails (pre-existing, not causal — postponed to B2 AUDIT-TEST). C6-D2pre chunk ✅ (gate green after 1 repair iter). | ✅ |
| 2026-05-15 | Phase 3.1 B2-C6 repair | repair-sub-phase B2-C6-W1 (iter 1/cap3, caller=D2pre-gate): C6-IMPL-1 closed. Audio-focus AudioModule observer arm restored (Spec1 §15.1 r3; KDoc fixed); BT-SCO ScoRouteResolved handshake (2500ms timeout, VOICE_COMMUNICATION/MIC fallback); ADR-0002 Mode-2→Mode-1; engagement-edge fix. assembleDebug+suite green. C6-IMPL-1 fixed, C5-IMPL-1 fixed-via-W1, C6-IMPL-2 documented (C7 must not delete RESUME :3286). Wave-commit 13c273c. **C6-D2pre RE-GATE next.** | ✅ |
| 2026-05-15 | Phase 3.1 B2-C5-B3 | B2-C5-B3-IMPL combined Steps 1-5 (HIGHEST-risk R-1/R-4): IME recording-trigger flip behind `USE_LEGACY_RECORDING_DRIVE=false` (compile-time const). R-1 GREEN (8 fresh fields 1:1 via new ImePipelineConfigResolver+Delegating+LocalBinder.register), R-4 GREEN (AC-10 grep table, mutually-exclusive boolean). Documented mid-size deviation: flip at record-button not post-record :2236 (post-record dispatch re-records over audio — Spec1 §15.2/AC-2). All 3 JobExecutor.start guarded (FN-1: fresh :2596, reprocess :3463→C3 adapter, resume :3286 both-legacy). C3-IMPL-1/2 + C4-IMPL-1 fixed (Paused notif + FSM emission, no-flicker hand-off); C4-IMPL-2 postponed cosmetic. 1009 tests green, assembleDebug green. No mid-chunk-triage (in-chunk). **3 new delegated (pre-existing dormant-layer gaps, guarded-fallback-covered, NOT C5 bugs): C5-IMPL-1 (Imp — new path lacks AudioFocus/BT-SCO; C6-D2pre MUST assess as gate criterion), C5-IMPL-2 (Imp — legacy recording-UI Idle on new path; Theme-C/C10 RenderBackend scope), C5-IMPL-3 (NTH resume).** Pre-existing R-7 LegacyAudioFileMigrationTest pollution flake noted (not C5). Commit 1 bf62eee + Commit 2 987432a. | ✅ |
| 2026-05-15 | Phase 3.1 B2-C4-B2 | B2-C4-B2-IMPL combined Steps 1-5 (HIGH-risk R-2): real PipelineNotificationCoordinator + PipelineActionRouter (Spec1 §7.4/7.5/7.6/§11.1.2). R-2 GREEN — coordinator never creates channel (Service Step-1 sole owner; channel-before-startForeground preserved), buildInitial pure (FGS-5s), notify/cancel swallowed. NOTIF_ID single SoT 0xD1C7A7E relocated to coordinator; legacy posts zero notifications → no duplicate conflict. OQ-3/FN-2 [Pause][Stopp][Senden] strings added de+en. Stub demoted @Deprecated; grep StubSubsystems.notificationCoordinator → zero functional. 987 tests green. No Critical/arch-conflict → no mid-chunk-triage. C4-IMPL-1 (Imp plan-deviation-resolved) + C4-IMPL-2 (NTH) delegated → C5 (Recording-status emission + Paused variant = C5 scope, mirrors C3-IMPL-1/2). Commit 1 715c452 + Commit 2 0cc96b6. | ✅ |
| 2026-05-15 | Phase 3.1 B2-C3-B1 | B2-C3-B1-IMPL combined Steps 1-5 (HIGH-risk R-1): real PipelineRunnerSubsystemAdapter (thin JobExecutor delegation, OQ-1 no-arch-conflict). Reprocess JobRequest 1:1 mapped+asserted; fresh-recording 8 IME-runtime fields → DefaultPipelineConfigResolver THROWS (fail-loud EffectFailure, not silent-default) — legacy IME path stays authoritative for fresh recordings until C5. Stub demoted @Deprecated test-only; grep StubSubsystems.pipelineRunner app/src/main → zero. 971 tests green, assembleDebug green. IMPL-1 (Imp, plan-deviation-resolved) + IMPL-2 (NTH) delegated to C5 (prescribed owner — fresh-config IME-trigger threading). NO Critical/architecture-conflict → mid-chunk-triage not needed. Commit 1 7967306 + Commit 2 b6e2011. | ✅ |
| 2026-05-15 | Phase 3.2 B1 | Block-Validate B1: 4-topic audit (PLAN-AND-API/CONVENTION/LOGIC/TEST) → 0 Crit / 2 Imp / 5 NTH raw → VAL-SANITY 7 unique → 🟢5 / 🟡1 / ❌1. B1-VAL-W1 (combined research+repair+self-check, SendMessage unavailable): 🟡 F-1 isStarting-inert → research → **Option (b)** delete inert trio + amend Epic AC-4/§4-A1 + F-12 tests (Spec1 §3 canonical ReprocessStaging has no isStarting; FSM-edge is the real guard; main-thread dispatch ADR-0001). 5 🟢 fixed (doc-drift cluster, UUID-import, completedSteps-comment, F-7 require(sessionId.isNotBlank)+regression). F-5 ❌. 3 C1-A1/C2-A2 plan-deviation-resolved CONFIRMED-JUSTIFIED. 964 tests green. Wave-commit 48e3be5. **Block B1 ✅ COMPLETE** (D2 — converged 1 wave). | ✅ |
| 2026-05-15 | Phase 3.1 B1-C2-A2 | B1-C2-A2-IMPL combined Steps 1-5: F-10 real sessionId (threaded through RecordingState.Preparing/Active/Paused + StartRecording; StopRecordingAndSend → payload-less data object; `grep sessionId=""` → zero) + F-15 language-aware dictateButtonText (reads state.language.effective read-only, no legacy writer, D-13 untouched). 8 prod files, 14 test files (12 sibling compile-fix updates), 964-test suite green, assembleDebug green. FSM graph unchanged (payload-only widening, Spec1 §15.2 faithful). 2 Important deviations delegated for B1 Block-Validate (IMPL-PLAN-FIX-1 sessionId-on-RecordingState Epic-authorised; IMPL-PLAN-FIX-2 StopRecordingAndSend payload removed → cross-block FN-4 to B3). No Critical/architecture-conflict → no mid-chunk-triage. Commit 1 d236ab2 + Commit 2 015b616. | ✅ |
| 2026-05-15 | Phase 3.1 B1-C1-A1 | B1-C1-A1-IMPL combined Steps 1-5: F-12 isStarting + F-13 Running counters (completedSteps/totalSteps/startedAtMs/elapsedMs) + B4 placeholder replaced. 3 prod files, 2 test files (+15 @Test), 959-test suite green, assembleDebug green, additive/source-compatible confirmed. 3 deviations inline-resolved (Dev-1 StepStarted no totalSteps payload; Dev-2 SendStaging keeps →Preparing edge — flagged IMPL-PLAN-FIX-1 Important `plan-deviation-resolved` for B1 Block-Validate to confirm; Dev-3 added startedAtMs reducer-baseline). No Critical/architecture-conflict → no mid-chunk-triage. Commit 1 9bacace (prod) + Commit 2 ca5dbed (tests). | ✅ |
| 2026-05-15 | Phase 1a | B0-PLAN-ANALYSIS agent created dictate-cutover-completion.chunks.json: 12 chunks, 4 skill-blocks (B1=Theme A 2ch, B2=Theme B+D2pre-gate 5ch, B3=Theme C 3ch, B4=Theme D 2ch), aggregate Impl-Score ≈9900. Modular-pattern: each chunk carries spec_references + targeted_sub_sections pointing at parent plan's 3 specs (NOT re-chunked). D2-pre gate (C6) modelled as atomic verification-chunk between C5(guarded flip) + C7(legacy deletion); C7 + all B3 hard-gated on green C6. Epic author's EXECUTION-PLAN breakdown adopted in full (no defect). Off-by-one "9 vs 10 blocks" prose noted (no chunking impact). Spec heading-refs all validated via plan-reader. | ✅ |
| 2026-05-15 | Phase 1b | B0-E2E-STRATEGY agent (fresh-spawn, not Phase-1a resume — SendMessage unavailable; `fresh_fallback_used: true`). Wrote `reports/e2e-runbook.md` (28 TCs: 4 auto AC-1/5/6/7/9 grep+regression, 24 manual), structured for two profiles — C6-SUBSET (in-plan D2-pre gate, authorises C7+Theme-C) + C12-FULL/Phase-4.5 (full set). Reuse-base = parent runbook (24 TCs inherited/adapted). Code seams verified: stubs at DictatePipelineService.kt:419/421, audioFile field :222, 3 JobExecutor.start sites (:2236/:2897/:3053 — AC-10 audit must cover all 3, not only :2236), no notification-action strings yet (confirms OQ-3). State-file End-to-End-Test-Plan + E2E Pre-Flight (E-1..E-9, parent DB-consent items dropped — no schema change) populated. Skip-recommendation: NOT skip (destructive core-feature cutover). 0 blocking user-questions (Q1-Q7 parent-answered, Q8/OQ-2 Epic-new non-blocking). | ✅ |
