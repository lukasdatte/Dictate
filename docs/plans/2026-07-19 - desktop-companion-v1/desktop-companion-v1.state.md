# State: desktop-companion-v1

> **On resume:** re-read `~/.claude/skills/implement-long-plan-v3/SKILL.md`
> and this file in full before any other action.

**Plan:** ~/.claude/plans/desktop-companion-v1.md (move pending Phase-2 approval)
**Chunks:** [→ chunks.json](chunks.json) (Phase 1 pending)
**Reports:** ./reports/
**Worktree:** worktrees/feature/desktop-companion-v1 (branch feature/desktop-companion-v1, base main@048fb37)
**Started:** 2026-07-19 23:05

## plan_lifecycle

```yaml
current_path: docs/plans/2026-07-19 - desktop-companion-v1/desktop-companion-v1.md
status: archived
moved_at: 2026-07-20 00:35
archived_at: 2026-07-20 18:45
archive_target: "2026-07-19 - desktop-companion-v1"
```

**Phase-2 checks (2026-07-20 00:35):** Briefing emitted + approved. Chunk cut:
adopted (16 chunks). Doc activation: **full**. E2E scope: **run** (16 cases,
runbook final). Per-chunk agents: opus-high everywhere EXCEPT C3/D2/E3 =
**fable-medium** (user override of fable-low recommendation), D3 lifted to
**opus-high** (entity-table migration weight). Git state: clean except
expected untracked (plan folder, tmp/). Plan-consistency: all research/,
reports/ references resolve; adrs/ created by A1. Groundwork commits:
62bf912 (E2E infra), c46cfe8 (schema assets).

## Documentation Plan

```yaml
doc_activation: TBD            # full | inline-only | skip (user, Phase 2)
doc_landscape: "docs/architecture (e2e-emulator.md + state-architecture/ + windows-dispatch/), docs/decisions (27 ADRs + README index), docs/runbooks (companion-windows-release.md), docs/DATABASE-PATTERNS.md"
doc_plan_sketch:
  - "8 plan-scoped ADR drafts (adrs/) → promotion to docs/decisions/0028+ in Block F"
  - "CLAUDE.md: new modules :shared-ai, entity model, peer catalog"
  - "docs/DATABASE-PATTERNS.md: SQLDelight parity + entity tables + Room v12"
  - "Companion README / docs/architecture updates (desktop host, panel, peer sync)"
  - "Konzept-Dokumente aus tmp/desktop-concept/ → research/ (Chunk A1, per D4.1)"
```

## Conventions

(Probed by the Phase-1 analysis agent, user-confirmed in Phase 2.)

```yaml
# Gradle multi-module monorepo (Gradle 8.14.3 wrapper, ./gradlew). Modules:
#   :app (Android, AGP 8.13.2, compile/target SDK 35, jvmTarget 1.8),
#   :shared (pure kotlin(jvm) 1.8, Android/Ktor/coroutine-free — SharedPurityTest),
#   :companion (Compose Desktop + Ktor, JVM 17),
#   :shared-ai (NEW in Block A — pure kotlin(jvm), own purity policy).
build_command: ./gradlew build            # all modules; the §2 build invariant
test_command: ./gradlew test              # aggregates all module unit tests
test_command_shared: ./gradlew :shared:test
test_command_companion: ./gradlew :companion:test
test_command_shared_ai: ./gradlew :shared-ai:test   # after Block A creates the module
test_command_app_unit: ./gradlew :app:testDebugUnitTest   # JVM unit tests (incl. Robolectric)
test_command_app_instrumented: ./gradlew :app:connectedAndroidTest  # NEEDS emulator/device (Room MigrationTestHelper, Espresso)
schema_verify_companion: ./gradlew :companion:verifySqlDelightMigration  # verifyMigrations=true, armed
lint_command: none                        # no linter/formatter configured
coverage_command: none                    # no jacoco / coverage gate in any module
coverage_threshold: none
test_file_pattern_app: "app/src/test/java/**/*Test.{kt,java}"       # unit; instrumented under app/src/androidTest/java/
test_file_pattern_jvm: "{shared,companion}/src/test/kotlin/**/*Test.kt"
test_helpers_location: "companion/src/test/kotlin/net/devemperor/dictate/companion/fakes/ (Fake*, MutableClock); app .../testutil/"
room_schema_export: "app/schemas/ (exportSchema — bump to v12 in C2)"
commit_format: "[{block}.{chunk}] {title} (desktop-companion-v1)"
# Kotlin ceiling 2.1.20 (ADR-0015) applies compiler-wide incl. :shared-ai — every new
# dependency must be built with Kotlin <= 2.1.20. Prefer JNA hand-roll over new libs (R4).
```

## Pre-Flight

```yaml
- kind: source-files-for-A1
  target: tmp/desktop-concept/{bestandsaufnahme,konzept-skizze,fragenkatalog}.md
  check: "test $(ls tmp/desktop-concept/*.md 2>/dev/null | wc -l) -eq 3 && echo OK"
  status: resolved-by-groundwork
  note: >
    Orchestrator copies the three concept docs into the worktree (untracked); A1 checks
    them into research/ (D4.1) and deletes tmp/desktop-concept afterwards. Expected
    present in the worktree before A1 runs.
- kind: toolchain
  target: JDK 17 (for :companion Compose Desktop + Ktor)
  check: "java -version 2>&1 | head -1"
  status: unverified
- kind: toolchain
  target: Android SDK (compile/target 35) for :app
  check: "test -n \"$ANDROID_HOME\" && ls $ANDROID_HOME/platforms | grep android-35"
  status: unverified
- kind: emulator
  target: running AVD for :app:connectedAndroidTest (Room MigrationTest v11->v12 in C2, Espresso/androidTest)
  check: "adb devices | grep -w device"
  status: unverified
  note: "Instrumented tests only (C2 Room migration, C3 settings smoke may use Robolectric on JVM). Memory: AVD dictate-perf/5556 exists; ADB Wireless is flaky."
- kind: constraint
  target: Kotlin 2.1.20 ceiling for every new dependency (ADR-0015)
  check: "manual per-chunk: any new lib in B1(JNA/Keystore), D1b(audio), D2(JNA hotkey), E2(WorkManager, D5.f) must be <= Kotlin 2.1.20"
  status: manual-gate
# ---- E2E pre-flight (Phase-1 E2E strategy; Q1-Q4 resolved 2026-07-19) ----
- kind: e2e-infra
  target: scripts/e2e/ + docs/architecture/e2e-emulator.md
  check: "test -x scripts/e2e/emulator-up.sh && test -f docs/architecture/e2e-emulator.md && echo OK"
  status: resolved-by-groundwork
  blocking: false
  note: >
    Q1(a): orchestrator commits the E2E infra into the feature branch as pre-A1
    groundwork. Expected versioned in the worktree before the run — the Android emulator
    cases (TC-A1/A2/A3) build on it.
- kind: test-config-defect
  target: androidTest source set wires app/schemas/ as assets
  check: "grep -q 'assets.srcDirs' app/build.gradle && echo OK  # then :app:connectedDebugAndroidTest has no 'Cannot find schema file' failure"
  status: resolved-by-groundwork
  blocking: false
  note: >
    Q2(b): the sourceSets one-liner (androidTest { assets.srcDirs += schemas }) lands as a
    separate pre-flight groundwork commit BEFORE the run — unblocks the C2 migration
    verification (R2). Per docs/architecture/e2e-emulator.md §Current status.
- kind: windows-device
  target: Windows host for F1 manual acceptance (hotkey/panel/DPAPI/auto-insert)
  check: "at F1: run TC-W1..W4 on the Windows box (not available on this Linux VM before F1)"
  status: scheduled-F1
  blocking: false
  note: "Q3(b): Windows device available at the F1 timepoint; Windows manual cases TC-W1..W4 run in Block F with the user. No earlier Windows access planned."
- kind: api-keys
  target: user's real provider key for the single manual real-provider smoke (TC-W5)
  check: "user enters their own key at test time; one real transcription; never checked in"
  status: manual-only
  blocking: false
  note: "Q4(a): all auto pipeline cases use fake runners; only TC-W5 (manual, Block F) does a real-provider check with the user's own key."
```

## End-to-End-Test-Plan

```yaml
scope: "Companion JVM (auto: pipeline D1, review D3, catalog E1, sync E2, parity, canonical C1); Android (emulator: migrations C2/B2, settings C3); Windows (manual F1: hotkey/panel/DPAPI/insert)"
runbook: ./reports/e2e-runbook.md
status: final   # all 4 user questions resolved 2026-07-19
test_case_count: 16   # auto: 10 (8 companion/shared JVM + 2 Android emulator), manual: 6
auto_runnable_here: "TC-P1..P4, TC-C1..C4 (companion/shared JVM, no emulator/Windows)"
auto_emulator: "TC-A1/A2 — run against the emulator after the two Q1a/Q2b groundwork commits are present"
manual: "TC-A3 (emulator+mobile-mcp), TC-W1..W4 (Windows/real two-process — Block F), TC-W5 (real-provider smoke, user's key — Block F)"
persistent_runbooks: none   # no docs/runbooks/agentic/ catalog in this repo
open_questions: none   # Q1(a) commit infra, Q2(b) pre-flight schema-assets fix, Q3(b) Windows at F1, Q4(a) fake auto + manual real
```

## Phase-3 Run (plan.workflow.js)

```yaml
runId: wf_fa0e4aa6-c68        # launched 2026-07-20 00:40, task w6obyu0xv
# Run 1 ended 2026-07-20 ~09:30 with status=escalation: login-expired killed
# E1-IMPL + D-REAUDIT-W2 (infrastructure, not content). 30 workflow commits
# (A/B/C complete incl. audits; D implemented, audit W2 open). Orphaned
# half-edits of killed agents reverted (PanelWindow.kt, CanonicalJsonTest.kt,
# DesktopSessionRepositoryTest.kt). Resumed 2026-07-20 ~13:05 with
# resumeFromRunId (task wgrxq655t) — FAILED as approach: cache did not replay,
# chunks re-ran live; A1 re-run escalated on a rate-limited commit-agent (no
# tree damage, only stray [A.2] self-fix commit 9c19a1b). Run 3 started
# 2026-07-20 ~13:35 as FRESH invocation wf_8e42561b-a58 (task w170puij2) with
# completedChunks=[A1..D3], completedAudits=[A,B,C] — scheduler pre-marks done,
# runs only AUDIT-D, E1→E2→E3, AUDIT-E, F1, AUDIT-F, wave-verify.
# Open item for Phase 4.7: unified history UI (§9.3 Compose surface) skipped
# in D repair wave 2 as dedicated follow-up chunk (data layer landed).
```

## Phase 4

```yaml
# Run 3 ended with escalation at the wave-verify gate: E2's catalog-sync/
# notification slice (~19 files) was never committed while HEAD imported it.
# Landed as 3bec2b8 after green :companion:test + verifySqlDelightMigration +
# :app:compileDebugKotlin; full ./gradlew test green afterwards.
# Post-audit of the slice (reports/E/post-audit-3bec2b8.md): 0 Critical,
# 1 Important (subscriber-side store missing) -> closed by e2-completion
# (9 commits, 268b76b..9fbd43d + report e3bcf81): productive subscriber
# stores both hosts, Room v12->v13, two-peer HTTP E2E, fingerprint spec fix.
e2e_part_a:
  auto: 10/10 pass (8 JVM + TC-A1 emulator + TC-A2 Robolectric)
  manual_pending: 6 (TC-A3, TC-W1..W5 — user acceptance at closure)
  runbook: reports/e2e-runbook.md § Phase-4 Execution Results
  followup_fix: 2f36da0  # pre-existing MigrationTo4/8/9/10Test fixtures, 13 red -> 35/35 green
finalize_runId: wf_d6d5dad6-a60   # task wanu2uh8w, e2eScope pre-run, docs full
maxParallel: 3
agents: { default: opus-high, impl: opus-high }
chunk_agent_overrides: { C3: fable-medium, D2: fable-medium, E3: fable-medium }
```

### Documentation (Phase-4 finalize, 2026-07-20T17:25)

```yaml
doc_activation: full
docs_prose: 4          # windows-dispatch README + companion README + DATABASE-PATTERNS (edited); CLAUDE.md (verified, no-change)
docs_prose_edited: 3
inline_groups: 11      # 9 edited, 2 verify-only (app-windows, companion-pipeline-capture)
inline_anchor_edits: ~57   # +@see anchors, 4 stale-slug/path fixes, 1 stale-gotcha reword; KDoc-only, no logic
auto_fixes: 0          # no safe within-set link breakage introduced
flagged: 4             # F1 CLAUDE.md .core caveat, F2 DB-PATTERNS migration scope, F3 api_credentials asymmetry, F4 tmp/plan-keyboard-action-engine.md broken link (pre-existing, out of range)
source_notes: 5        # adr-secret-store slug in 2 XML files, WindowsTarget V10 + DispatchOutcomeMapper §6.1 dangling anchors, BuildProbe.kt dead scaffolding, promptDtoByUuid O(n) scan, CatalogService.kt binary-diff
gaps: 3                # peer-catalog architecture overview, config-entity-model overview, shared/ + shared-ai/ module READMEs (follow-up doc plan: default no)
adr_flags: 0           # 0028-0035 promoted, ADR-0015 decision-history added — clean
knowledge_skill_flags: 0
report: reports/docs-final-report.md
```

**Task table** (chunks + per-block audits; cross-block deps gate on
the upstream AUDIT):

| Task | Block | Deps | Status |
|---|---|---|---|
| A1 | A | — | 🔄 |
| A2 | A | — | 🔄 |
| A3 | A | A2 | ⏳ |
| AUDIT-A | A | A1-A3 | ⏳ |
| B1 | B | A2 (via AUDIT-A) | ⏳ |
| B2 | B | B1, A3 | ⏳ |
| AUDIT-B | B | B1-B2 | ⏳ |
| C1 | C | — | 🔄 |
| C2 | C | C1, B2, A3 | ⏳ |
| C3 | C | C2 | ⏳ |
| AUDIT-C | C | C1-C3 | ⏳ |
| D1a | D | A2 (via AUDIT-A) | ⏳ |
| D1b | D | D1a, A3 | ⏳ |
| D2 | D | D1b | ⏳ |
| D3 | D | D2, C1 (via AUDIT-C) | ⏳ |
| AUDIT-D | D | D1a-D3 | ⏳ |
| E1 | E | C1, D3 (via audits) | ⏳ |
| E2 | E | E1, B1 (via AUDIT-B) | ⏳ |
| E3 | E | E2 | ⏳ |
| AUDIT-E | E | E1-E3 | ⏳ |
| F1 | F | all (via audits) | ⏳ |
| AUDIT-F | F | F1 (plan-and-api only) | ⏳ |

## Commits

| Commit | Kind | Task | Hash |
|---|---|---|---|

## Escalations & Postponed

| At | Source | Issue | Resolution |
|---|---|---|---|

| Postponed | Severity | Why | Tracking |
|---|---|---|---|
