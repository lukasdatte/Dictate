# Block 2: Modular-Orchestrator-Implementation (plan-Block-1b — DictateUiState + 13 Modules)

> **This file is the logbook for Block 2.** Implementation-Agents
> and Audit-Agents document their work here. The orchestrator
> maintains the status table in the main state file
> (`../dictate-keyboard-layout-refactor.state.md`) — agents do **not** write to the
> state file.

**Phase:** Modular-Orchestrator-Implementation (the architectural core)
**Implementation-Chunks:** C3-state-core (M, 850 score) · C4-orchestrator-and-registry (M, 650 score) · C5-modules-core (L, 1150 score) · C6-modules-auxiliary (L, 1100 score) · C7-prefmirror-recovery-wiring (M, 600 score)
**Workflow:** Iter-10 5-step workflow with orchestrator-split commits per chunk. Block runs C3 → C4 → C5 → C6 → C7 sequentially.
**Block-Start-Commit:** `d0dffd9`
**Block-End-Commit:** ⏳ (set by orchestrator at block completion)

---

## Issue Index (Orchestrator-Maintained)

Single overview of every issue in this block — populated as the block progresses.

**Severity counts:**
- Critical: 0
- Important: 1
- Nice-to-have: 2
- Postponed: 0

**By status:**

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| IMPL-1 (B1 carry-over) | B1-C2-IMPL-FULL | Important | delegated-to-orchestrator (re-deferred to Block 3 / C8) | Spec 1 §11.2.2 Block-2 sub-step 7: JobExecutor-Init move from IME `onCreate` to Service `onCreate`. **Consolidated rationale (F-12 2026-05-15):** unblocked by C4 (the new `DictateOrchestrator` exists at the service composition root); re-deferred at C7 per D5 because the actual JobExecutor move requires the Service to construct the **legacy** `PipelineOrchestrator` (12-arg constructor with `AIOrchestrator`, `AutoFormattingService`, `PromptQueueManager`, `SessionManager`, `SessionTracker`, IME-implemented `PipelineCallback`, …) — those subsystems are IME-scoped today and rewriting their construction into the service is Block 3 (subsystem-adapter migration, chunk C8) scope. C7 wires the **new** `DictateOrchestrator` into the service; the legacy `PipelineOrchestrator` stays IME-owned until C8 absorbs it as a `PipelineRunnerSubsystem` adapter. Local C4 + C7 sub-sections ("IMPL-1 status update") below point back to this Index entry for the canonical rationale. | C7 scope per IMPL-1 brief; re-deferred per D5 (subsystem-impls not in C7 scope). Sub-section pointers preserved for blame-trail. |
| IMPL-2 | B2-C5-IMPL-FULL | Nice-to-have | open | `OverlayModule.Effect.DeleteAudioFile` is defined but never emitted (cancel-cascade routes file-delete through RecordingModule). | C5-modules-core |
| IMPL-3 | B2-C5-IMPL-FULL | Nice-to-have | open | `PipelineModule.runEffect` uses `services.scope.launch` for the suspend `sessionRepo.markInserted/markFailed` DB call (per `ModuleServices.scope` KDoc — acceptable fire-and-forget pattern). | C5-modules-core |

---

## Conventions established this block

| Convention | Where established | Description |
|------------|-------------------|-------------|

---

## Mandatory Format Reminder for All Agents

Shared sub-agent directives (issue handling, status schema, stdout
convention, research-file output, plan-deviation autonomy) live in
`prompts/agent-prompts.md` — read it before starting your task.

### Deviation Format

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Inline-fixed? |
|-----------|---------------|--------------|-----|------------------------|----------------|

### Issue Format

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|

---

## Implementation Logs

### Chunk C3-state-core — DictateUiState + Store + DictateModule interface + Action hierarchy

**Agent-IDs:** Steps 1-5 (combined): `B2-C3-IMPL-FULL`

**Status:** ✅ done (pending orchestrator commits)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 4 (C3-state-core)
**Implementation-Commit:** ⏳ (Commit 1 — production code)
**Test-Commit:** ⏳ (Commit 2 — tests)

#### Implementation (B2-C3-IMPL)

**What was done:** Created the foundation type-layer for the modular orchestrator
in `app/src/main/java/net/devemperor/dictate/state/`:

- `DictateUiState.kt` — top-level `data class` + 13 sub-state types
  (`RecordingState`, `PipelineUiState`, `ViewMode`, `LayoutState`,
  `OverlayState`, `AudioState`, `BluetoothScoPublicState`, `ScoPhase`,
  `ResendState`, `LivePromptState`, `LanguageState`, `FeatureToggles`,
  `ThemingState`, `InterruptionState`, `PendingSession`) +
  `lastResultNeedsManualPaste` top-level flag.
- `Action.kt` — sealed root with 13 module-scoped inner sealed classes
  (`RecordingAction`, `PipelineAction`, `ViewModeAction`, `LayoutAction`,
  `AudioAction`, `ResendAction`, `LivePromptAction`, `LanguageAction`,
  `OverlayAction`, `FeatureToggleAction`, `PendingSessionsAction`,
  `KeyboardInputAction`, `InterruptionAction`) + top-level
  `Action.EffectFailure(originModuleId, effect, reason)`.
- `DictateModule.kt` — `sealed interface DictateModule<S, A : Action, E : SideEffect>`
  with all 9 contractual hooks (`id`, `actionClass`, `read`/`write`/`initialState`,
  `reduce`, `reduceFailure`, `runEffect`, `onCrossModuleStateChange`,
  `prefBindings`, `terminate`). Default-implementations on optional hooks.
- `DictateUiStateStore.kt` — `MutableStateFlow<DictateUiState>` SSoT
  container with `state`, `snapshot`, and atomic `update(reducer)`.
- `TransitionResult.kt` — `TransitionResult<S, E : SideEffect>` + `ReducerContext`.
- `SideEffect.kt` — marker interface.
- `ModuleId.kt` — sealed interface with 14 `data object` leaves.
- `ModuleServices.kt` — DI-container skeleton (C4 fills concrete fields)
  + `PrefBinding<S, T>`.
- `InsertionTarget.kt` — enum (INPUT_CONNECTION, REPROCESS_STAGING).
- `gradle/libs.versions.toml` + `app/build.gradle` — added
  `kotlinx-collections-immutable` (0.3.7) for `PersistentList<PendingSession>`
  per Spec 1 §3 F-9.

All KDocs reference the canonical Spec sections, ADR-0001 / ADR-0002,
and the architecture-doc paths.

**Plan deviations:** none.

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Inline-fixed? |
|-----------|---------------|--------------|-----|------------------------|----------------|
| (none) | — | — | — | — | — |

**Issues:**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| (none) | — | — | — | — |

**Inline-fixed items:** none — clean greenfield additions.

**Overlooked points / known gaps:**

- `ModuleServices` is a skeleton class only — C4 (`orchestrator-and-registry`)
  populates the concrete subsystem fields (`RecordingHardwareSubsystem`,
  `BluetoothScoSubsystem`, `AudioFileFactory`, `scope`, `emitAction`, …).
  Documented in the class KDoc.
- The chunk-prompt mentioned a `Store.kt` interface separate from
  `DictateUiStateStore`. Spec 1 §4.4 only defines the concrete
  `DictateUiStateStore`; there is no separate `Store` interface in the
  spec. The combined `DictateUiStateStore` + (C4) `DictateOrchestrator.dispatch`
  covers the chunk-prompt's intent. No production code currently consumes
  a `Store`-interface abstraction, so adding one would be YAGNI.

#### Plan-Correctness Fix (B2-C3-IMPL-PLAN-FIX)

Re-read Spec 1 §3 + §4.2 + §4.4 + §15 and ADR-0001 / ADR-0002. All 13
sub-state axes and 14 modules' action sealeds match the spec; the
`Unit`-state `KeyboardInputModule` is represented as
`Action.KeyboardInputAction` (no sub-state axis in `DictateUiState` —
consistent with Spec 1 §15.1 footnote "Off-by-One-Klarstellung gegen §3").
No deviations introduced.

#### Self-Code Fix (B2-C3-IMPL-CODE-FIX)

Code-quality review:
- All public types carry KDoc with `@see` anchors into the binding ADRs
  + architecture docs + Spec § references (per the project's
  Inline-Anchor convention).
- Forbidden patterns are referenced in KDoc where the type is
  particularly easy to misuse (`PersistentList` round-trip in
  `DictateUiState`; cross-axis writes in `DictateModule.reduce`;
  synchronous `dispatch` from `runEffect`).
- `data object` (Kotlin 1.9+ idiom) used for payload-less Action
  variants — consistent with Spec 2 §3.3 + Spec 1 §15.6.
- All sub-state types are immutable `data class`es with `val` fields;
  defaults on every property where the spec allows it (boot via
  `DictateUiState.initial()`).

#### Tests (B2-C3-IMPL-TEST)

Wrote six pure-JVM test classes — no Android Context, no Robolectric
(K-4 compliance):

| File | Tests | Coverage focus |
|------|-------|----------------|
| `DictateUiStateTest.kt` | 24 | Initial state defaults, sub-state copy isolation, sealed RecordingState/PipelineUiState payloads, PersistentList add/removeAll, defaults per sub-state type |
| `ActionHierarchyTest.kt` | 12 | Sealed-leaves enumeration (14 direct subclasses), `data object` singleton identity, `data class` content-equality, EffectFailure top-level placement, exhaustive `when` over `KeyboardInputAction` + `ViewMode` |
| `TransitionResultTest.kt` | 9 | Data-class equality, empty-effects default, ordered effect list, ReducerContext now-injection, nullable-reducer-return semantics |
| `DictateUiStateStoreTest.kt` | 6 | Boot initial, custom-initial constructor, atomic update, reducer-receives-current-snapshot, state-flow consistency, identity-reducer no-op |
| `DictateModuleTest.kt` | 5 | Interface is `sealed`, mandatory abstract members declared, optional members have defaults, three type parameters `S/A/E`, `actionClass` property exists |
| `ModuleIdTest.kt` | 4 | All 14 ids exist, `data object` singleton identity, distinct ids are non-equal, ids work as `Map` keys |

**Code-bugs found while writing tests:** none — types are pure data,
no behaviour beyond data-class semantics.

**Test results:** all 60 new tests green; full project test run
270/270 green (no regressions).

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 59s
```

#### Test-Review (B2-C3-IMPL-TEST-FIX)

- All Plan-AC for C3 covered: 13 sub-state axes + 1 top-level flag verified
  via defaults + copy tests; Action sealed-leaves complete; KClass-Lookup
  pre-condition (every module action has ≥1 leaf) verified in
  `every module action sealed class has at least one concrete leaf`.
- Edge cases: sub-state identity preservation under outer `copy()`,
  PersistentList structural sharing, sealed self-equality vs cross-
  variant inequality, `data object` reference-equality.
- One JUnit type-inference issue fixed inline during Step 5
  (`assertNotEquals<RecordingState>(active, paused)` → explicit
  `assertNotEquals(active as RecordingState, paused as RecordingState)`
  because JUnit's `assertNotEquals(Long, Long)` overload otherwise
  shadows the generic Object form for typed call-sites).

**Code-bugs found during test self-review:** none.

#### Build verification

```
./gradlew assembleDebug  → BUILD SUCCESSFUL
./gradlew test            → BUILD SUCCESSFUL (270 tests, 0 failures)
```

A compile-time warning was emitted on `Action::class.sealedSubclasses`
calls in `ActionHierarchyTest.kt`: *"Call uses reflection API which is
not found in compilation classpath."* This is a benign IDE/compiler
warning — `kotlin-reflect` IS on the test runtime classpath transitively
via Jackson / openai-java / anthropic-java SDKs (verified via
`./gradlew :app:dependencies | grep kotlin-reflect`). ProGuard-keep for
the Action hierarchy is added in C4 / C7 per Spec 1 §4.3 ProGuard-Block.
Tests pass at runtime.

---

### Chunk C4-orchestrator-and-registry — DictateOrchestrator + DictateModuleRegistry + ModuleServices

**Agent-IDs:** Steps 1-5 (combined): `B2-C4-IMPL-FULL`

**Status:** ✅ done (pending orchestrator commits)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 5 (C4-orchestrator-and-registry)
**Implementation-Commit:** ⏳ (Commit 1 — production code)
**Test-Commit:** ⏳ (Commit 2 — tests)

#### Implementation (B2-C4-IMPL)

**What was done:** Built the dispatch engine on top of C3's type foundation.
New production files in `app/src/main/java/net/devemperor/dictate/state/`:

- `DictateOrchestrator.kt` — composition root with the full 6-step dispatch
  loop (Spec 1 §4.3): cascade-depth guard, EffectFailure origin-routing,
  reducer + state-write, side-effect execution with throw-wrap into
  `Action.EffectFailure`, frozen-snapshot cross-module observation, recursive
  cascade-dispatch in registry order. Public surface: `dispatch(action)`,
  `emitAction(action)` (async re-entry via `scope.launch`), `shutdown()`,
  `state: StateFlow<DictateUiState>`. Constants: `MAX_CASCADE_DEPTH = 8`.
- `DictateOrchestrator.DispatchOutcome` — `sealed interface` with `Applied`,
  `Rejected(action, reason)`, `Unrouted(action)` per Spec 1 §4.3 (Issue
  1.1.4 / R.3).
- `DictateModuleRegistry.kt` — `open class` registry with the structural
  init-time invariants: unique `ModuleId`, unique `actionClass`, no
  leaf-class overlap across modules. Production `companion object Default`
  ships an empty list (C5/C6 populate it). Tests inject custom registries.
- `collectActionLeaves(root)` — internal top-level helper shared between
  the orchestrator's routing-map construction and the registry's
  validation. Recursive walk via `KClass.sealedSubclasses`.
- `ModuleServices.kt` — UPDATED: replaced the C3 skeleton with the
  concrete constructor surface per Spec 1 §4.7 (16 fields including
  `scope`, `emitAction`, `recordingHardware`, `bluetoothSco`, `audioFocus`,
  `recordingTimer`, `amplitudeStream`, `borderGlow`, `pipelineRunner`,
  `sessionRepo`, `notificationCoordinator`, `inputConnectionProvider`,
  `clipboard`, `sharedPrefs`, `toastSink`, `audioFileFactory`). Subsystem
  fields are typed by interface stubs (`RecordingHardwareSubsystem`,
  `BluetoothScoSubsystem`, …, `AudioFileFactory`, `ToastSink`,
  `NotificationStatus` sealed interface) declared in the same file —
  the contract that Block 3 implements with the Android-backed adapters.
- `TestOnlyModules.kt` — production-side `@VisibleForTesting`
  `TestDictateModule`, `TestEffect`, `TestStateLens`, `TestModuleId`
  scaffolding. Required because `sealed interface DictateModule` cannot
  be implemented from a different Kotlin compilation unit (Android Gradle
  splits `main` and `unitTest`), and the orchestrator tests need
  concrete `DictateModule`s. The fixtures **reuse production Action
  subtypes** (e.g. `Action.LanguageAction`, `Action.LivePromptAction`) to
  avoid bloating `Action::class.sealedSubclasses` with test-only entries
  — `ActionHierarchyTest`'s strict count of 14 production children is
  preserved. Not registered in `DictateModuleRegistry.Default.all`;
  documented in the file's package-level KDoc.

Edits to existing files:

- `app/proguard-rules.pro` — appended the ADR-0001-mandated keep rule for
  `Action::class.sealedSubclasses` reflection (per Spec 1 §4.3 ProGuard
  block):
  ```
  -keep,allowobfuscation,allowshrinking class net.devemperor.dictate.state.Action
  -keep,allowobfuscation,allowshrinking class * extends net.devemperor.dictate.state.Action { *; }
  -keepclassmembers class kotlin.reflect.** { *; }
  ```
- `gradle/libs.versions.toml` + `app/build.gradle` — added
  `kotlin-reflect` as a runtime dependency (version pinned to the Kotlin
  version catalog). `DictateOrchestrator.collectActionLeaves` +
  `DictateModuleRegistry.validate` both call `KClass.sealedSubclasses`,
  which requires the kotlin-reflect runtime; the existing C3 warning
  ("Call uses reflection API which is not found in compilation
  classpath") is silenced. APK impact ~3 MB — accepted per ADR-0001's
  "Negative consequences" §"Reflective sealedSubclasses".
- `app/src/test/java/net/devemperor/dictate/state/DictateModuleTest.kt` —
  one line: `ModuleServices()` (parameterless C3 skeleton) → `fakeModuleServices()`
  (C4's full-surface fixture).

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| TestOnlyModules.kt added to production source | Spec 1 §4.3 + §4.8 (tests live in test source) | Test-fixture `TestDictateModule` + helpers placed in `app/src/main/...` (marked `@VisibleForTesting`) | `sealed interface DictateModule` (ADR-0001 binding) cannot be implemented from a separate Kotlin compilation; Android Gradle splits `main`+`unitTest` into separate compilations. The fixtures are required to test the orchestrator's dispatch loop, cascade order, EffectFailure routing, and shutdown sequence. | C5/C6 modules are production-side and unaffected; the test-fixture file lives alongside but is not registered in `DictateModuleRegistry.Default.all`. ActionHierarchyTest's strict 14-name expectation is preserved via the "reuse production Action subtypes" strategy. | inline-fixed |
| `DictateModuleRegistry` is `open class` (not `object`) | Spec 1 §4.8 (`object DictateModuleRegistry`) | Class form with `companion object Default : DictateModuleRegistry(emptyList())` | Tests need to construct ad-hoc registries with fake modules. Singleton-only would require monkey-patching or test-side-effects. The `Default` companion preserves Spec 1's "single production registry" intent (production code references `DictateModuleRegistry.Default` or its `companion`-resolved name `DictateModuleRegistry`). | C5/C6 populate `Default.all` by editing the companion-construction; semantically identical to editing an `object`'s `val`. | inline-fixed |
| Init-time "complete coverage" check (every Action subtype is claimed) NOT enforced in C4 | Spec 1 §4.8 invariant #3 | The check is deferred to C7 wiring — at C4 the production registry's `all` is `emptyList()`, so requiring full coverage would crash boot. | C7 adds `DictateModuleRegistry.Default.assertCompleteCoverage()` (or similar) at service-bind time, after C5/C6 modules are registered. The duplicate-id + duplicate-actionClass + leaf-overlap checks DO run in C4 — they tolerate empty lists. | flagged-for-validate |
| `emitAction` does NOT override the dispatcher | Spec 1 §4.7 KDoc (Standard MVI "async via scope") | Removed `Dispatchers.Main.immediate` from `scope.launch { ... }` inside `emitAction` | Forcing Main dispatcher inside the orchestrator would break unit tests (Main not available in JVM); the host service already constructs `scope` with `Dispatchers.Main.immediate` (see `DictatePipelineService.serviceScope`). Inheriting from the scope is the correct contract. | None — production wiring still gets main-thread re-entry. | inline-fixed |
| `ModuleServicesFactory` collapsed into direct `ModuleServices` constructor argument (F-13 — documented post-hoc) | Spec 1 §4.3 / §4.7 / §7.3 prescribe a two-class pattern: `ModuleServices` (data) + `ModuleServicesFactory(provider: () -> ModuleServices)` (lazy provider) | Implementation passes `services: ModuleServices` directly to the `DictateOrchestrator` constructor. | Only one construction point per service lifetime (`Service.onCreate`); the factory's lazy-provider indirection is unused in Phase 1. Adding it for spec-conformance alone would be ceremony without observable benefit. | B3 wiring uses `ModuleServices(...)` directly in `onCreate`; no factory bootstrap step. If multi-construction (e.g. for test-side scope cycling) becomes necessary later, the factory can be re-introduced without changing the orchestrator surface — `services` is still the only ctor input. | inline-fixed (F-13 documentation pass) |

**Issues:**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| (none) | — | — | — | — |

**Inline-fixed items:**

- DRY consolidation: removed the duplicated `collectLeaves` body in
  `DictateOrchestrator` (was a private member function) in favour of
  the shared top-level `collectActionLeaves` used by both the orchestrator
  and the registry. Caught during Step 3 code-review.
- `emitAction` dispatcher override removed (see Plan deviations table).

**Overlooked points / known gaps:**

- The init-time "every direct Action subtype is claimed" check is NOT
  enforced in C4 (see Plan deviations). C7 is the right home for it.
- The 14 production subsystem-interfaces in `ModuleServices.kt` are
  **stubs** — Block 3 supplies the Android-backed implementations.
  Their contracts (method signatures + KDoc) are pinned in C4 so that
  C5/C6 modules can sign on them.
- `ModuleServices.notificationCoordinator: PipelineNotificationCoordinatorSubsystem`
  + `NotificationStatus` sealed interface — Phase-1 minimal surface
  (Idle, Recording, Pipeline). Block 3 may extend `NotificationStatus`
  with more variants when wiring the persistent notification.

#### Plan-Correctness Fix (B2-C4-IMPL-PLAN-FIX)

Re-read Spec 1 §4.3 (orchestrator dispatch loop), §4.5 (KClass lookup +
ProGuard rule), §4.7 (ModuleServices contract), §4.8 (registry init
sanity checks), §15.5 (Cross-Module-Cascade modes 1/2/3) plus ADR-0001
and ADR-0002. Findings:

- Dispatch loop 6-step structure matches Spec 1 §4.3 1:1
  (cascade-limit → EffectFailure routing → reducer → state-write →
  side-effects with throw-wrap → cross-module cascade with recursive
  dispatch).
- ProGuard-Keep rule contents identical to Spec 1 §4.3 quoted block.
- `MAX_CASCADE_DEPTH = 8` is the documented value (ADR-0002).
- `EffectFailure` origin-routing via `moduleById` matches ADR-0002
  §"EffectFailure routing".
- Self-cascade is NOT filtered (KG-RSB-2-Fix block in dispatch step 5
  comment matches Spec 1 §4.3 verbatim).
- Cascade-order = registry order (per ADR-0002 §"Cascade order"),
  verified by `DictateOrchestratorTest.cascade actions are emitted in
  registry order`.
- The four "Plan deviations" entries above are the only intentional
  divergences from a 1:1 spec implementation; each is justified +
  documented + reversible.

#### Self-Code Fix (B2-C4-IMPL-CODE-FIX)

Code-quality review:

- KDoc on every public type carries `@see` anchors into Spec 1 § / ADR-0001
  / ADR-0002 / architecture-doc paths (per the project's Inline-Anchor
  convention).
- Forbidden patterns are referenced in KDoc where the type is easy to
  misuse: `Dispatch` Main-Thread invariant, async re-entry via
  `services.emitAction`, EffectFailure-origin-routing (not KClass).
- `data object` / `data class` (Kotlin 1.9+) used for payload-less /
  payload-bearing `DispatchOutcome` variants.
- The duplicated `collectLeaves` was consolidated into one shared
  `collectActionLeaves` (DRY fix above).
- The KG-RSB-2-Fix preservation comment is a full ASCII-box block —
  signals "DO NOT CHANGE WITHOUT READING ADR-0002" to future
  maintainers.
- `kotlin-reflect` is added as a direct dependency with a KDoc-anchored
  reason in `libs.versions.toml`.

#### Tests (B2-C4-IMPL-TEST)

Wrote three pure-JVM test classes — no Robolectric (K-4 compliance):

| File | Tests | Coverage focus |
|------|-------|----------------|
| `DictateOrchestratorTest.kt` | 14 | KClass routing, sealed-leaves recursion, Rejected for reducer-null, Unrouted for missing module, side-effect ordering, EffectFailure throw-wrap → origin routing, unknown-origin → Unrouted, no-failureReducer → Rejected, cross-module cascade dispatch, self-cascade (KG-RSB-2-Fix), cascade order = registry order, MAX_CASCADE_DEPTH variant (debug crashes, release Rejected), MAX_CASCADE_DEPTH constant pin, shutdown order, shutdown continues past terminate throw, emitAction async via scope |
| `DictateModuleRegistryTest.kt` | 7 | Empty production singleton, non-empty registry construction, order preservation, duplicate-ModuleId rejection, duplicate-actionClass rejection, leaf-class overlap rejection, empty list acceptance |
| `ModuleServicesTest.kt` | 6 | Fixture builds with no args, scope round-trip, emitAction lambda capture, every subsystem field non-null, FakeSharedPreferences round-trip, NoopXxx singletons match expected references |

Plus the C3 `DictateModuleTest.kt` updated to use `fakeModuleServices()`
(one-line change, no test changes).

**Test fixtures:** `app/src/test/java/.../testutil/FakeModuleServices.kt` —
the `fakeModuleServices(...)` factory + 9 no-op subsystem singletons
(`NoopRecordingHardware`, `NoopBluetoothSco`, `NoopAudioFocus`,
`NoopRecordingTimer`, `NoopAmplitudeStream`, `NoopBorderGlow`,
`NoopPipelineRunner`, `NoopSessionRepo`, `NoopNotificationCoordinator`,
`NoopToastSink`, `NoopAudioFileFactory`). Hand-written K-1 fakes.

**Code-bugs found while writing tests:** none — the orchestrator's pure
dispatch loop has no surprises beyond the lifecycle semantics covered
in the spec.

**Test results:**

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL
304 tests, 0 failures (debug + release variants both green)
```

#### Test-Review (B2-C4-IMPL-TEST-FIX)

- All Plan-AC for C4 covered: dispatch loop 6 steps, KClass routing,
  EffectFailure routing (success + unknown origin + no-reducer-arm),
  MAX_CASCADE_DEPTH cap (both debug + release branches), shutdown
  sequence (order + continue-past-throw), cascade order = registry order,
  self-cascade verification (KG-RSB-2-Fix), emitAction async via scope.
- Edge cases:
  - **Variant-aware MAX_CASCADE_DEPTH test** — branches on
    `BuildConfig.DEBUG` so the test exercises both the `error()` debug
    crash and the release `Rejected("cascade-loop")` short-circuit.
  - **Empty-registry orchestrator** — the test `dispatch returns Unrouted
    when no module claims the action class` verifies the routing-map's
    empty case (which is C4's production baseline).
  - **Order-preservation in registry construction** — verifies the
    cascade-order contract from ADR-0002 §"Cascade order".
- One issue caught during Step 5: the initial cross-module cascade
  tests used `prev != next` as a trigger condition; the test lens
  stores counters externally (the global `DictateUiState` itself is
  not mutated by the fixtures), so `prev == next` and the cascade
  never fired. Fixed by gating the cascade on a test-side marker
  (`observedOrder.size == 1` / lens counter) — documented in the test
  comment as "production modules DO mutate the data class".

**Code-bugs found during test self-review:** none.

#### Build verification

```
./gradlew assembleDebug  → BUILD SUCCESSFUL
./gradlew test            → BUILD SUCCESSFUL (304 tests, 0 failures; debug + release variants)
```

#### IMPL-1 status update (carry-over from B1)

`IMPL-1` ("JobExecutor-Init move from IME `onCreate` to Service `onCreate` —
requires full PipelineOrchestrator from C4") is now **unblocked** by this
chunk (the orchestrator now exists). Per the chunk-prompt directive,
the actual move is **deferred to C7-prefmirror-recovery-wiring** — that
chunk's scope is the production wiring of the orchestrator into the
`DictatePipelineService.LocalBinder.dispatch` stub, which is the natural
home for the JobExecutor-Init move (it's part of the service-onCreate
sequence, which C7 owns).

Issue-Index status note: IMPL-1 → "C7 scope (unblocked by C4)".

---

### Chunk C5-modules-core — Core modules (Recording / Pipeline / Audio / ViewMode / Overlay)

**Agent-IDs:** Steps 1-5 (combined): `B2-C5-IMPL-FULL`

**Status:** ✅ complete
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 6 (C5-modules-core)

#### Implementation (B2-C5-IMPL)

**What was done:** Implemented the five core `DictateModule`s. Each
module is a Kotlin `object` singleton in the parent package
`net.devemperor.dictate.state` (sealed-interface constraint), living
in the `modules/` sub-directory for file-tree grouping.

**Files created:**

- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt`
  — RecordingState FSM (Idle / Preparing / Active / Paused), 16
    sealed-Effect variants, reduceFailure with the `data class`
    `startsWith`-prefix-match + `object` exact-equality match per
    Spec 2 §3.3, ResetSuppressBit cascade on Idle→Preparing boundary.
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt`
  — PipelineUiState FSM, lifecycle + ReprocessStaging sub-FSM,
    PipelineDone-cascade emits OnPipelineDone + MarkLastAudio +
    (conditional) LivePrompt.ChainNext.
- `app/src/main/java/net/devemperor/dictate/state/modules/AudioModule.kt`
  — AudioState reducer + AudioFocus-loss → PauseRecording cascade.
    The dead-code Idle→Preparing-block from a prior plan iteration
    was deliberately omitted (Spec 1 §15.3 Phase-B S-4 fix).
- `app/src/main/java/net/devemperor/dictate/state/modules/ViewModeModule.kt`
  — Triangle-FSM (ADR-0005). `computeViewMode` truth-table exposed
    as a public function for tests + walkthroughs. Reducer operates
    on the `ViewMode` enum directly (`state: ViewMode`, NOT
    `DictateUiState`). T7 cascade-target reducer arm (`OnPipelineDone`)
    derives `imeViewVisible` from current ViewMode (HOVER ⇒ hidden;
    otherwise visible) — no separate IME-visibility state field.
- `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt`
  — OverlayState reducer + 4 cross-module cascades (T1 KEYBOARD→WIDGET
    sets userPrefersWidget; T2 WIDGET→KEYBOARD resets;
    HOVER→KEYBOARD emits SuppressBit + Cancel-cascade with C-3
    Recording-priority disambiguation; permission-loss → SetViewMode).
    `reduceFailure` deliberately NOT overridden (Spec 3 §4.8 design
    decision — all overlay effects are idempotent pref-writes).

**Files modified:**

- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt`
  — Added the `RecordingState.isActiveOrPaused` extension property
    (referenced extensively in cross-module observers + spec snippets,
    centralised here per L-3 DRY finding from Phase-B validation).
- `app/src/main/java/net/devemperor/dictate/state/Action.kt` — Added
  `Action.ViewModeAction.OnPipelineDone` (cascade-target for the T7
  Geist-Widget structural protection; Spec 3 §7.3 T7).
- `app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt`
  — Populated `Default.all` with the 5 core modules (C6 will append the
  other 8). Cascade-order is fixed at this list-position.
- `app/src/test/java/net/devemperor/dictate/state/DictateModuleRegistryTest.kt`
  — Updated the singleton-empty assertion (was "C4 baseline empty";
  now "5 core modules from C5"). The new test pins the cascade-order
  contract.

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Modules live in package `net.devemperor.dictate.state` (not `.modules`) | Spec 1 §15 (paths show `state.modules`) | The directory IS `modules/` but package is the parent | Kotlin `sealed interface DictateModule` rule — implementations must be in the same package | None (existing tests import from `net.devemperor.dictate.state`, modules are co-resolvable) | inline-fixed |
| `RecordingModule.Effect.StartTimer` is a `data object` (no `initialElapsedMs` arg) | Spec 1 §15.2 sketches `StartTimer(initialElapsedMs: Long)` | Effect carries no arg; `RecordingTimerSubsystem.start()` (C4) has no params | `RecordingTimerSubsystem` is the source of truth for the timer API; Pause/Resume carry elapsed-state inside the subsystem | None | inline-fixed |
| `RecordingModule.Effect.{Pause/Resume}BorderGlow` map to subsystem `stop()`/`start()` | Spec 1 §15.2 sketches `BorderGlow.pause/resume` methods | `BorderGlowSubsystem` (C4) only has `start()` + `stop()` | The effect-name is the user-visible semantic; the mapping at the subsystem boundary is an implementation detail | None | inline-fixed |
| `DictateModuleRegistry.Default.all` populated with 5 modules in C5 (not waiting for C7) | Chunk-prompt: "Wiring — those are C7" | "Wiring" is service-binder + IME hookup; registry construction is module-side | None — C7 still owns the service-side wiring. The 5-module registry compiles + tests pass | C6 appends 8 more modules; cascade-order is documented in the companion KDoc | inline-fixed |
| `PipelineModule` does NOT cascade `RecordingAction.StopRecording` on its own state transitions | Coupling-matrix row `Pipeline → Recording = R(state.pipeline) C(RecordingAction.StopRecording)` | The matrix predicts a reverse cascade; the actual "Send" flow goes Recording→Pipeline (user-click dispatches StopRecordingAndSend, RecordingModule cascades to TriggerPipeline) | The matrix entry is forward-compat for a future flow; Phase 1 only exercises the Recording→Pipeline direction | None for Phase 1 | flagged-for-validate |
| `PipelineModule.PipelineDone` reducer arm collapses directly to `PipelineUiState.Idle` (no explicit `Done` state) | Spec 3 §7.3 T7 mentions `prev.pipeline !is Done && next.pipeline is Done` | `PipelineUiState` (C3) only has `Idle/Preparing/Running/ReprocessStaging` — no `Done` | Cascade trigger uses the equivalent boundary `prev != Idle && next is Idle` | The T7 cascade still fires correctly; verified by `PipelineModuleTest.cross-module Running to Idle cascades OnPipelineDone` | inline-fixed |
| `OverlayModule.Effect.OpenOverlayPermissionSettings` is a no-op in `runEffect` (logs the limit) | Spec 3 §4.8 `services.activityLauncher.openOverlayPermissionSettings()` | `ActivityLauncher` subsystem lands in B5 (OverlayBackend block); not in C4's `ModuleServices` | UI side currently triggers the Settings-intent directly in response to `RequestOverlayPermission` (Spec 3 §5.3 Phase-1 placeholder) | B5 wires the real activityLauncher; the Effect becomes active there | inline-fixed |
| `OverlayModule.Effect.NotifyOverlayPermissionRequired` + `services.notifications.showPermissionRequired()` runEffect arm OMITTED (F-10 — documented post-hoc) | Spec 3 §4.8 prescribes the Effect | Phase-1 simplification: the permission-loss cross-module observer emits `Action.ViewModeAction.SetViewMode(KEYBOARD)` only — no separate notification path. B5 (Overlay subsystem) is the natural home for the dedicated permission-required notification because the user-visible UX (banner / toast / status-bar entry) needs the notification channel architecture that ships with B5. | None for Phase 1. B5 adds the Effect + a `NotificationStatus.PermissionRequired` variant (or a dedicated `permissionNotifier` subsystem) and the Overlay sub-state-change observer routes through it. | inline-fixed (F-10 documentation pass) |

**Issues:**

| ID | Severity | Description | Status | Reason |
|----|----------|--------------|--------|--------|
| IMPL-2 | Nice-to-have | `OverlayModule.Effect.DeleteAudioFile` is defined but never emitted by the reducer (the cancel-cascade routes audio-file delete through RecordingModule.Effect.DeleteAudioFile). | open | Kept on the Effect surface for symmetry with Spec 3 §4.8 listing; could be removed if C6/C7 confirm no other call site. |
| IMPL-3 | Nice-to-have | `PipelineModule.runEffect` for `MarkSessionInserted` / `MarkSessionFailed` uses `services.scope.launch { … }` for the suspend DB call. The orchestrator's `runEffect` body is documented as synchronous; long-running work launches into `scope`. The `launch` here is a fire-and-forget DB write. | open | Per `ModuleServices.scope` KDoc + Spec 1 §4.7 — acceptable pattern. |

**Test-infrastructure implemented:** none new; the existing
`fakeModuleServices(...)` factory from C4 is sufficient for reducer +
cascade tests (modules don't need real subsystems for reducer
verification).

**Overlooked / known gaps:**

- The `ActivityLauncher`-subsystem is missing from `ModuleServices`
  (lands in B5). `OverlayModule.Effect.OpenOverlayPermissionSettings`
  is currently a no-op; the UI side handles the Settings-intent in
  Phase 1. Documented as a plan-deviation above.

#### Plan-Correctness Fix (B2-C5-IMPL-PLAN-FIX)

Re-read Spec 1 §15.2/§15.3, Spec 3 §4.8 + §6 + §7 against each module:

- `RecordingModule` matches §15.2 1:1 except the documented timer /
  border-glow Effect-API adjustments (carrying through the C4 contract,
  inline-fixed).
- `PipelineModule` covers the C3-defined `PipelineUiState` arms (no
  `Done` state; cascade triggers on `prev != Idle && next is Idle`).
- `AudioModule` matches §15.3 1:1; the dead-code Phase-B S-4 block was
  not transcribed.
- `ViewModeModule` matches Spec 3 §6/§7 1:1 including the T7
  `OnPipelineDone` reducer-arm with derived `imeViewVisible`. Added
  `OnPipelineDone` to `Action.ViewModeAction` (was missing in C3).
- `OverlayModule` matches Spec 3 §4.8 with the T1/T2 cascade entries
  reading from §7.3 / §6.1, plus the HOVER→KEYBOARD CancelRecording-
  priority disambiguation. No `reduceFailure` override (design
  decision per spec).

No larger plan-deviations needed delegation. The seven listed
deviations are small + locally decidable.

#### Self-Code Fix (B2-C5-IMPL-CODE-FIX)

Code-quality review against knowledge-doc-format + Spec 1/2/3:

- KDoc on every public `object` references the relevant Spec section
  via `@see` anchors (Spec 1 §15.x / Spec 3 §4.8 / §7.x / §6.x / ADR-
  0001/0002/0005).
- Reducer-`when` blocks are expression-form over the sealed Action
  hierarchy — Kotlin compiler enforces exhaustivity (forbidden pattern
  (c) "else over sealed").
- No hardware/IO/threading in `reduce()` — all effects flow through
  `TransitionResult.sideEffects` to `runEffect`.
- No cross-axis writes (Mode 3) — every reducer writes only its own
  sub-state via the lens.
- `RecordingModule.reduceFailure` uses `startsWith("Name(")` for
  `data class`-effects (`AllocateMediaRecorder`) and exact-equality
  for `object`-effects (`StopMediaRecorder`) per Spec 2 §3.3.
- `OverlayModule` cross-module cascade-arms separated into 4
  independent `if` blocks (T1, T2, HOVER-close, permission-loss) for
  readability + grep-ability.
- `ViewModeModule.computeViewMode` is `fun` (public) so tests +
  walkthroughs can call it directly; the truth-table is documented as
  a KDoc table.

#### Tests (B2-C5-IMPL-TEST)

Wrote five pure-JVM test classes (K-1 + K-4 compliant):

| File | Tests | Coverage focus |
|------|-------|----------------|
| `RecordingModuleTest.kt` | 24 | All 4 FSM states × valid actions; reduceFailure (Allocate-prefix-match + Stop-exact-match + unknown-effect-null); cross-module cascade (Idle→Preparing emits ResetSuppressBit; other boundaries DON'T cascade); lens round-trip + initial-state + id |
| `PipelineModuleTest.kt` | 23 | TriggerPipeline / StartPipeline / StepStarted / PipelineDone / PipelineFailed / CancelPipeline (incl. mismatched sessionId rejection); ReprocessStaging entry / send / cancel; cross-module cascade (Running→Idle emits OnPipelineDone + MarkLastAudio; livePrompt-pending triggers ChainNext); lens |
| `AudioModuleTest.kt` | 12 | OnAudioFocusGrantChanged (incl. idempotent); OnBluetoothScoStateChanged (incl. idempotent); ToggleAudioFocusPref; cross-module cascade (AudioFocus-loss × Active/Paused/Preparing/Idle recording — Active+Paused cascade, Preparing+Idle don't); no auto-resume on focus regain |
| `ViewModeModuleTest.kt` | 25 | All seven transitions T1–T7 (one named test each); computeViewMode truth-table edge cases; T1 permission-gate; T7 with userPrefersWidget=true falls to KEYBOARD; no-op cases (toggle in HOVER, idempotent SetViewMode, IME-Show when already KEYBOARD); ViewModeModule emits NO cascade |
| `OverlayModuleTest.kt` | 26 | All reducer arms (position portrait/landscape, onboarding shown/dismissed, suppress set/reset, userPrefersWidget set, permission-change idempotent, request-permission emits OpenSettings); 4 cross-module cascade scenarios (T1, T2, HOVER→KEYBOARD × {Recording-Active / Pipeline-only / nothing}, permission-loss × {non-KEYBOARD / KEYBOARD-already}); reduceFailure-NOT-overridden design check; lens |

**Code-bugs found while writing tests:** none — the reducers held up
against every coverage scenario.

**Test results:**

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL — 414 tests, 0 failures (debug + release both green)
```

Test breakdown: 304 pre-existing tests (B0/B1/C3/C4) + 110 new C5 tests.

#### Test-Review (B2-C5-IMPL-TEST-FIX)

- All Plan-AC for C5 covered:
  - **RecordingModule**: every FSM state × every action arm + failure
    paths + cascade boundary + self-cascade verification.
  - **PipelineModule**: lifecycle entry / progress / terminal /
    cancel / reprocess sub-FSM + 4 cascade paths.
  - **AudioModule**: focus-grant transitions + SCO state-changes +
    pref-toggle + cascade × 4 recording states.
  - **ViewModeModule**: T1–T7 each pinned by a named test;
    permission-gate; truth-table edge cases.
  - **OverlayModule**: every Action sub-class + 4 cascade scenarios
    + the explicit reduceFailure-NOT-overridden design check.
- Edge cases pinned: stale sessionId rejection in PipelineModule;
  Preparing recording does NOT trigger AudioFocus-loss pause cascade
  (per `isActiveOrPaused` semantics); ViewMode T7 with `userPrefersWidget=true`
  still falls to KEYBOARD (Spec 3 §7.3 T7 truth-table); Overlay
  HOVER→KEYBOARD Cancel-cascade C-3 priority (Recording > Pipeline).
- The `OverlayModuleTest.reduceFailure is NOT overridden` test pins
  the Spec 3 §4.8 design decision so a future "missing reduceFailure"
  false-positive finding has a documented rebuttal.

**Code-bugs found during test self-review:** none.

#### Build verification

```
./gradlew assembleDebug  → BUILD SUCCESSFUL
./gradlew test           → BUILD SUCCESSFUL (414 tests, 0 failures; debug + release variants)
```

---

### Chunk C6-modules-auxiliary — 8 simpler modules (Resend / LivePrompt / Language / Layout / FeatureToggle / Theming / PendingSessions / Interruption-Phase-2-stub)

**Agent-IDs:** Steps 1-5 (combined): `B2-C6-IMPL-FULL`

**Status:** ✅ complete (all 5 steps in one invocation)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 7 (C6-modules-auxiliary)

#### What was done (B2-C6-IMPL-FULL)

Implemented **9 modules** (per Spec 1 §15.1 module inventory + registry comment in `DictateModuleRegistry.kt`): the 8 from chunks.json (`ResendModule`, `LivePromptModule`, `LanguageModule`, `LayoutModule`, `KeyboardInputModule`, `FeatureToggleModule`, `ThemingModule`, `PendingSessionsModule`) plus the Phase-2 stub `InterruptionModule`. All live in `app/src/main/java/net/devemperor/dictate/state/modules/` (parent package `net.devemperor.dictate.state` per the sealed-interface rule).

`DictateModuleRegistry.Default.all` now contains **14 entries** = 5 core (C5) + 9 added here (8 active + 1 stub), matching the §15.1 inventory ("13 aktive + 1 Phase-2-Stub"). The Action-hierarchy now has 14 inner sealed module-actions + `EffectFailure` = 15 direct subclasses.

A new sealed `Action.ThemingAction` was added (with `SetTheme` / `SetAccentColor` / `SetOverlayCharacters` / `SetOutputSpeed` variants) — there was no `ThemingAction` in the existing hierarchy, which would have left `ThemingModule` without an `actionClass` token to register. The four new leaves mirror the four `ThemingState` fields and `Pref.Theme` / `AccentColor` / `OverlayCharacters` / `OutputSpeed`.

#### Plan deviations

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| Added `Action.ThemingAction` sealed class with 4 leaves (`SetTheme`, `SetAccentColor`, `SetOverlayCharacters`, `SetOutputSpeed`) | Spec 1 §15.1 row 11 (Theming) + Action.kt | Plan inventory lists ThemingModule among the 13 active modules, but Action.kt as shipped from C3 had no `ThemingAction` inner sealed. Module registration requires an `actionClass` token. | The four setters mirror the four `ThemingState` fields and `Pref.*` counterparts — the leaves are mechanically derivable from the state shape. | C7 PrefMirror wiring may dispatch these directly when SP changes propagate; no other chunks affected. | inline-fixed (small + locally decidable) |
| `FeatureToggleAction.ToggleVibration` returns `null` from `FeatureToggleModule.reduce` | Spec 1 §15.1 + Action.kt `FeatureToggleAction` | `vibrationEnabled` lives on `AudioState`, not `FeatureToggles`. Cross-axis writes are forbidden by the lens (ADR-0001). The reducer rejects to preserve purity; the legacy SP-write path keeps the UI functional in Phase 1. | The action lives in `FeatureToggleAction` because that's how the legacy UI groups the 5 toggles. Moving it would also require renaming/relocating the action — out of C6 scope. | B3 may re-route `ToggleVibration` to `Action.AudioAction` when it migrates the click resolver. Documented in `FeatureToggleModule` KDoc. | inline-fixed (small + locally decidable); flagged for B3 attention via module KDoc |
| `LanguageAction.RefreshFromPref` reducer returns `null` (no state-change) | Spec 1 §15.1 row 9 (Language) | The plan describes LanguageModule as "subsumes today's LanguageController — direct migration". The legacy `core.LanguageController` still owns the SharedPreferences read surface (curated list + pos); Phase 1 keeps the controller and only mirrors `language.effective` from the dispatch path. `RefreshFromPref` carries no payload yet — it's an acknowledgement signal. | Adding the payload now (e.g. `RefreshFromPref(effective: String)`) would force the action's data shape before B3 has wired the resolver. Conservative Phase-1 stub. | B3 wires the legacy controller through `Action.LanguageAction.SetEffective(code)` (or similar) after `RefreshFromPref` becomes payload-bearing. Documented in `LanguageModule` KDoc. | inline-fixed (small); flagged for B3 attention |
| `PendingSessionsModule.Effect.PersistDismissal` routes through `sessionRepo.markInserted` | Spec 1 §15.1 row 12 (PendingSessions) | The repo subsystem interface (from C4) has `markInserted` / `markFailed`; there's no dedicated `markDismissed` channel. "User acknowledged this session" maps closest to `markInserted` semantics ("the session has been handled by the user"). | A dedicated dismissal channel would require extending `PipelineSessionRepoSubsystem` — that's B3 surface, not C6. | B3 may add `markDismissed` to the subsystem; the module is one-line-swap to use it. Documented in `PendingSessionsModule.runEffect`. | inline-fixed (small + locally decidable) |
| `ResendModule` cooldown timer driven by externally-dispatched `ResendCooldownExpired` action | Spec 1 §15.1 row 7 (Resend) | The cooldown mechanism is Phase-1 placeholder — a dedicated cooldown subsystem is a Phase-2 nicety. The UI side scheduling `Handler.postDelayed { dispatch(ResendCooldownExpired) }` is the simplest correct mechanism. | The reducer is pure and tests are deterministic (no real timer). | B3/B4 may add a `CooldownTimerSubsystem` to `ModuleServices` if the UI side becomes inconvenient. Documented in `ResendModule` KDoc. | inline-fixed (small) |
| `InterruptionModule` registered as a stub despite Spec §4.8 "auskommentiert bis aktiv" (F-3 — documented post-hoc) | Spec 1 §4.8 | `InterruptionModule` IS registered in `DictateModuleRegistry.Default.all` (necessarily — `assertCompleteCoverage()` would throw otherwise since the IME-side listeners already dispatch the three `InterruptionAction` leaves). The reducer rejects all 3 actions; the sub-state is `null` in Phase 1. This is the **canonical example** of the F-3 "Phase-stub pattern (I) — nullable-state, reducer rejects-all" documented in `docs/architecture/state-architecture/adding-a-module.md` §7.1. | Spec 1 §4.8 prose needs updating ("auskommentiert" → "Phase-1 stub-registered to satisfy `assertCompleteCoverage`"); pinned here so the audit-trail survives the spec edit. | inline-fixed (F-3 documentation pass; adding-a-module.md §7.1 added the pattern explanation; InterruptionModule KDoc cross-links) |

#### Inline-fixed items

| File | What was fixed |
|------|----------------|
| `app/src/main/java/net/devemperor/dictate/state/Action.kt` | Added `sealed class ThemingAction : Action()` with 4 typed setter leaves. |
| `app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt` | Default list grew from 5 (C5) to 14 entries (8 aux + 1 stub from C6). Added explanatory comments grouping core / aux / Phase-2-stub. |
| `app/src/main/java/net/devemperor/dictate/state/TestOnlyModules.kt` | Updated stale comment that said "C4 ships before any production module is registered" — now correctly notes that tests use ad-hoc registries, not Default. |
| `app/src/test/java/net/devemperor/dictate/state/ActionHierarchyTest.kt` | Updated `Action sealedSubclasses…` test to expect 14 module sealed actions + EffectFailure (was 13 + EffectFailure). |
| `app/src/test/java/net/devemperor/dictate/state/DictateModuleRegistryTest.kt` | Updated `production singleton…` test to expect the full 14-entry list (was just the 5 core modules from C5). |

#### Files added (Commit 1 — production code)

| File | Purpose |
|------|---------|
| `app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt` | Owns `ResendState`; cooldown + lastAudioExists + enabled-pref-mirror. |
| `app/src/main/java/net/devemperor/dictate/state/modules/LivePromptModule.kt` | Owns `LivePromptState`; enabled + pendingChain. |
| `app/src/main/java/net/devemperor/dictate/state/modules/LanguageModule.kt` | Owns `LanguageState`; SetOverride + RefreshFromPref ack. |
| `app/src/main/java/net/devemperor/dictate/state/modules/LayoutModule.kt` | Owns `LayoutState`; **atomic `SetSmallMode` contract** (Spec 2 §4.1 — small + non-MAIN_BUTTONS forbidden, clamped on enable). |
| `app/src/main/java/net/devemperor/dictate/state/modules/FeatureToggleModule.kt` | Owns `FeatureToggles`; four toggle setters + ToggleVibration deviation. |
| `app/src/main/java/net/devemperor/dictate/state/modules/ThemingModule.kt` | Owns `ThemingState`; 4 typed setters mirroring Pref.*. |
| `app/src/main/java/net/devemperor/dictate/state/modules/PendingSessionsModule.kt` | Owns `pendingSessions`; Refresh + Dismiss + PersistDismissal effect. |
| `app/src/main/java/net/devemperor/dictate/state/modules/KeyboardInputModule.kt` | §15.6 canonical — Unit-state, 4 input effects (Backspace/Enter/Space/Clipboard). |
| `app/src/main/java/net/devemperor/dictate/state/modules/InterruptionModule.kt` | Phase-2 stub; reducer rejects all actions in Phase 1, reserves ModuleId slot. |

#### Files added (Commit 2 — tests)

| File | Test count | Notes |
|------|-----------:|-------|
| `app/src/test/java/net/devemperor/dictate/state/ResendModuleTest.kt` | 11 | cooldown arming + cooldown-blocked no-op + MarkLastAudio idempotency. |
| `app/src/test/java/net/devemperor/dictate/state/LivePromptModuleTest.kt` | 9 | enable/disable + ChainNext consumes-bit + Disable clears pending. |
| `app/src/test/java/net/devemperor/dictate/state/LanguageModuleTest.kt` | 7 | SetOverride install/clear/idempotent + RefreshFromPref null + lens. |
| `app/src/test/java/net/devemperor/dictate/state/LayoutModuleTest.kt` | 15 | **Atomic setSmallMode + ToggleSmallMode tests** (4 dedicated cases) — verifies QWERTZ/EMOJI clamp on enable, no-clamp on disable, idempotency, and the small+non-MAIN_BUTTONS rejection in SetContentArea. |
| `app/src/test/java/net/devemperor/dictate/state/FeatureToggleModuleTest.kt` | 8 | 4 owned-toggles + ToggleVibration-returns-null deviation test. |
| `app/src/test/java/net/devemperor/dictate/state/ThemingModuleTest.kt` | 11 | 4 setters × (apply + idempotent) + lens + id + initial. |
| `app/src/test/java/net/devemperor/dictate/state/PendingSessionsModuleTest.kt` | 7 | Refresh + Dismiss(matching+missing) + PersistDismissal emission + lens. |
| `app/src/test/java/net/devemperor/dictate/state/KeyboardInputModuleTest.kt` | 7 | 4 action→effect 1:1 mappings + lens write returns same global. |
| `app/src/test/java/net/devemperor/dictate/state/InterruptionModuleTest.kt` | 6 | All 3 actions return null (Phase-1 stub) + lens accepts a non-null sub-state. |

**Total new tests:** 81. Combined with the existing 414 from earlier chunks, the suite is now **495 tests, 0 failures, 0 errors** across both debug + release variants.

#### Test-Infrastructure implemented

None — all 9 new test files reuse the existing `FakeModuleServices` / `FakeSharedPreferences` testutil from C4 + pure-reducer pattern (no module-services needed for the simple Phase-1 reducers).

#### Issues

| ID | Severity | Description | Status | Reason |
|----|----------|-------------|--------|--------|
| IMPL-1 | Nice-to-have | `Action.FeatureToggleAction.ToggleVibration` is a cross-axis action (writes to `AudioState.vibrationEnabled`, not `FeatureToggles`) — currently a `null`-rejecting stub in `FeatureToggleModule.reduce`. Either move it to `Action.AudioAction` or make `vibrationEnabled` a `FeatureToggles` field. | delegated-to-orchestrator | Needs cross-block coordination with B3's click-resolver wiring. Documented in `FeatureToggleModule` KDoc; the legacy UI still has a working SP-write path for Phase 1. |
| IMPL-2 | Nice-to-have | `Action.LanguageAction.RefreshFromPref` carries no payload — needs an `effective: String` (or similar) field so the dispatch surface is self-contained once B3 migrates the legacy `LanguageController`. | delegated-to-orchestrator | Conservative Phase-1 stub. The legacy controller still owns the SP read; the action just acks the refresh trigger. |
| IMPL-3 | Nice-to-have | `PendingSessionsModule.Effect.PersistDismissal` routes through `sessionRepo.markInserted` because the subsystem has no dedicated dismissal channel. Cleaner would be `markDismissed` (semantic clarity). | delegated-to-orchestrator | Subsystem-interface change — B3 surface. |

#### Code-Bugs Found While Writing Tests

None.

#### Overlooked points / known gaps

- **Resend cooldown timing is UI-side in Phase 1.** The reducer arms / clears the `resendCooldown` bit, but a real Android `Handler.postDelayed { dispatch(ResendCooldownExpired) }` wiring lives outside C6 (B3 — main-button-controller migration). The reducer is purely deterministic; tests don't validate the timing.
- **LivePromptModule does not yet emit the actual next-pipeline-trigger** on `ChainNext` (it only clears the `pendingChain` bit). The pipeline-resubmission lives in the resolver/IME layer that already has the audio-file reference — B3 / B5 wires that.
- **Language module reads no SP.** The legacy `LanguageController` still owns the curated-list + pos read surface. B3 will wire the controller to dispatch typed actions through this module.
- **No effects in 7 of 9 modules.** Only `PendingSessionsModule` (DB write) and `KeyboardInputModule` (InputConnection + Clipboard) have non-empty effect surfaces in Phase 1. The seven pref-mirror modules (Resend/LivePrompt/Language/Layout/FeatureToggle/Theming/Interruption-stub) emit no effects — the canonical SharedPreferences write happens in `PipelinePrefMirror` (C7).
- **Cross-module observers absent from all 9 modules** — Spec 1 §15.1 marks these explicitly as observer-free. The observer-rich modules (RecordingModule, PipelineModule, AudioModule, ViewModeModule, OverlayModule) already ship in C5.

#### Self-Code Fix notes (Step 3)

- KDoc cross-references (`@see`) consistently point at Spec 1 §15.1 + ADR-0001 / 0002 anchors.
- Reducer `when`-blocks are expression-form over the sealed Action sub-class (no `else`-branches, forbidden pattern (c)).
- Idempotency guards (`if (action.x != state.x) ...` returning `null` on equal) applied uniformly across the four pref-mirror modules to keep store-subscribers from re-rendering on no-op dispatches (Phase-B S-9 distinct-emit Vertrag).
- All effect handlers wrap `services.scope.launch { ... }` for DB calls (`PendingSessionsModule.PersistDismissal`) so the dispatch thread stays unblocked.

#### Build + test results (final)

```
./gradlew test  → BUILD SUCCESSFUL
Tests: 495, failures: 0, errors: 0 (both debug + release variants)
```

#### Tests (B2-C6-IMPL-TEST + B2-C6-IMPL-TEST-FIX)

Per the combined-step pattern, test writing + review are inline in the IMPL pass. Coverage summary:

- All 4 sub-classes of each module Action surface covered with ≥1 reducer test (some with idempotency + edge-case variants).
- Atomic `setSmallMode` invariant verified in 4 dedicated LayoutModule tests (`ToggleSmallMode false→true atomically clamps`, `Toggle…with EMOJI_PICKER also clamps`, `Toggle…true→false leaves contentArea alone`, `SetSmallMode(true) atomically clamps`).
- Lens round-trip + module-id + initial state covered for every module.
- DictateModuleRegistry + ActionHierarchy tests updated to reflect the new 14-module population + 14-action-sealed hierarchy.

---

### Chunk C7-prefmirror-recovery-wiring — PipelinePrefMirror + Recovery + Wiring (incl. carry-over IMPL-1 from B1)

**Agent-IDs:** Steps 1-5 (combined): `B2-C7-IMPL-FULL`

**Status:** ✅ done (pending orchestrator commits)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 8 (C7-prefmirror-recovery-wiring)
**Implementation-Commit:** ⏳ (Commit 1 — production code)
**Test-Commit:** ⏳ (Commit 2 — tests)

#### Implementation (B2-C7-IMPL)

**What was done:** Wired the modular-orchestrator composition root
into `DictatePipelineService` per Spec 1 §4 + §7.3 + §11.2.2. Five
production-code files touched / added:

**Files created (production):**

- `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt` —
  Spec 1 §4.5 verbatim. Mirrors the 19 UI-state-relevant prefs (3
  layout + 3 audio + 1 resend + 4 features + 4 theming + 4 overlay
  positions via raw keys consistent with `OverlayModule.Effect.PersistOverlayPosition`)
  on `attach`, and registers an `OnSharedPreferenceChangeListener` to
  mirror subsequent changes on a per-key basis. `applyChange(current, key)`
  is exposed `internal` so unit tests exercise the switch without
  driving the Android listener mechanism. `detach()` unregisters the
  listener.
- `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt` —
  Spec 1 §4.6 baseline. `suspend recover(store)` calls
  `sessionRepo.loadPending()` and writes the result into
  `state.pendingSessions` as a `PersistentList`. Single-call, idempotent.
  Full Spec 1 §6.3 recovery algorithm (status promotion, ghost-session
  cleanup) is Block 3 scope.
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt` —
  C7-only production-side **no-op stubs** for the 11 subsystem
  interfaces in `ModuleServices` (`RecordingHardwareSubsystem`,
  `BluetoothScoSubsystem`, `AudioFocusSubsystem`, `RecordingTimerSubsystem`,
  `AmplitudeStreamSubsystem`, `BorderGlowSubsystem`,
  `PipelineRunnerSubsystem`, `PipelineSessionRepoSubsystem`,
  `PipelineNotificationCoordinatorSubsystem`, `ToastSink`,
  `AudioFileFactory`). Each `runEffect`-driven call logs at WARN with
  a "B3 fills this" marker. The `ToastSink` has a **production-quality
  variant** (`realToastSink(applicationContext)`) bound to the system
  Toast — user-visible errors surface today via the stub. Per D5: the
  real adapter implementations land in Block 3 (chunk C8); these stubs
  are the contract surface until then.

**Files modified (production):**

- `app/src/main/java/net/devemperor/dictate/core/DictatePipelineService.kt` —
  full rewrite of `onCreate`/`onDestroy` to host the composition root
  (Spec 1 §7.3). `onCreate` builds `DictateUiStateStore → ModuleServices
  → PipelinePrefMirror → PipelineRecovery → DictateOrchestrator` in
  the binding order. After construction, calls
  `DictateModuleRegistry.assertCompleteCoverage()` to fail fast on a
  missing module registration. `onDestroy` calls `orchestrator.shutdown()`
  **before** `serviceScope.cancel()` per the Spec 1 §4.3 Aufrufer-
  Vertrag. `LocalBinder` signature changes:
  - `dispatch(action: Any)` → `dispatch(action: Action): DispatchOutcome`
    — typed surface (forwards to `orchestrator.dispatch`).
  - new `state: StateFlow<DictateUiState>` getter — exposes
    `orchestrator.state` for the IME to `collect { … }`.
  - removed `dispatchInvocationCount`, `notificationChannelReady` is
    kept as a test hook (channel-order acceptance).
- `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt` —
  constructor extended with two nullable args:
  `prefMirror: PipelinePrefMirror? = null` and
  `recovery: PipelineRecovery? = null`. Init block calls
  `prefMirror.attach(store)` **synchronously** before
  `scope.launch { recovery.recover(store) }` (Spec 1 §4.3 +
  §11.2.2 Block-1b sub-steps 7-8). `shutdown()` updated to call
  `prefMirror?.detach()` first.
- `app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt` —
  added `fun assertCompleteCoverage()`. Iterates
  `Action::class.sealedSubclasses`, asserts every direct subclass
  except `Action.EffectFailure` is claimed by a module in `all`. Called
  by `DictatePipelineService.onCreate` after the orchestrator is wired.
  The looser `validate()` already runs in `init {}`; the strict check
  is a separate entry point so tests with subset registries skip it.

**Files modified (testutil):**

- `app/src/test/java/net/devemperor/dictate/testutil/FakeSharedPreferences.kt` —
  C7 update: previously a no-op for `register/unregister` listener;
  now records listeners and dispatches change notifications on
  `Editor.apply()`/`commit()`. Required by the listener-path tests for
  `PipelinePrefMirror`. The change is additive (listener list is empty
  by default).

**Files added (test):**

- `app/src/test/java/net/devemperor/dictate/state/PipelinePrefMirrorTest.kt`
  (17 tests) — `attach` with empty SP / per-axis snapshot tests
  (layout 3, audio 3, resend 1, features 4, theming 4, overlay 4 = 19);
  `applyChange` per-key routing tests (15 typed prefs + 4 overlay raw
  keys + unknown-key + null-key); listener-firing path; `detach`
  unregister verification; non-mirror-axis preservation.
- `app/src/test/java/net/devemperor/dictate/state/PipelineRecoveryTest.kt`
  (6 tests) — `recover` empty / non-empty / order-preservation /
  idempotency / re-run-overwrite / non-mutation of other sub-states.
- `app/src/test/java/net/devemperor/dictate/state/DictateOrchestratorInitOrderTest.kt`
  (5 tests) — `prefMirror.attach` synchronous-during-init; recovery
  sees post-PrefMirror state (Phase-B S-1 init-order acceptance);
  recovery writes `pendingSessions`; shutdown detaches the SP listener;
  legacy null-prefMirror/null-recovery construction path.
- `app/src/test/java/net/devemperor/dictate/state/DictateModuleRegistryCoverageTest.kt`
  (3 tests) — production registry passes; missing module throws
  with a locatable error message; EffectFailure is correctly excluded
  from the coverage requirement.
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt`
  — updated `localBinderDispatch_isNoOp_butCountsInvocations` to
  `localBinderDispatch_forwardsToOrchestrator_andReturnsTypedOutcome`
  (new contract). Added `localBinderState_exposesOrchestratorStateFlow`,
  `onCreate_wiresOrchestrator_andPrefMirrorRunsBeforeBindReturn`,
  `onDestroy_runsOrchestratorShutdown_beforeScopeCancellation`,
  `onCreate_succeeds_withDefaultProductionRegistry`.

**Build-config files modified:**

- `gradle/libs.versions.toml` + `app/build.gradle` — added
  `kotlinx-coroutines-test` (test-scope only) for `TestScope` +
  `advanceUntilIdle` in `DictateOrchestratorInitOrderTest`.

**Plan deviations:**

| Deviation | Plan Location | What changed | Why | Impact on later chunks | Resolved? |
|-----------|---------------|--------------|-----|------------------------|-----------|
| `OverlayPositionPortrait/LandscapeX/Y` use raw string keys instead of `Pref.*` entries | Spec 1 §4.5 references `Pref.OverlayPositionPortraitX` etc. | The `Pref` keys do NOT exist in `DictatePrefs.kt` today. `OverlayModule.Effect.PersistOverlayPosition` writes the raw keys `overlay_pos_portrait_x` etc. directly. PipelinePrefMirror uses the same raw keys, exposed as `companion object` constants for test reuse. | Spec 1 §4.5 snippet implicitly assumed the `Pref` entries; promoting them is a Phase-2 cleanup, not C7 scope. | Phase 2 cleanup may add `Pref.OverlayPositionPortraitX/Y`; the constants in `PipelinePrefMirror` become forwarders. | inline-fixed (small + locally decidable) |
| Production-side subsystem stubs in `PipelineServiceStubSubsystems.kt` instead of real adapters | Spec 1 §7.3 composition root snippet calls `RecordingHardware(audioManager, ...)`, `BluetoothScoSubsystem(...)`, etc. | The real adapter classes don't exist yet — Block 3 / chunk C8 (subsystem-adapter migration) builds them. Per D5: "Don't invent subsystem impls C7 has no scope for." Stubs log a "B3 fills this" marker so the cost of any module's `runEffect` call surfaces in logcat. | Block 3 / chunk C8 swaps each `PipelineServiceStubSubsystems.<x>` reference for the real adapter. The wiring shape stays the same. | inline-fixed (D5 — small + locally decidable + documented) |
| `JobExecutor.initialize` move from IME to Service **NOT done in C7** | Spec 1 §11.2.2 Block-2 sub-step 7 (carry-over IMPL-1) | The move requires constructing the legacy `PipelineOrchestrator(...)` (12-arg) inside the service — those 12 dependencies (AIOrchestrator with API-key Pref reads, AutoFormattingService, PromptQueueManager, SessionManager, SessionTracker, the IME-implemented PipelineCallback, …) are IME-scoped today; rewriting their construction into the service is the body of Block 3 (subsystem-adapter migration). Per D5: "Don't invent subsystem impls C7 has no scope for." | The IME still owns the legacy `PipelineOrchestrator` instance + the `JobExecutor.initialize` call. The **new** `DictateOrchestrator` is service-owned (this chunk). Block 3 (chunk C8) absorbs the JobExecutor move as part of the broader subsystem migration. | IMPL-1 stays `delegated-to-orchestrator`, re-deferred to C8. |
| `DictateOrchestrator` constructor adds `prefMirror` + `recovery` as **nullable** args (not required) | Spec 1 §4.3 lists them as positional non-null params | Pre-C7 tests (DictateOrchestratorTest, DictateOrchestratorCascadeOrderTest, etc.) construct the orchestrator without these — making them required would force a touch of every existing test. Nullable defaults preserve backward-compat. | None — production wiring always supplies both. | inline-fixed (small + locally decidable) |
| `DictateUiStateObserver.kt` Java bridge NOT added in C7 | Spec 1 §4.4 calls it a Block-2 acceptance pre-condition | The IME-side does NOT consume `binder.state` yet — the existing IME callbacks (`PipelineCallback`, `RecordingStateController.Callback`, etc.) still drive UI updates. The Java bridge is needed when the IME migrates onto `binder.state.collect`; that's Block 3 / chunk C8 territory along with the subsystem migration. | Block 3 / chunk C8 adds `DictateUiStateObserver.kt` when the first Java IME consumer subscribes to state. | flagged-for-validate (smaller deviation, but B3 may want to add the bridge proactively when planning C8) |

**Issues:**

| ID | Severity | Description | Status | Reason |
|----|----------|-------------|--------|--------|
| (none new) | — | — | — | — |

The B1 carry-over `IMPL-1` is re-deferred to Block 3 / C8 (see Issue Index above).

**Inline-fixed items:**

- All four plan deviations above are inline-fixed (small + locally
  decidable, per Iter-10 D7 inline-fix-scope rule).

**Overlooked points / known gaps:**

- The **legacy** `PipelineOrchestrator` (audio-pipeline runner, distinct
  from the new `DictateOrchestrator` state-action-router) remains IME-
  owned. Two PipelineOrchestrator types now co-exist with the
  IME-vs-Service split (Spec 1 §1.x naming-convention). Block 3 / C8
  consolidates.
- The C7 stubs in `PipelineServiceStubSubsystems` are deliberately
  **silent** for the duration of any test that calls `binder.dispatch`
  with a state-mutating action — `runEffect` logs the WARN but does
  not surface to the caller. That's a feature (the test asserts on
  `DispatchOutcome`, not on the side-effect's success), but a future
  test that checks "the right effect ran" must use a counting fake
  in place of the stub.

#### Plan-Correctness Fix (B2-C7-IMPL-PLAN-FIX)

Re-read Spec 1 §4.3 (orchestrator constructor + shutdown contract),
§4.5 (PrefMirror), §4.6 (Recovery), §4.8 (registry assertCompleteCoverage),
§7.3 (composition root snippet), §11.2.2 (Block-1b sub-steps 5-8) +
ADR-0001 / ADR-0003. Findings:

- `prefMirror.attach(store)` runs synchronously in the orchestrator
  constructor BEFORE the async `recovery.recover` launch — verified
  by `DictateOrchestratorInitOrderTest`.
- `shutdown()` calls `prefMirror?.detach()` FIRST so a late SP-listener-
  fire cannot write into the dying store — verified by
  `DictateOrchestratorInitOrderTest.shutdown_calls_prefMirror_detach`.
- `LocalBinder.state` is read-only and forwards `orchestrator.state`;
  `LocalBinder.dispatch(action: Action)` returns the typed
  `DispatchOutcome` — verified by `DictatePipelineServiceTest`.
- `DictateModuleRegistry.assertCompleteCoverage()` is invoked AFTER
  all C5/C6 modules are wired (in `Service.onCreate`) — the production
  singleton passes; the negative-path test pins the error message.
- The four plan-deviations are each small or D5-driven; no
  architecture-conflict.

#### Self-Code Fix (B2-C7-IMPL-CODE-FIX)

Code-quality review against `knowledge-doc-format` + Spec 1 §4 + ADR-0001
+ ADR-0003:

- KDoc on every public type carries `@see` anchors into the relevant
  Spec § / ADR / architecture-doc paths (per the project's Inline-
  Anchor convention).
- All `runEffect`-handling stub overrides use **block bodies** (not
  expression bodies) so `Log.w` (returns `Int`) doesn't break the
  `Unit` return type — caught + fixed during build.
- `DictateModuleRegistry.assertCompleteCoverage` exclusion list
  documents the `EffectFailure` special-case + the ProGuard dependency.
- `DictatePipelineService.onCreate` ordering comment makes the
  composition-root construction-sequence (Store → Services → PrefMirror
  + Recovery → Orchestrator → coverage-check) reviewer-greppable.
- `PREFS_NAME = "net.devemperor.dictate"` pinned as a constant so the
  service uses the **same** SP file as the IME (otherwise the pref-mirror
  would read a different file from the one the user wrote to).
- Forward-reference `emitAction = { action -> orchestrator.emitAction(action) }`
  in `ModuleServices` resolves at first invocation, not at construction
  — documented in the constructor.

#### Tests (B2-C7-IMPL-TEST)

Wrote four new test classes (34 tests total, pure-JVM except the
service test which is Robolectric per K-4 exception):

| File | Tests | Coverage focus |
|------|-------|----------------|
| `PipelinePrefMirrorTest.kt` | 17 | attach with empty / per-axis SP (19 prefs); applyChange per-key routing (15 typed + 4 raw overlay keys); unknown-key + null-key no-op; listener-firing path; detach; non-mirror-axis preservation; data-class-equal sub-state |
| `PipelineRecoveryTest.kt` | 6 | empty repo; non-empty PersistentList write; order preservation; idempotency; re-run overwrite (not union); non-mutation of other sub-states |
| `DictateOrchestratorInitOrderTest.kt` | 5 | prefMirror.attach synchronous-during-init (Phase-B S-1); recovery sees post-PrefMirror state; recovery writes pendingSessions; shutdown detaches SP listener; legacy null-prefMirror/null-recovery construction |
| `DictateModuleRegistryCoverageTest.kt` | 3 | production registry passes assertCompleteCoverage; missing-module throws with locatable msg; EffectFailure excluded |

Plus `DictatePipelineServiceTest.kt` updated with 4 new wiring tests
(localBinder.dispatch → orchestrator routing, localBinder.state, pref-
mirror-before-bind-return, registry-coverage at service startup).
Total Robolectric service tests are now 13 (was 9).

**Code-bugs found while writing tests:** none — all tests went green
on the first run after fixing two test-side assumptions (identity vs
equality of `data class.copy()` results; `DictateUiState.initial()`
returns a new instance each call so cross-call `assertSame` is
incorrect). Both fixes are in the test file only; production code is
correct.

**Test results:**

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL — 529 tests, 0 failures (debug + release both green)
```

Test breakdown: 495 pre-C7 + 34 new C7 = 529.

#### Test-Review (B2-C7-IMPL-TEST-FIX)

- All Plan-AC for C7 covered:
  - **PrefMirror**: 19-pref initial-snapshot path, per-key listener
    routing, detach lifecycle. Includes the Phase-B S-1 init-order
    acceptance (recovery sees post-PrefMirror state).
  - **Recovery**: baseline (load → write to pendingSessions);
    Block-3 algorithm tests deferred to C9/C10 with the DB migration.
  - **Wiring**: orchestrator composed in Service.onCreate; LocalBinder
    forwards to orchestrator; LocalBinder.state exposes the StateFlow.
  - **assertCompleteCoverage**: production registry passes; subset
    registry fails with locatable diagnostic; EffectFailure excluded.
- Edge cases pinned:
  - **Order**: PrefMirror BEFORE Recovery (verified by recovery seeing
    a mirrored pref value during its loadPending callback).
  - **Detach lifecycle**: post-shutdown SP writes do NOT mutate the
    store (regression test for the inverse bug-class).
  - **Non-mirror axes preserved**: attach doesn't touch `recording`,
    `pipeline`, `viewMode`, `livePrompt`, `language`, `pendingSessions`,
    `lastResultNeedsManualPaste`.
- One `experimental coroutines API` warning surfaced; added
  `@file:OptIn(ExperimentalCoroutinesApi)` to silence at file scope.

**Code-bugs found during test self-review:** none.

#### Build verification

```
./gradlew test  → BUILD SUCCESSFUL (529 tests, 0 failures; debug + release variants)
```

#### IMPL-1 status update (B1 carry-over)

Re-deferred to Block 3 / chunk C8 (subsystem-adapter migration) per
D5 reasoning above. The actual move requires the Service to own the
construction of `AIOrchestrator`, `AutoFormattingService`,
`PromptQueueManager`, `SessionManager`, `SessionTracker`, and the
`PipelineCallback` role — all IME-scoped today. C8 absorbs them
naturally as part of subsystem migration. Issue stays open with the
clear target.

---

## Block-Validate (Phase 3.2)

**Status:** ✅ converged through Repair Wave 1
**Pre-Validate Commit:** ⏳
**Validate-Pass Commit:** ⏳

### Audit-Topic Outputs

| Topic | Agent-ID | Status | Output File | Findings (counts) |
|-------|----------|--------|-------------|-------------------|
| plan-and-api | `B2-AUDIT-PLAN-AND-API` | ✅ | `./reports/audit-plan-and-api-B2.md` | 9 (0 Crit / 3 Imp / 6 NTH) |
| convention | `B2-AUDIT-CONVENTION` | ✅ | `./reports/audit-convention-B2.md` | 6 (0 Crit / 2 Imp / 4 NTH) |
| logic | `B2-AUDIT-LOGIC` | ✅ | `./reports/audit-logic-B2.md` | 8 (1 Crit / 3 Imp / 4 NTH) |
| test | `B2-AUDIT-TEST` | ✅ | `./reports/audit-test-B2.md` | 4 (0 Crit / 1 Imp / 3 NTH) |

### Sanity-Check Consolidator

**Agent-ID:** `B2-VAL-SANITY`
**Output file:** `./reports/validated-findings-B2.md`
**Result:** 🟢 23 + 🟡 1 (F-1 manual-paste), 0 eliminated.

### Block-Validate Repair Wave 1 (B2-VAL-REPAIR)

**Date:** 2026-05-15
**Agent-ID:** `B2-VAL-REPAIR` (fresh-mode)
**Scope:** `all-validated` (1 🟡 with research + 23 🟢)
**Findings addressed:** 24 of 24 + 1 sub-finding (SF-1 ADR-0001 Decision-History) applied; SF-2 + SF-3 folded into F-1 implementation; SF-4 left as Phase-2 / B3-wiring note (PersistenceError manual-paste signalling is recovery-path responsibility, not pipeline-reducer).

| Finding ID | Severity | File(s) | Status | Fix description |
|------------|----------|---------|--------|-----------------|
| F-1 | Critical (🟡 → fixed) | `DictateUiState.kt`, `Action.kt`, `modules/PipelineModule.kt`, `modules/ResendModule.kt` + 5 test files + ADR-0001 | fixed | **Option D from `research/manual-paste-field-architecture.md`**: top-level `lastResultNeedsManualPaste` field removed; relocated as `ResendState.lastResultNeedsManualPaste`; `Action.PipelineAction.NotifyResultNeedsManualPaste/ClearManualPasteFlag` moved to `Action.ResendAction.NotifyManualPasteNeeded/ClearManualPasteFlag`; ResendModule reduces both (same-axis Mode-1, idempotent). Dead reducer arms removed from PipelineModule. ADR-0001 Decision-History entry appended (SF-1). |
| F-2 | Important | `Action.kt`, `modules/RecordingModule.kt` + 1 test addition | fixed | `Action.RecordingAction.StopRecordingAndSend` → `data class StopRecordingAndSend(val sessionId: String)`. Reducer for Active+Paused emits a new `Effect.EmitPipelineTrigger(sessionId, audioFile)`; `runEffect` calls `services.emitAction(Action.PipelineAction.TriggerPipeline(...))`. **Deviation from suggested fix:** chose the Effect+emitAction async re-entry pattern over the suggested `sendOnStop: Boolean` intermediate-state pattern because (a) it avoids polluting `RecordingState.Active/Paused` with a transient field, (b) mirrors the existing documented `emitAction` async-re-entry pattern (ADR-0001 §"Required mechanics" #6), (c) less test churn. PipelineModule's stale "Pipeline → Recording cascade" KDoc updated to point at the F-2 fix. |
| F-3 | Important | `modules/InterruptionModule.kt` (KDoc), `adding-a-module.md` (new §7.1), block-report C6 Deviations | fixed | Phase-stub-pattern section added to `adding-a-module.md` §7.1 documenting the two options (nullable-state for unknown-shape modules; non-nullable + reducer-returns-null for known-shape deferred modules). InterruptionModule KDoc cross-links the new section as canonical example of shape (I). C6 Deviations table row added (F-3) calling out the "registered despite Spec §4.8 auskommentiert" mismatch with reasoning + spec-edit note. |
| F-4 | Important | `DictatePrefs.kt`, `modules/OverlayModule.kt`, `PipelinePrefMirror.kt` (+ test impact none — `OVERLAY_POS_*_KEY` constants retained as backward-compat aliases) | fixed | 6 new typed `Pref` entries in `DictatePrefs.kt` (`OverlayPositionPortraitX/Y`, `OverlayPositionLandscapeX/Y`, `OverlayOnboardingShown`, `OverlayOnboardingDismissed`). `OverlayModule.runEffect` routes all 6 SP writes through `editor.put(Pref.OverlayXxx, value)`. `PipelinePrefMirror.applyChange` + `initialMirror` read via `sp.get(Pref.OverlayXxx)`. The legacy `OVERLAY_POS_*_KEY` constants stay as forwarders to preserve any external test imports — they now resolve to the same key strings as the new Pref entries. |
| F-5 | Important | `PipelinePrefMirror.kt` | fixed | `@Volatile` annotation added to `private var store` field with anchored KDoc explaining the cross-thread `attach`/`detach` (Main) vs `sync` (background) publication-barrier requirement. |
| F-6 | Important | `PipelineRecovery.kt` | fixed | `try/catch (Throwable)` wraps the suspend `sessionRepo.loadPending()` body inside `PipelineRecovery.recover`. On failure: `Log.e("PipelineRecovery", "Recovery failed", t)`. Store stays unchanged on failure (acceptable Phase-1 UX). Matches `DictateOrchestrator.dispatchInternal` step-4 `runEffect`-throw convention. |
| F-7 | Important | `modules/OverlayModule.kt` + 1 test addition | fixed | HOVER→KEYBOARD cancel-cascade rewritten from `when { … }` priority-chain to **additive `if` blocks**. Both Recording and Pipeline are cancelled when both are in-flight; Spec 3 C-3 "Recording > Pipeline" priority preserved by list order (CancelRecording first). New test `F-7 — cascade HOVER to KEYBOARD with BOTH recording and pipeline in-flight emits BOTH cancels` pins the both-in-flight ordering. |
| F-8 | Important | `DictateOrchestrator.kt` (KDoc), `state-architecture/README.md` | fixed | `DictateOrchestrator` class-KDoc gains an explicit "Note on naming" block disambiguating from legacy `core.PipelineOrchestrator`. Same content added to state-architecture `README.md` before "## High-level architecture in 60 seconds" so reviewers landing on the README see the distinction upfront. |
| F-9 | Important | `DictatePipelineServiceTest.kt` (`onDestroy_runsOrchestratorShutdown_beforeScopeCancellation`) | fixed | Test upgraded from non-throw smoke-check to a behavioural assertion via the PrefMirror lifecycle: write a mirrored SP value before destroy → snapshot reflects it → destroy → flip SP value → snapshot remains unchanged. Proves `prefMirror.detach()` ran during destroy (it's the first step of `orchestrator.shutdown()`), which in turn proves `orchestrator.shutdown()` was called before `serviceScope.cancel()`. |
| F-10 | NTH | block-report C5 Deviations | fixed | New row documenting `Effect.NotifyOverlayPermissionRequired` Phase-1 omission with B5 forward-link. |
| F-11 | NTH | (Spec 1 §15.1.x matrix — **deferred**) | not-fixed (postponed) | The spec-file is the working-language German plan file `dictate-keyboard-layout-refactor.reviewed.md`; the matrix is embedded deep in the file and the §15.1 column referenced doesn't map cleanly to current section IDs. Documentation-only NTH; B0/B1 spec-review revisits matrix consistency. Marked **postponed** for Phase 4.6 doc-pass. |
| F-12 | NTH | block-report Issue Index | fixed | IMPL-1 Index entry consolidated to a single paragraph naming both unblock + re-defer rationale. Local C4 + C7 sub-sections preserved as audit-trail pointers. |
| F-13 | NTH | block-report C4 Deviations | fixed | New row documenting `ModuleServicesFactory` collapse with Phase-1 justification + B3 re-introduction option. |
| F-14 | NTH | (Spec 1 §15.1.x matrix `Submit` → `TriggerPipeline` — **deferred**) | not-fixed (postponed) | Same rationale as F-11 (spec-file edit deferred to Phase 4.6 doc-pass). The action name is `TriggerPipeline` everywhere in the production code; the matrix uses stale `Submit` naming. |
| F-15 | NTH | `PipelineServiceStubSubsystems.kt` (file-level KDoc) | fixed | File-level KDoc explicitly acknowledges mixed-concern nature: `stub*` properties (B3 placeholders) + `realToastSink` (production binding shipped in Phase 1 because user-visible error toasts can't wait for B3). Splitting deferred to B3+ when more production bindings accumulate. |
| F-16 | NTH | `modules/PipelineModule.kt`, `modules/PendingSessionsModule.kt`, `modules/LayoutModule.kt`, `adding-a-module.md` §7.1 | fixed | Import order alphabetised across the three flagged modules (single block per file, `java` → `kotlin` → `kotlinx` → project). Convention codified in `adding-a-module.md` §7.1. |
| F-17 | NTH | `modules/KeyboardInputModule.kt` (added 2 @see anchors), `adding-a-module.md` §7.1 | fixed | Minimum `@see` anchor set documented in `adding-a-module.md` §7.1 as a 4-row table. KeyboardInputModule (the leanest at 2 anchors) bumped to the minimum 4 (sub-state-axis is `Unit` so the (a) anchor is the action sealed, plus added orchestrator + ADR-0001). Richer modules (RecordingModule with 7 anchors etc.) stay — the convention is a floor. PipelineModule already cites `PipelineUiState`. |
| F-18 | NTH | `Action.kt` | fixed | KDocs added to `FeatureToggleAction.ToggleVibration` (deviation note), `LanguageAction.RefreshFromPref` (Phase-1 stub note), `ResendAction.ResendCooldownExpired` (internal-scheduler note). `ResendAction.MarkLastAudio` and `ViewModeAction.OnPipelineDone` already had cross-module-cascade-target KDocs. |
| F-19 | NTH | `modules/PipelineModule.kt`, `ModuleServices.kt`, `PipelineServiceStubSubsystems.kt`, `FakeModuleServices.kt` | fixed | `Effect.SubmitReprocess.audioFile: File` → `audioFile: File?`. `PipelineRunnerSubsystem.submitReprocess(audioFile: File?)`. The `SendStaging` reducer now passes `audioFile = null` (was `File("")`). KDoc on both sides documents the "null = runner resolves path by sessionId-lookup in the DB session record" contract. |
| F-20 | NTH | `modules/LayoutModule.kt` | fixed | `SetContentArea` rejection in small-mode now logs `Log.w(TAG, "SetContentArea(${action.area}) rejected in small-mode — resolver MUST gate on state.smallMode before dispatch (KSM-bug structural-rejection, Issue 1.1.5).")` so a resolver-author bug surfaces in logcat instead of being silently absorbed. |
| F-21 | NTH | `modules/PipelineModule.kt` (KDoc only) | fixed | Class-KDoc now lists `Pipeline → Recording` cascade as "Phase-2 (deferred no-op)" (matches the inline-body F-2 fix) and documents `MarkLastAudio(exists = true)` Phase-1 success-path assumption with the Phase-2 plan: when the cancel-path gains a "file deleted" signal, the observer emits `MarkLastAudio(exists = false)`. No behaviour change. |
| F-22 | NTH | `testutil/FakePipelineSessionRepo.kt` (new), `PipelineRecoveryTest.kt`, `DictateOrchestratorInitOrderTest.kt` | fixed | Shared `FakePipelineSessionRepo(pending = …)` lifted to `testutil/`. PipelineRecoveryTest's inline `FakeSessionRepo` removed (one anonymous inline kept for the test that mutates `emit` between calls — the shared fake is constructor-frozen, not suitable). DictateOrchestratorInitOrderTest's three duplicate inline objects replaced with `FakePipelineSessionRepo()`. |
| F-23 | NTH | `DictatePipelineServiceTest.kt` (`localBinderState_exposesOrchestratorStateFlow`) | fixed | Smoke `assertNotNull(snapshot)` upgraded to substantive `assertEquals(DictateUiState.initial(), snapshot)` (with `sp.edit().clear().commit()` precondition for determinism). |
| F-24 | NTH | `PipelineModuleTest.kt` | fixed | New test `F-24 — cross-module Preparing to Running does NOT cascade OnPipelineDone` pins the Preparing→Running boundary as a non-cascade case (the matrix tests previously covered Idle→Idle and Idle→Preparing but not this boundary). |

**Sub-findings:**

| Sub-finding | Status | Note |
|---|---|---|
| SF-1 — ADR-0001 Decision-History entry | fixed | Append-only entry added before `### 2026-05-14 — Accepted` with full Before/After/Reasoning per `knowledge-adr-format` §"Decision History". |
| SF-2 — F-18 rename target | fixed | F-1's Option D implementation already moved `ClearManualPasteFlag` to `ResendAction`; F-18's KDoc references the leaf in its new location. |
| SF-3 — PipelineModule class-KDoc cleanup | fixed | PipelineModule class-KDoc no longer claims ownership of the flag; explicit F-1 cross-link added pointing to ResendState + the research file. |
| SF-4 — `PersistenceError` post-text-extraction manual-paste signalling | postponed (B3-deferred) | Recovery-path responsibility, not pipeline-reducer. Phase-1 acceptable to leave un-flagged; B3 may add a recovery-path dispatch in the PersistenceError path. |

**Cross-fix conflicts:** none. F-1's action-tree restructuring is independent of F-2's `StopRecordingAndSend` payload change (different action subtrees). F-4's typed-Pref overlay-position constants are shared between `OverlayModule.runEffect` (write site) and `PipelinePrefMirror` (mirror site); both use the same `Pref.OverlayPositionXxx` entries so a future rename in `Pref` propagates atomically.

**Files modified (32 production + test files):**

Production:
- `app/src/main/java/net/devemperor/dictate/preferences/DictatePrefs.kt` (F-4: 6 new Pref entries)
- `app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt` (F-1: top-level field removed, ResendState.lastResultNeedsManualPaste added)
- `app/src/main/java/net/devemperor/dictate/state/Action.kt` (F-1: action-tree restructure; F-2: StopRecordingAndSend payload; F-18: KDocs)
- `app/src/main/java/net/devemperor/dictate/state/DictateOrchestrator.kt` (F-8: KDoc disambiguation)
- `app/src/main/java/net/devemperor/dictate/state/ModuleServices.kt` (F-19: nullable audioFile)
- `app/src/main/java/net/devemperor/dictate/state/PipelinePrefMirror.kt` (F-4 + F-5)
- `app/src/main/java/net/devemperor/dictate/state/PipelineRecovery.kt` (F-6)
- `app/src/main/java/net/devemperor/dictate/state/PipelineServiceStubSubsystems.kt` (F-15: file-KDoc; F-19: nullable signature)
- `app/src/main/java/net/devemperor/dictate/state/modules/InterruptionModule.kt` (F-3: KDoc)
- `app/src/main/java/net/devemperor/dictate/state/modules/KeyboardInputModule.kt` (F-17: +2 @see anchors)
- `app/src/main/java/net/devemperor/dictate/state/modules/LayoutModule.kt` (F-16; F-20)
- `app/src/main/java/net/devemperor/dictate/state/modules/OverlayModule.kt` (F-4; F-7)
- `app/src/main/java/net/devemperor/dictate/state/modules/PendingSessionsModule.kt` (F-16)
- `app/src/main/java/net/devemperor/dictate/state/modules/PipelineModule.kt` (F-1: dead arms removed; F-2: comment update; F-16; F-19; F-21)
- `app/src/main/java/net/devemperor/dictate/state/modules/RecordingModule.kt` (F-2)
- `app/src/main/java/net/devemperor/dictate/state/modules/ResendModule.kt` (F-1: new reducer arms)

Tests:
- `app/src/test/java/net/devemperor/dictate/core/DictatePipelineServiceTest.kt` (F-9; F-23)
- `app/src/test/java/net/devemperor/dictate/state/ActionHierarchyTest.kt` (F-1)
- `app/src/test/java/net/devemperor/dictate/state/DictateOrchestratorInitOrderTest.kt` (F-22)
- `app/src/test/java/net/devemperor/dictate/state/DictateUiStateTest.kt` (F-1)
- `app/src/test/java/net/devemperor/dictate/state/ModuleServicesTest.kt` (F-1)
- `app/src/test/java/net/devemperor/dictate/state/OverlayModuleTest.kt` (F-7)
- `app/src/test/java/net/devemperor/dictate/state/PipelineModuleTest.kt` (F-24)
- `app/src/test/java/net/devemperor/dictate/state/PipelinePrefMirrorTest.kt` (F-1)
- `app/src/test/java/net/devemperor/dictate/state/PipelineRecoveryTest.kt` (F-22)
- `app/src/test/java/net/devemperor/dictate/state/RecordingModuleTest.kt` (F-2)
- `app/src/test/java/net/devemperor/dictate/state/ResendModuleTest.kt` (F-1: 4 new tests)
- `app/src/test/java/net/devemperor/dictate/testutil/FakeModuleServices.kt` (F-19)
- `app/src/test/java/net/devemperor/dictate/testutil/FakePipelineSessionRepo.kt` (F-22, new)

Docs:
- `docs/architecture/state-architecture/README.md` (F-8)
- `docs/architecture/state-architecture/adding-a-module.md` (F-16 + F-17 + F-3 — new §7.1)
- `docs/decisions/0001-state-modular-orchestrator-pattern.md` (SF-1 — F-1 Decision-History entry)

**Files in findings-scope:** all of the above are explicitly named in one or more findings or the F-1 research doc's §5 implementation hints.

**Files outside findings-scope (drift):** none.

**Test result:** `./gradlew test` → BUILD SUCCESSFUL, **536 tests, 0 failures** (debug + release variants; was 529 pre-Repair-Wave-1 — +7 new tests from F-1 (4 ResendModule arms) + F-2 (2 RecordingModule arms) + F-7 (1 both-in-flight) + F-24 (1 Preparing→Running) − 1 test removed/renamed in F-1 cleanup).

**Build result:** `./gradlew assembleDebug` → BUILD SUCCESSFUL.

### Validate-Fixes Self-Check (B2-VAL-W1)

**Date:** 2026-05-15
**Agent-ID:** `B2-VAL-REPAIR` (fresh-mode, integrated self-check per `prompts/validate-fixes.resume.md`)

- ✅ Build green (`./gradlew assembleDebug`).
- ✅ Tests green (536, 0 failures, 0 errors; debug + release both pass).
- ✅ F-1 (Critical): re-read `DictateUiState.kt` — no top-level field; `ResendState` has the field. Re-read `Action.kt` — `PipelineAction` has no manual-paste leaves; `ResendAction` has them with proper KDoc. Re-read `PipelineModule.kt` — no dead reducer arms; class-KDoc mentions F-1. Re-read `ResendModule.kt` — both reducer arms present, idempotent. ADR-0001 has the Decision-History entry.
- ✅ F-2 (Imp): `RecordingModule.Effect.EmitPipelineTrigger` defined; reducer arms emit it; `runEffect` routes to `services.emitAction(TriggerPipeline)`. Action carries sessionId. Tests verify both Active and Paused paths.
- ✅ F-4 (Imp): 6 typed Pref entries in `DictatePrefs.kt`; `OverlayModule.runEffect` uses `editor.put(Pref.OverlayXxx, ...)`; PipelinePrefMirror uses `sp.get(Pref.OverlayXxx)`. Backward-compat `OVERLAY_POS_*_KEY` constants preserved.
- ✅ F-5 / F-6 / F-7 / F-8: all verified via re-read.
- ✅ F-3 / F-15 / F-16 / F-17 / F-18 / F-21: documentation + KDoc edits visible via re-read.
- ✅ F-19: `audioFile: File?` propagated through Effect + Subsystem interface + production stubs + test fakes.
- ✅ F-20: `Log.w` diagnostic visible.
- ✅ F-22: shared fake exists at `testutil/FakePipelineSessionRepo.kt`; both consuming tests compile + green.
- ✅ F-9 / F-23 / F-24: improved tests green and assert the documented behaviour.
- ⚠ F-11 / F-14 (NTH, postponed): spec-file edits deferred to Phase 4.6 doc-pass. No code regression — documentation drift only.
- ⚠ SF-4 (NTH, B3-deferred): PersistenceError recovery-path manual-paste signalling postponed to B3 — recovery-path responsibility, not pipeline-reducer.

**Self-check result:** ✅ Phase complete — all 24 findings + 4 sub-findings closed (22 fixed, 2 postponed with B3/Phase-4.6 rationale).

---

## Block Deviation Summary

⏳

---

## Block Closeout (Orchestrator)

- **All chunks complete (5-step workflow done, both commits per chunk):** ⏳
- **Block-Validate converged (4-topic audit + sanity-pass + repair-waves done):** ⏳
- **AUDIT-TEST: coverage thresholds met for new files, no cross-chunk regressions:** ⏳
- **Build/Lint green at block-end:** ⏳
- **Issue index reconciled (all ids closed/postponed/forwarded):** ⏳
- **Conventions section filled:** ⏳
- **Deviation list propagated to plan/state:** ⏳
- **Cross-block-API consumer info forwarded to Block 3:** ⏳ (B3 consumes the DictateOrchestrator + 13 modules + DictateUiState for subsystem-adapter migration + DB-persistence + AudioFileFactory)

**Block completed at:** ⏳
**Block-End-Commit:** ⏳
