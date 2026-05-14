---
date: 2026-05-14
author: Lukas + Claude Code
type: Architecture
status: Skeleton
context: How SideEffects are emitted, run, and how their failures are routed back to the origin module.
related-plan: ../../plans/2026-05-07 - dictate-keyboard-layout-refactor/dictate-keyboard-layout-refactor.reviewed.md
related-adrs: ADR-0001, ADR-0002
---

# Effects and Failures

This page describes the **SideEffect lifecycle**: what an effect is,
where it runs, how `services.emitAction` re-enters the dispatch loop,
and how `Action.EffectFailure` is routed back to the origin module.

Owner ADRs:
[ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)
defines the reducer/effect contract; [ADR-0002 — state-cross-module-cascade](../../decisions/0002-state-cross-module-cascade.md)
defines the failure-channel routing.

## 1. Vision and Motivation

### 1.1 Why split state from effects

Pure reducers are testable on the JVM without an Android `Context`.
Hardware/IO operations are not pure (timing-dependent, environment-dependent).
The separation:

```
┌──────────────────────────────────────────────────────────────────┐
│  Reducer (pure)            │  EffectHandler (async, hardware)    │
│  ─────────────────────     │  ─────────────────────────────       │
│  (state, action, ctx)      │  runEffect(effect, services)        │
│    → TransitionResult      │    → side-effects on real subsystems │
│      (next-state +         │  Runs in services.scope.launch       │
│       sideEffect plan)     │  Failures → Action.EffectFailure    │
└──────────────────────────────────────────────────────────────────┘
```

The reducer **plans** the effect (puts it in `TransitionResult.sideEffects`);
the orchestrator **executes** it after the state update.

### 1.2 What this solves

| Problem | Mechanism |
|---|---|
| Reducer reaching for hardware (non-deterministic tests) | Hardware lives in `runEffect`, never in `reduce` |
| Effect throwing crashes the IME | `try/catch` in orchestrator → `Action.EffectFailure(origin, effect, reason)` |
| Failure handling lives in the wrong module | `EffectFailure` is routed by `originModuleId`, not by KClass |
| Multiple emit paths competing | Single emission: `services.emitAction(action)` → async-via-scope |
| Cancel-on-shutdown leaks coroutines | All effect coroutines run in `services.scope` which is the service scope |

## 2. Properties this Architecture Guarantees

1. **Async-only execution.** `runEffect` is called from
   `services.scope.launch`. The reducer never blocks on it.
2. **Per-module effect type.** Each module defines its own
   `sealed interface Effect : SideEffect` (e.g.
   `RecordingModule.Effect.AllocateMediaRecorder`). The compiler
   knows the effects per module.
3. **Failure recovery is opt-in per module.** `reduceFailure` has a
   default return of `null` → "no failure path defined". Modules
   that need state-rollback override it.
4. **Origin-routed failure.** Every `EffectFailure` carries
   `originModuleId`. The orchestrator's secondary
   `moduleById: Map<ModuleId, DictateModule>` lookup routes the
   failure back to the emitting module.
5. **Single emission API.** `services.emitAction(action)` is the
   only way for an effect to re-enter dispatch. Synchronous
   `orchestrator.dispatch(...)` from inside `runEffect` is
   forbidden (forbidden pattern (h)).

## 3. `SideEffect` definitions

`SideEffect` is a marker interface. Each module defines its own
sealed interface that extends it:

```kotlin
interface SideEffect

// Inside RecordingModule:
sealed interface Effect : SideEffect {
    data class AllocateMediaRecorder(val target: InsertionTarget, val useBluetooth: Boolean, val audioFile: File) : Effect
    object ReleaseMediaRecorder : Effect
    object PauseMediaRecorder : Effect
    object ResumeMediaRecorder : Effect
    object StopMediaRecorder : Effect
    data class DeleteAudioFile(val file: File) : Effect
    data class StartTimer(val initialElapsedMs: Long) : Effect
    object PauseTimer : Effect
    object ResumeTimer : Effect
    object StopTimer : Effect
    object StartAmplitudeStream : Effect
    object StopAmplitudeStream : Effect
    object StartBorderGlow : Effect
    object PauseBorderGlow : Effect
    object ResumeBorderGlow : Effect
    object StopBorderGlow : Effect
}
```

The `sealed` keyword lets the compiler force exhaustivity in
`runEffect`:

```kotlin
override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
    is Effect.AllocateMediaRecorder -> services.recordingHardware.allocate(effect.target, effect.useBluetooth, effect.audioFile)
    Effect.ReleaseMediaRecorder     -> services.recordingHardware.release()
    Effect.PauseMediaRecorder       -> services.recordingHardware.pause()
    // … exhaustive
}
```

If a new variant is added to the sealed interface, every `runEffect`
that uses `when` over the type produces a compile error.

## 4. How the orchestrator runs effects (Spec 1 §4.3 Step 4)

```kotlin
result.sideEffects.forEach { effect ->
    try {
        typedModule.runEffect(effect, services)
    } catch (t: Throwable) {
        Log.e(TAG, "Effect failure in ${typedModule.id}: $effect", t)
        // Re-dispatch typed failure — keep cascade depth, do not crash IME.
        dispatchInternal(
            Action.EffectFailure(
                originModuleId = typedModule.id,
                effect = effect.toString(),
                reason = t.message ?: t.javaClass.simpleName,
            ),
            depth + 1,
        )
    }
}
```

Three things to note:

1. **`try { … } catch (t: Throwable)`** — any throw is converted to
   an EffectFailure action. The IME never crashes from a runEffect.
2. **`depth + 1`** — the failure dispatch goes through the cascade
   depth counter. Pathological failure-loops are still capped at
   `MAX_CASCADE_DEPTH = 8`.
3. **`effect.toString()`** — the effect identity is serialised to
   a string. For `object`-effects this is the simple-name
   (`"ReleaseMediaRecorder"`); for `data class` it's
   `"AllocateMediaRecorder(VOICE_RECOGNITION, true, /cache/audio.m4a)"`.
   Modules that handle failures from `data class`-effects use
   `failure.effect.startsWith("AllocateMediaRecorder(")` (Spec 1
   §15.2).

The execution is not awaited — `runEffect` is treated as
fire-and-forget by the orchestrator. Effects that need to feed
results back into the state do so via `services.emitAction(...)`
inside their own coroutine.

## 5. `services.emitAction` — async re-entry

When `runEffect` needs to emit a new action (e.g. after a long
audio operation completes), it uses:

```kotlin
override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
    Effect.StartTimer -> services.scope.launch {
        // long-running work…
        services.emitAction(Action.RecordingAction.TimerTick(elapsedMs))
    }
    // …
}
```

`emitAction` is the only sanctioned way for an effect to dispatch
a new action. Implementation:

```kotlin
fun emitAction(action: Action) {
    scope.launch { dispatch(action) }   // Main-thread re-post
}
```

Synchronous re-entry via `dispatch()` from inside `runEffect` is
forbidden pattern (h):

- It would break the frozen-cascade-snapshot invariant — the
  observer iteration in Step 5 would see state mutated by the
  re-entry.
- It would break the main-thread confinement (effects may run on
  IO dispatchers).

## 6. `Action.EffectFailure` — the failure channel

```kotlin
data class EffectFailure(
    val originModuleId: ModuleId,
    val effect: String,            // effect.toString() at emit time
    val reason: String,
) : Action()
```

**`originModuleId` is the routing key**, not `actionClass`. All
modules emit the same Action subtype (`Action.EffectFailure`) as
their failure channel, so KClass-routing would resolve to one
arbitrary module. Origin-module routing keeps recovery local to
the module that produced the failure.

### 6.1 The orchestrator's failure path

```kotlin
val module: DictateModule<*, *, *> = if (action is Action.EffectFailure) {
    moduleById[action.originModuleId]
        ?: return DispatchOutcome.Unrouted(action)
} else {
    moduleByLeafClass[action::class]
        ?: return DispatchOutcome.Unrouted(action)
}

val result = if (action is Action.EffectFailure) {
    typedModule.reduceFailure(subState, action, ctx)
} else {
    typedModule.reduce(subState, action, ctx)
} ?: return DispatchOutcome.Rejected(action, "reducer-null")
```

The `moduleById` map is built once at orchestrator init from the
registry. Unknown `originModuleId` is impossible in practice (the
module that emits a failure is the same module being routed to)
but handled defensively as `Unrouted`.

### 6.2 `reduceFailure` example (Spec 1 §15.2 RecordingModule)

```kotlin
override fun reduceFailure(
    state: RecordingState,
    failure: Action.EffectFailure,
    ctx: ReducerContext,
): TransitionResult<RecordingState, Effect>? {
    // RecordingModule only handles AllocateMediaRecorder failures —
    // they mean the hardware is busy / unavailable.
    if (!failure.effect.startsWith("AllocateMediaRecorder(")) return null

    return when (state) {
        is RecordingState.Preparing -> TransitionResult(
            nextState = RecordingState.Idle,           // roll back to Idle
            sideEffects = listOf(Effect.DeleteAudioFile(state.audioFile)),
        )
        else -> null   // unexpected — preparing-failure but we're not in Preparing
    }
}
```

Default-`null` return (`reduceFailure` not overridden) means
"no failure path defined" → `DispatchOutcome.Rejected("reducer-null")`,
which is semantically correct: the failure was acknowledged, no
recovery action is needed.

## 7. Failure-recovery patterns

Modules can choose among three patterns:

| Pattern | When to use | Form |
|---|---|---|
| **State rollback** | The effect was on the way to a target state; recovery is "revert to previous". | `reduce` to a previous state; emit cleanup effects (e.g. `DeleteAudioFile`). RecordingModule §6.2 example. |
| **Error marker** | Recovery is "tell the user, keep state". | `reduce` to current state with `lastErrorMessage = …` field set. PipelineModule example: failed transcription → `pipeline = Done(transcriptResult = Error("network"))`. |
| **No-op** | The failure is transient / harmless / handled elsewhere. | Default `reduceFailure` returns `null`. Spec-internal logging only. |

## 8. Concrete failure flow (RecordingModule example)

```
1. UI click: RECORD button → actionResolver → Action.RecordingAction.StartRecording
2. dispatch(StartRecording)
   → RecordingModule.reduce(Idle, StartRecording, ctx)
   → TransitionResult(
       nextState = Preparing(useBluetooth=true, audioFile=/cache/audio.m4a),
       sideEffects = [AllocateMediaRecorder(VOICE_RECOGNITION, true, /cache/audio.m4a)]
     )
3. store.update { it.copy(recording = Preparing(...)) }
4. services.scope.launch {
     try {
       RecordingModule.runEffect(AllocateMediaRecorder(...), services)
       // hardware throws — MediaRecorder.prepare() failed
     } catch (t: IOException) {
       dispatchInternal(
         EffectFailure(
           originModuleId = ModuleId.Recording,
           effect = "AllocateMediaRecorder(VOICE_RECOGNITION, true, /cache/audio.m4a)",
           reason = "MediaRecorder.prepare() failed: device busy",
         ),
         depth = 1,
       )
     }
   }
5. dispatchInternal(EffectFailure, depth=1)
   → routing by originModuleId → RecordingModule
   → RecordingModule.reduceFailure(Preparing(...), failure, ctx)
   → TransitionResult(
       nextState = Idle,
       sideEffects = [DeleteAudioFile(/cache/audio.m4a)]
     )
6. store.update { it.copy(recording = Idle) }
7. RecordingModule.runEffect(DeleteAudioFile(...), services)  // cleanup
8. Cross-module observers see (prev=Preparing, next=Idle) — but no observer
   reacts to that transition (it's a "step back", not a state-progress).
```

Result: the UI snaps back to Idle within ~10–50 ms of the click,
the audio file is cleaned up, and the user can retry.

## 9. ToastSink — surfacing errors to the user

`ModuleServices` carries an optional `toastSink: (CharSequence) -> Unit`
that lets a `runEffect` show a toast. This is the only sanctioned
side-effect on the system UI from a non-render-backend path:

```kotlin
override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
    is Effect.ShowError -> services.toastSink?.invoke(effect.message)
    // …
}
```

Modules use this for failures that are user-visible (e.g.
"Microphone unavailable"). Most failures are silent and only
visible via the state.

## 10. `terminate(services)` hook

When the service is destroyed, `orchestrator.shutdown()` calls
`terminate(services)` on every module:

```kotlin
fun shutdown() {
    prefMirror.detach()
    val services = servicesFactory.get()
    modules.forEach { module ->
        try {
            (module as DictateModule<Any, Action, SideEffect>).terminate(services)
        } catch (t: Throwable) {
            Log.w(TAG, "module ${module.id} terminate failed", t)
        }
    }
}
```

Module `terminate` implementations release synchronous hardware
(e.g. `services.recordingHardware.release()`). The default is a
no-op. The orchestrator wraps the whole loop in
`runBlocking { withTimeout(2_000L) { … } }` at the Service.onDestroy
site (Spec 1 §7.3) so a single misbehaving module can't hang the
service-destroy sequence.

> [!IMPORTANT]
> Order matters: call `orchestrator.shutdown()` BEFORE
> `serviceScope.cancel()`. Module-`terminate` calls require
> `services.scope.isActive == true`. The reverse order silently
> no-ops async cleanup.
> See ADR-0003 §"Required mechanics" item 8.

## N. Information Gaps

(no gaps known at this time — the effect lifecycle is fully specified in Spec 1 §4.2 + §4.3 + §15.2)

## N+1. Change History

### 2026-05-14 — Initial draft

- **Trigger:** Block 0 architecture anchor.
- **Reasoning:** Captures the runEffect lifecycle + EffectFailure
  routing from Spec 1 §4.3 + §15.2 + ADR-0002 in tutorial form.

## N+2. References

- [ADR-0001 — state-modular-orchestrator-pattern](../../decisions/0001-state-modular-orchestrator-pattern.md)
- [ADR-0002 — state-cross-module-cascade](../../decisions/0002-state-cross-module-cascade.md)
- [Spec 1 §4.2 — DictateModule interface (reduceFailure)](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §4.3 — dispatchInternal effect path](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [Spec 1 §15.2 — RecordingModule failure example](../../plans/2026-05-07%20-%20dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md)
- [`modules.md`](modules.md)
- [`cross-module-cascade.md`](cross-module-cascade.md)
