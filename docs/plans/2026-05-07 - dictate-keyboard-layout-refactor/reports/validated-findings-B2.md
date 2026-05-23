# Validated Findings — Block 2 (modular-orchestrator)

**Agent-ID:** B2-VAL-SANITY
**Date:** 2026-05-15
**Source audits:**
- `reports/audit-plan-and-api-B2.md` — 9 findings (0 Crit / 3 Imp / 6 NTH)
- `reports/audit-convention-B2.md` — 6 findings (0 Crit / 2 Imp / 4 NTH)
- `reports/audit-logic-B2.md` — 8 findings (1 Crit / 3 Imp / 4 NTH)
- `reports/audit-test-B2.md` — 4 findings (0 Crit / 1 Imp / 3 NTH)

**Total raw input:** 27 findings → post-dedup: 24 → validated 24 / eliminated 0.

Per D3 (fix every polish point): all severities are kept and routed
through repair waves.

## Repair-Wave Status (B2-VAL-REPAIR, 2026-05-15)

| Status | Count | Findings |
|---|---|---|
| ✅ fixed | 22 | F-1, F-2, F-3, F-4, F-5, F-6, F-7, F-8, F-9, F-10, F-12, F-13, F-15, F-16, F-17, F-18, F-19, F-20, F-21, F-22, F-23, F-24 |
| ⏸ postponed | 2 | F-11, F-14 (both NTH spec-file matrix edits — deferred to Phase 4.6 doc-pass) |

Sub-findings from `research/manual-paste-field-architecture.md` §"Sub-findings":
- SF-1 (NTH) ✅ fixed — ADR-0001 Decision-History entry appended.
- SF-2 (NTH) ✅ fixed — F-18 KDoc reference at `ResendAction.ClearManualPasteFlag` via the F-1 action-tree restructure.
- SF-3 (NTH) ✅ fixed — PipelineModule class-KDoc cleaned up via F-1 implementation.
- SF-4 (NTH) ⏸ postponed (B3-deferred) — PersistenceError post-text-extraction manual-paste signalling is recovery-path responsibility, not pipeline-reducer. Phase-1 acceptable.

See block-report §"Block-Validate Repair Wave 1 (B2-VAL-REPAIR)" for the per-finding fix log.

## Summary

- 🟢 valid + auto-fixable: **23** (Critical: 0, Important: 7, Nice-to-have: 16)
- 🟡 valid + research-needed: **1** (Critical: 1, Important: 0, Nice-to-have: 0)
- ❌ eliminated: **0**

The single 🟡 is `F-1` (B2-CRIT-MANUAL_PASTE) — the architectural call
on where `lastResultNeedsManualPaste` should live (relocate vs split
axis vs separate tiny module) needs documented reasoning before the
fix. Every other finding has a clear mechanical fix from the audit
suggestion.

## Cross-cut patterns

- **PipelineModule reducer + `lastResultNeedsManualPaste` field (F-1):**
  Two audits independently flagged the same dead-code issue from
  different angles — LOGIC classified Critical (functional regression
  for IME-service-death recovery), PLAN-AND-API classified Important
  (B3 forward-compat). Merged as F-1 with Critical severity (max-of-
  contributors rule). This is the **only** Critical finding in B2 and
  the **only** finding needing research.
- **`StopRecordingAndSend` cascade gap (F-2):** PLAN-AND-API-B2-2
  (Imp) + LOGIC-B2-6 (NTH) merge — same finding (Recording action's
  KDoc promises a cascade that doesn't exist). PLAN-AND-API's
  severity (Important) wins.
- **OverlayModule raw-string preferences (F-3):** CONVENTION-B2-2
  (Imp) is the primary finding; LOGIC out-of-scope observation (drift
  risk between `OverlayModule.runEffect` and `PipelinePrefMirror`
  string-literal constants) reinforces. Single finding, no dedup
  needed — the LOGIC note is consistent corroboration.
- **InterruptionModule "registered but stub" pattern (F-4 + F-13):**
  PLAN-AND-API-B2-3 (Imp — Spec §4.8 says "auskommentiert" but module
  is registered) plus CONVENTION-B2-5 (NTH — nullable sub-state
  precedent without documentation). Both point to a missing
  "Phase-stub patterns" section in `adding-a-module.md`. Kept as two
  findings because the fixes touch different artefacts (Spec text
  vs. architecture doc), but they're routed together (single repair
  agent can fix both).
- **Documentation drift across `### Deviations` tables:** Three
  separate findings (PLAN-AND-API-B2-1, -2, -3, -7, -8 — five out of
  nine PLAN-AND-API findings) consist of "this real implementation
  choice is not documented in the C5/C6/C7 deviations table."
  Systemic: the block had per-chunk deviation tables but no end-of-
  block reconciliation pass. Block-Validate Repair is the right
  place to consolidate.
- **Module-level KDoc conventions (F-11, F-12, F-13):** CONVENTION-
  B2-3 (import order), B2-4 (@see anchors), B2-5 (nullable-state
  precedent) all point to drift across the 14 module files. The
  remediation is partially mechanical (re-order imports, harmonise
  anchor sets) and partially documentation (a new section in
  `adding-a-module.md`). Domain-bundle candidate.
- **Test-side observability gaps (F-21..24):** AUDIT-TEST-B2-1..4 —
  none change production code, all add tests or tighten existing
  assertions. Clean domain-bundle for a final repair wave.

## Findings

### F-1 (was AUDIT-LOGIC-B2-1 + AUDIT-PLAN-AND-API-B2-1, merged)

- **Classification:** 🟡 valid + research-needed
- **Severity:** Critical
- **Files:**
  - `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:292-311` (reducer arms returning null)
  - `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt:73-80` (field declaration)
- **Description:** `Action.PipelineAction.NotifyResultNeedsManualPaste`
  and `Action.PipelineAction.ClearManualPasteFlag` reach the
  PipelineModule reducer but produce no observable state change. The
  reducer arms explicitly return `null`. The reducer's own KDoc admits
  the gap and refers to "out-of-band write via `_state.update` on
  PrefMirror init" — but **no such write exists** anywhere in the
  codebase (verified by `grep -rn lastResultNeedsManualPaste app/src/`
  — only the declaration, the axis-table KDoc, and tests).
  Consequence: the IME-service-death recovery user-affordance (Spec 1
  §11.6 + R.18 — "tell the user to paste from clipboard") is dead
  code today. B3 will dispatch `NotifyResultNeedsManualPaste` from
  the recovery path and the dispatch will silently resolve as a
  reducer no-op.
- **Why research-needed:** The fix requires an architectural call
  between three plausible options, none of which Spec 1 §3 / §15.1
  prescribes:
  1. **Relocate field into PipelineModule's owned axis.** The natural
     home is `PipelineUiState`, but it's a `sealed interface` —
     sealed-interface members can't carry shared fields, so this
     would force the flag into one specific sub-state (`Idle`?
     awkward — the flag is meaningful even when pipeline is back to
     Idle after the post-result signal) or add a new sub-state
     variant.
  2. **Extend PipelineModule's lens to encompass the top-level
     flag.** Today the lens is `(state) -> state.pipeline` /
     `(state, sub) -> state.copy(pipeline = sub)`. Extending it to
     a `Pair<PipelineUiState, Boolean>` or a wrapping data class
     adds Mode-1 capacity for the second field without violating
     ADR-0001 (still a same-axis write from the reducer's
     perspective).
  3. **Introduce a dedicated tiny module** (e.g.
     `ManualPasteModule`) owning a `data class ManualPasteState(val
     flag: Boolean)` axis. Cleanest separation of concerns but adds
     a 15th module for a single Boolean.

  Option (2) appears cleanest under ADR-0001's pure-reducer
  invariant (no Mode-3 violation since the lens itself carries both
  axes), but it changes the `DictateModule<S>` contract — the
  generic type parameter `S` becomes a tuple. Worth confirming
  against existing module signatures before committing. Option (1)
  is the most "natural" placement but constrained by Kotlin's
  sealed-interface limitation.
- **Suggested research topic:** `manual-paste-flag-ownership` —
  decide between options (1)/(2)/(3) above, document the trade-offs,
  and produce a concrete `### Deviations` entry + implementation
  diff. Research should also confirm no other module reads the
  top-level flag (`grep` already confirms only `DictateUiState.kt`
  declares it, tests in `DictateUiStateTest.kt` and
  `PipelinePrefMirrorTest.kt` reference it as an unmodified
  pass-through — i.e. no consumer in production code).
- **Why this issue is Critical not Important:** The LOGIC audit
  correctly classifies it as Critical. The PLAN-AND-API audit
  classifies it as Important (forward-compat for B3). Max-of-
  contributors rule applies — but the actual reasoning is that the
  dead reducer arms ship today, and B3's first dispatch attempt
  will fail silently. That's a functional regression, not just a
  forward-compat gap.

### F-2 (was AUDIT-PLAN-AND-API-B2-2 + AUDIT-LOGIC-B2-6, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt:184-205` (reducer arm) + `:344-353` (cross-module observer)
- **Description:** `Action.RecordingAction.StopRecordingAndSend`'s
  KDoc promises a cascade emit of `PipelineAction.TriggerPipeline` on
  `Active/Paused → Idle` boundary. The reducer at line 184-205
  collapses `StopRecording` and `StopRecordingAndSend` into the same
  `TransitionResult`, and the cross-module observer at line 344-353
  emits only `OverlayAction.ResetSuppressBit` on `Idle → Preparing`.
  Result: `StopRecordingAndSend` is **functionally identical** to
  `StopRecording` — the "Send" semantic is lost.
- **Suggested fix:** Add a `sendOnStop: Boolean` parameter to
  `RecordingState.Active` / `RecordingState.Paused` (Mode-1 same-axis
  write — both fields owned by RecordingModule). Set to `true` on
  the `StopRecordingAndSend` reducer path immediately before the
  Active→Idle transition (intermediate state). Then in
  `onCrossModuleStateChange`, add an arm:
  ```kotlin
  if ((prev.recording is Active && prev.recording.sendOnStop ||
       prev.recording is Paused && prev.recording.sendOnStop) &&
      next.recording is Idle) {
      cascade += Action.PipelineAction.TriggerPipeline(/* … */)
  }
  ```
  Alternative (simpler but less type-safe): a separate sub-state
  variant `RecordingState.StoppingForSend` that fires the cascade.
  The implementer-agent should pick whichever fits the existing
  reducer style best.
- **Domain bundle candidate:** RecordingModule.kt (sole touched
  file).

### F-3 (was AUDIT-PLAN-AND-API-B2-3 + CONVENTION-B2-5, merged)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:**
  - `app/src/main/java/net/devemperor/dictate/state/modules/InterruptionModule.kt:42` (nullable-state declaration)
  - `app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt:148-152` (registration site)
  - `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md` Spec 1 §4.8 (stale "auskommentiert bis aktiv" comment)
  - `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/B2-modular-orchestrator.md` C6 Deviations table
  - `docs/architecture/state-architecture/adding-a-module.md` (new "Phase-stub patterns" section)
- **Description:** Spec 1 §4.8 says the InterruptionModule is
  "auskommentiert bis aktiv" but the implementation registers it
  (necessarily — `assertCompleteCoverage()` would throw if no module
  claimed `Action.InterruptionAction`). InterruptionModule uses a
  nullable sub-state type (`InterruptionState?`) which is the **only**
  module to do so — setting an undocumented precedent for future
  Phase-2 modules vs the alternative pattern (non-nullable state +
  reducer-returns-null, used by `LanguageModule.RefreshFromPref`,
  `FeatureToggleModule.ToggleVibration`, etc.).
- **Suggested fix:**
  1. C6 Deviations table: add a row "InterruptionModule registered as
     stub despite Spec §4.8 comment-out — `assertCompleteCoverage`
     forces registration. Reducer rejects all 3 actions, sub-state is
     `null` in Phase 1."
  2. Spec 1 §4.8: replace "auskommentiert bis aktiv" with "Phase-1
     stub-registered to satisfy `assertCompleteCoverage`; reducer
     rejects all actions until B3 / Phase 2 promotes it."
  3. `architecture/state-architecture/adding-a-module.md`: add a
     "Phase-stub patterns" sub-section documenting the two options
     (nullable-state for unknown-shape modules; non-nullable +
     reducer-returns-null for known-shape deferred-behaviour
     modules). InterruptionModule is the canonical example of the
     former.
- **Domain bundle candidate:** documentation-only (no code change).

### F-4 (was AUDIT-CONVENTION-B2-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:**
  - `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` (add 6 entries)
  - `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:158-172` (replace 6 raw-string accesses)
  - `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt` (replace the 4 named-constant `OVERLAY_POS_*_KEY` accesses to use the new `Pref` entries; keep the constants as aliases or remove them)
- **Description:** `OverlayModule.runEffect` writes to 6
  `SharedPreferences` keys using raw string literals:
  `overlay_pos_portrait_x/y`, `overlay_pos_landscape_x/y`,
  `overlay_onboarding_shown`, `overlay_onboarding_dismissed`. Project
  convention (`CLAUDE.md`): "Preferences are always accessed through
  `DictatePrefs.kt` sealed class — never use raw string keys."
  PipelinePrefMirror uses named constants for 4 of the 6, but they
  too are raw string keys (not `Pref` entries). The two sites must
  stay in lockstep; today a rename in one breaks the mirror silently.
- **Suggested fix:** Add 6 entries to `Pref` sealed class in
  `DictatePrefs.kt`:
  - `Pref.OverlayPositionPortraitX : Pref<Float>(defaultValue = 1.0f)`
  - `Pref.OverlayPositionPortraitY : Pref<Float>(defaultValue = 0.1f)`
  - `Pref.OverlayPositionLandscapeX : Pref<Float>(defaultValue = 1.0f)`
  - `Pref.OverlayPositionLandscapeY : Pref<Float>(defaultValue = 0.1f)`
  - `Pref.OverlayOnboardingShown : Pref<Boolean>(defaultValue = false)`
  - `Pref.OverlayOnboardingDismissed : Pref<Boolean>(defaultValue = false)`

  (Defaults match the current `OverlayState` defaults.) Then replace
  raw-string accesses in `OverlayModule.runEffect` with `sp.put(Pref.OverlayXxx, value)`
  / `sp.get(Pref.OverlayXxx)`. Replace the 4
  `OVERLAY_POS_*_KEY` constant usages in `PipelinePrefMirror` with
  the typed `Pref` entries. The 2 onboarding keys gain typed access
  for the first time.
- **Domain bundle candidate:** Yes — `DictatePrefs.kt` + `OverlayModule.kt` + `PipelinePrefMirror.kt` together.

### F-5 (was AUDIT-LOGIC-B2-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt:65-102`
- **Description:** The `private var store: DictateUiStateStore?`
  field is mutated from the Main thread (in `attach`/`detach`) but
  read from arbitrary threads in `sync(key)` (Android's
  `OnSharedPreferenceChangeListener` fires on the thread that called
  `apply()`/`commit()` — typically a background disk thread). Without
  `@Volatile` or a similar publication barrier, the JVM memory model
  does not guarantee `detach()`'s `null` write is visible to a
  concurrent reader.
- **Suggested fix:** Annotate the field with `@Volatile`:
  ```kotlin
  @Volatile private var store: DictateUiStateStore? = null
  ```
  No other code change needed — the existing `targetStore = store ?: return`
  read-once-into-local pattern in `sync()` is already correct under
  the `@Volatile` guarantee.
- **Domain bundle candidate:** PipelinePrefMirror.kt (sole touched
  file).

### F-6 (was AUDIT-LOGIC-B2-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:**
  - `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt:57-60`
  - `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt:158-162` (init block call site)
- **Description:** `scope.launch { recovery.recover(store) }` has no
  `try/catch`. `recover()` is a suspend fun that calls
  `sessionRepo.loadPending()` (Dispatchers.IO in B3) and may throw
  `SQLiteException` / `IOException`. The `serviceScope` uses
  `SupervisorJob()`; without a `CoroutineExceptionHandler`, throws
  are silently swallowed. The service still boots but
  `pendingSessions` stays empty + no logcat error tag identifies the
  failure.
- **Suggested fix:** Wrap the suspend call inside
  `PipelineRecovery.recover` (cleaner — the class owns its own
  failure contract):
  ```kotlin
  suspend fun recover(store: DictateUiStateStore) {
      try {
          val pending = sessionRepo.loadPending()
          store.update { it.copy(pendingSessions = pending.toPersistentList()) }
      } catch (t: Throwable) {
          Log.e("PipelineRecovery", "Recovery failed", t)
      }
  }
  ```
  Same convention as `DictateOrchestrator.dispatchInternal` step 4
  (line 327-337) for `runEffect` failures.
- **Domain bundle candidate:** PipelineRecovery.kt (sole touched file).

### F-7 (was AUDIT-LOGIC-B2-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt:218-228`
- **Description:** The HOVER→KEYBOARD cancel-cascade chooses between
  `RecordingAction.CancelRecording` and `PipelineAction.CancelPipeline`
  via `if/else if/else`. The both-in-flight case (Recording Active +
  Pipeline Preparing/Running simultaneously) emits only one
  cancellation; the other axis continues. Result: a user closes the
  HOVER overlay during the brief Send-cascade window → recording is
  cancelled but the pipeline keeps running, producing a transcript
  the user opted out of.
- **Suggested fix:** Change `when`/`if-else if` to an additive list:
  ```kotlin
  val cascade = mutableListOf<Action>()
  if (next.recording.isActiveOrPaused || next.recording is RecordingState.Preparing) {
      cascade += Action.RecordingAction.CancelRecording
  }
  if (next.pipeline !is PipelineUiState.Idle) {
      cascade += Action.PipelineAction.CancelPipeline(sessionId = null)
  }
  return cascade
  ```
  The orchestrator dispatches the list serially at depth+1 with
  re-snapshotting, so each cancellation sees the previous one's
  effect. Spec C-3 priority "Recording > Pipeline" is preserved by
  list-order.
- **Domain bundle candidate:** OverlayModule.kt (sole touched file).

### F-8 (was AUDIT-CONVENTION-B2-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **Files:**
  - `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt:48-124` (class KDoc)
  - `docs/architecture/state-architecture/README.md` (mirroring sentence)
- **Description:** New `DictateOrchestrator` (state-action-router)
  shares the "Orchestrator" name root with legacy
  `core/PipelineOrchestrator.kt` (audio-pipeline runner). The new
  class's KDoc has no naming disambiguation. Both types co-exist
  during the B2 → B3 migration window.
- **Suggested fix:** Add to `DictateOrchestrator` class KDoc after
  "Composition root of the state-mutation pipeline.":
  > **Note on naming.** This is the **state-action-router**
  > introduced by ADR-0001. The legacy
  > `net.devemperor.dictate.core.PipelineOrchestrator` is the
  > **audio-pipeline runner** (transcription/completion + DAO
  > writes); the two are unrelated and co-exist during the Block 2
  > → Block 3 migration.

  Same content in `architecture/state-architecture/README.md`.
- **Domain bundle candidate:** Documentation-only.

### F-9 (was AUDIT-TEST-B2-1)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Important
- **File:** `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt:349`
- **Description:**
  `onDestroy_runsOrchestratorShutdown_beforeScopeCancellation`
  self-admits "We can't intercept the order from the outside" and
  only asserts non-throw. The order-of-operations claim is
  unverifiable from this test alone.
- **Suggested fix:** Inject an `orchestratorShutdownObserver` test
  hook (e.g., a counter or list in the test's `FakeOrchestrator`)
  that records the timestamp/sequence-position of the
  `shutdown()` call relative to `serviceScope.cancel()`. Assert
  ordering with `assertTrue(orchestratorShutdownTimestamp <
  scopeCancellationTimestamp)` or
  `assertEquals(listOf("shutdown", "scope-cancel"), order)`.

  Alternative (simpler): pass a `CompletableDeferred<Long>` to the
  fake; complete it in `shutdown()`. In the test, register a
  `serviceScope.coroutineContext[Job]!!.invokeOnCompletion`
  callback that records its own timestamp. Assert
  `shutdownTimestamp < scopeCancellationTimestamp`.
- **Domain bundle candidate:** Test infrastructure (touches the
  Robolectric service-test only).

### F-10 (was AUDIT-PLAN-AND-API-B2-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/B2-modular-orchestrator.md` C5 Deviations table
- **Description:** Spec 3 §4.8 lists
  `OverlayModule.Effect.NotifyOverlayPermissionRequired` and a
  `services.notifications.showPermissionRequired()` runEffect arm.
  The implementation omits the Effect entirely; the permission-loss
  cross-module observer at OverlayModule emits only
  `SetViewMode(KEYBOARD)`. The deviation is not documented in C5.
- **Suggested fix:** Add a row to C5 Deviations table:
  > Spec 3 §4.8 prescribes `Effect.NotifyOverlayPermissionRequired`
  > + `services.notifications.showPermissionRequired()`. Phase-1
  > simplification: cross-module observer at permission-loss emits
  > `SetViewMode(KEYBOARD)` only. B5 (Overlay subsystem) adds the
  > Effect + a `NotificationStatus.PermissionRequired` variant or
  > a dedicated `permissionNotifier` subsystem.
- **Domain bundle candidate:** Documentation-only (deviation table).

### F-11 (was AUDIT-PLAN-AND-API-B2-5)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md` Spec 1 §15.1.x matrix
- **Description:** Spec §15.1 column "Cross-Module-Observer?" marks 9
  modules as observer-free; Spec §15.1.x matrix lists arrows from 8
  of those 9 modules. The matrix and the column are internally
  inconsistent. Plus several action leaves cited by the matrix
  (`Action.ViewModeAction.OnRecordingActive`,
  `Action.LayoutAction.OnViewModeChanged`,
  `Action.OverlayAction.OnViewModeChanged`,
  `Action.PendingSessionsAction.Insert`) don't exist.
- **Suggested fix:** Add caption to §15.1.x matrix:
  > **Phase-1 scope:** rows for the 5 core modules (Recording /
  > Pipeline / Audio / ViewMode / Overlay — see §15.1 column
  > "Cross-Module-Observer?") are implemented. Rows for the 8
  > auxiliary modules are forward-compat / Phase-2; the cells
  > reference action leaves that do not exist in Phase-1
  > `Action.kt`. Re-evaluated per Phase-2 module that promotes.
- **Domain bundle candidate:** Spec documentation.

### F-12 (was AUDIT-PLAN-AND-API-B2-6)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/B2-modular-orchestrator.md` Issue Index (IMPL-1 row)
- **Description:** IMPL-1's audit-trail is split across three voices
  (B1 carry-over, C4 unblock-but-still-deferred, C7 re-deferred to
  C8 with D5 reasoning). A reviewer searching for "IMPL-1" gets
  three slightly different framings of the same conclusion.
- **Suggested fix:** Consolidate the Issue-Index entry's reason
  column to a single paragraph that lists both the unblock + the
  re-defer rationale. Keep C4 + C7's local "IMPL-1 status update"
  sub-sections as pointers to the Index entry rather than as
  duplicated rationales.
- **Domain bundle candidate:** Documentation-only (block report).

### F-13 (was AUDIT-PLAN-AND-API-B2-7)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:**
  - `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/reports/B2-modular-orchestrator.md` C4 Deviations table
- **Description:** Spec 1 §4.3 / §4.7 / §7.3 prescribes a two-class
  pattern: `ModuleServices` (data) + `ModuleServicesFactory(provider:
  () -> ModuleServices)` (lazy provider). Implementation collapses
  to a single direct `services: ModuleServices` constructor argument
  (sensible — factory adds indirection without observable benefit in
  Phase 1). C4 deviations table doesn't document this.
- **Suggested fix:** Add row to C4 Deviations table:
  > `ModuleServicesFactory` collapsed into direct `ModuleServices`
  > constructor argument. Justification: only one construction
  > point per service lifetime (`Service.onCreate`), factory
  > indirection unused. Impact: B3 wiring uses `ModuleServices(...)`
  > directly in `onCreate`; no factory bootstrap step.
- **Domain bundle candidate:** Documentation-only.

### F-14 (was AUDIT-PLAN-AND-API-B2-8)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:**
  - `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md` Spec 1 §15.1.x matrix
- **Description:** Spec 1 §15.1.x matrix cells reference
  `PipelineAction.Submit`; the implementation uses
  `PipelineAction.TriggerPipeline`. Three spec sites use `Submit`;
  one (KDoc on `TriggerPipeline`) uses `TriggerPipeline`.
- **Suggested fix:** Update Spec 1 §15.1.x matrix cells from
  `PipelineAction.Submit` to `PipelineAction.TriggerPipeline`.
  Add a one-line note: "Naming reconciled C5 — was `Submit` in
  earlier draft."
- **Domain bundle candidate:** Spec documentation.

### F-15 (was AUDIT-PLAN-AND-API-B2-9)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt:189-197`
- **Description:** The file is named `*StubSubsystems.kt` and its
  top-level KDoc says "Production-side **no-op stub subsystems**"
  but it also contains `realToastSink(applicationContext: Context):
  ToastSink` — a production-quality binding to the Android Toast
  system. Mixed-concern file.
- **Suggested fix:** Update the file-level KDoc to acknowledge the
  mixed nature explicitly:
  > **Note: file contains both stub and production bindings.** The
  > `stub*Subsystem` functions are no-op placeholders that B3
  > replaces with real adapters. `realToastSink` is a
  > production-quality binding that ships in Phase 1 because user-
  > visible error toasts are needed before B3's full adapter
  > swap. Keep `realToastSink` here (or move to a future
  > `PipelineServiceProductionSubsystems.kt` when more
  > production-quality bindings join it).

  (Renaming the file or splitting is also valid — keep this Phase-1
  light and let B3 decide if a split is warranted when more
  production bindings accumulate.)
- **Domain bundle candidate:** Documentation-only.

### F-16 (was AUDIT-CONVENTION-B2-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:**
  - `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:8-10`
  - `app/src/main/java/net/devemperor/dictate/state/modules/PendingSessionsModule.kt:7-11`
  - `app/src/main/java/net/devemperor/dictate/state/modules/LayoutModule.kt:7-8`
  - `docs/architecture/state-architecture/adding-a-module.md` (convention line)
- **Description:** Import order is non-uniform across the 14 module
  files. Three modules show interleaved or non-alphabetical orders;
  the rest are trivially fine.
- **Suggested fix:** Run IntelliJ's "Optimize Imports" pass across
  the three modules (PipelineModule, PendingSessionsModule,
  LayoutModule) to enforce a single alphabetical block by FQN. Add
  a line to `architecture/state-architecture/adding-a-module.md`:
  > Imports sorted alphabetically as a single block — IDE default
  > ("Optimize Imports") is the source of truth.
- **Domain bundle candidate:** Module file housekeeping.

### F-17 (was AUDIT-CONVENTION-B2-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:**
  - All 14 module files in `app/src/main/java/net/devemperor/dictate/state/modules/`
  - `docs/architecture/state-architecture/adding-a-module.md` (anchor convention)
- **Description:** `@see` anchor counts vary across modules (2–7
  anchors), with inconsistent ordering. RecordingModule has 7;
  KeyboardInputModule has 2; PipelineModule has 4 but its sub-state
  type `PipelineUiState` is not in the `@see` list.
- **Suggested fix:** Add to
  `architecture/state-architecture/adding-a-module.md` a "minimum
  anchor set" specification:
  > Every module's class KDoc carries at least:
  > (a) `@see` for its `XxxState` / sub-state type,
  > (b) `@see` for its `Action.XxxAction`,
  > (c) `@see` for the spec `§15.x` and any binding ADR (ADR-0001
  >     always; ADR-0002 if it emits cross-module cascades).

  Then bring the leaner modules (KeyboardInputModule + others with
  <3 anchors) up to the baseline. Add `@see PipelineUiState` to
  PipelineModule's KDoc. RecordingModule's richer pattern stays —
  the convention is a floor, not a ceiling.
- **Domain bundle candidate:** Module KDoc housekeeping (touches
  most module files).

### F-18 (was AUDIT-CONVENTION-B2-6)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/Action.kt`
- **Description:** `data object` Action variants are inconsistently
  documented. Most plain-name leaves (`StartRecording`,
  `StopRecording`) are obvious-by-name and don't need KDoc; but
  semantically-special leaves (`ToggleVibration` deviation case,
  `MarkLastAudio` cascade target, `OnPipelineDone` /
  `ResendCooldownExpired` / `ClearManualPasteFlag`) have non-obvious
  semantics that the action-level should carry.
- **Suggested fix:** Add 1-line KDoc to ~5-8 `data object` leaves
  that have non-obvious semantics:
  - `FeatureToggleAction.ToggleVibration` — note the
    deviation-from-other-toggles status.
  - `LanguageAction.RefreshFromPref` — "Phase-1 stub: reducer
    returns null (legacy LanguageController owns SP read)."
  - `ResendAction.MarkLastAudio` — "Cross-module cascade target —
    emitted after PipelineDone."
  - `PipelineAction.OnPipelineDone` — "Cross-module fan-out
    trigger; reducer returns null (no state change)."
  - `ResendAction.ResendCooldownExpired` — "Internal scheduler-fired
    action; clears cooldown."
  - `PipelineAction.ClearManualPasteFlag` — "Clears
    `lastResultNeedsManualPaste`; see F-1 for the routing question."
- **Domain bundle candidate:** Action.kt (sole touched file).

### F-19 (was AUDIT-LOGIC-B2-5)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:233-256`
- **Description:** `SendStaging` reducer emits `Effect.SubmitReprocess`
  with `audioFile = File("")` (empty-path placeholder) and `queue =
  emptyList()`. The contract that "empty path means look-up by
  sessionId in the DB" is enforced only by KDoc + a future B3
  implementer reading the comment.
- **Suggested fix:** Make the contract explicit at the type level —
  change `Effect.SubmitReprocess.audioFile: File` to
  `audioFile: File? = null` with a KDoc that documents the contract:
  > `null` means "runner resolves the path by `sessionId`-lookup in
  > the DB session record." Pass `File("")` is forbidden — use
  > `null`. Phase-1 SendStaging emits with `null` since the staged
  > session record is the authoritative path source.

  Alternative (smaller change): keep `File` typed, document the
  empty-string convention explicitly in the KDoc, and add a
  `require(file.path.isEmpty() || file.exists())` debug assertion
  in the runner contract. The implementer-agent picks per ADR-0001
  preference for type-encoded invariants.
- **Domain bundle candidate:** PipelineModule.kt (sole touched file).

### F-20 (was AUDIT-LOGIC-B2-7)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/LayoutModule.kt:116-128`
- **Description:** `SetContentArea` silently returns `null` when
  `state.smallMode && action.area != ContentArea.MAIN_BUTTONS`. The
  silent rejection masks resolver-author bugs where the gate is
  forgotten.
- **Suggested fix:** Add a `Log.w` or a comment-level diagnostic:
  ```kotlin
  // Design: small-mode + non-MAIN_BUTTONS is structurally forbidden
  // (KSM-bug fix, Issue 1.1.5). Resolver path MUST gate on
  // state.smallMode before dispatch. Silent reject here for safety.
  Log.w("LayoutModule", "SetContentArea(${action.area}) rejected in small-mode")
  null
  ```
  Or, for a stricter contract: emit an `Effect.LayoutModeViolation`
  (new Effect) that the IME-side observer logs structurally. The
  implementer-agent picks the simpler form first (Log.w + comment)
  and only escalates to a structured Effect if other modules need
  the same pattern.
- **Domain bundle candidate:** LayoutModule.kt (sole touched file).

### F-21 (was AUDIT-LOGIC-B2-8)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt:355-400` (cross-module observer)
- **Description:** Two related observations:
  1. The KDoc lists `Pipeline → Recording` cascade as part of the
     Coupling-Matrix but the observer body has a long comment
     acknowledging "Phase 1 keeps the cascade as a no-op." Reader
     mismatch: KDoc cell says implemented, code says deferred.
  2. `MarkLastAudio(exists = true)` is hard-coded `true` regardless
     of whether Pipeline-Done was success vs cancel. The cancel-path
     deletion of the audio file isn't reflected in the
     ResendModule's marker.
- **Suggested fix:**
  1. KDoc: change the `Pipeline → Recording` row in the
     Coupling-Matrix listing to "Phase-2 (deferred no-op)" with a
     pointer to the inline comment.
  2. Observer body: add a Phase-1 KDoc-note that the
     `MarkLastAudio(exists = true)` flag assumes success-path
     completion. Document the Phase-2 plan: when the cancel-path
     gains a "file deleted" signal (Phase-2 cancel-cascade), the
     observer emits `MarkLastAudio(exists = false)` instead.

  Don't change behaviour today — the cancel-path file-deletion is
  not yet implemented (Phase-2). Just align KDoc + comment with
  reality.
- **Domain bundle candidate:** PipelineModule.kt.

### F-22 (was AUDIT-TEST-B2-2)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **Files:**
  - `app/src/test/java/net/devemperor/dictate/testutil/FakePipelineSessionRepo.kt` (new file)
  - `app/src/test/java/net/devemperor/dictate/state/PipelineRecoveryTest.kt:23`
  - `app/src/test/java/net/devemperor/dictate/state/DictateOrchestratorInitOrderTest.kt:40, 57`
- **Description:** Two inline `PipelineSessionRepoSubsystem` fakes
  duplicate each other (one in PipelineRecoveryTest, one in
  DictateOrchestratorInitOrderTest), plus an anonymous one. Lift to
  a shared testutil fake.
- **Suggested fix:** Create
  `testutil/FakePipelineSessionRepo.kt` with a default
  `emptyList()` for `loadPending()` plus configurable per-test
  overrides (e.g., constructor parameter for the pending-list
  result). Replace the inline definitions with `FakePipelineSessionRepo(...)`.
- **Domain bundle candidate:** Test infrastructure.

### F-23 (was AUDIT-TEST-B2-3)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt:276`
- **Description:**
  `localBinderState_exposesOrchestratorStateFlow` only asserts
  `assertNotNull(snapshot)` — weak smoke check. Tighten to
  substantive equality:
  `assertEquals(DictateUiState.initial(), snapshot)`.
- **Suggested fix:** Replace the `assertNotNull` with
  `assertEquals(DictateUiState.initial(), snapshot)`. If
  `initial()` is parameterised or has SP-dependent defaults, use a
  controlled `FakeSharedPreferences` to make the expected state
  deterministic.
- **Domain bundle candidate:** Test infrastructure.

### F-24 (was AUDIT-TEST-B2-4)

- **Classification:** 🟢 valid + auto-fixable
- **Severity:** Nice-to-have
- **File:** `app/src/test/java/net/devemperor/dictate/state/PipelineModuleTest.kt`
- **Description:** Negative-cascade test for `Preparing → Running`
  is missing. Existing tests cover `Idle → Idle no cascade` and
  `Idle → Preparing no cascade-OnPipelineDone`, but the
  `Preparing → Running` boundary (which also must NOT emit
  OnPipelineDone) is uncovered.
- **Suggested fix:** Add a test:
  ```kotlin
  @Test
  fun `cross-module Preparing to Running does NOT cascade OnPipelineDone`() {
      val prev = DictateUiState.initial().copy(pipeline = PipelineUiState.Preparing("s"))
      val next = DictateUiState.initial().copy(pipeline = PipelineUiState.Running("s", InsertionTarget.Direct))
      val cascade = PipelineModule.onCrossModuleStateChange(prev, next)
      assertTrue(cascade.none { it is Action.PipelineAction.OnPipelineDone })
  }
  ```
- **Domain bundle candidate:** Test infrastructure.

## Eliminated findings

| Source ID | Source audit | Reason for elimination |
|-----------|--------------|------------------------|

(none — every finding is valid per the audits' detailed analysis and
the cross-checks against the production code.)

## Repair-wave recommendation

Recommended sequence per D3 (fix-every-polish-point) + the 🟡-vs-🟢
split:

**Wave 1 — Research-then-fix for the single 🟡 (F-1):**
- Spawn `B2-VAL-RES-1` with topic `manual-paste-flag-ownership`.
- Research produces `./research/manual-paste-flag-ownership.md` with
  the three options' trade-offs and a recommendation (likely option
  2 — extended lens — but the research call confirms it against
  ADR-0001 + the `DictateModule<S>` generic signature).
- Resume `B2-VAL-RES-1` as `B2-VAL-REPAIR-1` to apply the fix.

**Wave 2 — Mechanical 🟢 fixes, single repair-agent:**
The remaining 23 findings are mechanical or documentation-only.
Suggested batch order (low-risk → higher-touch):
1. **Code-quality fixes (Important):** F-5 (@Volatile), F-6
   (try/catch in PipelineRecovery), F-7 (OverlayModule cancel-
   cascade priority).
2. **Convention fixes (Important):** F-4 (Pref entries for
   OverlayModule), F-2 (StopRecordingAndSend cascade), F-3
   (Interruption stub-pattern docs), F-8 (DictateOrchestrator
   disambiguation KDoc).
3. **Test fixes (Important):** F-9 (orchestratorShutdown order
   probe).
4. **Documentation (NTH):** F-10..F-15 (deviation tables, spec
   matrix, file KDoc).
5. **Module-file housekeeping (NTH):** F-16..F-18, F-19..F-21.
6. **Test infrastructure (NTH):** F-22..F-24.

**Estimated repair waves: 2.** If F-1 research is quick (under 30
minutes) it can bundle with Wave 2 to make it 1 wave — but the
architectural call deserves a dedicated research document for
future readers.

## Output sign-off

```
Block 2 audit consolidation complete.
Validated: 🟢 23 (0 Crit / 7 Imp / 16 NTH), 🟡 1 (1 Crit). Eliminated: 0.
Cross-cut patterns: 7 (manual-paste field, StopRecordingAndSend
cascade, OverlayModule raw prefs, InterruptionModule stub-pattern,
deviation-table drift, module-KDoc drift, test-side gaps).
Output: ./reports/validated-findings-B2.md
Phase complete — orchestrator decides routing.
```
