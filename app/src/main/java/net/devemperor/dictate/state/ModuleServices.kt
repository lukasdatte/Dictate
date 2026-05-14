package net.devemperor.dictate.state

/**
 * Dependency-injection container for the per-module side-effect handlers.
 *
 * **Skeleton (Chunk C3).** This file declares the type so [DictateModule]
 * compiles; the concrete fields (RecordingHardwareSubsystem,
 * BluetoothScoSubsystem, AudioFileFactory, scope, …) are filled out
 * in Chunk C4 (`orchestrator-and-registry`). Until then, modules can
 * declare effect handlers signing on `ModuleServices` without having
 * to reference its concrete field shape.
 *
 * **Why a class (not an interface)?** Spec 1 §4.7 prescribes a concrete
 * class with `val`-fields. Modules access fields by name (not via
 * method calls), so the class form is the natural DI container — and
 * the orchestrator factory (`ModuleServicesFactory`) builds a fresh
 * instance per service-bind.
 *
 * **Threading contract:** all field accesses run on the
 * `serviceScope.dispatcher` (Main.immediate). Background effects
 * launch into [scope] via `services.scope.launch { … }`.
 *
 * @see net.devemperor.dictate.state.DictateModule.runEffect
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §4.7
 */
class ModuleServices {
    // C4 will populate concrete fields per Spec 1 §4.7. The skeleton class
    // suffices for compile-green type-references from DictateModule.kt.
}

/**
 * Declarative SharedPreferences ↔ sub-state mirror entry.
 *
 * **Phase 1 (today):** the mirror is hard-coded in `PipelinePrefMirror`
 * (Spec 1 §4.5). Module `prefBindings()` is **dead code** — the default
 * empty list MUST stay empty in Phase 1, otherwise Pref mutations would
 * fire twice (once via hardcoded mirror, once via module bindings) with
 * race-risk.
 *
 * **Phase 2 (backlog):** the hardcoded mirror is removed and
 * `PipelinePrefMirror` iterates `modules.flatMap { it.prefBindings() }`
 * as the single source.
 *
 * @param S the module's sub-state type
 * @param T the binding's value type (read from `SharedPreferences`,
 *   written into the sub-state via [write]).
 * @property prefKey the SharedPreferences key as a string (matches
 *   `Pref.X.key` in `DictatePrefs.kt`).
 * @property read function reading the typed value from a
 *   `SharedPreferences` snapshot.
 * @property write function applying the value to a sub-state instance,
 *   returning the updated sub-state.
 *
 * @see net.devemperor.dictate.state.DictateModule.prefBindings
 */
data class PrefBinding<S, T>(
    val prefKey: String,
    val read: (android.content.SharedPreferences) -> T,
    val write: (S, T) -> S,
)
