# State: dictate-keyboard-layout-refactor

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

**Plan:** [→ dictate-keyboard-layout-refactor.reviewed.md](dictate-keyboard-layout-refactor.reviewed.md)
**Worktree:** /home/lukas/WebStorm/Dictate/worktrees/feature/dictate-keyboard-layout-refactor
**Branch:** feature/dictate-keyboard-layout-refactor
**Plan-Reader-Script:** ~/.claude/skills/implement-long-plan-v2/plan-reader.ts
**Block-Reports:** ./reports/
**Started:** 2026-05-14

**Active Modes:**
- Plan-Reader-Mode: ✓ (plan 1699 lines, ≥ 1500 threshold)
- Block-Mode: ✓ (always — see Skill Invariants)
- Validation+Tests Closeouts: ✓ (default; user invoked "ohne Walkthrough" — auto-accept skill-recommended defaults)
- Modular Plan Pattern: ✓ (three large specs in research/, see Spec Inventory below)

**Phase-2 Briefing emitted:** 2026-05-14 (briefing-only — user invoked "ohne Walkthrough", no `AskUserQuestion` pause)
**Pre-Phase-3 Git-State Check:** clean (verified 2026-05-14 after commit e4edf6e)

---

## plan_lifecycle

```yaml
current_path: docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
move_to: docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md     # already in target location
status: archived
archived_at: 2026-05-17 15:20
note: |
  Plan was authored directly in docs/plans/ — no Phase-0 mv needed.
  Phase-5a archive: per-plan README.md authored (status archived;
  records that this plan's Phase 4.5/4.6/4.7/5 were COMPLETED-VIA the
  follow-up Epic dictate-cutover-completion — see "deferred_phases"
  below; bidirectional link to that Epic). Plan body + 3 specs
  preserved unchanged as the historical record (specs remain the SoT
  the Epic referenced). EN translation (5b/5c) pending.

# This plan's Phase-4 escalated INT-1 (Critical, escalate-to-user):
# the new state-architecture shipped as a parallel-dormant layer; the
# plan had no remaining block for the make-it-live + retire-legacy
# cutover. User chose INT-1 routing option (a) — implement the cutover.
# That follow-up is the Epic dictate-cutover-completion, run on the
# SAME branch + worktree + commit lineage (Epic baseline sits on this
# plan's 52 commits — one unified codebase).
deferred_phases:
  status: closed-via-epic dictate-cutover-completion
  closed_at: 2026-05-17 15:20
  phases: [Phase 4.5 (E2E), Phase 4.6 (Documentation), Phase 4.7 (Implementation-Report), Phase 5 (Closure)]
  rationale: |
    Deferred when INT-1 escalated — a dormant architecture cannot be
    meaningfully E2E'd / documented-as-live / closed. The follow-up
    Epic dictate-cutover-completion made the architecture LIVE and
    retired legacy on the IDENTICAL unified codebase (same branch +
    commit lineage). Per the orchestrator's D4 / single-source-of-truth
    decision (no redundant re-run of identical-code phases), these
    phases are satisfied VIA the Epic's corresponding phases rather
    than separately re-run on the same code:
      - Phase 4.5 / 4.7 ← Epic reports/e2e-test.md +
        reports/implementation-report.md (1180/0 both variants on the
        unified codebase; +226 over this plan's 946 baseline).
      - INT-1 ← Epic reports/integration-check.md Central Verdict
        (all four INT-1 constituent facts code-verified FALSE;
        CutoverArchitectureInvariantTest regression-lock).
      - Phase 4.6 ← Epic Phase-4.6 doc pass: ADR-0001/0003/0005
        Decision-History appends captured THIS plan's now-live
        architecture decisions; state-architecture/ dormant-seam docs
        updated to the live cutover.
  see_also: ../2026-05-15 - dictate-cutover-completion/reports/integration-check.md

en_translation:
  decided_at: 2026-05-17
  decided_by: PHASE5-TRANSLATE (Phase 5b/5c, D4 disposition)
  status: CLOSED — all genuinely-German plan-scope docs translated, 0 outstanding
  done:
    # wave 1 — 6 German research files
    - research/motionlayout-architecture-options.en.md
    - research/main-button-area-inventory.en.md
    - research/_pending-ime-lifecycle-view-recreation/_pending-ime-lifecycle-view-recreation.en.md
    - research/_pending-layout-container-architecture/_pending-layout-container-architecture.en.md
    - research/_pending-persistence-background-architecture/_pending-persistence-background-architecture.en.md
    - research/_pending-state-machine-visibility-owners/_pending-state-machine-visibility-owners.en.md
    # wave 2 — the plan-file (split per D16, >1500 lines) + the 3 specs
    # (~14,200 lines of dense technical German). Each parity-verified:
    # heading-count, fenced-block-count, code-block byte-identity.
    - dictate-keyboard-layout-refactor.reviewed-0-overview.en.md          # plan split 1/3
    - dictate-keyboard-layout-refactor.reviewed-1-building-blocks.en.md   # plan split 2/3
    - dictate-keyboard-layout-refactor.reviewed-2-specs-risks-references-iteration-log.en.md  # plan split 3/3
    - research/1-pipeline-service/1-pipeline-service.reviewed.en.md       # 152/152 headings, 79/79 blocks byte-identical
    - research/2-keyboard-layout/2-keyboard-layout.reviewed.en.md         # 75/75 headings, 29/29 blocks byte-identical
    - research/3-floating-overlay/3-floating-overlay.reviewed.en.md       # 94/94 headings, 49/49 blocks byte-identical
  outstanding-genuine-german: []   # CLOSED — wave 2 produced + parity-verified the plan + 3 specs
  english-native-no-sidecar:
    # german-only-headings / english-native prose — SSoT: a near-verbatim
    # sidecar is the redundant duplication the SSoT rule forbids; the
    # language-conventions.md German→EN trigger does not apply.
    - research/b3-cleanup-cascade-and-backfill-policy.md     # english-native
    - research/b5-ime-activation-wiring.md                   # english-native
    - research/manual-paste-field-architecture.md            # english-native
```

---

## Documentation Plan

```yaml
phase_4_6_activation: full
phase_4_6_decided_at: 2026-05-14
phase_4_6_decided_by: skill-default (user invoked "ohne Walkthrough" — accepts default `full`)

doc_landscape:
  architecture_dir: null                       # absent — plan §4.0 creates docs/architecture/state-architecture/
  decisions_dir: null                          # absent — plan §4.0.1 creates docs/decisions/ + 5 ADRs
  api_dir: null                                # absent
  runbooks_dir: null                           # absent
  module_readmes: 0                            # no src/{module}/README.md present
  nested_claude_md: []                         # only root CLAUDE.md
  other_docs:
    - docs/DATABASE-PATTERNS.md
  knowledge_skills_relevant:
    - knowledge-adr-format                     # writing the 5 ADRs in Block 0
    - knowledge-doc-format                     # writing state-architecture/ docs
    - knowledge-reference                      # general code-pattern references
  knowledge_skills_missing:
    - knowledge-kotlin / knowledge-android     # codebase is Kotlin+Java+Android — gap flagged for follow-up

doc_plan_sketch:
  - "Block 0 creates the entire docs/decisions/ infrastructure (README.md + 5 ADRs); Block 0 is plan-driven, not Phase-4.6"
  - "Block 0 creates docs/architecture/state-architecture/ (README.md + 11 sub-files per plan §4.0.2)"
  - "Phase 4.6a discovery confirms Block-0 docs are in place + scans plan-touched src/ files for inline anchors"
  - "Phase 4.6c inline-worker: module headers + @see ADR/spec anchors + gotcha comments on new modules (~13 DictateModule singletons + LayoutCatalog + Triangle-FSM + DictatePipelineService)"
  - "spec-files in research/*/ — author specs as Spec status, do NOT convert to architecture/ (plan §4.0 doc-set replaces sole-architecture-source role per D21 SSoT)"
  - "no nested CLAUDE.md per subsystem yet — out-of-scope for this plan"
  - "docs/runbooks/ absent — out-of-scope (Phase 4.5 e2e-runbook lives in ./reports/, not docs/runbooks/)"

re_evaluations: []
```

---

## Chunks Files

Phase 1a created chunks.json for all 4 files (plan + 3 specs). Modular-plan-pattern (D21) is active: plan-level chunks reference spec-internal sub-sections via `spec_references` + `targeted_sub_sections` fields. Each spec's chunks.json has its own H2-level chunking that maps 1:1 to the plan-chunk IDs (via the `id` field convention `spec{N}-C{plan-chunk-num}-{topic}`).

| Source File | Chunks File | Status |
|-------------|-------------|--------|
| dictate-keyboard-layout-refactor.reviewed.md | dictate-keyboard-layout-refactor.reviewed.chunks.json | ✅ Phase 1a — validated (9 headings, 19 chunks, 6 blocks) |
| research/1-pipeline-service/1-pipeline-service.reviewed.md | research/1-pipeline-service/1-pipeline-service.reviewed.chunks.json | ✅ Phase 1a — validated (15 headings, 11 chunks) |
| research/2-keyboard-layout/2-keyboard-layout.reviewed.md | research/2-keyboard-layout/2-keyboard-layout.reviewed.chunks.json | ✅ Phase 1a — validated (15 headings, 4 chunks) |
| research/3-floating-overlay/3-floating-overlay.reviewed.md | research/3-floating-overlay/3-floating-overlay.reviewed.chunks.json | ✅ Phase 1a — validated (14 headings, 3 chunks) |

---

## Execution Strategy

### Skill-Conventions

```yaml
build_command: ./gradlew assembleDebug
test_command: ./gradlew test
lint_command: null                              # no linter configured per CLAUDE.md "No linter or formatter is configured"
coverage_command: ./gradlew test               # gradle includes coverage in default test task
instrumented_test_command: ./gradlew connectedAndroidTest
plan_reader_cmd: npx tsx ~/.claude/skills/implement-long-plan-v2/plan-reader.ts
test_file_pattern: "app/src/test/**/*.kt"      # JVM unit tests
android_test_file_pattern: "app/src/androidTest/**/*.kt"   # instrumented (Room DB migrations etc.)
mock_factory: handwritten-fakes              # Quality-Gate K-1 — no Mockito/MockK; pattern: FakeXxx classes in app/src/test/.../core/ (see FakeAudioFocusGate.kt for the canonical example: KDoc header explains K-1+K-4 invariants, counter-based fake with public mutable result-fields). K-4 — no Android Context required (no Robolectric).
source_extension_main: .kt                     # new code in Kotlin per project convention
source_extension_legacy: .java                 # legacy Java code stays Java
test_helpers_location_hint: "app/src/test/java/net/devemperor/dictate/"
coverage_threshold_branches: 70                # provisional; Phase-1a/AUDIT-TEST may refine
```

### Pre-Flight

(Phase 1a — initial population. Phase 1b adds E2E-specific items.)

| # | Kind | Target | Programmatic check | Why |
|---|------|--------|--------------------|-----|
| 1 | gradle-wrapper | `./gradlew` in worktree | `test -x ./gradlew` | All build/test commands route through it |
| 2 | jvm | JDK 17+ for Android Gradle Plugin | `java -version` shows ≥ 17 | Required for AGP 8.x |
| 3 | android-sdk | Android SDK with API 35 installed | `ls $ANDROID_HOME/platforms/android-35` | targetSdk=35 per CLAUDE.md |
| 4 | knowledge-skill | `knowledge-adr-format` skill installed | `ls ~/.claude/skills/knowledge-adr-format` | Mandatory load for Block 0 ADR writing |
| 5 | knowledge-skill | `knowledge-doc-format` skill installed | `ls ~/.claude/skills/knowledge-doc-format` | Mandatory load for Block 0 architecture-docs |
| 6 | template | `~/.claude/templates/adr.md` exists | `test -f ~/.claude/templates/adr.md` | Block 0 ADR template (mandatory per `~/.claude/snippets/docs/docs.md`) |
| 7 | template | `~/.claude/templates/universal.md` exists | `test -f ~/.claude/templates/universal.md` | Block 0 architecture-doc template (mandatory per `~/.claude/snippets/docs/docs.md`) |
| 8 | git-state | working tree clean OR user-acknowledged | `git status --porcelain` | Required for clean block-end-commits (Phase 2.5 will re-check) |
| 9 | filesystem | `docs/decisions/` and `docs/architecture/` writable | `test -w docs/` then ensure mkdir succeeds | Block 0 creates these directories |

Note: Block 3 (DB-Persistence, chunk C9) requires an Android emulator or test device for `connectedAndroidTest` (per Spec 1 §11.7.0a). That requirement surfaces ONLY for B3 in-block; it is **not** a global pre-flight item — flagged for Phase 4.5 E2E and for the Block-3 implementer.

**Phase-1b appended (E2E-specific) — applies before Phase 4.5 only:**

| # | Kind | Target | Programmatic check | Blocking |
|---|------|--------|--------------------|----------|
| 10 | device | Android device OR emulator (API 26-35) reachable via ADB | `adb devices` shows ≥1 device with state `device` (not `unauthorized`/`offline`) | yes |
| 11 | adb-connection | **USB-cable connection — NOT Wireless** (memory-flag: `user_dev_setup.md` — ADB Wireless is unstable for this user) | `adb shell ip route` succeeds 5× in 60 s without disconnect | yes |
| 12 | apk-installed | Dictate APK installed from this worktree's `./gradlew assembleDebug` output | `adb shell pm list packages \| grep net.devemperor.dictate` | yes |
| 13 | ime-enabled | Dictate IME is **enabled** in Android Settings → Languages & Input → Manage keyboards | `adb shell ime list -a \| grep net.devemperor.dictate` | yes |
| 14 | ime-selected | Dictate IME is the **currently selected** keyboard | `adb shell settings get secure default_input_method` outputs `net.devemperor.dictate/...` | yes |
| 15 | mic-permission | `RECORD_AUDIO` permission granted | `adb shell dumpsys package net.devemperor.dictate \| grep RECORD_AUDIO` shows granted | yes |
| 16 | target-app | A target app with a text input field (Notes/Keep/Messages) installed | `adb shell pm list packages -d` lists ≥1 | yes |
| 17 | network + api-key | Device has WLAN reachable to chosen AI-provider + Dictate has ≥1 provider API-key configured | `adb shell ping -c 3 api.openai.com` succeeds (or chosen provider) + manual: Dictate Settings → API Keys | yes |
| 18 | personal-device-consent | If personal device: user has explicitly confirmed "I'm OK with running v3→v4 DB-migration + Foreground-Service-Notification + Overlay-Permission ask on my actual phone" | manual: see User-Question Q1+Q2 below | **yes — BLOCKING** |

For the optional/TC-specific items (overlay-permission grant/deny per Q7, room-testing Gradle dep per B3, logcat baseline) see the runbook prerequisites table (rows 9, 12, 16, 17).

### Test-Strategy-Completeness

**Mocking strategy** — DOCUMENTED. Quality-Gate K-1 (handwritten fakes only, no Mockito/MockK), K-4 (no Android Context — no Robolectric). The pattern is established (see `app/src/test/java/net/devemperor/dictate/core/FakeAudioFocusGate.kt` as canonical example: KDoc explains K-1+K-4 invariants, counter-based fake with public mutable result-fields). All new modules write their own `FakeXxxModule` / `FakeXxxHardware` / `FakeXxxFactory` as needed.

**Test approach (tier-mix)** — DOCUMENTED across the three specs:
- **JVM unit tests** (`app/src/test/...`): primary tier — pure Kotlin reducer-tests, fake hardware, no Android-Runtime. Covers all modules, all cascades, all predicates.
- **Robolectric** (where unavoidable for Android-Runtime): minimal — flagged in spec 1 §11.6 (OOM-Recovery test), spec 2 §11.5 (RecordingAnimationController), spec 3 §14.1 (DefaultOverlayPermissionGate). Robolectric usage is opt-out by default per K-4; explicit justification required.
- **Instrumented tests** (`app/src/androidTest/...`): NEW for this plan — first-time introduction (Spec 1 §11.7.0a). Used ONLY for DB-Migration testing (MigrationTo4Test with `room-testing` library), connected to a device/emulator. Local-only — no CI emulator setup (per spec 1 §11.7.0a explicit decision). Block 3 (chunk C9) implements the androidTest infrastructure as step 0.
- **Espresso UI tests** (subset of `app/src/androidTest/...`): Spec 2 §14.2 defines 10 UI-Tests (1-10) covering the resend-visibility bug-class + permission-gated overlay-toggle. Block 5 (chunk C14 or C15) introduces these.

**Coverage goals**:
- Default `coverage_threshold_branches: 70` (per state-file, provisional). AUDIT-TEST may refine per block.
- Spec 1/2/3 each have ##14 "Test-Strategie" sections (spec 1's Test-Strategy is distributed across §10 acceptance + §11.7) — implementer-agents derive per-chunk coverage from those.

**Gaps flagged for Phase 2.6 / Phase 4.5**:
- **Knowledge-skill gap**: no `knowledge-kotlin` or `knowledge-android` skill in `~/.claude/skills/`. The codebase is Kotlin+Java+Android — flagged in state-file `doc_landscape.knowledge_skills_missing`. Phase 4.6c inline-anchors will hand-roll without these; consider adding a Dictate-specific `knowledge-dictate` skill mid-implementation.
- **No CI for androidTest**: explicit decision per spec 1 §11.7.0a. Block 3 acceptance is local-run-only — flagged for AUDIT-TEST in B3 closeout.
- **No central coverage report**: gradle `./gradlew test` runs unit tests; no JaCoCo or branch-coverage report currently configured. AUDIT-TEST in each block-closeout uses inspection rather than threshold-fail-build.

### Test-Strategy (per skill-block)

| Block | Tier-mix | Mocking strategy | Coverage targets |
|-------|----------|------------------|-------------------|
| B0 (Architecture-Foundation) | docs-only — no code, no tests | n/a | UDOC-skeleton compliance check (knowledge-doc-format) + ADR section completeness (knowledge-adr-format) |
| B1 (Pre-Architecture + Service-Skeleton) | JVM unit (predResendVisible-helper); Robolectric only for FGS-boot-latency test in chunk C2 (Spec 1 §10 Phase-B S-5) | Handwritten fakes (FakeKeyboardUiController for helper extraction; FakeServiceConnection for Bind-tests) | Helper-function 100% branch; Service-onCreate channel-order test + FGS-5s-budget test |
| B2 (Modular-Orchestrator-Implementation) | JVM unit (all reducers + cross-module cascades, pure Kotlin); Architecture-tests (Modul-Registry sanity-check, EffectFailure-routing, cascade-order-determinism, shutdown-order, ProGuard-Keep-rule) | FakeRecordingHardware, FakeBluetoothScoSubsystem, FakeAudioFocusGate, FakeAudioFileFactory, FakeToastSink, FakeSharedPreferences, FakeSessionDao | All §15.1 cascade-arrows (per plan §10 Block-1b acceptance 8 bullet-points); 13 modules each with ≥1 reducer-test |
| B3 (Migration-Persistence-AudioFactory) | JVM unit (subsystem-adapter migration tests); **First androidTest introduction** (MigrationTo4Test 6 cases v1→v4-chain); Robolectric (recovery-from-OOM-replay test) | room-testing MigrationTestHelper; handwritten FakeDao for non-migration tests | M3→M4 migration 6 cases (FAILED/CANCELLED/COMPLETED idempotent + inserted_at-NULL + CHECK-constraint); orphan-cleanup KG-SST-2 6 sub-cases |
| B4 (Keyboard-Layout-Catalog) | JVM unit (VisibilityMatrixTest parameterized, 25 cases per Spec 2 §14.2); Espresso UI-Test 1-10 (Spec 2 §14.2 — covers bug-symptoms §1.1 #1/#2/#3a/#3b); Spike-validations (§11.3 PulseLayout, §11.4 Inflation-Cost <50ms) | FakeKeyboardLayoutManager, FakeRenderBackend, FakeServiceBinder | VisibilityMatrixTest 25 cases; UI-Tests 1-10 all green |
| B5 (Floating-Overlay) | JVM unit (OverlayBackendTest with FakeOverlayWindow; DefaultOverlayPositionMapperTest); Robolectric (DefaultOverlayPermissionGate with in-memory SharedPreferences); manual integration (drag, multi-window, orientation-change) | FakeOverlayWindow, FakePermissionGate, FakeLayoutParamsFactory | OverlayBackend full attach/detach lifecycle + render-permission-gated; T7-cascade structural-fix verified (Geist-Widget-Bug regression) |

### End-to-End-Test-Plan

```yaml
scope: "Android IME refactor — survival, Triangle-FSM, visibility-predicates, DB-migration, overlay-permission, MotionLayout, cross-module-cascade — verified on device against user-visible behaviour."
runbook: ./reports/e2e-runbook.md
relevant_knowledge:
  available:
    - knowledge-adr-format             # only for TC-B0-DOCS verification
    - knowledge-doc-format             # only for TC-B0-DOCS verification
    - test-orchestrator                # discovers project test-runner — useful for Setup-step androidTest invocation
  missing:
    - test-knowledge-android           # GAP — no skill for Android IME E2E patterns; hand-rolled in runbook
    - test-knowledge-mobile            # GAP — same
    - test-knowledge-ime               # GAP — same; per-TC steps are self-contained
  consequence: "Phase-4.5 agent runs entirely from runbook hand-rolled steps; no skill-grounding fallback available. Each TC is fully self-contained — no idiom inference required."
test_case_count: 24                    # TC-PRE + TC-B0-DOCS + TC-1..TC-23
auto_count: 0                          # Android-IME E2E has no headless runner; auto-tier covered by JVM unit + instrumented tests in block-validate phases
manual_count: 24
prerequisites_count: 17                # 8 from Phase-1a + 9 new E2E-specific (device, adb-connection, apk-installed, ime-enabled, ime-selected, overlay-permission, mic-permission, target-app, network, api-key, personal-device-consent, etc.)
blocking_user_questions: 3             # Q1 (device-choice) + Q2 (personal-DB-migration consent) + Q3 (USB vs wireless) gate Phase-4.5 start
fresh_fallback_used: true              # Phase-1b ran as fresh-spawn (no SendMessage available in this environment); per skill, prompt is identical for resume vs fresh
skip_recommended: false                # architectural refactor with significant behaviour-impact — E2E mandatory
risk_points_covered:
  - "Foreground-Service lifecycle (FGS-5s-budget, channel-order, notification, OOM-Recovery) — TC-1, TC-3, TC-15, periodic-visits fgs-notification-presence + battery-impact"
  - "IME lifecycle (onCreateInputView, onStartInputView, view recreation) — TC-1, TC-3, TC-21"
  - "Audio capture pipeline (microphone permission, audio-focus, BT-SCO) — TC-1 (mic in flight), TC-15 (force-stop preserves DB)"
  - "Room DB v3→v4 migration (SessionStatus 4→6, +inserted_at column, CHECK-Recreate) — TC-14 explicit; supplemented by instrumented MigrationTo4Test in B3"
  - "Triangle-FSM transitions (KEYBOARD/WIDGET/HOVER + Pipeline-Done T7) — TC-4 through TC-10 (one TC per transition)"
  - "MotionLayout transitions (visual regression risk) — TC-21, TC-22 (Pulse-in-transition)"
  - "Cross-Module-Cascade (Mode 1/2 allowed, Mode 3 forbidden) — TC-23 + logcat-check"
  - "Visibility-Predicate (resend_btn / record_btn / send-in-single-row) — TC-11, TC-12, TC-13"
  - "Overlay-Permission flow (grant + deny + drag + orientation) — TC-17, TC-18, TC-19, TC-20"
  - "Orphan FAILED-audio-cleanup (KG-SST-2) — TC-16"
```

**Blocking user-questions (forwarded to orchestrator):**

| # | Question | Default options | Why blocking |
|---|----------|-----------------|--------------|
| Q1 | Which device — personal phone, dedicated test-device, or emulator? | personal-with-consent / test-device / emulator-api35 / decide-later | Determines blast-radius for DB-migration |
| Q2 | If personal phone: accept that v3→v4 Room-migration runs on real session DB? | proceed / backup-first / use-fresh-emulator | Real-user-data sensitivity |
| Q3 | ADB connection: USB-cable (recommended) or Wireless? | usb-cable / wireless-accept-known-unstable | `user_dev_setup.md` memory: Wireless drops mid-test invalidates logcat correlations |
| Q4 | AI-provider for transcription happy-path? | openai-whisper / groq-whisper / use-configured | Test latency + API quota |
| Q5 | Target app for typing? | keep-or-notes / signal-whatsapp / browser / all-three | Different IME-targets surface different edge-cases |
| Q6 | Cleanup after E2E? | full-teardown / clear-data-only / leave-installed | Personal-phone vs test-device decision |
| Q7 | Overlay-Permission: test grant + deny, or grant only? | grant-and-deny / grant-only / deny-only | Full coverage ~10 min more |

Q1+Q2+Q3 are **gate-blocking** for Phase-4.5 start. Q4-Q7 are configuration — can be defaulted but should be confirmed.

**Knowledge-gap escalation (orchestrator note):**

No `test-knowledge-android`, `test-knowledge-mobile`, or `test-knowledge-ime` skill exists. The runbook is hand-rolled with explicit step-by-step instructions per TC. Phase-4.6c may add a project-local `knowledge-dictate-ime` skill once implementation surfaces stable patterns (e.g., the adb-logcat-tag conventions used in the new modules). Until then, Phase-4.5 runs without skill-grounding — flagged in the doc-plan `knowledge_skills_missing` field.

### Plan-Consistency-Check

```yaml
status: warnings                          # not `fail` — all internal links resolve to existing files; only quality-skew + 1 broken reverse-link in specs
checked_at: 2026-05-14
broken_links: 1                           # spec reverse-link to parent plan
quality_warnings: 5                       # Phase 1a findings preserved below
spec_existence: all_present
external_unreachable: 0
proceed_decision: yes                     # user-mode `ohne Walkthrough` accepts warnings — broken reverse-links don't block code-implementation
```

**Phase 2.6 mechanical findings (orchestrator-run 2026-05-14):**

**F6 [NEW — broken reverse-link in specs]**: All three spec files have a header `**Hauptplan:** [→ keyboard-layout-refactor.md](../../keyboard-layout-refactor.md)` pointing at a non-existent file at the repo root. The actual plan is `../dictate-keyboard-layout-refactor.reviewed.md` (same directory, not parent). Mechanical fix is trivial (3 specs × 1 line each) but is deferred to Phase 4.6c inline-anchor work (the broken reverse-link is a doc-anchor, not implementation-blocking). Block 0 docs being written by the implementer-agent will use correct paths from the start.

**F7 [NEW — plan-to-spec links point at skeleton (.md) not reviewed (.reviewed.md)]**: Plan §4 building-blocks reference specs as `research/N-spec/N-spec.md` (skeleton versions; still exist). SoT is `.reviewed.md`. Links resolve (no broken-link error), but readers may consult an outdated skeleton. Phase 4.6c inline-anchor work re-points these. Implementer-agents reading the spec via `plan-reader` CLI on `.reviewed.md` (which is what the chunks.json `source_file` field points at) bypass the plan-→-spec link entirely — they go via chunks.json.

**Pre-Phase-2.6 findings (Phase 1a — verified in Phase 2.6):**

1. **Plan/Spec block-numbering drift**: Plan §4 table at lines 212-221 numbers blocks as 0 / 1a / 2 / 1b / 3 (Subsystem-Adapter-Migration) / 4 (RecordingHardwareSubsystem) / 5 / 6. Spec 1 §11.2.2 uses different numbering: Block-1a / Block-2 / Block-1b / Block-3 (= DB-Persistence) / Block-4 (= AudioFileFactory). The plan-§4-Block-3 "Subsystem-Adapter-Migration" is implicitly part of spec-1-Block-1b's Module-Migration (LanguageController → LanguageModule), and the plan-§4 has NO dedicated DB-Persistence block. Disambiguation in chunks.json: plan-C8 = subsystem-migration per plan §4, plan-C9 + plan-C10 = DB-persistence per spec1 §6 + §11.4. Phase 2.6 should align the two numbering conventions either by amending plan §4 to add a "Block 3.5 DB-Persistence" entry, or by amending spec 1 §11.2.2 to use plan numbering.

2. **§4 in Spec 1 = 30k tokens**: largest H2 section. Sub-divided into §4.1-§4.11. Chunking treats §4 as a single H2-owner (spec1-C4) but documents that targeted sub-sections (§4.2/§4.4 → spec1-C3, §4.5/§4.6 → spec1-C7, §4.11 → spec1-C11) are read by other chunks. Auto-Refactor (H3→H2 promotion) was NOT performed — preserves cross-references but requires implementer-discipline. Phase 2.6 should validate that no chunk-implementer mis-reads §4 wholesale when only a sub-section applies.

3. **§11 in Spec 1 = 23k tokens** (Research-TODOs für Agent — Detail-Antworten): similar issue — §11.1 (FGS), §11.2 (Block-implementation steps), §11.3 (Bound-Service), §11.4 (DB), §11.5 (Notification-UX), §11.6 (OOM-recovery), §11.7 (Migration-Reihenfolge + androidTest-Setup). Multiple chunks read targeted sub-sections. Owner is spec1-C11 (AudioFileFactory has the heaviest §11.2.2-Block-4 footprint).

4. **plan §3.3 LayoutCatalog OVERLAY_5BUTTON** and spec 3 §3.1 OVERLAY_5BUTTON should be cross-validated as identical (the plan says shared for WIDGET + HOVER; the spec defines the slots). Phase 2.6 grep-check.

5. **Acceptance criteria distribution**: plan has no top-level `## 10 Acceptance-Kriterien` (plan H2 stops at §9 Iteration-Log). All Block-acceptance lives in the spec files: spec 1 §10 (the most extensive — covers plan-Block-1a/1b/2 + spec-Block-3); spec 2 §10 (plan-Block-5); spec 3 §10 (plan-Block-6). This is consistent with the modular-plan-pattern (D21 — plan is high-level, specs are SoT for detail), but Phase 2.6 should verify (a) each plan-block has at least one acceptance section reachable from the plan via spec-link, and (b) the plan-§7 "Verbleibende offene Fragen" doesn't have orphaned acceptance-bullets that didn't migrate to a spec §10.

---

## Spec Inventory

(Modular-plan-pattern is active — three large specs in research/.)

| Spec File | Status | Lines | Chunks File | Implementation Block |
|-----------|--------|-------|-------------|-----------------------|
| research/1-pipeline-service/1-pipeline-service.reviewed.md | Spec — programmer-ready | 6984 | research/1-pipeline-service/1-pipeline-service.reviewed.chunks.json | B1 (C1+C2) · B2 (C3-C7) · B3 (C8+C9+C10+C11) |
| research/2-keyboard-layout/2-keyboard-layout.reviewed.md | Spec — programmer-ready | 2601 | research/2-keyboard-layout/2-keyboard-layout.reviewed.chunks.json | B4 (C12+C13+C14+C15) |
| research/3-floating-overlay/3-floating-overlay.reviewed.md | Spec — programmer-ready | 2857 | research/3-floating-overlay/3-floating-overlay.reviewed.chunks.json | B5 (C16+C17+C18) |

Additional research (background / probes; not spec-authoritative):
- research/main-button-area-inventory.md — main-buttons inventory
- research/motionlayout-architecture-options.md — MotionLayout architecture options
- research/_pending-*/ — 4 sub-folders of pending research notes

---

## Chunks

Status legend: ⏳ pending, 🔄 in progress, ✅ done, ⚠️ blocked.

**Skill-Block layout (6 blocks, 19 chunks, aggregate Implementation-Score ~16,200):**

| Block | Name | Chunks | Score | LOC | Test-Agents | Status |
|-------|------|--------|------:|----:|------------:|--------|
| B0 | Architecture-Foundation (ADRs + state-architecture docs) | C0 (1) | 3600 | 2800 (docs) | 1 | ✅ (commits 1ca3bcc → 3e5a8dd, 14 fixes 1 wave) |
| B1 | Pre-Architecture and Service-Skeleton | C1+C2 (2) | 1250 | 650 | 1 | ✅ (commits bd8f1e6 → 7a5afd1, 23 fixes 1 wave; 198 tests pass) |
| B2 | Modular-Orchestrator-Implementation | C3+C4+C5+C6+C7 (5) | 4350 | 3100 | 3 | ✅ (commits d0dffd9 → 6c16951; 14 modules + Orchestrator + Registry + PrefMirror + Recovery + Service-wiring; 22 fixes 1 wave; 536 tests pass) |
| B3 | Migration-Persistence-AudioFactory | C8+C9+C10+C11 (4) | 3150 | 1900 | 2 | ✅ (commits b97e09a → 676a62f; 2 Crit data-loss fixed in W1; IMPL-1 + SF-4 closed; 679 tests pass) |
| B4 | Keyboard-Layout-Catalog | C12+C13+C14+C15 (4) | 2500 | 1510 | 2 | ✅ (commits f8ba56a → 307b4e6; 4 Crit dual-render-path fixed in W1; 843 tests pass) |
| B5 | Floating-Overlay | C16+C17+C18 (3) | 2000 | 1250 | 2 | ✅ (commits 74f9dd3 → 6164453; keystone IME-activation F-1/F-2/F-3 wired; ~933 tests pass) |

**Per-chunk overview (plan-level IDs; spec-level mapping in spec chunks.json):**

| Chunk-ID | Title | Plan-Block | Score | Bracket | Status |
|----------|-------|-----------|------:|---------|--------|
| C0-block0-arch-docs | Block 0: ADRs + state-architecture docs | Block 0 | 3600 | XL (D12-atomic Foundation-Pack) | ✅ (commit 1ca3bcc + wave 3e5a8dd, 18 docs + 4 back-refs + 14 audit-fixes) |
| C1-block1a-quick-wins | Block 1a: Quick-Wins in today's code | Block 1a | 400 | S/M | ✅ (ff6da41 prod + f1686e8 tests, 17 unit-tests) |
| C2-block2-pipeline-service-skeleton | Block 2: DictatePipelineService skeleton + FGS | Block 2 | 850 | M | ✅ (cf7a8ba prod + 0b3d126 tests, 10 Robolectric tests) |
| C3-state-core | Block 1b/1: DictateUiState + Store + DictateModule + Action | Block 1b | 850 | M | ✅ (c127730 prod + 6af14a8 tests, 60 tests) |
| C4-orchestrator-and-registry | Block 1b/2: DictateOrchestrator + Registry + ModuleServices | Block 1b | 650 | M | ✅ (d0af21a prod + 8ca80d8 tests, 34 architecture-tests) |
| C5-modules-core | Block 1b/3: Core modules — Recording/Pipeline/Audio/ViewMode/Overlay | Block 1b | 1150 | L | ✅ (57c2079 prod + 71580a4 tests, 110 reducer+T1-T7 tests) |
| C6-modules-auxiliary | Block 1b/4: Auxiliary modules (9 modules incl. KeyboardInput + Interruption-stub) | Block 1b | 1100 | L | ✅ (565a434 prod + 4746cdf tests, 81 tests) |
| C7-prefmirror-recovery-wiring | Block 1b/5: PipelinePrefMirror + Recovery + Wiring | Block 1b | 600 | M | ✅ (eb3471c prod + 3fb5e46 tests, 34 tests; IMPL-1 re-deferred to C8) |
| C8-block3-subsystem-adapter-migration | Block 3a: Subsystem-Adapter-Migration | Block 3 (plan) | 700 | M | ✅ (d5b9f0f prod + 5f02d25 tests; 7 adapters + PipelineCallbackBridge; IMPL-1 closed) |
| C9-block3-db-persistence-schema-m4 | Block 3b: DB-Persistence — Schema-Migration M3→M4 | Block 3 (spec1) | 850 | M | ✅ (d78984c prod + 02124ba tests; first androidTest infra; MigrationTo4 7 cases) |
| C10-block3-db-persistence-recovery | Block 3c: DB-Persistence — Recovery + Cleanup | Block 3 (spec1) | 600 | M | ✅ (eea6f6e prod + 9666c93 tests; SF-4 closed; 6 KG-SST-2 cases) |
| C11-block4-audio-file-factory | Block 4: AudioFileFactory + Pre-Dispatch | Block 4 | 1000 | L | ✅ (3c28d48 prod + 38f28a0 tests; KG-AFF-1..5 all implemented; LegacyMigration + CacheDirFactory) |
| C12-block5-layout-catalog-and-render-backend | Block 5/1: LayoutCatalog + RenderBackend + helpers | Block 5 | 800 | M | ✅ (47a5c3c prod + e548d5a tests; 11 production files; 92 tests incl. 25-case VisibilityMatrixTest) |
| C13-block5-motionscene-xml | Block 5/2: MotionScene XML + layout-XML refactor | Block 5 | 500 | M | ✅ (832d912 prod + c988135 tests; 5 ConstraintSets + 5 Transitions; visibilityMode="ignore" on all 9 buttons) |
| C14-block5-ime-view-backend | Block 5/3: ImeViewBackend + Controllers + Animation | Block 5 | 750 | M | ✅ (aa3b229 prod + 5870740 tests; 7 production files; 52 unit + 10 Espresso skeletons) |
| C15-block5-service-wiring-and-cleanup | Block 5/4: Service wiring (5c) + Cleanup (5d destructive) | Block 5 | 450 | M | ✅ (d198d19 prod + a956274 tests; KeyboardLayoutModeController deleted; 5 new wiring tests; D-13 re-deferred to B7) |
| C16-block6-overlay-backend-and-window | Block 6/1: OverlayBackend + Window-wrapper + XML | Block 6 | 800 | M | ✅ (1f3ef06 prod + d69b95d tests; OverlayBackend + AndroidOverlayWindow + OVERLAY_5BUTTON body; 41 tests) |
| C17-block6-permission-onboarding | Block 6/2: Permission-Observer + Gate + Onboarding-UI | Block 6 | 550 | M | ✅ (commit pair + B5-VAL-W1; DefaultOverlayPermissionGate + Observer + OnboardingActivity; 22 tests) |
| C18-block6-mode-transitions-and-drag | Block 6/3: Mode-transitions T1-T7 + Drag + OverlayModule | Block 6 | 650 | M | ✅ (0d6c12e prod + e1486fe tests; T1-T7 wiring + DragController + PositionMapper; 27 tests; activation completed in B5-VAL-W1) |

---

## Repair-Sub-Phase Log (Iter 10)

| Wave-ID | Caller | Iter | Findings (🟢/🟡/❌) | Outcome | Wave-commit |
|---------|--------|------|---------------------|---------|-------------|
| B0-VAL-W1 | Block-Validate B0 (AUDIT-PLAN-AND-API + AUDIT-CONVENTION; AUDIT-LOGIC + AUDIT-TEST n/a for docs-only) | 1 | 11/0/5 (consolidator deduplicated 14 audit-findings into 11 unique + 5 ❌ eliminated; implementer tracked as F-1..F-14 in block-report) | ✓ converged (self-check verified, no Wave-2 needed) | 3e5a8dd |
| B1-VAL-W1 | Block-Validate B1 (4 audits: AUDIT-PLAN-AND-API + AUDIT-CONVENTION + AUDIT-LOGIC + AUDIT-TEST) | 1 | 23/0/2 (consolidator deduplicated 26 audit-findings → 23 unique + 1 ❌ false-positive + 1 OOS for ADR follow-up; LOGIC found 3 real bugs — hard-coded Idle, startForeground exceptions, bind-failure silent) | ✓ converged (self-check verified; 198 tests pass; new tests for KeyboardUiController + PipelineServiceConnection + pre-API-34 + NotificationChannel-invariants) | 7a5afd1 |
| B2-VAL-W1 | Block-Validate B2 (4 audits: AUDIT-PLAN-AND-API + AUDIT-CONVENTION + AUDIT-LOGIC + AUDIT-TEST) — the architectural core, 5 chunks / ~3100 LOC diff | 1 | 23/1/0 (1 🟡-with-research + 23 🟢; research recommended Option D for Critical F-1 manual-paste flag — fold into ResendState). 22 fixed in this wave; 2 NTH postponed to Phase 4.6 (spec-matrix); 1 SF postponed to B3 (post-extraction-failure recovery wiring) | ✓ converged (536 tests pass; 7 new behavioural tests; Option D implemented + ADR-0001 Decision-History entry appended) | 6c16951 |
| B3-VAL-W1 | Block-Validate B3 (4 audits) — 4 chunks / ~141 new tests / 2 Crit data-loss bugs found | 1 | 27/2/0 (2 🟡-with-research = 2 Crit + 27 🟢; research recommended Option (a) for both: F-1 FK ON DELETE CASCADE → SET NULL, F-2 backfill inserted_at = NULL with Pref.PendingInsertionFreshnessMs gate; amend M4 in-place pre-merge). 25 fixed in this wave (2 Crit + 11 Imp + 12 NTH); 4 deferred (F-15/16/23 → B4 AUDIT-TEST, F-29 → cleanup-wave); 1 partially fixed | ✓ converged (679 tests pass; Schema 4.json regenerated with ON DELETE SET NULL; ADR-0003 + DATABASE-PATTERNS amended) | 676a62f |
| B4-VAL-W1 | Block-Validate B4 (4 audits: AUDIT-PLAN-AND-API + AUDIT-CONVENTION + AUDIT-LOGIC + AUDIT-TEST) — 4 chunks / 4 Crit dual-render-path regressions + 30 🟢 + 4 🟡 plan-evolution | 1 | 30/4/0 (4 🟡 deferred to B5-pre / B7 — see Postponed Issues; 30 🟢 fixed in this wave: 4 Crit (F-1/F-2/F-3/F-7 — long-press cascade preservation + audio_focus icon inversion + single_row_state overlap) + 12 Imp + 14 NTH) | ✓ converged (843 unit tests pass; net +5 vs C15 baseline; assembleDebug + compileDebugAndroidTestKotlin green) | ⏳ (set by orchestrator) |

## Postponed Issues

| Block | Postponed-Issue | Severity | Why postponed | Tracking |
|-------|-----------------|----------|---------------|----------|
| B2 | F-11, F-14: spec-internal §15.1 matrix inconsistency + §15 module-inventory column drift | NTH | Spec-doc-edits, not implementation-blocking; consolidate with Phase 4.6 doc-pass | Phase 4.6c |
| B2 | SF-4: post-extraction-failure manual-paste-flag wiring | NTH | Recovery-path responsibility — needs B3 SessionRepo + Recovery + Service-side error handler; pipeline-reducer is not the right owner | B3 recovery wiring |
| B2 | IMPL-1 (B1 carry-over re-deferred): JobExecutor-Init move from IME.onCreate → Service.onCreate | Important | Service composition-root requires AIOrchestrator + AutoFormattingService + PromptQueueManager + SessionManager + SessionTracker + PipelineCallback construction = B3 chunk C8 subsystem-migration scope | B3 C8 — **CLOSED** in commit d5b9f0f |
| B3 | F-15/16/23: 3 new Robolectric test files (KG-AFF-1 sofort-delete, cleanup-order, ResolverPreDispatch allocate) | NTH-test | Forwarded to B4 AUDIT-TEST per consolidator | B4 AUDIT-TEST |
| B3 | F-29: LegacyAudioFileMigration package-move (single-file `migration/` package) | NTH | Style sweep — deferred to follow-up cleanup-wave | TBD cleanup-wave |
| B3 | D-13: LanguageController not fully replaced (bridge dispatch instead) | Important | Settings-activity decoupling needed for full removal; bridge preserves both-sides-in-sync; full deletion is B4/B5 LayoutCatalog scope when orchestrator becomes primary | B4/B5 LayoutCatalog |
| B3 | D-14: DictateInputMethodService.audioFile field NOT removed | Important | 5 unrelated IME-side reads still need it; full removal requires B5/B6 LayoutCatalog | B5/B6 LayoutCatalog |
| B4 | F-10 (Block-Validate 🟡): `Action.RecordingAction.StopRecordingAndSend(sessionId = "")` empty-string sentinel — research: nullable sessionId vs documented sentinel | Important | Spec 1 §3 state-shape change + RecordingModule.reduce + every call-site; out of B4 scope per consolidator | B5-pre (proposed mini-block: pipeline state-shape evolution) — see B4-keyboard-layout-catalog.md Postponed Issues |
| B4 | F-12 (Block-Validate 🟡): `PipelineUiState.ReprocessStaging.isStarting` field doesn't exist; double-click race during SendStaging load | Important | Spec 1 §3 state-shape extension + PipelineModule.reduce | B5-pre — see B4-keyboard-layout-catalog.md Postponed Issues |
| B4 | F-13 (Block-Validate 🟡): `PipelineUiState.Running.completedSteps/totalSteps/elapsedMs` fields missing; counter labels are placeholders | Important | Spec 1 §3 state-shape extension + PipelineModule.reduce + LayoutStrings consumption | B5-pre — see B4-keyboard-layout-catalog.md Postponed Issues |
| B4 | F-15 (Block-Validate 🟡): `LayoutStrings.dictateButtonText` not language-aware | Important | Tied to D-13 — depends on LanguageModule.effectiveLanguage exposure once LanguageController is fully removed | B7 LanguageController + Settings-UI decoupling |

## Mid-Chunk-Triage Trigger Log

| Chunk | Step | Issue-ID | Severity | Wave-ID | Resolution |
|-------|------|----------|----------|---------|------------|

---

## End-to-End-Test-Result

**CLOSED-VIA-EPIC `dictate-cutover-completion` (2026-05-17).** Phase 4.5
was deferred at INT-1 escalation (a parallel-dormant architecture cannot
be meaningfully E2E'd) and is satisfied via the Epic's Phase-4.5 on the
identical unified codebase — see
`../2026-05-15 - dictate-cutover-completion/reports/e2e-test.md`
(auto-tier GREEN; device-tier env-blocked, not a failure) and the D4 /
SSoT rationale in `plan_lifecycle.deferred_phases` above. Not separately
re-run on the same code (no redundant re-run of identical-code phases).

## Phase 4.6 Documentation Update

**CLOSED-VIA-EPIC `dictate-cutover-completion` (2026-05-17).** Phase 4.6
deferred at INT-1 (a dormant architecture cannot be documented as live).
Satisfied via the Epic's Phase-4.6 doc pass on the unified codebase: the
**ADR-0001 / ADR-0003 / ADR-0005 Decision-History appends captured this
plan's now-live architecture decisions** (e.g. ADR-0001 2026-05-17
"Two-orchestrator coexistence collapsed; single-dispatch is now the
production recording driver"), and the `state-architecture/`
dormant-seam docs were updated to the live cutover. See
`../2026-05-15 - dictate-cutover-completion/reports/phase-4.6-report.md`.

## Block-End Commits

| Block | Block-Start-Commit | Block-End-Commit | Status |
|-------|--------------------|--------------------|--------|
| B0 | 0df6557 | 3e5a8dd | ✅ |
| B1 | bd8f1e6 | 7a5afd1 | ✅ |
| B2 | d0dffd9 | 6c16951 | ✅ |
| B3 | b97e09a | 676a62f | ✅ |
| B4 | f8ba56a | 307b4e6 | ✅ |
| B5 | 74f9dd3 | 6164453 | ✅ |

---

## Implementation Report (Phase 4.7)

**CLOSED-VIA-EPIC `dictate-cutover-completion` (2026-05-17).** Phase 4.7
deferred at INT-1; satisfied via the Epic's Phase-4.7 aggregate on the
unified codebase — see
`../2026-05-15 - dictate-cutover-completion/reports/implementation-report.md`
(33 issues / 26 drifts / 24 fixes; 1180/0 both variants; INT-1
code-verified RESOLVED; the 3× anti-pattern arc). Not separately re-run
on the identical code (D4 / SSoT — see `plan_lifecycle.deferred_phases`).

---

## Run Log

| Timestamp | Phase | Action | Outcome |
|-----------|-------|--------|---------|
| 2026-05-14 | Phase 0 | State-file created in worktree feature/dictate-keyboard-layout-refactor; reports/ directory created; plan-file Block-0 changes committed (2679312) | ✅ |
| 2026-05-14 | Phase 1a | Plan-Analysis-Agent created chunks.json files for plan + all 3 specs; 19 chunks across 6 skill-blocks; all 4 chunks.json validated via plan-reader. Modular-plan-pattern (D21) confirmed active. NO Auto-Refactor (H3→H2 promotion) performed on spec 1 §4/§11 — chunks reference targeted ###-sub-sections instead. Plan-Consistency-Check findings documented (5 items, primarily plan/spec block-numbering drift for Block 3). | ✅ |
| 2026-05-14 | Phase 1b | E2E-Test-Strategy agent (fresh-spawn; SendMessage not available in this env) produced 460-line runbook (24 TCs all manual, 17 prerequisites), populated `### End-to-End-Test-Plan` + extended Pre-Flight by 9 items. Knowledge-gap flagged: no test-knowledge-android/mobile/ime skill. 3 blocking + 4 non-blocking user-questions for Phase-4.5 — defaulted per "ohne Walkthrough" mode; user can override before Phase 4.5 starts. | ✅ |
| 2026-05-14 | Phase 1.5 | Execution-plan injected into plan-file (lines 8-23, `<!-- EXECUTION-PLAN -->` fences). Skill-block summary, test approach, E2E-question handling, worktree path, state-file pointer. | ✅ |
| 2026-05-14 | Phase 2 | Briefing-only emitted to user (6 block-boxes, doc-plan, test-strategy, E2E-runbook overview, phase status list). No `AskUserQuestion` per "ohne Walkthrough" mode. Defaults: validation+tests closeouts active, Phase-4.6 `full`. | ✅ |
| 2026-05-14 | Phase 2.5 | Git-state pre-check: working tree clean after orchestrator setup-commit e4edf6e. Verified via `git status --porcelain` (empty). | ✅ |
| 2026-05-14 | Phase 2.6 | Plan-Consistency-Check status `warnings`. F6 [new]: broken reverse-links in 3 specs (`../../keyboard-layout-refactor.md` doesn't exist; should be `../dictate-keyboard-layout-refactor.reviewed.md`) — deferred to Phase 4.6c. F7 [new]: plan-→-spec links point at skeleton `.md` not `.reviewed.md` (both exist, no broken-link error) — deferred to Phase 4.6c. F1-F5 (Phase 1a flagged): verified — block-numbering drift is mitigated in chunks.json via disambiguation, OVERLAY_5BUTTON cross-spec is consistent post-C-5, acceptance-criteria distribution conforms to D21 modular-plan-pattern. No blocking issues — Phase 3 starts. | ✅ |
| 2026-05-14 | Phase 3.1 B0-C0 | B0-C0-IMPL agent wrote 18 docs (5 ADRs + ADR-index + 12 architecture-doc files) + 4 back-references (plan §8.1 + Spec 1/2/3 §12) in one pass with 3-step adapted workflow. 0 critical/important issues. 2 D22-inline deviations documented. Commit 1ca3bcc. | ✅ |
| 2026-05-14 | Phase 3.2 B0 | Block-Validate: 2-topic audit (AUDIT-PLAN-AND-API + AUDIT-CONVENTION; LOGIC + TEST n/a for docs-only) found 0 Crit + 6 Imp + 14 NTH = 20 raw findings. VAL-SANITY consolidator deduplicated to 11 🟢 + 0 🟡 + 5 ❌. B0-VAL-REPAIR Wave 1 fixed all 11 (tracked as F-1..F-14 in block-report) with self-check ✅. Wave-commit 3e5a8dd. Block-Validate converged in 1 wave (soft-cap 3 not approached). | ✅ |
| 2026-05-15 | Phase 3.1 B1-C1 | B1-C1-IMPL-FULL combined Steps 1-5: predResendVisible helper extracted (now `isResendVisible` post-W1 rename); 5/6 resend-button mutation sites migrated; recordButton.text/isEnabled hybrid centralised in KeyboardUiController.applyRecordButtonForRecording; KSM.refresh quick-wins on Single-Row + Audio-Focus toggles. 17 JUnit tests. 3 D22 inline deviations. Split into Commit 1 ff6da41 (prod) + Commit 2 f1686e8 (tests). | ✅ |
| 2026-05-15 | Phase 3.1 B1-C2 | B1-C2-IMPL-FULL combined Steps 1-5: DictatePipelineService.kt new (303 lines KDoc-rich Foreground Service skeleton with LocalBinder); Manifest entries (FGS + FGS-MICROPHONE + POST_NOTIFICATIONS + service decl with `foregroundServiceType="microphone"`); IME-side bind/unbind; Robolectric 4.14.1 introduced as K-4 opt-out (documented). 10 Robolectric tests (channel-order + FGS 5s-budget + LocalBinder + onDestroy). 1 IMPORTANT issue IMPL-1 (JobExecutor-init move deferred to B2 — Spec 1 §7.3 Composition-Root requires full PipelineOrchestrator). Split into Commit 1 cf7a8ba + Commit 2 0b3d126. | ✅ |
| 2026-05-15 | Phase 3.2 B1 | Block-Validate: 4-topic audit (all 4 active) found 0 Crit + 10 Imp + 16 NTH = 26 raw findings. VAL-SANITY consolidated → 23 🟢 + 0 🟡 + 2 ❌ (1 OOS to ADR-0003 path-drift follow-up + 1 informational FP). B1-VAL-REPAIR Wave 1 fixed all 23 incl. 3 substantive LOGIC bugs (hard-coded Idle / startForeground exception / silent bind-failure), F-6 Spec-mandated German translation, F-8 predXxx→isXxx mechanical rename. 3 new test files (KeyboardUiControllerTest 6, PipelineServiceConnectionContractTest 4, DictatePipelineServicePreApi34Test 1). Self-check ✅; ./gradlew test all green. Wave-commit 7a5afd1. | ✅ |
| 2026-05-14 | Phase 1b | E2E-Test-Strategy-Agent (fresh-spawn, no SendMessage available — per skill the Phase-1b prompt is identical for resume vs fresh) wrote `./reports/e2e-runbook.md` with 24 manual TCs (TC-PRE + TC-B0-DOCS + TC-1..TC-23) covering 10 risk-points: FGS-lifecycle, IME-lifecycle, audio-capture, Room v3→v4 migration, Triangle-FSM T1-T7, MotionLayout transitions, Cross-Module-Cascade, Visibility-Predicate, Overlay-Permission flow, Orphan-FAILED-audio-cleanup. Knowledge-gap flagged: no `test-knowledge-android`/`-mobile`/`-ime` skill exists — runbook hand-rolled per TC with self-contained adb steps. 7 user-questions emitted (Q1-Q3 blocking, Q4-Q7 configuration). Pre-Flight extended with 9 E2E-specific items (device, adb-connection-USB, apk-installed, ime-enabled+selected, mic-permission, target-app, network+api-key, personal-device-consent). E2E NOT skipped — refactor has significant behaviour-impact. | ✅ |
| 2026-05-15 | Phase 3.1 B2-C3..C7 | B2 architectural core: 5 chunks implemented sequentially in combined-Steps-1-5 pattern with orchestrator-split commits. C3 c127730+6af14a8 (state-core types, 60 tests). C4 d0af21a+8ca80d8 (Orchestrator+Registry+ModuleServices+ProGuard, 34 tests). C5 57c2079+71580a4 (5 core modules incl. ViewMode T1-T7, 110 tests). C6 565a434+4746cdf (9 aux modules incl. KeyboardInput+Interruption-stub, 81 tests). C7 eb3471c+3fb5e46 (PrefMirror+Recovery+Service-wiring; assertCompleteCoverage active; LocalBinder typed; IMPL-1 re-deferred to C8 per D5). Total: 14 active DictateModule + 1 stub, all 14 in Registry.Default.all. New deps: kotlinx-collections-immutable, kotlin-reflect, kotlinx-coroutines-test. | ✅ |
| 2026-05-15 | Phase 3.2 B2 | Block-Validate (the heaviest of the plan, ~3100 LOC diff): 4-topic audit found **1 Critical** + 9 Imp + 17 NTH = 27 raw findings. Critical: LOGIC-B2-1 PipelineModule.NotifyResultNeedsManualPaste dead code (claimed "out-of-band write" doesn't exist; service-death recovery user-affordance non-functional). VAL-SANITY → 23 🟢 + 1 🟡 + 0 ❌. B2-VAL-RES-1 research-agent investigated 3 architectural options; **recommended Option D** (fold flag into ResendState — sibling of lastAudioExists, owned by ResendModule). B2-VAL-REPAIR Wave 1 implemented Option D + 22 other findings; 2 NTH postponed to Phase 4.6 (spec-matrix doc-edits); 1 SF postponed to B3 (post-extraction recovery wiring). ADR-0001 Decision-History entry appended. Self-check ✅; 536 tests pass. Wave-commit 6c16951. Block-Validate converged in 1 wave + 1 research-step (soft-cap 3 not approached). | ✅ |
| 2026-05-15 | Phase 3.1 B3-C8..C11 | B3 migration/persistence/audio-factory: 4 chunks. **C8** d5b9f0f+5f02d25: 7 adapter classes (RecordingHardware, BluetoothSco, AudioFocus, RecordingTimer, AmplitudeStream, BorderGlow, PipelineCallbackBridge); **IMPL-1 closed** — AIOrchestrator + AutoFormattingService + PromptQueueManager + SessionManager + SessionTracker + legacy PipelineOrchestrator + JobExecutor.initialize moved to Service.onCreate; LocalBinder typed getters for IME consumers. **C9** d78984c+02124ba: M3→M4 schema (SessionStatus 6 variants + inserted_at column + CHECK-Recreate per DATABASE-PATTERNS); first androidTest infrastructure (`app/src/androidTest/` + room-testing dep + MigrationTo4Test 7 cases); local-only-no-CI per spec §11.7.0a. **C10** eea6f6e+9666c93: PipelineSessionRepoAdapter real impl + PipelineOrphanCleaner with 6 KG-SST-2 cases; PipelineRecovery filled with Spec 1 §6.3 algorithm (MERGE semantics); **SF-4 closed** — NotifyManualPasteNeeded dispatch for COMPLETED-with-pending-insertion. **C11** 3c28d48+38f28a0: CacheDirAudioFileFactory + Pre-Dispatch-Allocation (UUID files) + LegacyAudioFileMigration filesDir→cacheDir; all KG-AFF-1..5 implemented 1:1. New deps: room-testing 2.6.1 + test-runner + test-rules. | ✅ |
| 2026-05-15 | Phase 3.2 B3 | Block-Validate: 4-topic audit found **2 CRITICAL data-loss bugs** + 14 Imp + 17 NTH = 33 raw findings. Criticals: LOGIC-B3-1 (parent_session_id ON DELETE CASCADE wipes fresh POST_PROCESSING children when parent ages out — data loss); LOGIC-B3-2 (M3→M4 backfill inserted_at = created_at for pre-existing rows → first post-upgrade idle-stop silently deletes user history >7d old — data loss). VAL-SANITY → 27 🟢 + 2 🟡 (Criticals research-needed). B3-VAL-RES-1 combined research-agent recommended **Option (a) for both** (FK → SET NULL + backfill = NULL + Pref.PendingInsertionFreshnessMs gate) + **amend M4 in-place** (pre-merge, no users affected — no M5 needed). B3-VAL-REPAIR Wave 1 implemented research + 25 other findings; 4 deferred (3 to B4 AUDIT-TEST, 1 cleanup-wave); 1 partially. Schema 4.json regenerated with ON DELETE SET NULL. **ADR-0003 + DATABASE-PATTERNS.md amended** (Data-preservation rule). Self-check ✅; 679 tests pass. Wave-commit 676a62f. Block-Validate converged in 1 wave + 1 research-step. | ✅ |
| 2026-05-15 | Phase 4 Integration | INTEGRATION agent cross-block audit (6 blocks, 19 chunks, 52 commits). No code-correctness regression in the shipped diff (legacy paths authoritative; keystone IME-activation chain green; 946 tests). **Escalated INT-1 (`Critical, escalate-to-user`):** the new state-architecture is a permanent parallel-dormant layer; the make-it-live + retire-legacy work was forwarded to blocks (B5-pre/B6/B7) the executed plan never contained; D15 postponed-aggregate over threshold (7 open Important). Verdict: do NOT silently archive — user must decide (a) follow-up plan or (b) accept-dormant. Phases 4.5/4.6/4.7/5 deferred (a dormant architecture cannot be meaningfully E2E'd / documented-as-live / closed). | ✅ (escalated) |
| 2026-05-16 | INT-1 routing | User chose **INT-1 routing option (a)** — implement the cutover. Follow-up Epic `dictate-cutover-completion` authored + run on the SAME branch + worktree + commit lineage (Epic baseline 65bb303 on this plan's 52 commits — one unified codebase). This plan's deferred Phase 4.5/4.6/4.7/5 to be satisfied VIA the Epic (D4 / SSoT — no redundant re-run of identical-code phases). | ✅ |
| 2026-05-17 | Phase 4.5/4.6/4.7 | **CLOSED-VIA-EPIC `dictate-cutover-completion`.** Epic Phase-4 Integration Check **code-verified INT-1 RESOLVED** (all four constituent facts FALSE; CutoverArchitectureInvariantTest regression-lock). Epic Phase 4.5 (e2e-test.md auto-tier GREEN) + 4.7 (implementation-report.md, 1180/0 both variants, +226 over the 946 baseline) + 4.6 (ADR-0001/0003/0005 Decision-History appends captured THIS plan's now-live architecture decisions; state-architecture/ docs updated to the live cutover) satisfy this plan's deferred phases on the identical unified codebase. See `plan_lifecycle.deferred_phases` + the deferred-phase section notes above. | ✅ (via Epic) |
| 2026-05-17 15:20 | Phase 5a | PHASE5-ARCHIVE: plan archived. Per-plan `README.md` authored (status archived; Phase 4.5/4.6/4.7/5 completed-via the Epic with D4/SSoT rationale; plan body + 3 specs preserved unchanged as the historical record / Epic SoT; bidirectional link to the Epic). `docs/plans/README.md` process index authored (was MISSING). `plan_lifecycle.status: archived` + deferred-phases `closed-via-epic`. Cross-reference integrity verified. EN translation deferred to Phase 5b/5c. Self-check ✅. | ✅ |
