package net.devemperor.dictate.state

/**
 * Marker interface for module side-effects (hardware / IO / async operations).
 *
 * Each module owns its own `sealed interface Effect : SideEffect` and a single
 * `runEffect(effect, services)` handler. The orchestrator never inspects a
 * concrete effect — it only feeds them back to the owning module.
 *
 * **Invariants (binding contract):**
 *
 * - Per-module `Effect` interfaces MUST be `sealed interface` so the compiler
 *   can enforce exhaustivity in `runEffect`-`when` blocks (Spec 1 §4.2
 *   "Exhaustivity-Konvention").
 * - SideEffects are **plans**, not executions. A reducer emits them in
 *   `TransitionResult.sideEffects`; the orchestrator runs them after the
 *   state-update via `services.scope.launch`.
 * - SideEffects MUST NOT carry hardware references — only the inputs needed
 *   to perform the operation (e.g. `AllocateMediaRecorder(target, useBluetooth, audioFile)`,
 *   not a `MediaRecorder`-instance). Hardware lives in `ModuleServices`.
 *
 * **Effect-identity for failure routing (Phase-C C-3):** The orchestrator
 * converts an effect to a string via `effect.toString()` for the
 * [Action.EffectFailure.effect] field. `object`-effects yield their
 * simple-name (`"ReleaseMediaRecorder"`); `data class`-effects yield the
 * full `toString()` representation
 * (`"AllocateMediaRecorder(target=..., useBluetooth=..., audioFile=...)"`).
 * Modules matching on data-class effects in `reduceFailure` MUST use
 * `failure.effect.startsWith("AllocateMediaRecorder(")` — see Spec 1 §15.2
 * RecordingModule.reduceFailure.
 *
 * @see net.devemperor.dictate.state.DictateModule
 * @see net.devemperor.dictate.state.TransitionResult
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Pure-Reducer Invariant"
 * @see docs/architecture/state-architecture/effects-and-failures.md §3
 */
interface SideEffect
