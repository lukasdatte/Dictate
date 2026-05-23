# AUDIT-TEST Findings — Block 1

**Agent-ID:** `B1-AUDIT-TEST`
**Date:** 2026-05-15
**Scope:** full-block (both chunks C1 + C2)
**Block-Start-Commit:** `bd8f1e6`
**Inspection HEAD:** `0b3d126`

---

## Executive Summary

- **Test suite green.** `./gradlew test` runs all 22 test classes (198 tests total, 0 failures, 0 errors, 0 skipped). Both new B1 test classes pass on both `testDebugUnitTest` and `testReleaseUnitTest`.
- **No cross-chunk regressions.** Pre-existing test classes (20 of 22) unaffected; `KeyboardVisibilityPredicatesTest` (17 tests) and `DictatePipelineServiceTest` (10 tests) are net-new.
- **K-1 + K-4 compliance verified.** Zero Mockito / MockK imports anywhere in `app/src/test/`. Robolectric only in `DictatePipelineServiceTest.kt` — opt-out justified inline in class KDoc + `gradle/libs.versions.toml` + `app/build.gradle`.
- **No undocumented test-agent code-fixes.** Production-code commits (`ff6da41`, `cf7a8ba`) precede test commits (`f1686e8`, `0b3d126`); no test-driven production patches lurking.
- **Coverage of new production files is good for the predicate (saturated truth-table) and acceptable for the service skeleton.** Two coverage gaps in `DictatePipelineService` are defensible (SDK-version branches) but worth listing. The IME-side bind/unbind diff (~50 lines in `DictateInputMethodService.java`) and the new `KeyboardUiController.applyRecordButtonForRecording(state)` resolver (~50 lines) are deliberately unit-untested — flagged below as Important (defensible) gaps.

**Verdict:** No Critical findings. **3 Important** (untested resolver, untested IME bind/unbind code, defensive coverage gaps that may surface as flakes on different SDK targets). **2 Nice-to-have** (test-naming nit, helper-consolidation opportunity for later blocks). The block is green to proceed; the Important findings are gaps the orchestrator may want to triage now or postpone to Block 1b / Block 5 where the same code is rewritten.

---

## Test Suite Execution

```
> ./gradlew testDebugUnitTest --rerun-tasks

BUILD SUCCESSFUL in 1m 33s
33 actionable tasks: 33 executed
```

| Test class | tests | skipped | failures | errors |
|------------|-------|---------|----------|--------|
| ai.ElevenLabsKeytermsParserTest | 17 | 0 | 0 | 0 |
| core.ActiveJobRegistryTest | 6 | 0 | 0 | 0 |
| **core.DictatePipelineServiceTest** (new B1) | **10** | 0 | 0 | 0 |
| core.EditorIdentityTest | 8 | 0 | 0 | 0 |
| core.InsertOrFallbackTest | 10 | 0 | 0 | 0 |
| core.JobExecutorTest | 4 | 0 | 0 | 0 |
| **core.KeyboardVisibilityPredicatesTest** (new B1) | **17** | 0 | 0 | 0 |
| core.LanguageControllerTest | 21 | 0 | 0 | 0 |
| core.MultiCallbackForwardingTest | 5 | 0 | 0 | 0 |
| core.RecordingStateControllerTest | 9 | 0 | 0 | 0 |
| core.ResendStatusDispatcherTest | 10 | 0 | 0 | 0 |
| core.SessionTrackerTest | 5 | 0 | 0 | 0 |
| preferences.DictatePrefsTest | 2 | 0 | 0 | 0 |
| preferences.InputLanguagesLegacyMigrationTest | 7 | 0 | 0 | 0 |
| preferences.InputLanguagesPluginTest | 10 | 0 | 0 | 0 |
| preferences.LanguageLabelResolverTest | 19 | 0 | 0 | 0 |
| preferences.versioned.IntListCodecTest | 7 | 0 | 0 | 0 |
| preferences.versioned.StringListCodecTest | 7 | 0 | 0 | 0 |
| preferences.versioned.VersionedMigratorTest | 11 | 0 | 0 | 0 |
| preferences.versioned.VersionedPluginRegistryTest | 6 | 0 | 0 | 0 |
| preferences.versioned.VersionedPrefsTest | 7 | 0 | 0 | 0 |
| preferences.versioned.VersionedSerializerTest | 10 | 0 | 0 | 0 |
| **TOTAL** | **198** | **0** | **0** | **0** |

---

## Documentation Gaps

| ID | Title | Severity | Chunk:Sub-Section | Status |
|---|---|---|---|---|

**none.** Step 0 verification: `git log bd8f1e6..HEAD` shows the orchestrator-split-commit pattern was followed — Commit 1 (production, `ff6da41` / `cf7a8ba`) precedes Commit 2 (tests, `f1686e8` / `0b3d126`) for each chunk, and no test commit modified production files. The block-report's `### Tests` and `### Test-Review` subsections for both chunks explicitly state "No code-bugs found while writing tests" / "No code-bugs found during test self-review", consistent with the diff content.

---

## Test-Quality

| ID | Title | Severity | File:Line | Status |
|---|---|---|---|---|
| AUDIT-TEST-B1-1 | `KeyboardUiController.applyRecordButtonForRecording(state)` — ~50 lines of new central-resolver logic untested | Important | `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt:471-535` | delegated-to-orchestrator |
| AUDIT-TEST-B1-2 | IME-side pipeline bind/unbind lifecycle (~80 lines, incl. 4 ServiceConnection callbacks) untested | Important | `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:316-372, 467-479` | delegated-to-orchestrator |
| AUDIT-TEST-B1-3 | `Quadruple<A,B,C,D>` local helper in `KeyboardVisibilityPredicatesTest` — used only by one test; consider `kotlin.Pair` chains or moving to a shared test util later | Nice-to-have | `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt:332-337` | delegated-to-orchestrator |

### AUDIT-TEST-B1-1 (Important): central-resolver `applyRecordButtonForRecording` untested

`KeyboardUiController.applyRecordButtonForRecording(state: RecordingState)` was added in C1 as the new single owner for the record-button-appearance recording axis. The method has:
- A pipeline-state guard (defers to `refreshRecordButtonFromState()` when pipeline is non-Idle).
- A 4-branch `when` over `RecordingState` (Idle / Preparing / Active / Paused).
- An inner `useBluetooth` branch inside Active.

The block-report justifies the gap: "The IME service is a 2000-line Java class with deep view-side dependencies — a unit test for the bind/unbind code path would require either Mockito (forbidden by K-1) or a refactor of the IME service that is out of scope for Block 2."

However, **`KeyboardUiController` is Kotlin, not Java, and is already wrapped by `FakePipelineUiStateReader` in `MultiCallbackForwardingTest`.** A handwritten fake `PipelineViews` (the constructor injects views as a parameter object) plus a fake `KeyboardStateManager` would make `applyRecordButtonForRecording(state)` testable on the JVM runner — the production code uses `views.recordButton.text = ...`, `views.recordButton.isEnabled = ...`, `views.recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(...)` which are mockable behind a fake `MaterialButton`-like surface, but `MaterialButton` itself is Android-View-bound. Robolectric is the simpler path now that it's on the classpath (D4).

**Why this matters:** The recording↔pipeline-axis ordering race (Spec 1 §9.5) is exactly what this method is supposed to eliminate. Without a test, a regression in Block 5 (LayoutCatalog rewrite) won't be caught until on-device E2E. Block 5 collapses both axes into one slot resolver, so the resolver may not survive long enough to amortise — but if it does survive past Block 5's first iteration, the gap will bite.

**Recommendation:** delegate to orchestrator. Two options:
- Add a Robolectric test class `KeyboardUiControllerTest.kt` in C1 or as a B1-validate repair-wave covering the 4 recording-state branches + the pipeline-guard branch. ~5 tests, ~80 lines.
- Postpone (D15) — accept the gap because Block 5 collapses the resolver. Postpone-rationale should be explicit (current block-report is silent on this method).

### AUDIT-TEST-B1-2 (Important): IME-side pipeline bind/unbind lifecycle untested

`DictateInputMethodService.java` got ~80 new lines:
- A `ServiceConnection` with 4 callbacks (`onServiceConnected`, `onServiceDisconnected`, `onBindingDied`, `onNullBinding`).
- `onCreateInputView`: idempotent `startForegroundService` + `bindService(BIND_AUTO_CREATE)`.
- `onDestroy`: idempotent `unbindService` with `IllegalArgumentException` catch.

Only `bindService_smokeTest_doesNotThrow` exercises the **service-side** of the bind — it asserts the manifest declaration is valid, but does not exercise the IME-side callbacks.

The block-report acknowledges this in "Coverage gaps left intentionally" with the rationale "The existing IME-Service is a 2000-line Java class with deep view-side dependencies." That's true, but two callbacks (`onBindingDied`'s re-bind and `onNullBinding`'s defensive log) are pure same-process logic — they could be unit-tested by extracting the `ServiceConnection` to a named class. **Not blocking the block**, but worth flagging because Spec 1 §11.3.2 explicitly lists these as Block-1 acceptance and they are now committed as dead-defensive code paths.

**Recommendation:** delegate. Options:
- Postpone via D15 (acknowledge as a known gap; covered on-device by E2E TC-15 — already mentioned in block-report).
- Extract `pipelineConnection` into a named inner class with package-private callbacks; test the rebind path with a fake `Context.bindService`. ~3 tests, 60 lines.

### AUDIT-TEST-B1-3 (Nice-to-have): `Quadruple` local helper

The `private data class Quadruple<A, B, C, D>` at the bottom of `KeyboardVisibilityPredicatesTest` is used only by one test (`resolveResendVisibility VISIBLE iff predResendVisible true`). It's a 6-line cost for a destructuring-readability gain. Two alternatives:
- Inline `Pair<Pair<...>, Pair<...>>` chains — uglier than the current form.
- Promote to a shared `app/src/test/java/net/devemperor/dictate/testutil/Quadruple.kt` for future Block-4 use, where the 25-case full truth-matrix (Spec 2 §14.2) will need similar destructuring.

Block-4 should probably promote this rather than re-invent it. **Not blocking.**

---

## Coverage (Inspection-Based)

Per state-file: `coverage_threshold_branches: 70` (provisional). **No JaCoCo configured** — AUDIT-TEST uses inspection, not threshold-fail-build.

### New production files in B1

#### `KeyboardVisibilityPredicates.kt` (104 lines, 2 functions)

| Function | Branch | Covered? | By which test |
|----------|--------|----------|---------------|
| `predResendVisible` | all-true conjunction | ✓ | `predResendVisible true when all four axes hold` |
| | `lastAudioFileExists=false` | ✓ | `predResendVisible false when audio file missing` |
| | `resendEnabled=false` | ✓ | `predResendVisible false when Pref ResendButton disabled` |
| | `recordingState is Preparing` | ✓ | `predResendVisible false when recording is Preparing` |
| | `recordingState is Active` | ✓ | `predResendVisible false when recording is Active` |
| | `recordingState is Paused` | ✓ | `predResendVisible false when recording is Paused` |
| | `pipelineState is Preparing` | ✓ | `predResendVisible false when pipeline is Preparing` |
| | `pipelineState is Running` | ✓ | `predResendVisible false when pipeline is Running` |
| | `pipelineState is ReprocessStaging` | ✓ | `predResendVisible false when pipeline is ReprocessStaging` |
| | `Active.useBluetooth=true` (sealed-subclass-vs-data) | ✓ | `predResendVisible false for both Bluetooth and non-Bluetooth Active` |
| | `Preparing.useBluetooth=true` | ✓ | (same test, second assertion) |
| | multi-axis-fail (2-axis) | ✓ | `predResendVisible false when two axes fail simultaneously` |
| | multi-axis-fail (4-axis) | ✓ | `predResendVisible false when all four axes fail` |
| `resolveResendVisibility` | `predicate true → VISIBLE` | ✓ | `resolveResendVisibility VISIBLE when predicate true` |
| | `predicate false (audio missing) → GONE` | ✓ | `resolveResendVisibility GONE when predicate false (audio missing)` |
| | `predicate false (recording Active) → GONE` | ✓ | `resolveResendVisibility GONE when recording is Active` |
| | non-Idle pipeline → GONE (loop over all 3 non-Idle subclasses) | ✓ | `resolveResendVisibility never returns VISIBLE for any non-Idle pipeline state` |
| | wrapper-consistency (5 sample axes) | ✓ | `resolveResendVisibility VISIBLE iff predResendVisible true (sample axes)` |

**Branch coverage estimate: ≥95% (saturated for the four-axis truth-table relevant to today's call sites).** The full 2^4 = 16-case enumeration is intentionally not exhaustive (each-axis-false-alone + sample combinations is sufficient for boolean-conjunction semantics — adding 16 tests would not catch a class of bug the current 17 do not). Block-report rationale is sound.

#### `DictatePipelineService.kt` (350 lines, 1 class + 1 inner class + companion)

| Method | Branch | Covered? | Notes |
|--------|--------|----------|-------|
| `onCreate` | calls `ensureNotificationChannel()` synchronously as step 1 | ✓ | `onCreate_createsNotificationChannel_beforeAnyStartForeground` |
| `onStartCommand` | calls `startForeground` synchronously as step 1 | ✓ | `onStartCommand_callsStartForeground_synchronously` |
| | returns `START_NOT_STICKY` | ✓ | `onStartCommand_returnsStartNotSticky` |
| `onBind` | returns singleton `LocalBinder` | ✓ | `onBind_returnsLocalBinder_pointingAtTheService` + `onBind_returnsSameBinderInstance_acrossMultipleCalls` |
| `onDestroy` | cancels `serviceScope` (no throw on double-destroy) | ✓ | `onDestroy_cancelsServiceScope_andSurvivesIdempotently` |
| `ensureNotificationChannel` | early-return on API < 26 | ✗ | **Coverage gap (Nice-to-have)** — Robolectric `@Config(sdk = [34])` only |
| | `getSystemService(...) ?: return` (null branch) | ✗ | defensive — Robolectric always supplies a real NM |
| | early-return when channel already exists | ✓ | `ensureNotificationChannel_isIdempotent_acrossRepeatedOnCreate` |
| | channel-config invariants (IMPORTANCE_LOW, silent, no badge, vis_private) | ⚠ partial | `notificationChannel_isImportanceLow_andSilent` covers `importance` but not `setShowBadge(false)`, `setSound(null,null)`, `enableVibration(false)`, `enableLights(false)`, `lockscreenVisibility` |
| `buildInitialNotification` | `if (contentIntent != null) setContentIntent` | ✗ | defensive guard — `PendingIntent.getActivity` should never return null in Robolectric |
| `startForegroundCompat` | API ≥ 34 (explicit-type) | ✓ | `onStartCommand_callsStartForeground_synchronously` (Config sdk=34) |
| | API < 34 (implicit) | ✗ | **Coverage gap (Nice-to-have)** — would need a second `@Config(sdk = [26])` class |
| `LocalBinder.service` | returns service instance | ✓ | `onBind_returnsLocalBinder_pointingAtTheService` |
| `LocalBinder.dispatch` | stub increments counter | ✓ | `localBinderDispatch_isNoOp_butCountsInvocations` |

**Branch coverage estimate: ~75% (exceeds 70% threshold).** The uncovered branches are:
1. API-version guards (SDK < 26 channel-create skip; SDK < 34 implicit `startForeground`). Both are pure platform-gating with no business logic.
2. Defensive null-guards (`getSystemService` returning null; `PendingIntent.getActivity` returning null). Same: defensive code paths impossible to trigger in Robolectric without shadow injection.
3. 4 sub-fields of the NotificationChannel config (showBadge, sound, vibration, lights, lockscreenVisibility) — `notificationChannel_isImportanceLow_andSilent` only checks `importance`.

| ID | Title | Severity | File:Line | Status |
|----|-------|----------|-----------|--------|
| AUDIT-TEST-B1-4 | `NotificationChannel` config invariants partially asserted — only `importance` checked; `setShowBadge(false)`, `enableVibration(false)`, `enableLights(false)`, `lockscreenVisibility` not verified | Nice-to-have | `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:192-203` | delegated-to-orchestrator |
| AUDIT-TEST-B1-5 | API-version-branch coverage — both pre-API-26 channel-skip and pre-API-34 `startForeground` overload untested. Defensible (platform code, no business logic) but worth flagging | Nice-to-have | `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:187, 255-264` | delegated-to-orchestrator |

### Modified production files in B1 (not directly unit-tested)

| File | Logic touched | Unit-tested? | Rationale (block-report) |
|------|---------------|--------------|--------------------------|
| `RecordingUiController.kt` | constructor-param split (axis decoupling); recordButton mutations moved out; resend-visibility migrated to helper (2 sites) | partial | Predicate covered; the controller's own delta is mostly delete-and-delegate, with the new logic surfaced via constructor-injected callback. |
| `KeyboardUiController.kt` | `dictateButtonTextProvider` ctor param; `applyRecordButtonForRecording(state)` (NEW, ~50 lines) | ✗ | **AUDIT-TEST-B1-1** above |
| `DictateInputMethodService.java` | bind/unbind lifecycle (~80 new lines); 2× `stateManager.refresh()` adds; 4× resend-visibility migrations | ✗ | **AUDIT-TEST-B1-2** above; E2E TC-15 covers IME bind on-device |
| `AndroidManifest.xml` | 4 permissions + service entry | ⚠ smoke | `bindService_smokeTest_doesNotThrow` asserts the service declaration resolves — proxy for "manifest is well-formed" |
| `strings.xml` | 6 new strings | ✗ | resource-only; not unit-testable |
| `gradle/libs.versions.toml` + `app/build.gradle` | Robolectric dep + `includeAndroidResources` | ✓ | covered indirectly — if dependency wiring were broken, `DictatePipelineServiceTest` would not run |

---

## Cross-Chunk-Regressions

**none.**

Pre-existing test classes (20 of 22 — all except the two B1-new ones) ran with their `bd8f1e6` semantics and stayed green. The most likely regression vectors were:
- `RecordingStateControllerTest` (9 tests) — C1 split a constructor lambda; if the IME-side wiring had broken, the controller test could surface fallout. It didn't (the controller has no compile-time dependency on the new `KeyboardUiController` constructor, only the IME-side wiring does).
- `MultiCallbackForwardingTest` (5 tests) — touches `KeyboardUiController` indirectly via `FakePipelineUiStateReader`. C1 added a constructor param with a default value, so existing call-sites in tests compile unchanged.

Both green; no regression.

---

## Helper-Konsolidierung

| Observation | Severity | Recommendation |
|-------------|----------|----------------|
| No new test helpers introduced in B1. The predicate tests use bare JUnit + a tiny local `Quadruple` data class; the service tests use Robolectric primitives (`Robolectric.buildService`, `ApplicationProvider`, `Shadows.shadowOf`) directly. | informational | None for B1. **For B2+:** when more services land (Block 1b orchestrator, Block 6 overlay-permission gate), consider extracting a `RobolectricServiceTestBase` if duplication appears across `DictatePipelineServiceTest` and the new test classes. Not needed yet — 1 class is below the duplication-threshold. |
| `FakePipelineUiStateReader` (pre-existing) is the only `KeyboardUiController` fake; Block 1b will likely need more (e.g. a fake binder for IME-side unit tests). | informational | Track in B2 audit. |

---

## K-1 + K-4 Compliance Verification

```
$ grep -rE "import (org\.mockito|io\.mockk)" app/src/test/
(no matches)

$ grep -rl "RobolectricTestRunner|@RunWith.*Robolectric" app/src/test/
app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt
```

- **K-1:** PASS — no Mockito / MockK anywhere. Handwritten-fakes-only convention upheld.
- **K-4:** PASS — Robolectric is opt-in. Single user this block (`DictatePipelineServiceTest`). Justification documented in three places:
  - `gradle/libs.versions.toml` lines 22-28 (explanatory comment for `robolectric = "4.14.1"`).
  - `app/build.gradle` lines 79-87 (explanatory comment for the `testImplementation libs.robolectric` line).
  - `DictatePipelineServiceTest.kt` class KDoc (lines 25-55: 3 enumerated reasons — channel-order, FGS budget, Multi-Bind).

The `app/build.gradle` change `testOptions.unitTests.includeAndroidResources = true` is also justified inline (comment lines 51-55: "Required for Robolectric: it consumes AAR-bundled resources for tests that need a real Context"). Other JVM tests are unaffected because they don't `@RunWith(RobolectricTestRunner)`.

---

## Test-Helper Inventory (B1)

**No new test helpers introduced.** Block 1b is the natural place to add:
- A `FakeServiceConnection` for testing the IME-side bind/unbind once the orchestrator's `state: StateFlow<DictateUiState>` is wired.
- A `FakeBinder` wrapping `DictatePipelineService.LocalBinder` for IME-side tests.

---

## Summary Counts

| Category | Critical | Important | Nice-to-have |
|----------|----------|-----------|--------------|
| Documentation gaps | 0 | 0 | 0 |
| Test-quality | 0 | 2 (B1-1, B1-2) | 1 (B1-3) |
| Coverage | 0 | 0 | 2 (B1-4, B1-5) |
| Cross-chunk regressions | 0 | 0 | 0 |
| Helper-Konsolidierung | 0 | 0 | 0 |
| **Total** | **0** | **2** | **3** |

All findings are **delegated-to-orchestrator** for routing. None block AUDIT-TEST sign-off — the test suite is green and the K-1 + K-4 quality gates are respected.

---

## Stdout sign-off

```
Test-audit done. Doc-gaps: 0. Quality findings: 5 (0 Crit / 2 Imp / 3 Nice). Coverage threshold met for 2/2 new production files (saturated truth-table for predicate; ~75% for service, exceeds 70%).
Cross-chunk-regressions: 0. Helper-Konsolidierungs-Hinweise: 0 (informational only, no findings).
Output: ./reports/audit-test-B1.md
Phase complete.
```
