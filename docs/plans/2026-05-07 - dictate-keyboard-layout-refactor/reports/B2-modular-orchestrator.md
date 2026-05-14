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
| IMPL-1 (B1 carry-over) | B1-C2-IMPL-FULL | Important | open (delegated-to-orchestrator) | Spec 1 §11.2.2 Block-2 sub-step 7: JobExecutor-Init move from IME `onCreate` to Service `onCreate` (requires full PipelineOrchestrator from C4) | C4 scope |

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

**Status:** ⏳ pending (depends on C3)
**Chunks file:** `../dictate-keyboard-layout-refactor.reviewed.chunks.json` chunk index 5 (C4-orchestrator-and-registry)

⏳

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
