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

(Populated by Phase 1b. The parent plan's runbook in the sibling folder's
`reports/e2e-runbook.md` is the reuse base — the Epic's E2E is the same
two-keyboard survival + Triangle-FSM trace, now on the LIVE path.)

### Plan-Consistency-Check

(Populated by Phase 2.6.)

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
| 1 | C1-A1 | B1 | state-shape F-12 isStarting + F-13 Running counters | A | Low | ⏳ | | | |
| 2 | C2-A2 | B1 | F-10 sessionId source + F-15 language-aware strings | A | Med | ⏳ | | | |
| 3 | C3-B1 | B2 | real PipelineRunnerSubsystemAdapter (JobExecutor) | B | **HIGH** (R-1) | ⏳ | | | |
| 4 | C4-B2 | B2 | real PipelineNotificationCoordinator + ActionRouter | B | **HIGH** (R-2) | ⏳ | | | |
| 5 | C5-B3 | B2 | IME recording-trigger flip (guarded fallback) | B | **HIGHEST** (R-1/R-4) | ⏳ | | | |
| 6 | C6-D2pre | B2 | **D2-pre VERIFICATION GATE** (authorises C7 + Theme C) | D-gate | Gate | ⏳ | | | |
| 7 | C7-B3 | B2 | legacy call-site deletion (GATED on C6 green) | B | Med | ⏳ | | | |
| 8 | C8-C1 | B3 | LanguageController full removal (D-13) | C | Med-High (R-3) | ⏳ | | | |
| 9 | C9-C2 | B3 | audioFile field removal (D-14) | C | Med (R-5) | ⏳ | | | |
| 10 | C10-C3 | B3 | dead-controller retire + PipelineOrchestrator disposition | C | Med | ⏳ | | | |
| 11 | C11-D1 | B4 | Espresso UI-Tests 1-10 + Robolectric mirror | D | Low-Med (R-6) | ⏳ | | | |
| 12 | C12-D2 | B4 | final integration E2E + cleanup-grep regression | D | Low | ⏳ | | | |

**Gate rule (load-bearing, Epic §6.2):** C7 (B3 legacy-deletion) and ALL of
Block B3 (C8/C9/C10 — Theme C, point of no return) are **hard-gated on a green
C6 (D2-pre)**. C5 lands the flip behind `USE_LEGACY_RECORDING_DRIVE`; the
legacy call-site is only deleted in C7 after C6 proves the new path. mid-chunk-
triage armed for C3/C4/C5 (architecture-conflict / blocks-following-chunks).

---

## Repair-Sub-Phase Log (Iter 10)

| Wave-ID | Caller | Iter | Findings (🟢/🟡/❌) | Outcome | Wave-commit |
|---------|--------|------|---------------------|---------|-------------|

## Postponed Issues

| Block | Postponed-Issue | Severity | Why postponed | Tracking |
|-------|-----------------|----------|---------------|----------|

## Mid-Chunk-Triage Trigger Log

(Epic §9 ESTIMATE: mid-chunk-triage armed for B1/B2/B3 — architecture-conflict
/ blocks-following-chunks markers likely on the recording-drive flip.)

| Chunk | Step | Issue-ID | Severity | Wave-ID | Resolution |
|-------|------|----------|----------|---------|------------|

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

---

## Implementation Report (Phase 4.7)

(Populated by Phase-4.7 aggregator-agent.)

---

## Run Log

| Timestamp | Phase | Action | Outcome |
|-----------|-------|--------|---------|
| 2026-05-15 | Phase 0 | Epic state-file created in worktree feature/dictate-keyboard-layout-refactor; reports/ dir created; Epic file committed (65bb303). Plan in-place (no mv). Doc-landscape probed: docs/decisions/ (5 ADRs) + docs/architecture/state-architecture/ exist (parent plan B0). Phase-4.6 activation `full` (skill-default, "ohne Walkthrough"). | ✅ |
| 2026-05-15 | Phase 1a | B0-PLAN-ANALYSIS agent created dictate-cutover-completion.chunks.json: 12 chunks, 4 skill-blocks (B1=Theme A 2ch, B2=Theme B+D2pre-gate 5ch, B3=Theme C 3ch, B4=Theme D 2ch), aggregate Impl-Score ≈9900. Modular-pattern: each chunk carries spec_references + targeted_sub_sections pointing at parent plan's 3 specs (NOT re-chunked). D2-pre gate (C6) modelled as atomic verification-chunk between C5(guarded flip) + C7(legacy deletion); C7 + all B3 hard-gated on green C6. Epic author's EXECUTION-PLAN breakdown adopted in full (no defect). Off-by-one "9 vs 10 blocks" prose noted (no chunking impact). Spec heading-refs all validated via plan-reader. | ✅ |
