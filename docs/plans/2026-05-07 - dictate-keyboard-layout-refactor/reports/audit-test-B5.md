# AUDIT-TEST Findings — Block 5 (Floating-Overlay, final implementation block)

**Agent-ID:** `B5-AUDIT-TEST`
**Scope:** full-block · last-verify-commit `74f9dd3` · HEAD `e1486fe`
**Date:** 2026-05-15

## `./gradlew test` results

| Run | Command | Result | tests | failures | errors | skipped |
|-----|---------|--------|-------|----------|--------|---------|
| 1 (cached) | `./gradlew test` | UP-TO-DATE (cached, all green) | 933 | 0 | 0 | 0 |
| 2 (clean) | `./gradlew testDebugUnitTest --rerun-tasks` | **BUILD FAILED — 1 failure** | 933 | **1** | 0 | 0 |
| 3 (clean, repro) | `./gradlew testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL | 933 | 0 | 0 | 0 |
| 4 (DB-tests subset) | `--tests "*migration*" + 4 DB classes` | BUILD SUCCESSFUL | — | 0 | 0 | 0 |
| 5 (overlay+migration pair) | `--tests Overlay…Transition + Legacy…Migration` | BUILD SUCCESSFUL | — | 0 | 0 | 0 |

- `testDebugUnitTest` aggregate: **933 tests** (843 pre-B5 + ~90 new B5). Matches the expected whole-plan suite size.
- `./gradlew compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL (AndroidTest sources compile).
- Suite is **green on cached + repeated clean runs**; one **non-reproducible** failure observed on a single clean run (see AUDIT-TEST-B5-1).

### B5 new-test inventory (per `testDebugUnitTest` XML)

| File | tests | Chunk |
|------|-------|-------|
| `OverlayLayoutParamsFactoryTest` | 16 | C16 |
| `AndroidOverlayWindowTest` | 11 | C16 |
| `OverlayBackendTest` | 18 (14 C16 + 4 C18) | C16/C18 |
| `DefaultOverlayPermissionGateTest` | 10 | C17 |
| `OverlayPermissionObserverTest` | 5 | C17 |
| `OverlayPermissionOnboardingActivityTest` | 7 | C17 |
| `DefaultOverlayPositionMapperTest` | 9 | C18 |
| `OverlayDragControllerTest` | 5 | C18 |
| `DictatePipelineServiceOverlayTransitionTest` | 8 | C18 |
| `LayoutCatalogTest` | +2 −1 (placeholder replaced) | C16 |

Totals: C16 41 ✓, C17 22 ✓, C18 27 ✓ — matches the block-report claims exactly.

## Documentation Gaps

| ID | Title | Severity | Chunk:Sub-Section | Status |
|---|---|---|---|---|
| — | none — no `### Code-Bugs Found While Writing Tests` entries claimed; no undocumented production-code changes by IMPL-TEST agents in the B5 commit range (production commits `1f3ef06`/`587034b`/`0d6c12e` are IMPL, test commits `d69b95d`/`d138c88`/`e1486fe` are test-only per diff inspection) | — | — | none |

## Test-Quality

| ID | Title | Severity | File:Line | Status |
|---|---|---|---|---|
| AUDIT-TEST-B5-1 | **Flaky test (cross-test DB-singleton pollution amplified by B5).** `LegacyAudioFileMigrationTest.run leaves non-legacy-path sessions untouched` failed once on a clean full-suite run (`expected:<[RECORDING]> but was:<[FAILED]>` at `LegacyAudioFileMigrationTest.kt:233`), passed on cached run and on a repeat clean run. Root cause: the test shares the `DictateDatabase` **singleton** + default SharedPreferences across the Robolectric JVM fork (the test's own `@Before` KDoc documents this fragility). B5's new `DictatePipelineServiceOverlayTransitionTest` boots the full `DictatePipelineService` 8× — each `onCreate` calls `LegacyAudioFileMigration.run(applicationContext)` (`DictatePipelineService.kt:384`) against the shared singleton and dispatches `TriggerPipeline` (creates session rows). Non-deterministic Robolectric fork-assignment occasionally co-locates it with `LegacyAudioFileMigrationTest`, leaving the `Pref.LegacyAudioPurgedV4` flag / session rows in a state the `deleteAll()`+pref-clear `@Before` does not fully neutralise. Production code is uninvolved (B5 touched 0 migration/DB files). | Important | `app/src/test/java/net/devemperor/dictate/migration/LegacyAudioFileMigrationTest.kt:233` (failure surface); `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceOverlayTransitionTest.kt` (amplifier) | open (delegated-to-orchestrator) |
| AUDIT-TEST-B5-2 | T6 (HOVER→WIDGET) has **no service-level transition test** in `DictatePipelineServiceOverlayTransitionTest` — covered only "transitively" via the `viewMode != KEYBOARD` rule asserted by T4, plus reducer-level `ViewModeModuleTest.T6 HOVER to WIDGET…`. Full-stack T6 (overlay stays attached when HOVER→WIDGET on `OnImeViewShown` + `userPrefersWidget=true`) is the one transition of the seven without its own service-Robolectric assertion. Low regression risk (the no-churn rule is structurally simple and reducer-tested), but it is the only T-row not independently asserted at the service layer. | Nice-to-have | `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceOverlayTransitionTest.kt` | open (delegated-to-orchestrator) |

### Test-quality positives (no findings)

- **No Mockito/MockK in any B5 test** (K-1 honoured). All doubles are hand-rolled: `FakeOverlayWindow`, `FakeOverlayPermissionGate`, `RecordingWindowManager` (nested), `FixedPositionMapper`/`RecordingPositionMapper` (nested), captured-action lists.
- **Robolectric usage justified** everywhere it appears: `AndroidOverlayWindow`/`OverlayBackend` (real `LayoutInflater` + `WindowManager.LayoutParams`), `DefaultOverlayPermissionGate`/`OverlayPermissionOnboardingActivity` (`Settings.canDrawOverlays`/Activity), `DefaultOverlayPositionMapper` (`displayMetrics`), `OverlayDragController` (real `MotionEvent`), `DictatePipelineServiceOverlayTransitionTest` (full Service). `OverlayPermissionObserverTest` is correctly **pure-JVM** (no Robolectric) — DIP via `dispatch: (Action)->Unit`. K-4 opt-out applied conservatively.
- **Behavior-assertions, not implementation-assertions:** clicks emit specific `Action`s; window lifecycle asserted via recorded `events`; permission matrix asserted via the 2×2 truth-table; FSM asserted via observable `attachedBackendCount()`.
- **Test names** are scenario-descriptive throughout (e.g. ``T7 pipeline-done in HOVER cascades to KEYBOARD and detaches (Geist-Widget protection)``).
- **Edge cases well covered:** C16 — BadToken-on-attach + recover-after-revoke + double-attach + permission-revoke-mid-session + click-after-detach + mismatched-backend `require`. C17 — API-34 permission boundary, observer 3-emit transition sequence, SRP pref-separation (4 cross-assertions), idempotent marks, canonical-pref-key round-trip. C18 — drag-threshold (below/above), boundary clamping (out-of-range both directions), zero-free-area divide-by-zero guard, round-trip identity, mid-drag-detach persistence (R.18), null-params short-circuit, T4 no-churn.
- **Triangle-FSM coverage is the plan's central concern and is well-served:** service-level `DictatePipelineServiceOverlayTransitionTest` covers T1/T2/T3/T4/T5/**T7** explicitly; reducer-level `ViewModeModuleTest` (B2) covers **all 7** including T6 and two T7 variants. **T7 / Geist-Widget regression has an explicit, named test at both layers** — the single most important test for the whole plan is present and asserts the no-Geist-Widget invariant.

## Coverage

No coverage reporter (JaCoCo/Kover) is configured in `app/build.gradle` (`Skill-Conventions` has no `coverage_threshold_branches`; no plan-AC numeric threshold). Coverage assessed by static branch-mapping against the B5 production diff instead:

| File | Branch coverage (static assessment) | Untested branches |
|---|---|---|
| `OverlayLayoutParamsFactory.kt` | High — every §4.4 flag (set + not-set), type, format, gravity, dims, animation, fresh-instance | none material |
| `OverlayWindow.kt` (`AndroidOverlayWindow`) | High — attach/update/detach forwarding, double-attach, update-before-attach, detach-before-attach, BadToken, IAE-on-update, IAE-on-remove swallow, recover-after-revoke | API<26 `TYPE_PHONE` branch not asserted (minSDK 26 makes it dead in practice) |
| `OverlayBackend.kt` | High — permission gate, suppress-bit teardown, attach-once, mismatched-backend, per-ViewMode close, R.3 null-resolver, click-after-detach, idempotent re-render, permission-revoke-mid-session, applyPosition via mapper, idempotent position, drag-persist→action, detach-after-position | `view.post` retry path for unmeasured-first-render not directly asserted (noted by C18 as `view.post` retry; covered indirectly) |
| `DefaultOverlayPermissionGate.kt` | Medium-High — `shouldShowOnboarding` 2×2 matrix, mark* write-paths, SRP separation, idempotency, canonical key. `hasOverlayPermission()==true` branch only via Fake (Robolectric `canDrawOverlays` defaults false — documented limitation, granted-branch verified via FakeGate truth-table) | `canDrawOverlays==true` real-shadow branch (documented; acceptable) |
| `OverlayPermissionObserver.kt` | High — init false/true, refresh current value, unconditional 3× dispatch, init→refresh toggled sequence | none material |
| `OverlayPermissionOnboardingActivity.kt` | High — inflation, grant→Settings intent (package URI + NEW_TASK), grant flips Shown, grant SRP, dismiss finish(), dismiss SRP, status text on resume | `onResume` granted-state branch not asserted (Robolectric default no-permission) |
| `OverlayPositionMapper.kt` (`Default…`) | High — corners, centre, clamp both dirs, round-trip, unmeasured null both dirs, zero-free-area | none material |
| `OverlayDragController.kt` | Medium-High — tap-below-threshold, drag-above-threshold (update-per-move + persist-on-UP), isDragging() lifecycle, mid-drag-detach R.18, idle-detach no-persist, null-params guard | accessibility-scaled `dragThresholdPx = max(8dp, scaledTouchSlop*1.5)` exact boundary not parametrically swept (tap=2px / drag=300px are far from the boundary) |
| `DictatePipelineService.kt` (B5 delta: `syncOverlayBackendAttachment`, collector guard) | Medium-High — T1/T2/T3/T4/T5/T7 + boot-state + backend-availability | **T6 not asserted at service layer** (AUDIT-TEST-B5-2); per-emit `try/catch` render-isolation path not directly exercised by a throwing-backend test |

Threshold verdict: no numeric gate defined → assessed against the implicit 80% default. All B5 production files clear it on branch-mapping; the two gaps (T6 service-level, drag-threshold boundary sweep) are Nice-to-have, not threshold breaches.

## Cross-Chunk-Regressions

One **non-reproducible** clean-run failure — see **AUDIT-TEST-B5-1** (Important, test-isolation flakiness amplified by B5's `DictatePipelineServiceOverlayTransitionTest`, not a production regression; suite green on cached + repeat-clean + all targeted-subset runs). No deterministic cross-chunk regression: the 843 pre-B5 tests pass on every green run; no B0-B4 test was structurally broken by B5 (B5 touched only new `overlay/` files + additive `DictatePipelineService`/`LayoutCatalog`/`ActionResolvers`/`TextResolvers` + the `LayoutCatalogTest` placeholder swap).

## Helper-Konsolidierung

| Observation | Verdict |
|---|---|
| `FakeOverlayWindow` (C16) reused by `OverlayBackendTest` + `OverlayDragControllerTest` (C18) — single shared K-1 fake, no duplicate | Good — First-Use + reuse, no consolidation needed |
| `FakeOverlayPermissionGate` (C17) used by `OverlayPermissionObserverTest`; `OverlayBackendTest`/transition tests use the production `NoOverlayPermissionGate` stub directly | Good — appropriate double per scope, no duplication |
| Nested per-test doubles `RecordingWindowManager` (`AndroidOverlayWindowTest`), `FixedPositionMapper` (`OverlayBackendTest`), `RecordingPositionMapper` (`OverlayDragControllerTest`) | Acceptable — each is a 1-file-scoped recording double with distinct recording needs; promoting to shared helpers would over-generalise. No quasi-duplicate `OverlayPositionMapper` fake leaks across chunks beyond these intentional per-test variants. |

No helper-consolidation findings.

## Espresso UI-Tests 1-10 (B4-C14 carry-over) — status

- `app/src/androidTest/java/net/devemperor/dictate/ui/KeyboardLayoutUiTest.kt`: 10 `@Test` + 13 `@Ignore` (class-level + per-test).
- **The B4-5 AUDIT-TEST silently-passing risk is RESOLVED.** Each skeleton body now contains `fail("pending: spec 2 §14.2 UI-N — body skeleton; un-ignore + implement assertions")`. If a future implementer removes `@Ignore` without writing the body, the test **fails loudly** instead of silently passing. `@Ignore` reasons carry the greppable `pending:` prefix and per-test readiness blockers (e.g. "needs PipelineUiState.Running counter shape", "needs ActivityScenario rotation harness", "needs per-frame IdlingResource").
- B5's overlay attach-wiring (C18) does **not** make any of the 10 runnable — they target IME-view layout/MotionScene (Spec 2 §14.2 UI-1…UI-10), a different surface from the WindowManager overlay. Correctly remaining `@Ignore`d post-B5.
- No new finding — this carry-over is now in a safe state (previously open silently-passing risk is closed by the `fail("pending:")` bodies).

## AndroidTest inventory (final state before Phase 4.5 E2E)

| File | Tests | Notes |
|---|---|---|
| `database/migration/MigrationTo4Test.kt` | 8 `@Test` (B3) | device-required |
| `database/migration/AndroidTestSetupSmokeTest.kt` | 1 `@Test` | device-required |
| `ui/KeyboardLayoutUiTest.kt` | 10 `@Test`, all `@Ignore` w/ `fail("pending:")` bodies (B4-C14) | device-required, skeletons |

- `./gradlew compileDebugAndroidTestKotlin` → **BUILD SUCCESSFUL** (all androidTest sources compile).
- AndroidTest suite is device-only and **not run by this agent** (no device); documented as local-only-no-CI in prior block reports / state file. No B5 AndroidTest was added (B5 overlay surface is JVM/Robolectric-covered).

## Summary

- **Whole-plan suite: 933 tests, green on cached + repeated clean runs.** AndroidTest compiles.
- B5 added ~90 well-structured tests; C16/C17/C18 counts (41/22/27) match the block-report exactly.
- **Triangle-FSM T1-T7 + the T7 Geist-Widget regression are explicitly covered at both reducer (`ViewModeModuleTest`) and service (`DictatePipelineServiceOverlayTransitionTest`) layers** — the plan's central test concern is satisfied.
- K-1 (no Mockito/MockK) and K-4 (justified Robolectric / pure-JVM where possible) are honoured throughout.
- Espresso skeleton silently-passing risk (carry-over from AUDIT-TEST-B4-5) is **resolved** — bodies now `fail("pending:")`.
- **Two findings:** 1 Important (AUDIT-TEST-B5-1 — flaky `LegacyAudioFileMigrationTest` via shared-DB-singleton pollution amplified by the new full-Service-boot transition test; non-reproducible, production code uninvolved); 1 Nice-to-have (AUDIT-TEST-B5-2 — T6 lacks an independent service-level assertion). No deterministic cross-chunk regression.
