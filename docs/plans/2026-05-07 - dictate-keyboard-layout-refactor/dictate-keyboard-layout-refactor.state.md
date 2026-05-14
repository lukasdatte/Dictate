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

**Phase-2 Briefing emitted:** pending
**Pre-Phase-3 Git-State Check:** pending

---

## plan_lifecycle

```yaml
current_path: docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
move_to: docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md     # already in target location
status: in_place
note: plan was authored directly in docs/plans/ — no Phase-0 mv needed
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

**Pre-Phase-2.6 findings (Phase 1a — orchestrator should escalate to Phase 2.6 for verification):**

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
| B0 | Architecture-Foundation (ADRs + state-architecture docs) | C0 (1) | 3600 | 2800 (docs) | 1 | ⏳ pending |
| B1 | Pre-Architecture and Service-Skeleton | C1+C2 (2) | 1250 | 650 | 1 | ⏳ pending |
| B2 | Modular-Orchestrator-Implementation | C3+C4+C5+C6+C7 (5) | 4350 | 3100 | 3 | ⏳ pending |
| B3 | Migration-Persistence-AudioFactory | C8+C9+C10+C11 (4) | 3150 | 1900 | 2 | ⏳ pending |
| B4 | Keyboard-Layout-Catalog | C12+C13+C14+C15 (4) | 2500 | 1510 | 2 | ⏳ pending |
| B5 | Floating-Overlay | C16+C17+C18 (3) | 2000 | 1250 | 2 | ⏳ pending |

**Per-chunk overview (plan-level IDs; spec-level mapping in spec chunks.json):**

| Chunk-ID | Title | Plan-Block | Score | Bracket | Status |
|----------|-------|-----------|------:|---------|--------|
| C0-block0-arch-docs | Block 0: ADRs + state-architecture docs | Block 0 | 3600 | XL (D12-atomic Foundation-Pack) | ⏳ |
| C1-block1a-quick-wins | Block 1a: Quick-Wins in today's code | Block 1a | 400 | S/M | ⏳ |
| C2-block2-pipeline-service-skeleton | Block 2: DictatePipelineService skeleton + FGS | Block 2 | 850 | M | ⏳ |
| C3-state-core | Block 1b/1: DictateUiState + Store + DictateModule + Action | Block 1b | 850 | M | ⏳ |
| C4-orchestrator-and-registry | Block 1b/2: DictateOrchestrator + Registry + ModuleServices | Block 1b | 650 | M | ⏳ |
| C5-modules-core | Block 1b/3: Core modules — Recording/Pipeline/Audio/ViewMode/Overlay | Block 1b | 1150 | L | ⏳ |
| C6-modules-auxiliary | Block 1b/4: Auxiliary modules (8 simpler modules) | Block 1b | 1100 | L | ⏳ |
| C7-prefmirror-recovery-wiring | Block 1b/5: PipelinePrefMirror + Recovery + Wiring | Block 1b | 600 | M | ⏳ |
| C8-block3-subsystem-adapter-migration | Block 3a: Subsystem-Adapter-Migration | Block 3 (plan) | 700 | M | ⏳ |
| C9-block3-db-persistence-schema-m4 | Block 3b: DB-Persistence — Schema-Migration M3→M4 | Block 3 (spec1) | 850 | M | ⏳ |
| C10-block3-db-persistence-recovery | Block 3c: DB-Persistence — Recovery + Cleanup | Block 3 (spec1) | 600 | M | ⏳ |
| C11-block4-audio-file-factory | Block 4: AudioFileFactory + Pre-Dispatch | Block 4 | 1000 | L | ⏳ |
| C12-block5-layout-catalog-and-render-backend | Block 5/1: LayoutCatalog + RenderBackend + helpers | Block 5 | 800 | M | ⏳ |
| C13-block5-motionscene-xml | Block 5/2: MotionScene XML + layout-XML refactor | Block 5 | 500 | M | ⏳ |
| C14-block5-ime-view-backend | Block 5/3: ImeViewBackend + Controllers + Animation | Block 5 | 750 | M | ⏳ |
| C15-block5-service-wiring-and-cleanup | Block 5/4: Service wiring (5c) + Cleanup (5d destructive) | Block 5 | 450 | M | ⏳ |
| C16-block6-overlay-backend-and-window | Block 6/1: OverlayBackend + Window-wrapper + XML | Block 6 | 800 | M | ⏳ |
| C17-block6-permission-onboarding | Block 6/2: Permission-Observer + Gate + Onboarding-UI | Block 6 | 550 | M | ⏳ |
| C18-block6-mode-transitions-and-drag | Block 6/3: Mode-transitions T1-T7 + Drag + OverlayModule | Block 6 | 650 | M | ⏳ |

---

## Repair-Sub-Phase Log (Iter 10)

| Wave-ID | Caller | Iter | Findings (🟢/🟡/❌) | Outcome | Wave-commit |
|---------|--------|------|---------------------|---------|-------------|

## Postponed Issues

| Block | Postponed-Issue | Severity | Why postponed | Tracking |
|-------|-----------------|----------|---------------|----------|

## Mid-Chunk-Triage Trigger Log

| Chunk | Step | Issue-ID | Severity | Wave-ID | Resolution |
|-------|------|----------|----------|---------|------------|

---

## End-to-End-Test-Result

(Populated by Phase-4.5 agent.)

## Phase 4.6 Documentation Update

(Populated by Phase-4.6 final agent.)

## Block-End Commits

| Block | Block-Start-Commit | Block-End-Commit | Status |
|-------|--------------------|--------------------|--------|

---

## Implementation Report (Phase 4.7)

(Populated by Phase-4.7 aggregator-agent.)

---

## Run Log

| Timestamp | Phase | Action | Outcome |
|-----------|-------|--------|---------|
| 2026-05-14 | Phase 0 | State-file created in worktree feature/dictate-keyboard-layout-refactor; reports/ directory created; plan-file Block-0 changes committed (2679312) | ✅ |
| 2026-05-14 | Phase 1a | Plan-Analysis-Agent created chunks.json files for plan + all 3 specs; 19 chunks across 6 skill-blocks; all 4 chunks.json validated via plan-reader. Modular-plan-pattern (D21) confirmed active. NO Auto-Refactor (H3→H2 promotion) performed on spec 1 §4/§11 — chunks reference targeted ###-sub-sections instead. Plan-Consistency-Check findings documented (5 items, primarily plan/spec block-numbering drift for Block 3). | ✅ |
| 2026-05-14 | Phase 1b | E2E-Test-Strategy-Agent (fresh-spawn, no SendMessage available — per skill the Phase-1b prompt is identical for resume vs fresh) wrote `./reports/e2e-runbook.md` with 24 manual TCs (TC-PRE + TC-B0-DOCS + TC-1..TC-23) covering 10 risk-points: FGS-lifecycle, IME-lifecycle, audio-capture, Room v3→v4 migration, Triangle-FSM T1-T7, MotionLayout transitions, Cross-Module-Cascade, Visibility-Predicate, Overlay-Permission flow, Orphan-FAILED-audio-cleanup. Knowledge-gap flagged: no `test-knowledge-android`/`-mobile`/`-ime` skill exists — runbook hand-rolled per TC with self-contained adb steps. 7 user-questions emitted (Q1-Q3 blocking, Q4-Q7 configuration). Pre-Flight extended with 9 E2E-specific items (device, adb-connection-USB, apk-installed, ime-enabled+selected, mic-permission, target-app, network+api-key, personal-device-consent). E2E NOT skipped — refactor has significant behaviour-impact. | ✅ |
