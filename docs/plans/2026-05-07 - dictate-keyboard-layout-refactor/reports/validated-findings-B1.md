# Validated Findings — Block 1

**Agent-ID:** B1-VAL-SANITY
**Date:** 2026-05-15
**Source audits:**
- `./reports/audit-plan-and-api-B1.md` (B1-AUDIT-PLAN-AND-API — 0 Crit / 3 Imp / 4 NTH)
- `./reports/audit-convention-B1.md` (B1-AUDIT-CONVENTION — 0 Crit / 2 Imp / 5 NTH)
- `./reports/audit-logic-B1.md` (B1-AUDIT-LOGIC — 0 Crit / 3 Imp / 4 NTH)
- `./reports/audit-test-B1.md` (B1-AUDIT-TEST — 0 Crit / 2 Imp / 3 NTH)

Raw input: 7 + 7 + 7 + 5 = **26 findings**. After dedup (3 cross-audit overlaps) and false-positive filtering: **23 unique findings**, all kept (no eliminations).

## Summary

| Verdict | Critical | Important | Nice-to-have | Total |
|---|---|---|---|---|
| 🟢 valid + auto-fixable | 0 | 9 | 14 | **23** |
| 🟡 valid + research-needed | 0 | 0 | 0 | **0** |
| ❌ eliminated | 0 | 0 | 0 | **0** |

Per D3 (every severity gets fixed in this run), all 23 findings are routed to the repair-sub-phase.

## Repair status (Block-Validate Repair Wave 1 — B1-VAL-REPAIR, 2026-05-15)

All 23 🟢 findings are **fixed** in one wave. See block-report `### Block-Validate Repair Wave 1 (B1-VAL-REPAIR)` in `B1-pre-architecture-service-skeleton.md` for the per-finding fix-description table + file-modification list + self-check.

| Finding | Status | Note |
|---------|--------|------|
| F-1 | fixed | `pipelineStateProvider` threaded through `RecordingUiController`; IME wires `uiController.getState()`. |
| F-2 | fixed | D6 deviation row added to C2 Deviations table. |
| F-3 | fixed | Issue IMPL-2 added to Issue Index, `delegated-to-orchestrator`, target Phase 4.5 runbook. |
| F-4 | fixed | try/catch around `startForegroundCompat` + `createNotificationChannel`. |
| F-5 | fixed | `bindService` return-value captured + reset on failure. |
| F-6 | fixed | 6 strings added to `values-de`, `values-es`, `values-pt`. DE uses Spec 1 §11.3.2a verbatim. |
| F-7 | fixed | Dedup of F-3 — same Issue paperwork. |
| F-8 | fixed | `predResendVisible` → `isResendVisible` rename across 4 files. |
| F-9 | fixed | `LocalBinder.service` → `internal val service`. |
| F-10 | fixed | `// TODO(Block 1b): remove when action is forwarded to orchestrator` added. |
| F-11 | fixed | KDoc rewording — "Block 5 collapses the 4-arg signature into …" |
| F-12 | fixed | `@see` jointly cites Spec 1 §4.3 + ADR-0001 §"Required mechanics" item 2. |
| F-13 | fixed | `Quadruple<A,B,C,D>` extracted to `app/src/test/java/net/devemperor/dictate/testutil/Quadruple.kt`. |
| F-14 | fixed | SYSTEM_ALERT_WINDOW pre-decl moved to its own `TODO(Block 6)` comment block. |
| F-15 | fixed | `stubDispatchCount` is now `AtomicInteger`. |
| F-16 | fixed | Block-1b shutdown-ordering comment expanded in `onDestroy`. |
| F-17 | fixed | "Discarding `state` is intentional" comment in `applyRecordButtonForRecording`. |
| F-18 | fixed | 4-step ordering KDoc on `RecordingUiController.onStateChanged`. |
| F-19 | fixed | New `KeyboardUiControllerTest.kt` — 6 Robolectric tests covering applyRecordButtonForRecording. |
| F-20 | fixed | New `PipelineServiceConnectionContractTest.kt` — 4 tests via FakePipelineConnection (Option B). |
| F-21 | fixed | `notificationChannel_invariants` extended with 5 new asserts. |
| F-22 | fixed | New `DictatePipelineServicePreApi34Test.kt` — `@Config(sdk = [33])` pre-API-34 branch test. |
| F-23 | fixed | Dedup of F-13 — same Quadruple extraction. |
| F-24 | fixed | `@see` paths with spaces backticked across 3 files. |
| F-25 | fixed | 4 section markers inside the inline anonymous `ServiceConnection`. |

**Repair-wave recommendation:** **1 wave** covers all 23 🟢 findings. Most are small/mechanical (KDoc tweaks, defensive try/catch, name rename, localized strings, deviation-table additions, doc-trail comments). The largest single change is threading a `pipelineStateProvider` constructor parameter into `RecordingUiController` (F-3, ~6 lines + 2 call-sites + test plumbing). Bundling order below is recommendation-only — the repair-agent may interleave, but the listed P-* domain bundles keep diff-locality tight.

**Estimated total:** **1 repair-wave**, ~12 production files touched + 3 doc/manifest files + the block-report's `### Deviations` and `## Issue Index` sections.

## Cross-cut patterns

Five systemic patterns emerged across the four audits — they inform fix-bundling for the repair-wave:

1. **LocalBinder skeleton-state surface (P-LOCAL_BINDER)** — three findings flag the same surface from three angles:
   - F-2 (was AUDIT-PLAN-AND-API-B1-3): ADR-0003 §"Required mechanics" item 3 contract (`state: StateFlow<DictateUiState>` + `dispatch(action: Action): DispatchOutcome`) divergence not in `### Deviations` table.
   - F-9 (was AUDIT-CONVENTION-B1-3): `LocalBinder.service` getter is a public back-door; should be `internal`.
   - F-15 (was AUDIT-LOGIC-B1-5): `dispatch(...)`'s `stubDispatchCount += 1` is non-atomic.
   - F-10 (was AUDIT-CONVENTION-B1-4): `@Suppress("UNUSED_PARAMETER")` on `dispatch(action: Any)` lacks Block-1b removal TODO.
   Bundle: all four edits land in `DictatePipelineService.kt` lines 292-319 + a block-report `### Deviations` row. One repair-chunk.

2. **FGS lifecycle defensive paths (P-FGS-DEFENSIVE)** — two logic findings + one plan-paperwork finding cluster on the FGS-permission/binding edge cases:
   - F-4 (was AUDIT-LOGIC-B1-2): `startForegroundCompat` + `createNotificationChannel` have no try/catch for `ForegroundServiceStartNotAllowedException` / `SecurityException`.
   - F-5 (was AUDIT-LOGIC-B1-3): `bindService(...)` return value discarded in IME `onCreateInputView`.
   - F-7 (was AUDIT-PLAN-AND-API-B1-2): POST_NOTIFICATIONS runtime prompt (Spec 1 §11.2.2 Block-2 sub-step 6) silently deferred without `delegated-to-orchestrator` Issue paperwork.
   Bundle: F-4 + F-5 are code edits; F-7 is a block-report doc-fix (file as proper Issue, route to a target block — Phase 4.5 runbook is the natural landing per E2E TC-15). All three relate to "the FGS-permission denied first-launch path".

3. **Documentation precision drift (P-DOCS)** — five NTH findings tighten KDoc / comment claims to match implementation:
   - F-11 (was AUDIT-PLAN-AND-API-B1-4): "Block 5 lifts the body verbatim" KDoc overstates copy-paste vs. signature collapse.
   - F-12 (was AUDIT-PLAN-AND-API-B1-7): `@see ADR-0001 §'Required mechanics'` pointer in `serviceScope` KDoc is loosely accurate (should jointly cite Spec 1 §4.3).
   - F-16 (was AUDIT-LOGIC-B1-4): `serviceScope.cancel()` ordering — Block 1b's `runBlocking { shutdown() }` MUST run before — add a `// Block 1b:` comment.
   - F-17 (was AUDIT-LOGIC-B1-6): `applyRecordButtonForRecording`'s discard of `state` in early-return branch — add one-line comment "intentional".
   - F-18 (was AUDIT-LOGIC-B1-7): `RecordingUiController.onStateChanged` ordering contract — add KDoc capturing the 4-step ordering invariant.
   Bundle: 5 KDoc / comment-only edits across 3 files (`KeyboardVisibilityPredicates.kt`, `DictatePipelineService.kt`, `KeyboardUiController.kt`, `RecordingUiController.kt`). Diff-locality medium.

4. **Test-coverage gaps for resolver + bind/unbind (P-TESTS)** — two AUDIT-TEST Important findings + two NTH:
   - F-19 (was AUDIT-TEST-B1-1): `KeyboardUiController.applyRecordButtonForRecording(state)` (~50 LOC central resolver) untested.
   - F-20 (was AUDIT-TEST-B1-2): IME-side ServiceConnection callbacks (~80 LOC across 4 callbacks) untested.
   - F-21 (was AUDIT-TEST-B1-4): NotificationChannel config invariants partially asserted (only `importance` checked; missing `showBadge`, `sound`, `vibration`, `lights`, `lockscreenVisibility`).
   - F-22 (was AUDIT-TEST-B1-5): API-version-branch coverage (SDK < 26 channel-skip, SDK < 34 implicit startForeground) untested.
   All four delegated by AUDIT-TEST to orchestrator; per D3 we fix all four in this wave.
   - F-19: new Robolectric test class `KeyboardUiControllerTest.kt` covering the 4 `RecordingState` branches + the pipeline-guard branch. ~5 tests, ~80 LOC.
   - F-20: write tests for the four ServiceConnection callbacks (extracting `pipelineConnection` to a named inner class is fine if it eases testability; otherwise inject a fake `Context`-shim). ~3 tests, ~60 LOC.
   - F-21: tighten `notificationChannel_isImportanceLow_andSilent` to assert all 5 invariants. 1 test, ~8 LOC.
   - F-22: add a `@Config(sdk = [26])` test class (or `@Config(sdk = [26])` annotation on dedicated test methods) covering both SDK-branches. ~2 tests, ~30 LOC.
   Bundle: all four are test-only edits in `app/src/test/`. One repair-chunk.

5. **Test-utility extraction prep (P-TEST-UTIL)** — two findings flagging the same future-block prep:
   - F-13 (was AUDIT-CONVENTION-B1-7): `Quadruple<A,B,C,D>` private data class in `KeyboardVisibilityPredicatesTest`.
   - F-23 (was AUDIT-TEST-B1-3): same artefact, same scope.
   Bundle (dedup): one extraction to `app/src/test/java/net/devemperor/dictate/testutil/Quadruple.kt`. Spec 2 §14.2 anticipates Block 4 + 5 needing the same. Resolves both findings.

Two minor sub-patterns worth noting but not bundle-worthy:
- **Naming convention drift (P-NAMING)** — F-8 (was AUDIT-CONVENTION-B1-1) is a single rename (`predResendVisible` → `isResendVisible`) propagating to 4 test cases + 2 production call sites. Mechanical; no other `predXxx` exists in the codebase (verified by grep — only the one site).
- **Localization gap (P-LOCALIZATION)** — F-6 (was AUDIT-PLAN-AND-API-B1-1) is one finding across 3 locale files (`values-de`, `values-es`, `values-pt`) for 6 new strings. Spec 1 §11.3.2a line 4968-4969 provides the DE text for `dictate_service_not_ready`; the other 5 strings + ES/PT translations need consistent translations. Mechanical.

## Recommended repair-bundling (orchestrator hint)

The repair-agent (B1-VAL-REPAIR via resume-chain) should land the 7 bundles in this order to keep diff-locality tight and minimise rework:

1. **P-LOCAL_BINDER** — F-2 + F-9 + F-10 + F-15 (all in `DictatePipelineService.kt:292-319` + 1 block-report deviation row).
2. **P-FGS-DEFENSIVE** — F-4 + F-5 + F-7 (defensive try/catch in service, bindService-return-check in IME, POST_NOTIFICATIONS Issue paperwork).
3. **P-RECORDING_UI** — F-3 (thread `pipelineStateProvider` through `RecordingUiController`).
4. **P-LOCALIZATION** — F-6 (3 locale files × 6 strings).
5. **P-NAMING** — F-8 (`predResendVisible` → `isResendVisible` rename).
6. **P-DOCS** — F-11 + F-12 + F-16 + F-17 + F-18 (comment-only KDoc edits).
7. **P-MANIFEST** — F-14 (split SYSTEM_ALERT_WINDOW into its own Block-6 comment group); F-25 (extract `pipelineConnection` to named inner class with section markers; minor — fine to keep inline if section-markers added).
8. **P-TEST-UTIL** — F-13 + F-23 → one `TestTuples.kt` extraction.
9. **P-TESTS** — F-19 + F-20 + F-21 + F-22 (new test classes + tightened assertions).

Wave-commit boundaries: orchestrator may split into multiple commits within the single repair-wave (e.g. bundle 1 + 2 + 3 as the "code-bugs" commit, bundle 4 as "localization", bundles 5-9 as "convention + test polish") — that's the orchestrator's call. Verify self-check after the full wave.

## Out-of-scope deviations (recorded for documentation, NOT for fix in B1)

These findings concern files outside the B1 implementation scope but were surfaced by the audits. They are routed as **separate Issues** for orchestrator triage — they are **not** included in the 23 repair-wave findings.

| ID | Source | Scope | Action |
|---|---|---|---|
| OOS-1 | AUDIT-PLAN-AND-API-B1-5 | ADR-0003 §"Required mechanics" item 1 — manifest snippet `<service android:name=".pipeline.DictatePipelineService" …>` (package `.pipeline.`) vs. canonical Spec 1 §11.5 line 1562 placement at `.core.DictatePipelineService`. | File as a separate `delegated-to-orchestrator` Issue against ADR-0003 (Decision-History append, append-only per `Status: Accepted`). Outside Block 1 audit scope to fix. |

## Findings

### F-1 — predResendVisible call sites in RecordingUiController use hard-coded `PipelineUiState.Idle` literal (was AUDIT-LOGIC-B1-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt:178-183`, `:198-203`
- **Description:** `applyIdleState()` and `applyActiveState(useBluetooth)` pass `pipelineState = PipelineUiState.Idle` (literal) to `resolveResendVisibility(...)`. The block-report's deviation rationale claims this captures an invariant ("at the moment a RecordingStateController-driven Idle transition runs, the pipeline FSM is also Idle"), but `RecordingUiController` holds no reference to a `PipelineUiStateReader`, so the invariant is **not enforced**. Any caller of `recordingStateController.notifyState(...)` from a non-stop pathway (view-recreate `restoreUiState`, language-flip, cancel-recording paths bypassing `onRecordingCompleted`) can fire `applyIdleState()` while the pipeline is actually `Preparing`/`Running`/`ReprocessStaging`. In that frame the predicate sees `recording=Idle ∧ pipeline=Idle (literal)` → `true` → resend button shows VISIBLE while the pipeline still paints "Sending…". This is exactly the §9.5 race the plan claims to eliminate. The pipeline-axis is owned by `KeyboardUiController.state` (the SoT); the literal divorces the resend button's pipeline axis from that owner.
- **Suggested fix (mechanical):**
  1. Add a constructor parameter to `RecordingUiController`: `private val pipelineStateProvider: () -> PipelineUiState = { PipelineUiState.Idle }` (default keeps existing tests compiling).
  2. Replace `pipelineState = PipelineUiState.Idle` on lines 182 + 202 with `pipelineState = pipelineStateProvider()`.
  3. IME-side wire: `pipelineStateProvider = { uiController.getState() }` (mirrors the existing provider pattern at `DictateInputMethodService.java:605` for `isReprocessStaging`).
  4. Drop the "invariant" framing from the comment in `applyIdleState` (replace with "reads the live pipeline state via the injected provider").
  5. Update `RecordingUiController` test fixtures if any are pinned to the literal value (none expected — default `{ PipelineUiState.Idle }` matches the previous behaviour).
- **Domain bundle candidate:** P-RECORDING_UI (single-finding bundle)

### F-2 — LocalBinder API divergence from ADR-0003 not flagged in Deviations table (was AUDIT-PLAN-AND-API-B1-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:292-319`, B1 block-report `### Deviations` table (C2 section)
- **Description:** ADR-0003 §"Required mechanics" item 3 specifies `LocalBinder` exposing exactly two surfaces: `state: StateFlow<DictateUiState>` and `dispatch(action: Action): DispatchOutcome`. The Block-2 implementation exposes `service: DictatePipelineService` + `dispatch(action: Any): Unit`. The KDoc transparently documents this as skeleton-state with Block-1b restoration path — but the deviation is NOT recorded in the C2 `### Deviations` table (which only lists D4 + D5). Per D22, mid-size deviations with solution clear from plan knowledge belong in the table with `plan-deviation-resolved` marker.
- **Suggested fix (mechanical, no code edit):** Append a deviation row to the C2 block-report (around `B1-pre-architecture-service-skeleton.md:232`):
  | D6 | ADR-0003 §"Required mechanics" item 3 (`state: StateFlow<DictateUiState>` + `dispatch(action: Action): DispatchOutcome`) | `LocalBinder` exposes `service: DictatePipelineService` + `dispatch(action: Any): Unit` instead | Skeleton — orchestrator/Action sealed class don't exist until Block 1b; widening `Any` → `Action` and adding `state: StateFlow` is a non-breaking IME-side change once orchestrator is wired | Block 1b restores the canonical ADR-0003 surface; if a Block-1b caller uses `binder.service.someInternal()` instead of `state`/`dispatch`, regression catch fails | flagged-for-validate (plan-deviation-resolved) |
- **Domain bundle candidate:** P-LOCAL_BINDER

### F-3 — POST_NOTIFICATIONS runtime prompt silently deferred without `delegated-to-orchestrator` Issue (was AUDIT-PLAN-AND-API-B1-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/onboarding/OnboardingActivity.java`, `app/src/main/java/net/devemperor/dictate/onboarding/OnboardingAdapter.java`, B1 block-report `## Issue Index`
- **Description:** Spec 1 §11.2.2 Block-2 sub-step 6 explicitly assigns the POST_NOTIFICATIONS runtime prompt to Block 2 ("POST_NOTIFICATIONS Runtime-Permission-Prompt in Onboarding ergänzen (§11.5.1)"). The block-report's `Overlooked points / known gaps` for C2 silently defers this ("Phase-B S-5 POST_NOTIFICATIONS-Prompt — separate UI surface, not a service-skeleton concern") but does not record it as an Issue in the Issue Index. The deferral rationale is defensible (onboarding UI vs. service-skeleton scope) — but per D22, deferrals of this scale must be flagged explicitly with `delegated-to-orchestrator` + named target block.
- **Suggested fix (mechanical, no code edit):** Add Issue IMPL-2 to the C2 `## Issue Index` table:
  | IMPL-2 | Important | POST_NOTIFICATIONS runtime prompt (Spec 1 §11.2.2 Block-2 sub-step 6) — not implemented in B1; on API 33+ devices the FGS notification will be hidden by default until a prompt is shown. Target: Phase 4.5 runbook line item (E2E TC-15 already exercises FGS notification visibility) OR a dedicated "Block-2-Onboarding-Completion" mini-chunk if a unit-level coverage is preferred. | delegated-to-orchestrator | UI-surface modification (OnboardingActivity ActivityResultLauncher per §11.5.1) is out of scope for a service-skeleton chunk. Block 1b's composition-root work does not touch onboarding; the natural carrier is Phase 4.5 runbook step + a small follow-up. |
- **Domain bundle candidate:** P-FGS-DEFENSIVE (related to FGS-permission first-launch path)

### F-4 — `startForegroundCompat` / `createNotificationChannel` have no defensive try/catch (was AUDIT-LOGIC-B1-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:148` (`onStartCommand`) + `:192-203` (`ensureNotificationChannel`)
- **Description:** `startForeground` is invoked unconditionally. On API ≥ 31 it can throw `ForegroundServiceStartNotAllowedException` (background-start restrictions); on API ≥ 33 it can throw `SecurityException` / `MissingForegroundServiceTypeException` if POST_NOTIFICATIONS is denied + `FOREGROUND_SERVICE_TYPE_MICROPHONE` mismatched. The plan (Spec 1 §11.5.1 Finding 11) explicitly anticipates POST_NOTIFICATIONS-denied — but no try/catch means the service crashes on first launch in that state. Combined with `ServiceConnection.onBindingDied` rebinding (DictateInputMethodService.java:354), the failure becomes a tight crash-loop.
- **Suggested fix (mechanical):**
  1. Wrap `startForegroundCompat(buildInitialNotification())` (`onStartCommand`) in:
     ```kotlin
     try {
         startForegroundCompat(buildInitialNotification())
     } catch (e: ForegroundServiceStartNotAllowedException) {  // API 31+
         Log.w(TAG, "FGS start denied (background-start restriction)", e)
         stopSelf()
         return START_NOT_STICKY
     } catch (e: SecurityException) {  // POST_NOTIFICATIONS / FGS-type
         Log.w(TAG, "FGS start denied (security)", e)
         stopSelf()
         return START_NOT_STICKY
     }
     ```
     (Use `RequiresApi(31)` guard for the first catch or a top-level `try { … } catch (e: Exception) { … }` with `if (e is ForegroundServiceStartNotAllowedException || …)` to avoid the SDK-version gate.)
  2. Wrap `mgr.createNotificationChannel(channel)` in `ensureNotificationChannel` with `try { … } catch (e: SecurityException) { Log.w(TAG, "channel-create denied", e); return }` (locked-down devices).
  3. Add a `TAG` constant (`private const val TAG = "DictatePipelineService"`) if not already present.
- **Domain bundle candidate:** P-FGS-DEFENSIVE

### F-5 — `bindService(...)` return value discarded in IME `onCreateInputView` (was AUDIT-LOGIC-B1-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:463-468`
- **Description:** `bindService(pipelineIntent, pipelineConnection, BIND_AUTO_CREATE)` is called without capturing the return value. On `false` (manifest entry missing, permission denied, package-manager-resolve fails), `pipelineServiceBindAttempted` is still flipped to `true`, no `onServiceConnected` ever fires, `pipelineBinder` stays null indefinitely, and `onDestroy`'s `unbindService(pipelineConnection)` throws `IllegalArgumentException` (currently caught silently). Spec 1 §11.3.2 lists this as a required IME-side edge case. Block 1b's first dispatch through the binder silently no-ops behind the `pipelineBinder != null` guard with no user-visible feedback.
- **Suggested fix (mechanical):**
  ```java
  boolean bound = bindService(pipelineIntent, pipelineConnection, BIND_AUTO_CREATE);
  if (!bound) {
      Log.e("DictateIME", "bindService(DictatePipelineService) returned false");
      pipelineServiceBindAttempted = false;  // allow retry on next onCreateInputView
      // optionally: Toast.makeText(this, R.string.dictate_service_not_ready, ...).show();
      //   — but onCreateInputView runs pre-user-interaction; a Toast here may confuse.
      //   The pre-bind-action-Toast at click-time (line ~1450) covers user feedback.
  }
  ```
- **Domain bundle candidate:** P-FGS-DEFENSIVE

### F-6 — German + Spanish + Portuguese localized strings missing for 6 new B1 strings (was AUDIT-PLAN-AND-API-B1-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/res/values-de/strings.xml`, `app/src/main/res/values-es/strings.xml`, `app/src/main/res/values-pt/strings.xml`
- **Description:** Block 1 added 6 new strings to `values/strings.xml` (`dictate_pipeline_service_description`, `dictate_pipeline_channel_name`, `dictate_pipeline_channel_description`, `dictate_pipeline_notif_title`, `dictate_pipeline_notif_idle`, `dictate_service_not_ready`) but none of them were added to the three localized locale dirs (verified: `ls app/src/main/res/` shows `values`, `values-de`, `values-es`, `values-night`, `values-pt`). Spec 1 §11.3.2a (line 4968-4969) explicitly calls this out as "Pflicht-Aufgabe Block-2" for `dictate_service_not_ready`. The FGS notification displayed during recording/transcription is highly user-visible (lock-screen + status bar); DE/ES/PT users see the English fallback otherwise.
- **Suggested fix (mechanical):** Add localized entries to all three locale files. The DE text for `dictate_service_not_ready` is provided by Spec 1 §11.3.2a line 4968 ("Service startet noch — bitte kurz warten."). Suggested translations (the repair-agent should verify with existing strings' tone):
  - `dictate_pipeline_service_description` — DE: "Foreground-Service für Aufnahme + Transkription"; ES: "Servicio en primer plano para grabación + transcripción"; PT: "Serviço em primeiro plano para gravação + transcrição"
  - `dictate_pipeline_channel_name` — DE: "Diktat-Pipeline"; ES: "Canal de Dictado"; PT: "Canal de Ditado"
  - `dictate_pipeline_channel_description` — DE: "Status der Aufnahme- und Transkriptions-Pipeline"; ES: "Estado de la grabación y transcripción"; PT: "Estado da gravação e transcrição"
  - `dictate_pipeline_notif_title` — DE: "Dictate"; ES: "Dictate"; PT: "Dictate" (product name, untranslated)
  - `dictate_pipeline_notif_idle` — DE: "Bereit"; ES: "Listo"; PT: "Pronto"
  - `dictate_service_not_ready` — DE (per Spec 1 §11.3.2a): "Service startet noch — bitte kurz warten."; ES: "El servicio se está iniciando — espera un momento, por favor."; PT: "O serviço está iniciando — aguarde um momento, por favor."

  The repair-agent SHOULD cross-check existing translations in each locale file for tone consistency (e.g. `dictate_storage_full` DE) before finalizing; the strings above are a starting point.
- **Domain bundle candidate:** P-LOCALIZATION (single-bundle, 3 files × 6 strings)

### F-7 — Same as F-3 (deduplicated)

*(POST_NOTIFICATIONS Issue paperwork — see F-3 above. Listing kept in pattern table for orchestrator traceability; no separate fix.)*

### F-8 — `predResendVisible` naming drifts from codebase `isXxx` convention (was AUDIT-CONVENTION-B1-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt:75`, `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt` (all `predResendVisible` references), `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt` (KDoc only), `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java` (KDoc / comment-only references)
- **Description:** Every other boolean function in `core/` uses Kotlin's standard `isXxx` prefix (`isPipelineRunning`, `isPipelineActive`, `isBusy`, `isReprocessStaging`, `isAnyActive`, `isActive`, `isRunning`, `isBluetoothAvailable`, `isSame`, `isEnabled`). The new `predResendVisible` introduces a brand-new `pred` prefix with no prior usage (verified by grep — only `predResendVisible` exists; no other `pred*` function). The plan uses `predResendVisible` as a working-title (not a hard contract), and the companion `resolveResendVisibility` already uses the standard `resolveXxx` verb-prefix — so the inconsistency lives in one file. Once Block 4 / 5 lift more UI predicates, the codebase will have a permanent two-convention split unless this is fixed now.
- **Suggested fix (mechanical, rename only):**
  1. Rename `fun predResendVisible(...)` → `fun isResendVisible(...)` in `KeyboardVisibilityPredicates.kt:75`.
  2. Update KDoc internal references (`[predResendVisible]` → `[isResendVisible]`) in the same file (lines 32, 87, 98).
  3. Update test method names in `KeyboardVisibilityPredicatesTest.kt` (`predResendVisible true when …` → `isResendVisible true when …`) and the call sites within tests.
  4. Update comments in `DictateInputMethodService.java` (lines 680, 1443, 1774, 1957) and `RecordingUiController.kt` (lines 38, 45) — these are doc-only references, no logic.
  5. The plan's working-title (`predResendVisible`) can be referenced in the new KDoc as "(working title in plan: predResendVisible)" if backwards-traceability is desired, but is not load-bearing.
- **Domain bundle candidate:** P-NAMING (single-bundle, mechanical rename across 4 files)

### F-9 — `LocalBinder.service` is publicly accessible (escape-hatch risk for Block 1b) (was AUDIT-CONVENTION-B1-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:300`
- **Description:** `val service: DictatePipelineService get() = this@DictatePipelineService` is `public` by default (Kotlin module visibility). The ADR-0003 contract says "exactly two surfaces" — but the public `service` accessor lets any IME-side caller reach into arbitrary service-private fields once they hold the binder. The IME-side currently does NOT use this (only assigns `pipelineBinder` and never dereferences `.service`), so the fix is `internal` visibility to lock the contract before Block 1b tempts a shortcut.
- **Suggested fix (mechanical):** Change `val service: DictatePipelineService` → `internal val service: DictatePipelineService` on line 300. Add a 1-line KDoc note: "Module-internal: enforces the ADR-0003 `state + dispatch` sealed contract by preventing IME-side callers from reaching into service internals." If `service` IS read from `app/src/test/`, the test is in the same module and `internal` still works.
- **Domain bundle candidate:** P-LOCAL_BINDER

### F-10 — `@Suppress("UNUSED_PARAMETER")` on `dispatch` lacks Block-1b removal marker (was AUDIT-CONVENTION-B1-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:312`
- **Description:** `@Suppress("UNUSED_PARAMETER")` masks a real warning. Block 1b will widen `dispatch(action: Any)` → `dispatch(action: Action)` and consume `action`, at which point the suppression must be removed — but there's no greppable marker. Compare with `JobExecutor.kt:183` (same pattern, same risk).
- **Suggested fix (mechanical):** Append a same-line comment: `@Suppress("UNUSED_PARAMETER") // TODO(Block 1b): remove when action is forwarded to orchestrator`.
- **Domain bundle candidate:** P-LOCAL_BINDER

### F-11 — `KeyboardVisibilityPredicates` KDoc overstates Block-5 "verbatim lift" (was AUDIT-PLAN-AND-API-B1-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt:75-84` (file-level KDoc)
- **Description:** KDoc claims "Block 5 (LayoutCatalog) lifts this predicate verbatim into the `RESEND` slot's `visibilityPredicate`". Spec 2 §3.2 defines `visibilityPredicate: (DictateUiState) -> Boolean` — a single-arg projection over the global state. The B1 helper has shape `(Boolean, Boolean, RecordingState, PipelineUiState) -> Boolean`. The truth-table body is preserved; the signature is not. A future reader may expect copy-paste.
- **Suggested fix (mechanical):** Replace "Block 5 lifts the body verbatim" with "Block 5 collapses the 4-arg signature into the single-state-arg form `(DictateUiState) -> Boolean` per Spec 2 §3.2; the truth-table body — same 4 axes ANDed in same order — is preserved".
- **Domain bundle candidate:** P-DOCS

### F-12 — `serviceScope` KDoc `@see ADR-0001 §'Required mechanics'` is loosely accurate (was AUDIT-PLAN-AND-API-B1-7)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:69`
- **Description:** KDoc claims "the orchestrator's single-dispatch path is Main-thread-confined per ADR-0001 §'Required mechanics'". ADR-0001 §"Required mechanics" item 2 does mention `Dispatchers.Main.immediate`, so the pointer survives audit — but the precise binding is Spec 1 §4.3 (orchestrator's `dispatch` impl detail). Broaden the pointer.
- **Suggested fix (mechanical):** Update the `@see` line to jointly cite both: `@see Spec 1 §4.3 (orchestrator single-dispatch on Main.immediate) + ADR-0001 §"Required mechanics" item 2`.
- **Domain bundle candidate:** P-DOCS

### F-13 — `Quadruple<A,B,C,D>` private data class duplicated risk; promote to shared test util (was AUDIT-CONVENTION-B1-7 + AUDIT-TEST-B1-3, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:** `app/src/test/java/net/devemperor/dictate/core/KeyboardVisibilityPredicatesTest.kt:332-337` (current) → `app/src/test/java/net/devemperor/dictate/testutil/Quadruple.kt` (new)
- **Description:** Kotlin stdlib does not ship `Quadruple`. Spec 2 §14.2 anticipates Block 4 + 5 will need the same 25-case truth-table destructuring. Two copies = drift trigger.
- **Suggested fix (mechanical):** Move the `data class Quadruple<A, B, C, D>` declaration to a new file `app/src/test/java/net/devemperor/dictate/testutil/Quadruple.kt` (`internal data class Quadruple<A, B, C, D>(...)`). Update the import in `KeyboardVisibilityPredicatesTest.kt`. Add a 2-line file-header KDoc explaining: "Test-only N-tuple — Kotlin stdlib ships only Pair / Triple. Used by N-axis truth-table tests (KeyboardVisibilityPredicates, future LayoutCatalog slot resolvers per Spec 2 §14.2)."
- **Domain bundle candidate:** P-TEST-UTIL

### F-14 — Manifest: SYSTEM_ALERT_WINDOW (Block-6 permission) mixed into Block-2 permission group (was AUDIT-CONVENTION-B1-6)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/AndroidManifest.xml:25-28`
- **Description:** Four `<uses-permission>` entries land in one B2 group (FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW). SYSTEM_ALERT_WINDOW is a Block-6 permission pre-declared "so the manifest is touched only once" — but the grouping invites future pre-declaration drift.
- **Suggested fix (mechanical):** Split into two comment blocks:
  ```xml
  <!-- Block 2: DictatePipelineService Foreground Service -->
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

  <!-- TODO(Block 6): keyboard-floating overlay. Pre-declared here so the manifest is touched only once. -->
  <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
  ```
- **Domain bundle candidate:** P-MANIFEST

### F-15 — `LocalBinder.dispatch` counter is non-atomic (was AUDIT-LOGIC-B1-5)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:312-319`
- **Description:** `stubDispatchCount += 1` on a `var Int` is non-atomic. Same-process binder transactions are not guaranteed to be main-thread. Block 1b adds a second client (Spec 1 §11.3.4 Multi-Bind) — concurrent dispatch becomes plausible.
- **Suggested fix (mechanical):**
  ```kotlin
  private val stubDispatchCount: AtomicInteger = AtomicInteger(0)

  fun dispatch(@Suppress("UNUSED_PARAMETER") action: Any) {
      stubDispatchCount.incrementAndGet()
  }

  val dispatchInvocationCount: Int get() = stubDispatchCount.get()
  ```
  Test assertion remains identical (`binder.dispatchInvocationCount`).
- **Domain bundle candidate:** P-LOCAL_BINDER

### F-16 — `serviceScope.cancel()` ordering vs. Block-1b shutdown comment-only (was AUDIT-LOGIC-B1-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:164`
- **Description:** Block 1b's planned `runBlocking { withTimeout(2000L) { orchestrator.shutdown() } }` (Spec 1 §11.5 Finding 6) MUST run BEFORE `serviceScope.cancel()` — otherwise `shutdown()`'s suspending `terminate` calls hit an already-cancelled scope and abort instantly. Current code is correct (no orchestrator yet) but the seam is fragile.
- **Suggested fix (mechanical, comment-only):** Insert above `serviceScope.cancel()`:
  ```kotlin
  // Block 1b: insert `runBlocking { withTimeout(2000L) { orchestrator.shutdown() } }`
  // HERE — BEFORE serviceScope.cancel(). Otherwise the shutdown's suspending
  // terminate calls run on an already-cancelled scope and abort instantly.
  ```
- **Domain bundle candidate:** P-DOCS

### F-17 — `applyRecordButtonForRecording` early-return discards `state` arg — comment-only (was AUDIT-LOGIC-B1-6)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt:495-498`
- **Description:** The pipeline-axis-owns-when-non-Idle branch discards the `state` arg and calls `refreshRecordButtonFromState()`. Correct under the SoT invariant; not obvious from code alone.
- **Suggested fix (mechanical, comment-only):** Add one-line comment above `refreshRecordButtonFromState()`:
  ```kotlin
  // Discarding `state` is intentional — pipeline owns record-button appearance entirely when non-Idle (Spec 1 §11.2.2 single-owner invariant).
  ```
- **Domain bundle candidate:** P-DOCS

### F-18 — `RecordingUiController.onStateChanged` ordering contract undocumented (was AUDIT-LOGIC-B1-7)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/RecordingUiController.kt:79-96`
- **Description:** The 4-step ordering — (1) record-button resolver, (2) recording-axis side-effects, (3) QWERTZ rec-button mirror, (4) `stateManager.refresh()` — is load-bearing (all side-effects must complete before KSM rebuilds visibilities in the same Main-thread task). Block 5's LayoutCatalog rewrite needs this contract documented to avoid a torn-frame regression.
- **Suggested fix (mechanical, comment-only):** Add KDoc to `onStateChanged`:
  ```kotlin
  /**
   * Ordering contract (load-bearing — Block 5 must preserve until LayoutCatalog
   * collapses both axes into one subscriber):
   *  1. record-button resolver (axis-aware appearance)
   *  2. recording-axis side-effects (pauseButton, animation, prompt buttons, resend)
   *  3. QWERTZ rec-button mirror
   *  4. stateManager.refresh() — rebuilds visibilities; runs LAST so all internal
   *     mutations are visible to KSM in the same Main-thread task.
   */
  ```
- **Domain bundle candidate:** P-DOCS

### F-19 — `KeyboardUiController.applyRecordButtonForRecording` central-resolver logic untested (was AUDIT-TEST-B1-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/KeyboardUiController.kt:471-535` → new test class `app/src/test/java/net/devemperor/dictate/core/KeyboardUiControllerTest.kt`
- **Description:** `applyRecordButtonForRecording(state: RecordingState)` is the new central resolver for the record-button recording axis (~50 LOC). The §9.5 race the plan eliminates lives here. Branches: (a) pipeline-state-guard (defers to `refreshRecordButtonFromState`); (b) 4-branch `when` over `RecordingState` (Idle / Preparing / Active / Paused); (c) inner Active.useBluetooth split. Audit estimates ~5 tests, ~80 LOC.
- **Suggested fix (test-only):** Create `KeyboardUiControllerTest.kt`. Robolectric (already on classpath via D4) for the view-bound `MaterialButton` calls. Use a fake `KeyboardStateManager` (handwritten per K-1). Tests:
  1. `pipeline_non_idle_defers_to_refreshFromState` — set state via `setState(PipelineUiState.Running)`, call `applyRecordButtonForRecording(RecordingState.Idle)`, assert appearance matches `refreshRecordButtonFromState` output (not the recording-state-Idle painted-Idle).
  2. `recording_idle_when_pipeline_idle` — `applyRecordButtonForRecording(RecordingState.Idle)`, assert button text + drawable + enabled-state match the Idle styling.
  3. `recording_preparing_when_pipeline_idle` — assert disabled + "Preparing…" text.
  4. `recording_active_useBluetooth_true_when_pipeline_idle` — assert active+BT styling.
  5. `recording_active_useBluetooth_false_when_pipeline_idle` — assert active+mic styling.
  6. `recording_paused_when_pipeline_idle` — assert paused styling.
- **Domain bundle candidate:** P-TESTS

### F-20 — IME-side ServiceConnection callbacks untested (was AUDIT-TEST-B1-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:316-372, 463-468` → new test class (location TBD by repair-agent)
- **Description:** The 4 ServiceConnection callbacks (`onServiceConnected`, `onServiceDisconnected`, `onBindingDied`, `onNullBinding`) + the `bindService`/`unbindService` pair are ~80 LOC of new logic with only the service-side smoke-test (`bindService_smokeTest_doesNotThrow`) exercising them. Audit estimates ~3 tests, ~60 LOC.
- **Suggested fix (test-only):**
  Option A (preferred): Extract `pipelineConnection` to a named package-private inner class `PipelineConnection` so the four callbacks can be invoked directly from a test. Add tests:
  1. `onServiceConnected_storesBinder` — invoke with a synthetic `LocalBinder`, assert `pipelineBinder == binder`.
  2. `onServiceDisconnected_clearsBinder` — invoke, assert `pipelineBinder == null`.
  3. `onBindingDied_rebinds` — invoke with a fake `Context` whose `bindService` returns true; assert `bindService` is called again with the same intent.
  4. `onNullBinding_logsAndStaysUnbound` — invoke, assert `pipelineBinder == null` + appropriate log captured.

  Option B: keep the connection inline anonymous; use Robolectric `Shadows.shadowOf(application).bindService(...)` to drive the callbacks via the test framework. Less surgical but no production refactor.

  The repair-agent picks the option. Note: F-25 below (out-of-scope-for-this-finding observation in AUDIT-CONVENTION-B1-5) recommends Option A regardless for IDE-navigability reasons; if both are done together the bundles overlap.
- **Domain bundle candidate:** P-TESTS (with overlap to P-MANIFEST/F-25)

### F-21 — NotificationChannel config invariants partially asserted (was AUDIT-TEST-B1-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:192-203` (channel-config) + `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt` (existing test `notificationChannel_isImportanceLow_andSilent`)
- **Description:** Existing test asserts `importance` only; missing assertions for `setShowBadge(false)`, `setSound(null, null)`, `enableVibration(false)`, `enableLights(false)`, `lockscreenVisibility`. Each invariant is part of the "silent, unobtrusive FGS notification" contract.
- **Suggested fix (test-only):** Extend `notificationChannel_isImportanceLow_andSilent` (rename if needed for clarity, e.g. `notificationChannel_invariants`) with:
  ```kotlin
  assertThat(channel.shouldShowBadge()).isFalse()
  assertThat(channel.sound).isNull()
  assertThat(channel.shouldVibrate()).isFalse()
  assertThat(channel.shouldShowLights()).isFalse()
  assertThat(channel.lockscreenVisibility).isEqualTo(Notification.VISIBILITY_PRIVATE)  // or _SECRET, per Spec 1 §11.5
  ```
  (Verify `lockscreenVisibility` target value against `DictatePipelineService.ensureNotificationChannel` — repair-agent confirms with the file.)
- **Domain bundle candidate:** P-TESTS

### F-22 — API-version-branch coverage gaps (was AUDIT-TEST-B1-5)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:187, 255-264`, `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt`
- **Description:** Robolectric default `@Config(sdk = [34])` misses two branches:
  1. `ensureNotificationChannel` pre-API-26 early-return (Build.VERSION.SDK_INT < O).
  2. `startForegroundCompat` pre-API-34 implicit `startForeground(id, notif)` overload (no explicit FGS-type).
  Both are platform-gating with no business logic, BUT verified: project `minSdk = 26` (per CLAUDE.md), so the pre-API-26 branch is logically reachable only on the lower bound. The pre-API-34 branch covers a wider range (API 26-33).

  **False-positive check on the pre-API-26 branch:** Build.VERSION_CODES.O = API 26. The early-return is `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return`. With `minSdk=26`, the check is **always false** on real devices but the code path is still reachable in tests via `@Config(sdk = [...])` only if Robolectric supports SDK < 26 (Robolectric 4.14 supports SDK 19+). So the test IS still meaningful for defensive coverage, but on a project with `minSdk=26` this is the lowest-priority of the bunch.
- **Suggested fix (test-only):** Add a `@Config(sdk = [26])` test class (or `@Config(sdk = [33])` for the startForegroundCompat pre-API-34 branch). At minimum:
  1. `@Config(sdk = [33])` test of `startForegroundCompat` — assert the implicit-type `startForeground(id, notification)` overload is used (verify via `ShadowService.getLastForegroundNotification()` or similar).
  2. **Optional** (per minSdk=26 reachability): `@Config(sdk = [25])` test of `ensureNotificationChannel` — assert NO channel is created (Shadow NotificationManager has 0 channels). This is mostly belt-and-suspenders.
- **Domain bundle candidate:** P-TESTS

### F-23 — Same as F-13 (deduplicated)

*(`Quadruple` extraction — see F-13. Listing kept in pattern table for orchestrator traceability; no separate fix.)*

### F-24 — `@see` paths with embedded spaces don't resolve in IntelliJ KDoc-linker (was AUDIT-CONVENTION-B1-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:** `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt:51-52`, `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt:53-54`, `app/src/main/java/net/devemperor/dictate/core/KeyboardVisibilityPredicates.kt:50-51`
- **Description:** `@see` references to `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/...` contain a literal space — IntelliJ's KDoc-linker renders them as plain text (no ctrl-click). The same-file paths to `docs/decisions/0003-service-foreground-pipeline-architecture.md` (no spaces) DO resolve. Mixed form across 3 files will entrench as more `@see` references land in subsequent blocks.
- **Suggested fix (mechanical):** Pick one convention and apply to all 3 files. Two viable forms:
  - **Backtick the path:** `@see` `` `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` §7`` — KDoc renders the path as code but still treats it as a string, not a navigable link. Acceptable for "the path is the data, navigation is manual".
  - **Markdown link:** Use the inline-comment form `// See: [Spec 1 §7](../../../../docs/plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)` — proper URL-encoding makes the link click-through. More verbose but actually navigable in IntelliJ Markdown preview.

  Recommend backtick form (simpler, consistent with existing `@see` style for non-spaced paths). Document the chosen convention once — either in CLAUDE.md "Code-side conventions" section, or as a `knowledge-doc-format` skill addendum.
- **Domain bundle candidate:** P-DOCS

### F-25 — ServiceConnection inline anonymous class lacks section markers / IDE-outline support (was AUDIT-CONVENTION-B1-5)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/core/DictateInputMethodService.java:319-360`
- **Description:** The ~50-line anonymous `ServiceConnection` has no section markers; IDE outline doesn't surface the 4 callbacks individually. Other large callback bodies in the same file have been extracted to named inner classes (`PromptQueueManager.PromptQueueCallback`, `recordingStateCallback`). Block 1b will add `state.collect { … }` here, further inflating the anonymous class.
- **Suggested fix (mechanical):** Either (a) extract `pipelineConnection` to a named private inner class `PipelineConnection` (preferred — synergizes with F-20 test extraction option A); or (b) add 4 section markers inside the anonymous class:
  ```java
  // ── onServiceConnected ──
  // ── onServiceDisconnected ──
  // ── onBindingDied ──
  // ── onNullBinding ──
  ```
  If F-20 lands with option A, this finding is resolved by the extraction. If F-20 lands with option B, the section markers alone resolve F-25.
- **Domain bundle candidate:** P-MANIFEST (synergy with F-20)

## Eliminated findings

| Source ID | Source audit | Reason for elimination |
|-----------|--------------|------------------------|
| AUDIT-PLAN-AND-API-B1-5 | plan-and-api | Out-of-scope — ADR-0003 §"Required mechanics" item 1 manifest snippet drift (`.pipeline.` vs `.core.`) is in the ADR text, not the B1 implementation. Recorded as OOS-1 in the "Out-of-scope deviations" table above; orchestrator may file a separate Issue against ADR-0003 (Decision-History append, append-only per `Status: Accepted`). |
| AUDIT-PLAN-AND-API-B1-6 | plan-and-api | False-positive (informational confirmation, no action). The 6th `onShowResend` site keeping `setVisibility(View.VISIBLE)` is well-documented as Deviation D1 with sound rationale (PipelineOrchestrator timing). Audit explicitly tagged "Suggested fix scope: none — confirmation only". |

(No other audit findings were eliminated. The remaining 21 findings from the four audits + 2 duplicate merges = 23 unique findings, all classified 🟢.)

## Stdout sign-off

```
Block 1 audit consolidation complete.
Validated: 🟢 23 (Critical: 0, Important: 9, Nice-to-have: 14). 🟡 0. Eliminated: 2 (1 out-of-scope, 1 false-positive informational).
Cross-cut patterns: 7 (P-LOCAL_BINDER, P-FGS-DEFENSIVE, P-RECORDING_UI, P-LOCALIZATION, P-NAMING, P-DOCS, P-MANIFEST, P-TEST-UTIL, P-TESTS).
Repair-wave recommendation: 1 wave (all 🟢, no 🟡).
Output: ./reports/validated-findings-B1.md
Phase complete — orchestrator decides routing.
```
