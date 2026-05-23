# AUDIT-TEST Findings — Block 3

**Agent-ID:** `B3-AUDIT-TEST`
**Scope:** full-block (B3 = C8 + C9 + C10 + C11)
**Last-verify-commit:** `b97e09a`
**Block-report:** `./reports/B3-migration-persistence-audiofactory.md`
**Plan:** `dictate-keyboard-layout-refactor.reviewed.md` (Spec 1)
**Date:** 2026-05-15

---

## Executive Summary

- **`./gradlew test --rerun-tasks`** → **BUILD SUCCESSFUL** in 2 m 14 s. 677 tests, 0 failures, 0 errors, 0 skipped.
- B3 added **141 net-new JVM tests across 19 test files** + **2 androidTest classes** (8 cases total — local-only per Spec 1 §11.7.0a). Aggregate XML totals match the plan's claim of 677 tests.
- No cross-chunk regression detected (every pre-B3 test still passes; no test rot).
- **K-1 compliance:** no `mockk` / `mockito` / `nhaarman` import anywhere in `app/src/test` or `app/src/androidTest`. All test doubles are handwritten fakes.
- **K-4 compliance:** Robolectric is opt-in for 5 B3-new files (`RecordingTimerAdapterTest` Handler, `RecordingHardwareAdapterTest` MediaRecorder, `DictatePipelineServiceCompositionTest` + `DictatePipelineServiceAudioFileFactoryWiringTest` Service lifecycle, `LegacyAudioFileMigrationTest` filesDir Context). Each carries an inline justification.
- **AndroidTest infrastructure (NEW in C9):** `app/src/androidTest/` correctly wired with `room-testing 2.6.1`, `androidx-test-runner`, `androidx-test-rules` via `androidTestImplementation` in `app/build.gradle:103-107`. `MigrationTo4Test` uses `MigrationTestHelper` per Room conventions. `AndroidTestSetupSmokeTest` guards the wiring. Local-only status is documented in test KDocs (Spec 1 §11.7.0a "CI-Integration" — no CI invocation today).
- **Coverage map vs. Spec 1:**
  - 6 KG-SST-2 sub-cases ✅ (all in `PipelineRecoveryFullTest` cases 2–7)
  - SF-4 dispatch ✅ (`PipelineRecoveryFullTest` cases 6, 7)
  - 6 Spec 1 §11.4.2 mandated androidTest cases + 1 bonus v1→v4 chain ✅ (`MigrationTo4Test`)
  - KG-AFF-1 through KG-AFF-5 ✅ except KG-AFF-1 cache-delete-after-persist patch — see AUDIT-TEST-B3-1 below
  - KG-SST-4 EnumSwitch lint promotion — covered via the runtime `HistoryAdapter` defensive `default:` branch (lint side-tested via `./gradlew lintDebug` — not in `./gradlew test` scope; per the C9 deviation table, `abortOnError` deliberately deferred)
- **Doc-trail (Step 0 of the audit):** the test commits (02124ba, 9666c93, 38f28a0) contain **only** `app/src/test/**` and `app/src/androidTest/**` diff content — no production-code changes were folded in during test-writing. Consequently no `### Code-Bugs Found While Writing Tests` sub-section is needed for any chunk (the block-report correctly omits them).
- **Helper inventory:** B3 added `testutil/FakeSessionDao.kt` + 13 self-tests. The pre-existing private `FakeSessionDao` inside `SessionTrackerTest.kt` was extended with `notUsed()` stubs for the 8 new DAO methods, kept private to preserve its "fail-loud" semantics. Soft consolidation opportunity flagged (AUDIT-TEST-B3-3).

---

## Documentation Gaps

| ID | Title | Severity | Chunk:Sub-Section | Status |
|----|-------|----------|-------------------|--------|

(none — see Executive-Summary doc-trail note)

---

## Test-Quality

| ID | Title | Severity | File:Line | Status |
|----|-------|----------|-----------|--------|
| AUDIT-TEST-B3-2 | `PipelineCallbackBridge` thread-safety / delegate-flip race not covered by a concurrent test. The delegate is held in `AtomicReference` (line 49) which makes single-write visibility safe, but no test exercises the spec-relevant concurrent-reader scenario (pipeline thread reads while main-thread re-binds). `delegate cleared mid-pipeline drops further calls` (line 221) demonstrates sequential ordering only. | Nice-to-have | `app/src/test/java/net/devemperor/dictate/core/PipelineCallbackBridgeTest.kt` | open |
| AUDIT-TEST-B3-3 | Helper duplication: `app/src/test/java/net/devemperor/dictate/core/SessionTrackerTest.kt:104` carries a private `FakeSessionDao` with call-count + `notUsed()`-throw semantics, parallel to the canonical `app/src/test/java/net/devemperor/dictate/testutil/FakeSessionDao.kt`. The two have different semantics (call-counting vs. behavioral state), but the duplication doubles maintenance cost when new DAO methods land — the private fake was already touched once in B3 to stub 8 new methods. Consider migrating `SessionTrackerTest` to the shared fake (add `findLatestByOrigin` to the behavioral fake) in a future cleanup pass. | Nice-to-have | `app/src/test/java/net/devemperor/dictate/core/SessionTrackerTest.kt:104` | open |
| AUDIT-TEST-B3-5 | Plan-vs-actual test count drift in the C8 block-report sub-section: report claims `PipelineCallbackBridgeTest` = 19 tests, actual count is 14 (Test-results XML + `grep -c '@Test'`); report claims `DictatePipelineServiceCompositionTest` = 14 tests, actual count is 15. The block-report's aggregate "595 tests" target after C8 is therefore off-by-{+5,-1} = +4 vs. real run. Net `B3 added 141` claim is correct. Tighten the sub-section counts in a future plan-doc pass. | Nice-to-have | `B3-migration-persistence-audiofactory.md:140,147` | open |

---

## Coverage

| File | Coverage status | Notes |
|------|-----------------|-------|
| `CacheDirAudioFileFactory.kt` | ✅ strong (allocate happy + 2 IO-throw paths + KG-AFF-4 cutoff + KG-AFF-5 null + cleanupOrphans 5 sub-cases) | 13 tests; the `requireNotNull` failure path uses an inline subclass — clean. |
| `LegacyAudioFileMigration.kt` | ✅ strong (pref-flag short-circuit, legacy-file delete, recoverable promotion, idempotence S-7, non-legacy untouched, second-run no-op, flag-flip) | 8 Robolectric tests; uses real filesDir Context (K-4 opt-out justified by `getFilesDir()` reach). |
| `PipelineSessionRepoAdapter.kt` | ✅ strong (10 tests cover RECORDED-with-file, COMPLETED-with-pending, FAILED/CANCELLED/RECORDING/TRANSCRIBING exclusion, markInserted, markFailed, pendingFlow empty-Flow contract, boundary mapper) | All on FakeSessionDao. |
| `PipelineRecovery.kt` | ✅ strong (13 tests cover all 6 KG-SST-2 sub-cases + SF-4 dispatch + mixed + merge + dedup + idempotence + DAO-failure graceful degradation) | `ioContext = EmptyCoroutineContext` injection is testable-by-design. |
| `PipelineOrphanCleaner.kt` | ✅ strong (11 tests cover empty, COMPLETED-old vs. NULL-insertedAt, KG-SST-2 audio for FAILED + CANCELLED, fresh skip, ghost-clear, twice-idempotent, dual-DAO-failure absorption) | `nowProvider` injection lets all timing be deterministic. |
| `PipelineOrchestrator.persistNewSession` KG-AFF-1 cache-delete patch | ⚠️ uncovered (intentional — flagged below as AUDIT-TEST-B3-1) | C11 known-gap section already documents the absence + rationale. |
| `MigrationTo4.kt` | ✅ strong via androidTest (6 spec-mandated + 1 bonus chain case) + 2 JVM metadata tests | androidTest is **local-only** — not run by `./gradlew test`. Schema export at `app/schemas/.../4.json` lets reviewers verify without running. |
| `SessionStatus.kt` (M4 extension) | ✅ exact value set, valueOf round-trip, unknown-rejection, live-vs-terminal partition | 4 JVM tests. |
| `SessionEntity.insertedAt` field + `statusEnum` round-trip | ✅ default null, copy preservation, all 6 statuses, fallback | 4 JVM tests. |
| `SessionDao` 8 new methods | ✅ via `FakeSessionDaoTest` (13 tests — including `markLegacyAudioSessionsFailed` idempotency rerun) | Behavioural; production DAO is Room-generated, the fake is the integration surface for module tests. |
| `ResendStatusDispatcher` M4 branches | ✅ 2 added cases (RECORDING + TRANSCRIBING → NoOp under all 3 output flavours) | Extends existing 10 → 12 tests. |
| All 6 C8 adapters + `PipelineCallbackBridge` | ✅ contract-level coverage (lifecycle flags, idempotency, delegate-forward + null-gap, throw-isolation) | 8 test files, 59 tests; per-adapter contract. |
| `DictatePipelineService` composition root (IMPL-1 closure) | ✅ binder accessor surface (8 fields × non-null) + JobExecutor.initialize + callback registration + multi-get-same-instance | 15 Robolectric tests. `triggerOrphanCleanupAsync` invocation from `onDestroy` is the one missed branch — see AUDIT-TEST-B3-4. |

Per-branch metrics: no coverage report (jacoco) is wired into this project's gradle setup — the audit relies on manual branch enumeration vs. test names. All KG-* and SF-4 control-flow branches identified in the plan have at least one direct test, with the two exceptions documented in the findings table below.

---

## Findings

| ID | Severity | Description | Status |
|----|----------|-------------|--------|
| AUDIT-TEST-B3-1 | Important | KG-AFF-1 cache-delete-after-persist patch in `PipelineOrchestrator.persistNewSession` (lines 858-873 of B3 diff) has no unit test. The C11 sub-section already documents this as an "Overlooked points / known gaps" entry citing Android-bound dependencies. Behaviour is observable on a real device only. The patch is small (`runCatching { audioFile.delete() }` plus a WARN log on failure) and the failure path is the documented graceful-degrade contract — but a regression where `delete()` silently stops being called would not be caught by any current test. Suggest a minimal Robolectric-shadowed test (or an inline `verify` against a shadow filesystem) before the patch is judged safe to ship. | open |
| AUDIT-TEST-B3-2 | Nice-to-have | (test-quality) `PipelineCallbackBridge` thread-safety / delegate-flip race not covered by a concurrent test. See Test-Quality table. | open |
| AUDIT-TEST-B3-3 | Nice-to-have | (helper-consolidation) `FakeSessionDao` duplication between `testutil/` and `SessionTrackerTest.kt:104`. See Test-Quality table. | open |
| AUDIT-TEST-B3-4 | Important | `DictatePipelineService.onDestroy` → `triggerOrphanCleanupAsync()` invocation is not exercised by any test. `PipelineOrphanCleanerTest` covers the cleaner directly, but the Service-side wiring (where the orphan-cleanup is invoked, with what `referencedPaths` snapshot, and whether it survives a SIGKILL race) is uncovered. A Robolectric `controller.destroy()` assertion that verifies the cleaner was invoked (e.g. via a spy on the cleaner instance exposed through the binder, or by checking the side-effect on a temp filesDir) would close the gap. | open |
| AUDIT-TEST-B3-5 | Nice-to-have | (test-quality) Block-report test-count drift, see Test-Quality table. | open |

---

## Cross-Chunk-Regressions

none — `./gradlew test --rerun-tasks` ran the full 677-test suite end-to-end with zero failures, zero errors. No pre-B3 test (B0/B1/B2 era, 536 baseline) was rotted by the C8-C11 changes.

---

## Helper-Konsolidierung

| Helper | Location | Notes |
|--------|----------|-------|
| `FakeSessionDao` (canonical, behavioral) | `app/src/test/java/net/devemperor/dictate/testutil/FakeSessionDao.kt` | NEW in B3-C9. 161 LoC, LinkedHashMap-backed, full implementation of all SessionDao methods. Used by `PipelineSessionRepoAdapterTest`, `PipelineRecoveryFullTest`, `PipelineOrphanCleanerTest`, `FakeSessionDaoTest`. |
| `FakeSessionDao` (private, fail-loud) | `app/src/test/java/net/devemperor/dictate/core/SessionTrackerTest.kt:104` | Pre-existing, extended in B3-C9 with 8 `notUsed()` stubs for new interface methods (block-report Inline-fixed items §C9). Different semantics: stubs `findLatestByOrigin` with call-counting, throws on every other method. |

Both are valid — they trade off behaviorality (canonical) vs. fail-loud strictness (private). Consolidation finding AUDIT-TEST-B3-3 is Nice-to-have; the two-fake situation is not a defect today but is a deferred maintenance cost.

---

## AndroidTest Coverage (Spec 1 §11.4.2 + §11.7.0a)

| # | Test name | Spec mapping |
|---|-----------|--------------|
| 1 | `migrate3To4_addsInsertedAtColumn_andBackfillsCompleted` | §11.4.2 case 1 + §6.1 backfill |
| 2 | `migrate3To4_checkConstraint_acceptsNewStatusValues` | §11.4.2 case 2 + §6.1 CHECK extension |
| 3 | `migrate3To4_checkConstraint_rejectsUnknownStatus` | §11.4.2 case 3 + Double-Enum |
| 4 | `migrate3To4_preservesAllLegacyStatuses` | §11.4.2 case 4 + §6.1 round-trip |
| 5 | `migrate3To4_preservesChildRows_processingStepsAndTranscriptions` | §11.4.2 case 5 + §11.7.0 FK-cascade |
| 6 | `migrate3To4_preservesIndices` | §11.4.2 case 6 + §11.7.0 indices |
| 7 (bonus) | `migrate1To4_chain_preservesData` | §11.7.0 KG-SST-3 v1→v4 chain |
| smoke | `AndroidTestSetupSmokeTest.smoke` | §11.7.0a step 4 — wiring guard |

8 androidTest cases total across 2 classes. Spec 1 §11.7.0a "CI-Integration" is honoured — both classes' KDocs flag local-only execution; no CI invocation today.

---

## Test-Suite Size Verification

| Metric | Value |
|--------|-------|
| Total tests (after B3) | **677** |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| B3-new test files (JVM) | 19 |
| B3-new tests (JVM only, net of pre-existing files modified) | 141 |
| B3-new androidTest files | 2 |
| B3-new androidTest cases | 8 (7 migration + 1 smoke) |
| Baseline (B2-end) | 536 |
| Computed (536 + 141) | 677 ✅ |

The 677-after-B3 claim is exactly reproduced.

---

## Verdict

**Block 3 test-audit passes** with 5 findings:
- 2 Important (AUDIT-TEST-B3-1 KG-AFF-1 uncovered, AUDIT-TEST-B3-4 onDestroy→cleanup uncovered)
- 3 Nice-to-have (AUDIT-TEST-B3-2 race-test, AUDIT-TEST-B3-3 helper consolidation, AUDIT-TEST-B3-5 doc-count drift)

No Critical findings, no cross-chunk regressions, K-1 + K-4 compliant, AndroidTest infrastructure correctly wired.
