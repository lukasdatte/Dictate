package net.devemperor.dictate.state

/**
 * Stable identifier for a [DictateModule]. Used for:
 *
 * 1. **EffectFailure-routing** — [Action.EffectFailure.originModuleId]
 *    carries the id of the module whose `runEffect` threw, so the
 *    orchestrator can dispatch the failure back to the right
 *    `reduceFailure` hook (Spec 1 §4.3 step 1a). KClass-routing would
 *    fail here because all modules emit the same `Action.EffectFailure`
 *    subtype.
 * 2. **Logging / telemetry** — every dispatch line carries the target
 *    module's id.
 * 3. **Debug-mode invariants** — `DictateOrchestrator.shutdown()` iterates
 *    by id to call `module.terminate(services)`.
 *
 * **Why `sealed interface` + `data object` leaves?** Compile-time-known
 * enumeration with identity comparison (`===`). Adding a new module is a
 * single new `data object Foo : ModuleId` plus registry entry; the
 * compiler then flags any `when (id: ModuleId)` that forgot to handle it.
 *
 * @see net.devemperor.dictate.state.DictateModule.id
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Module inventory"
 */
sealed interface ModuleId {
    data object Recording : ModuleId
    data object Pipeline : ModuleId
    data object Audio : ModuleId
    data object ViewMode : ModuleId

    /** B3 / ADR-0008 — replaces [ViewMode] once cutover completes. */
    data object Widget : ModuleId

    data object Overlay : ModuleId
    data object Resend : ModuleId
    data object LivePrompt : ModuleId
    data object Language : ModuleId
    data object Layout : ModuleId
    data object FeatureToggle : ModuleId
    data object Theming : ModuleId
    data object PendingSessions : ModuleId

    /** §15.6 — Unit-state effect-producer for IME direct input. */
    data object KeyboardInput : ModuleId

    /** ADR-0006 completion — transient pipeline-error + engagement hints. */
    data object InfoHint : ModuleId

    /** F-036 (2026-07-02) — audio-focus / headset interruption axis. */
    data object Interruption : ModuleId

    /** ADR-0013 — in-keyboard review panel for ambiguous post-processing. */
    data object ReviewPanel : ModuleId
}
