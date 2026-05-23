---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Accepted
context: Walkthrough — how to add a new module (BatterySaverModule example) — 8 steps from sub-state to test.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0001, ADR-0002
---

# Walkthrough — Adding a new module

A worked example for adding `BatterySaverModule`. The module
observes Android's BatterySaver status and pauses the recording
when BatterySaver becomes active.

Total cost: ~80 LoC across one new file + 4 small edits + one test
file. No new RenderBackend, no DB migration, no UI change.

Owner ADRs: ADR-0001 (module contract) + ADR-0002 (cross-module
cascade). Source-of-truth in the plan: §4.0.6.3.

## 1. Vision and Motivation

### 1.1 When this walkthrough applies

Use this walkthrough when:

- You need a **new state axis** that isn't covered by an existing
  module.
- The new axis has its own lifecycle (state transitions, effects).
- Other modules may observe the new axis (via cross-module-cascade).

Use [`adding-a-button.md`](adding-a-button.md) when:

- You just need a new UI button that emits one of an existing
  module's actions.

Use [`adding-a-sub-keyboard.md`](adding-a-sub-keyboard.md) when:

- You're adding a new content area or a new render surface.

## 2. Properties this Walkthrough Guarantees

1. **No central code touched.** The orchestrator, the dispatch
   loop, and the existing modules stay untouched.
2. **One new file.** The module is implemented in
   `state/modules/BatterySaverModule.kt` as an `object` singleton.
3. **Compile-time routing.** Adding a new `Action.BatterySaverAction`
   inner sealed class registers the module via
   `actionClass = Action.BatterySaverAction::class` and the
   `KClass`-lookup picks it up automatically at init.
4. **Init-time validation.** If you accidentally route an action
   that another module already owns, `DictateModuleRegistry.init`
   raises an `IllegalStateException` at service start.
5. **Tests are JVM-only.** Pure reducer + pure cascade observer →
   no Android Context needed.

## 3. The 8 steps

### Step 1 — Define the sub-state class

Add the field to `DictateUiState`:

```kotlin
// app/src/main/java/net/devemperor/dictate/state/DictateUiState.kt
data class BatterySaverState(val isActive: Boolean = false)

data class DictateUiState(
    // … existing fields
    val batterySaver: BatterySaverState = BatterySaverState(),
)
```

And add the initial-value to `DictateUiState.initial()`:

```kotlin
companion object {
    fun initial(): DictateUiState = DictateUiState(
        // … existing
        batterySaver = BatterySaverState(),
    )
}
```

### Step 2 — Define the Action sealed class

```kotlin
// app/src/main/java/net/devemperor/dictate/state/Action.kt
sealed class Action {
    // … existing inner sealed classes
    sealed class BatterySaverAction : Action() {
        data class SetActive(val active: Boolean) : BatterySaverAction()
    }
}
```

The convention: one inner sealed class per module, named
`<Module>Action`. The KClass-lookup uses this class to route at
init.

### Step 3 — Implement the module

```kotlin
// app/src/main/java/net/devemperor/dictate/state/modules/BatterySaverModule.kt
package net.devemperor.dictate.state.modules

import net.devemperor.dictate.state.*
import kotlin.reflect.KClass

/**
 * BatterySaverModule — observes the device's Battery-Saver status and
 * pauses the recording when BatterySaver becomes active. Pure reducer +
 * cross-module-cascade.
 *
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Required mechanics"
 * @see docs/decisions/0002-state-cross-module-cascade.md §"Mode 2"
 */
object BatterySaverModule : DictateModule<
    BatterySaverState,
    Action.BatterySaverAction,
    BatterySaverModule.Effect,
> {
    override val id = ModuleId.BatterySaver
    override val actionClass: KClass<Action.BatterySaverAction> =
        Action.BatterySaverAction::class

    override fun read(global: DictateUiState) = global.batterySaver
    override fun write(global: DictateUiState, sub: BatterySaverState) =
        global.copy(batterySaver = sub)
    override fun initialState() = BatterySaverState()

    // No effects — module is purely observational
    sealed interface Effect : SideEffect

    override fun reduce(
        state: BatterySaverState,
        action: Action.BatterySaverAction,
        ctx: ReducerContext,
    ): TransitionResult<BatterySaverState, Effect>? = when (action) {
        is Action.BatterySaverAction.SetActive ->
            if (action.active != state.isActive)
                TransitionResult(state.copy(isActive = action.active), emptyList())
            else null   // no-op — already at target value
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = Unit

    // Cross-Module-Cascade: BatterySaver becomes active while recording → pause
    override fun onCrossModuleStateChange(
        prev: DictateUiState,
        next: DictateUiState,
    ): List<Action> =
        if (!prev.batterySaver.isActive && next.batterySaver.isActive
            && next.recording is RecordingState.Active)
            listOf(Action.RecordingAction.PauseRecording)
        else emptyList()
}
```

> [!IMPORTANT]
> The module is an `object` singleton. It has **no instance fields**
> (apart from `id` and `actionClass` which are constants). All
> per-instance state lives in the `BatterySaverState` sub-state.

### Step 4 — Add `ModuleId` entry

```kotlin
// app/src/main/java/net/devemperor/dictate/state/DictateModule.kt
sealed interface ModuleId {
    data object Recording : ModuleId
    data object Pipeline : ModuleId
    // … existing
    data object BatterySaver : ModuleId   // ← NEW
}
```

### Step 5 — Add to `DictateModuleRegistry.all`

```kotlin
// app/src/main/java/net/devemperor/dictate/state/DictateModuleRegistry.kt
object DictateModuleRegistry {
    val all: List<DictateModule<*, *, *>> = listOf(
        RecordingModule,
        // … existing modules
        BatterySaverModule,                 // ← NEW (append at end; cascade-order is the registry order, ADR-0002 §"Cascade-Order")
    )

    init {
        val byActionClass = all.groupBy { it.actionClass }
        require(byActionClass.values.all { it.size == 1 }) {
            "Duplicate routing: ${byActionClass.filter { it.value.size > 1 }}"
        }
    }
}
```

The `init` block catches double-routing at service-start. If you
accidentally registered the new module before another with the
same `actionClass`, you get a clear exception.

### Step 6 — System subscription (optional but typical)

A module that reacts to OS events needs a subscription set up in
`DictatePipelineService.onCreate`:

```kotlin
// app/src/main/java/net/devemperor/dictate/pipeline/DictatePipelineService.kt
override fun onCreate() {
    super.onCreate()
    // … existing wiring

    // BatterySaver subscription — feeds the module via dispatch
    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            orchestrator.dispatch(Action.BatterySaverAction.SetActive(powerManager.isPowerSaveMode))
        }
    }
    registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
    // Also dispatch initial state on register
    orchestrator.dispatch(Action.BatterySaverAction.SetActive(powerManager.isPowerSaveMode))
}
```

Don't forget to `unregisterReceiver(receiver)` in `onDestroy`.

### Step 7 — Update the Cross-Module-Coupling matrix

Edit Spec 1 §15.1.x to add a row + column for BatterySaver. New
coupling:

```
BatterySaver × Recording = C(RecordingAction.PauseRecording)
```

The Diagonal stays `—` (self-reads are implicit per KG-RSB-3
convention — see [`cross-module-cascade.md`](cross-module-cascade.md)
§7.1). All other Battery-Saver columns are empty (no other module
observes BatterySaver in this example).

A `onCrossModuleStateChange` hook without a matrix update is a
code-review violation.

### Step 8 — Tests

Cascade test (JVM, pure, no Android Context):

```kotlin
// app/src/test/java/net/devemperor/dictate/state/modules/BatterySaverModuleTest.kt
@Test
fun batterySaver_activeWhileRecording_cascadesToPause() {
    val cascadeActions = BatterySaverModule.onCrossModuleStateChange(
        prev = DictateUiState.initial().copy(
            batterySaver = BatterySaverState(isActive = false),
            recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x.m4a")),
        ),
        next = DictateUiState.initial().copy(
            batterySaver = BatterySaverState(isActive = true),
            recording = RecordingState.Active(useBluetooth = false, audioFile = File("/tmp/x.m4a")),
        ),
    )
    assertEquals(listOf(Action.RecordingAction.PauseRecording), cascadeActions)
}

@Test
fun batterySaver_activeWhileIdle_doesNotCascade() {
    val cascadeActions = BatterySaverModule.onCrossModuleStateChange(
        prev = DictateUiState.initial().copy(batterySaver = BatterySaverState(false)),
        next = DictateUiState.initial().copy(batterySaver = BatterySaverState(true)),
    )
    assertEquals(emptyList(), cascadeActions)
}

@Test
fun reduce_setActive_emitsStateChange() {
    val result = BatterySaverModule.reduce(
        state = BatterySaverState(isActive = false),
        action = Action.BatterySaverAction.SetActive(true),
        ctx = ReducerContext(DictateUiState.initial()),
    )
    assertEquals(BatterySaverState(isActive = true), result?.nextState)
    assertEquals(emptyList(), result?.sideEffects)
}

@Test
fun reduce_setActiveToSameValue_returnsNull() {
    val result = BatterySaverModule.reduce(
        state = BatterySaverState(isActive = true),
        action = Action.BatterySaverAction.SetActive(true),
        ctx = ReducerContext(DictateUiState.initial()),
    )
    assertNull(result)
}
```

## 4. What you DON'T have to do

- ❌ No UI change. BatterySaver affects behavior, not rendering.
  (Add a UI hint via a slot's `enabledResolver` reading
  `state.batterySaver.isActive` if you want; that's a UI choice,
  not a module requirement.)
- ❌ No new Spec file.
- ❌ No DB migration.
- ❌ No edits to `DictateOrchestrator`, `DictateUiStateStore`,
  `PipelinePrefMirror`, or any other co-aggregate.
- ❌ No edits to other modules. The cross-module cascade is one-way:
  BatterySaver observes Recording, but Recording doesn't need to
  know BatterySaver exists.

## 5. What you MIGHT need to do

| Scenario | Extra step |
|---|---|
| The module needs to read user preferences (e.g. "Pause on BatterySaver — user-toggle") | Add a Pref-mirror entry in `PipelinePrefMirror.sync()` (Phase-1 hardcoded list) or override `prefBindings()` (Phase-2) |
| The module performs hardware operations | Add `Effect` variants + implement `runEffect`; inject the subsystem into `ModuleServices` |
| The module needs a UI hint (e.g. show a banner when BatterySaver pauses) | Add a `lastErrorMessage`-style field to the sub-state OR use a `services.toastSink` from `runEffect` |
| Multiple modules need to observe the new axis | Add cross-module-coupling rows in the matrix |
| The module needs to clean up on service shutdown | Override `terminate(services)` |

## 6. Common mistakes (forbidden patterns)

| Mistake | Why it breaks | Correct shape |
|---|---|---|
| `reduce` writes to `state.recording = ...` (cross-axis) | Mode-3 forbidden | Cascade via `onCrossModuleStateChange` returning `RecordingAction.PauseRecording` (forbidden pattern (g)) |
| `reduce` calls `services.recordingHardware.pause()` | Reducer is pure; no hardware | Emit a SideEffect and run it in `runEffect` (forbidden pattern (b)) |
| Module references another module by reference (`recordingModule.foo()`) | Module-to-module coupling | Use `ctx.global.recording` to read; use cascade or `services.emitAction` to write (forbidden pattern (n)) |
| Module forgets `init`-validation registration in registry | Action not routed at runtime → `Unrouted` | Always append to `DictateModuleRegistry.all` |
| Module's `reduce` has `else` branch over sealed Action | New variants silently swallowed | Use expression-form `when` over sealed (compiler exhaustivity, forbidden pattern (c)) |

See [`forbidden-patterns.md`](forbidden-patterns.md) for the full
catalogue.

## 7. Module-design checklist

Before submitting the PR:

- [ ] Sub-state class is `data class` (immutable, `copy()`-based)
- [ ] Action sealed class follows the `<Module>Action` naming
- [ ] Module is `object` singleton
- [ ] `actionClass` references the sealed class itself (not a leaf)
- [ ] `read` returns `global.batterySaver` (the lens)
- [ ] `write` returns `global.copy(batterySaver = sub)` (the lens)
- [ ] `initialState()` returns a sane default
- [ ] `reduce` is pure (no hardware, no logging side-effects, no
      `else` branch over sealed Actions)
- [ ] `runEffect` is exhaustive `when` over the Effect sealed
      interface
- [ ] `onCrossModuleStateChange` (if used) takes (prev, next) and
      returns `List<Action>` — never mutates state directly
- [ ] `ModuleId` entry added
- [ ] `DictateModuleRegistry.all` entry appended
- [ ] System-subscription wired in `DictatePipelineService.onCreate`
      (if needed)
- [ ] Cross-Module-Coupling-Matrix updated (Spec 1 §15.1.x)
- [ ] At least one reducer test + one cascade test (if cascade
      exists)
- [ ] Inline anchor in the module file:
      `@see docs/decisions/0001-state-modular-orchestrator-pattern.md`

## 7.1 Conventions (F-16 / F-17 / F-3 — codified post-Block-2)

Three small conventions emerged during Block-2 module validation;
new modules MUST follow them and the existing 14 should be aligned
on next-touch.

### Import order (F-16)

Imports sorted alphabetically as a single block — IDE default
("Optimize Imports") is the source of truth. Mixed `java.` /
`kotlin.` / `kotlinx.` / project imports are all merged into one
sorted block. No blank lines between sections.

```kotlin
package net.devemperor.dictate.state

import java.io.File
import kotlin.reflect.KClass
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.launch
import net.devemperor.dictate.preferences.Pref
```

### Minimum `@see` anchor set (F-17)

Every module's class KDoc carries **at minimum**:

| Anchor | Target |
|---|---|
| (a) sub-state type | `@see net.devemperor.dictate.state.XxxState` (the data class owned by this module) |
| (b) action sealed | `@see net.devemperor.dictate.state.Action.XxxAction` |
| (c) spec section | `@see docs/plans/.../research/{topic}/{topic}.reviewed.md §15.x` (or the equivalent post-archive path) |
| (d) binding ADRs | `@see docs/decisions/0001-state-modular-orchestrator-pattern.md`; plus `0002-state-cross-module-cascade.md` **if** the module emits cross-module cascades; plus any module-specific ADR (e.g. `0005-triangle-fsm.md` for ViewModeModule) |

Modules with richer KDoc (RecordingModule has 7 anchors) are fine
— this is a **floor**, not a ceiling. The minimum set guarantees
that the SSoT-anchor (Inline-Anchor convention,
`knowledge-doc-format` §"Inline anchors") survives any rename.

### Phase-stub patterns (F-3)

Two valid shapes for a module whose behaviour is not yet active
(Phase-2 deferred / future-Block-owned):

**(I) Nullable sub-state, reducer rejects-all.** Use when the
sub-state shape is itself uncertain. The sub-state defaults to
`null` and the reducer returns `null` for every action.

```kotlin
object InterruptionModule : DictateModule<InterruptionState?, ...> {
    override fun initialState(): InterruptionState? = null
    override fun reduce(...): TransitionResult<...>? = null
}
```

The downside: every reader of `state.interruption` must handle
the nullable. Use when the future shape might add fields and the
default values can't yet be chosen.

**(II) Non-nullable sub-state with default, reducer rejects-all.**
Use when the sub-state shape is known and a sane default exists,
but reducer logic isn't wired yet.

```kotlin
object LanguageModule : DictateModule<LanguageState, ...> {
    override fun initialState(): LanguageState =
        LanguageState(effective = "system")
    override fun reduce(state, action: Action.LanguageAction.RefreshFromPref, ctx): ... = null
}
```

Both shapes preserve the `assertCompleteCoverage()` invariant
(the action sealed class IS owned by a module). The key contract:
**stub-registered ≠ removed**. The C6 deviations table records
which modules use which shape; the canonical example for shape (I)
is `InterruptionModule`.

## 8. Information Gaps

(no gaps known at this time — the walkthrough is end-to-end runnable from the plan §4.0.6.3)

## 9. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Captures plan §4.0.6.3 in didactic form. The
  BatterySaverModule example shows every step of a new module
  with one cascade and zero new effects — small enough to learn
  from, large enough to exercise the pattern.

## 10. References

- [Parent plan §4.0.6.3](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md)
- [ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)
- [ADR-0002 — state-cross-module-cascade](../../decisions/0002-state-cross-module-cascade.md)
- [Spec 1 §4.2 — DictateModule interface](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §4.8 — DictateModuleRegistry](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §15.1.x — Cross-Module-Coupling Matrix](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §15.2 — RecordingModule example](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [`modules.md`](modules.md)
- [`cross-module-cascade.md`](cross-module-cascade.md)
- [`forbidden-patterns.md`](forbidden-patterns.md)
