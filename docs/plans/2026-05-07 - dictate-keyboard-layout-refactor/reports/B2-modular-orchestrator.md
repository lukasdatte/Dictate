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
- Important: 0
- Nice-to-have: 0
- Postponed: 0

**By status:**

| ID | Source agent | Severity | Status | Title | Source phase |
|----|--------------|----------|--------|-------|--------------|
| IMPL-1 (B1 carry-over) | B1-C2-IMPL-FULL | Important | open (delegated-to-orchestrator) | Spec 1 §11.2.2 Block-2 sub-step 7: JobExecutor-Init move from IME `onCreate` to Service `onCreate` (requires full PipelineOrchestrator from C4) | C7 scope (unblocked by C4) |

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

**Status:** ⏳ pending (depends on C4)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 6 (C5-modules-core)

⏳

---

### Chunk C6-modules-auxiliary — 8 simpler modules (Resend / LivePrompt / Language / Layout / FeatureToggle / Theming / PendingSessions / Interruption-Phase-2-stub)

**Agent-IDs:** Steps 1-5 (combined): `B2-C6-IMPL-FULL`

**Status:** ⏳ pending (depends on C5)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 7 (C6-modules-auxiliary)

⏳

---

### Chunk C7-prefmirror-recovery-wiring — PipelinePrefMirror + Recovery + Wiring (incl. carry-over IMPL-1 from B1)

**Agent-IDs:** Steps 1-5 (combined): `B2-C7-IMPL-FULL`

**Status:** ⏳ pending (depends on C6)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 8 (C7-prefmirror-recovery-wiring)

⏳

---

## Block-Validate (Phase 3.2)

**Status:** ⏳ pending (run after all 5 chunks)
**Pre-Validate Commit:** ⏳
**Validate-Pass Commit:** ⏳

### Audit-Topic Outputs

| Topic | Agent-ID | Status | Output File | Findings (counts) |
|-------|----------|--------|-------------|-------------------|
| plan-and-api | `B2-AUDIT-PLAN-AND-API` | ⏳ | `./reports/audit-plan-and-api-B2.md` | — |
| convention | `B2-AUDIT-CONVENTION` | ⏳ | `./reports/audit-convention-B2.md` | — |
| logic | `B2-AUDIT-LOGIC` | ⏳ | `./reports/audit-logic-B2.md` | — |
| test | `B2-AUDIT-TEST` | ⏳ | `./reports/audit-test-B2.md` | — |

3 test-agents per state-file (the block's diff will be large — modular-orchestrator core).

### Sanity-Check Consolidator

**Agent-ID:** `B2-VAL-SANITY`
**Output file:** `./reports/validated-findings-B2.md`

⏳

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
