---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Accepted
context: The DictateModule plugin contract — interface, lens pattern, registry, ModuleServices DI.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0001
---

# Modules — the `DictateModule` plugin contract

This page describes the **`DictateModule<S, A, E>` interface** that
every state-mutation plugin implements. The single ADR governing
this material is
[ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md).
Read [`state-and-actions.md`](state-and-actions.md) first.

## 1. Vision and Motivation

### 1.1 Why this interface exists

A modular state container must define what "a module" is. We picked
**`sealed interface DictateModule<S, A, E>`** with `object`-singletons
per module because:

- Compile-time the entire module population is known (the `sealed`
  hierarchy gives us exhaustivity if we ever switch over modules).
- `object`-singletons give us identity (`ModuleId`-comparison
  works) and a single allocation per module.
- Per-instance state goes through the lens (`read`/`write`), not
  through fields on the object — keeps modules stateless and
  testable.

### 1.2 What this solves

| Problem | Mechanism |
|---|---|
| Routing an Action to the right reducer at runtime | `actionClass: KClass<A>` + KClass-Lookup in orchestrator |
| Reducer signing on the wrong axis | `S` parameter pins the sub-state type at compile time |
| Cross-module reaction without direct module references | `onCrossModuleStateChange(prev, next): List<Action>` |
| Hardware/IO from a pure reducer | `runEffect(effect, services)` is the only hardware path |
| Cleanup on service shutdown | `terminate(services)` hook called from `orchestrator.shutdown()` |
| Failure handling per origin module | `reduceFailure(state, failure, ctx)` hook |

## 2. Properties this Architecture Guarantees

1. **Single-owner per axis.** Each `DictateModule` owns one
   `S` sub-state type. No other module writes that axis.
2. **Stateless implementations.** Modules are `object`-singletons;
   per-instance state is the global `DictateUiState`. Module fields
   are constants only.
3. **Init-time routing validation.** Duplicate `actionClass` ↔ module
   mappings raise an exception at `DictateOrchestrator` init —
   silent runtime ambiguity is impossible.
4. **Compile-time module discovery.** `sealed interface DictateModule`
   + `DictateModuleRegistry.all` together mean the compiler can
   see every module; reflection only resolves to known instances.

## 3. The `DictateModule` interface

```kotlin
sealed interface DictateModule<S, A : Action, E : SideEffect> {

    /** Stable identifier for the module (logging, debug, telemetry). */
    val id: ModuleId

    /** The Action class this module handles. Used for type-safe action routing. */
    val actionClass: KClass<A>

    // ─── State Lens ────────────────────────────────────────────
    fun read(global: DictateUiState): S
    fun write(global: DictateUiState, sub: S): DictateUiState
    fun initialState(): S

    // ─── Reducer (F1+F2 pure function) ──────────────────────────
    /**
     * (state, action, ctx) → (next-state, side-effects). Pure.
     * Return null = "action not relevant in this state".
     */
    fun reduce(state: S, action: A, ctx: ReducerContext): TransitionResult<S, E>?

    /**
     * Failure reducer for Action.EffectFailure. Default returns null
     * ("no failure path defined"). Modules with recovery needs override
     * with state-rollback or error-marker logic.
     */
    fun reduceFailure(
        state: S,
        failure: Action.EffectFailure,
        ctx: ReducerContext,
    ): TransitionResult<S, E>? = null

    // ─── EffectHandler (hardware / IO) ──────────────────────────
    fun runEffect(effect: E, services: ModuleServices)

    // ─── Cross-Module Observer (optional) ───────────────────────
    /**
     * Called AFTER any other module mutated state. Allows this module
     * to cascade actions. Default: no reaction.
     */
    fun onCrossModuleStateChange(prev: DictateUiState, next: DictateUiState): List<Action> = emptyList()

    // ─── Pref Bindings (Phase 2) ────────────────────────────────
    /** Declarative pref-mirror specs. Phase-1 default empty. */
    fun prefBindings(): List<PrefBinding<S, *>> = emptyList()

    // ─── Shutdown hook ──────────────────────────────────────────
    /**
     * Cleanup effect, called from orchestrator.shutdown() with
     * runBlocking-timeout wrap. Modules emit final hardware-release effects.
     */
    fun terminate(services: ModuleServices) = Unit
}

data class TransitionResult<S, E : SideEffect>(
    val nextState: S,
    val sideEffects: List<E>,
)
```

**Required:** `id`, `actionClass`, `read`, `write`, `initialState`,
`reduce`, `runEffect`. **Optional (default-implementation):**
`reduceFailure`, `onCrossModuleStateChange`, `prefBindings`,
`terminate`.

ISP-check: 7 mandatory members + 4 optional. Module implementations
that don't need a Cross-Module Observer leave the default and the
compiler is happy.

## 4. The Lens pattern

A module's `S` is a sub-state type
(`RecordingState`, `OverlayState`, etc.). The lens lets the
orchestrator swap the sub-state without the reducer ever touching
the `DictateUiState`:

```kotlin
object RecordingModule : DictateModule<RecordingState, Action.RecordingAction, RecordingModule.Effect> {
    override val id = ModuleId.Recording
    override val actionClass = Action.RecordingAction::class

    override fun read(global: DictateUiState) = global.recording
    override fun write(global: DictateUiState, sub: RecordingState) = global.copy(recording = sub)
    override fun initialState() = RecordingState.Idle

    // reducer signs on `state: RecordingState`, not on DictateUiState.
    // Compiler: `state.copy(audio = …)` is a compile error — RecordingState
    // has no `audio` field. The lens forces single-owner per axis.
    override fun reduce(state: RecordingState, action: Action.RecordingAction, ctx: ReducerContext)
        : TransitionResult<RecordingState, Effect>? = when (state) {
            is RecordingState.Idle -> when (action) {
                // …
            }
            // …
        }
}
```

The orchestrator wires the lens like this (Spec 1 §4.3):

```kotlin
// dispatchInternal Step 2 + 3:
val subState = typedModule.read(prevGlobal)             // raus-Lens
val result = typedModule.reduce(subState, action, ctx)
store.update { typedModule.write(it, result.nextState) } // rein-Lens
```

This means the reducer's universe is `S`; only the orchestrator
sees `DictateUiState`. Cross-module reads happen via `ctx.global` —
read-only by construction (`val global: DictateUiState` is
immutable).

## 5. Three modules side by side

```
                ┌──────────────────────────────────────────────┐
                │            DictateUiState (global)           │
                │                                              │
                │   recording   pipeline   layout   overlay    │
                │      ▲           ▲          ▲        ▲       │
                │      │           │          │        │       │
                └──────┼───────────┼──────────┼────────┼───────┘
                       │           │          │        │
          read/write   │           │          │        │
          own axis     │           │          │        │
                       │           │          │        │
                ┌──────┴──┐  ┌────┴────┐ ┌───┴────┐ ┌─┴──────┐
                │Recording│  │Pipeline │ │ Layout │ │Overlay │
                │ Module  │  │ Module  │ │ Module │ │ Module │
                │         │  │         │ │        │ │        │
                │ Sub-State:                                  │
                │   RecordingState  Actions: …  Effects: …    │
                │                                             │
                │ ─── Read ──► ctx.global.audio.useBluetooth  │
                │              ctx.global.layout.singleRowMode │
                │              (read-only, in own reducer)    │
                │                                             │
                │ ─── Write ─► state.copy(... own only ...)   │
                │              NEVER state.copy(audio=...)    │
                │                                             │
                │ ─── Cascade ► return List<Action> from      │
                │               onCrossModuleStateChange,     │
                │               orchestrator dispatches them. │
                └─────────────────────────────────────────────┘
```

## 6. Three communication channels between modules

| Channel | Mechanism | When |
|---|---|---|
| **Read** (polling at reduce) | `ctx.global.<otherAxis>` inside the reducer | Synchronous, every dispatch |
| **Cascade** (Mode 2) | `onCrossModuleStateChange(prev, next): List<Action>` | After every successful state mutation, all modules are iterated |
| **Effect-Emit** (Mode 1 → other Action) | `services.emitAction(Action.OtherModule.Foo)` from inside `runEffect` | Asynchronous, fresh cascade snapshot |

**Forbidden:** module-to-module direct calls
(`recordingModule.overlayModule.foo()`). All inter-module
communication runs through the global state + Action pipe (forbidden
pattern (n) in [`forbidden-patterns.md`](forbidden-patterns.md)).

## 7. `DictateModuleRegistry.all`

There is one central list of all modules:

```kotlin
object DictateModuleRegistry {
    val all: List<DictateModule<*, *, *>> = listOf(
        RecordingModule,
        PipelineModule,
        AudioModule,
        ViewModeModule,
        OverlayModule,
        ResendModule,
        LivePromptModule,
        LanguageModule,
        LayoutModule,
        FeatureToggleModule,
        ThemingModule,
        PendingSessionsModule,
        KeyboardInputModule,
        // InterruptionModule (Phase 2)
    )

    init {
        // Sanity check: no two modules share an Action class
        val byActionClass = all.groupBy { it.actionClass }
        require(byActionClass.values.all { it.size == 1 }) {
            "Duplicate routing: ${byActionClass.filter { it.value.size > 1 }}"
        }
    }
}
```

The orchestrator reads this list at service `onCreate` and builds:

```kotlin
private val moduleByLeafClass: Map<KClass<out Action>, DictateModule<*, *, *>> =
    DictateModuleRegistry.all.flatMap { module ->
        collectLeaves(module.actionClass).map { it to module }
    }.toMap()
```

`collectLeaves` walks `KClass.sealedSubclasses` recursively so that
every concrete (`data class` / `data object`) Action variant is
routed at O(1). The init-time `require` catches doubly-routed
actions before the service starts.

### 7.1 Module-Inventar (13 active + 1 Phase-2 stub)

| # | Module | Axis | Cross-Module Observer? |
|---|---|---|---|
| 1 | RecordingModule | `recording` (sealed RecordingState) | yes (Idle → Preparing → `OverlayAction.ResetSuppressBit`) |
| 2 | PipelineModule | `pipeline` (sealed PipelineUiState) | yes (PipelineDone → Resend, LivePrompt, ViewMode T7) |
| 3 | AudioModule | `audio` | yes (AudioFocus-Loss → `RecordingAction.PauseRecording`) |
| 4 | ViewModeModule | `viewMode` (enum) | yes (Triangle-FSM transitions, ADR-0005) |
| 5 | OverlayModule | `overlay` (position + permission + suppress + onboarding) | yes (T1/T2 `userPrefersWidget` cascades) |
| 6 | ResendModule | `resend` | yes (Pipeline-Done → `MarkAvailable`) |
| 7 | LivePromptModule | `livePrompt` | yes (Pipeline-Done → `ChainNext`) |
| 8 | LanguageModule | `language` | yes (Reprocess-Override) |
| 9 | LayoutModule | `layout` | yes (T2 → `SetSmallMode(true)`) |
| 10 | FeatureToggleModule | `features` | no |
| 11 | ThemingModule | `theming` | no |
| 12 | PendingSessionsModule | `pendingSessions` | no (DB-subscriber-driven, no reducer) |
| 13 | KeyboardInputModule | n/a (Unit state, effect-only) | no |
| 14 | InterruptionModule (Phase 2) | `interruption` | yes (call-incoming → `CancelRecording`) |

See Spec 1 §15.1 for the canonical inventory + the
Cross-Module-Coupling-Matrix.

## 8. `ModuleServices` DI container

Every `runEffect` call receives a `ModuleServices` parameter that
carries the injected hardware adapters:

```kotlin
class ModuleServices(
    val recordingHardware: RecordingHardware,
    val bluetoothSco: BluetoothScoSubsystem,
    val audioFocus: AudioFocusSubsystem,
    val audioFileFactory: AudioFileFactory,
    val inputConnectionProvider: () -> InputConnection?,
    val clipboard: android.content.ClipboardManager?,
    val scope: CoroutineScope,                // serviceScope from DictatePipelineService
    val emitAction: (Action) -> Unit,         // wraps orchestrator.emitAction
    // … additional subsystem adapters
)
```

The `ModuleServicesFactory` (Spec 1 §4.7) builds a `ModuleServices`
instance per orchestrator session. Modules access **only** the
subsystem interfaces — they don't know about concrete classes
(`RecordingHardware` is the interface; the implementation is
`AndroidRecordingHardware`). DIP-clean.

## 9. Adding a new module — pointer

The full walkthrough is in [`adding-a-module.md`](adding-a-module.md).
Summary:

1. Define `S` (sub-state class) + add field to `DictateUiState`.
2. Define inner `sealed class FooAction : Action()`.
3. Implement `FooModule : DictateModule<…>` in one file.
4. Add `ModuleId.Foo` entry.
5. Append to `DictateModuleRegistry.all`.
6. If needed: system-subscription in `DictatePipelineService.onCreate`.
7. Update the Cross-Module-Coupling matrix (Spec 1 §15.1.x).
8. Write tests (reducer + cascade + effect).

## 10. `MAX_CASCADE_DEPTH = 8`

The single loop guard against runaway cascades. See
[`cross-module-cascade.md`](cross-module-cascade.md) §"MAX_CASCADE_DEPTH"
for details. Mentioned here because it's the only safety net once
the self-filter was removed (KG-RSB-2 fix, Spec 1 §4.3 ⚠-banner).

## 11. Information Gaps

(no gaps known at this time — the module interface is fully specified in Spec 1 §4.2 + §4.8)

## 12. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Captures the contract from Spec 1 §4.2 + §15 +
  ADR-0001 in tutorial form. Content stays canonical to those
  sources.

## 13. References

- [ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)
- [Spec 1 §4.2 — DictateModule interface](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §4.7 — ModuleServices + Factory](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §4.8 — DictateModuleRegistry](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §15 — Modul-Inventar](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [`state-and-actions.md`](state-and-actions.md)
- [`cross-module-cascade.md`](cross-module-cascade.md)
- [`adding-a-module.md`](adding-a-module.md)
