// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import android.content.ClipData
import kotlin.reflect.KClass

/**
 * Owns the [KeyboardInputState] axis and forwards direct IME-input
 * actions (Backspace / Enter / Space / Clipboard-Copy) as side-effects
 * on the `InputConnection` and the system clipboard.
 *
 * **Why the state axis?**
 *
 * Originally this module was `S = Unit` — pure effect-translator. With
 * the Enter-button cutover
 * (`docs/plans/2026-05-23 - dictate-enter-button-host-action/`) the
 * module gained `KeyboardInputState.hostEditor`: the `EditorInfo`
 * snapshot of the currently-focused host editor. Catalog
 * `iconResolver` and `actionResolver` for the ENTER slot both read this
 * axis, so icon (Send / Search / Check / Return) and action
 * (`performEditorAction(SEND)` vs `commitText("\n", 1)` vs
 * `sendKeyEvent(KEYCODE_ENTER)`) cannot drift apart.
 *
 * The module is still the **single owner** of every Dictate Action
 * involving the host `InputConnection` (F-8 invariant) — placing the
 * host-editor snapshot on the same module keeps the
 * "all keyboard-input concerns in one place" SRP intact and avoids a
 * second module whose sole responsibility would be a unit fan-out.
 *
 * **Failure handling:** the effect handler returns no-op when the
 * `InputConnection` is `null` (Editor lost focus) — this is the
 * documented "no input target" state, not an error condition. No
 * `EffectFailure` is emitted, matching legacy behaviour (the existing
 * `DictateInputMethodService` already treats null InputConnection as
 * no-op).
 *
 * **Cross-module observer:** none — host-editor state is consumed by
 * Catalog resolvers (pull-from-state via lens read), not pushed to
 * other modules. Spec 1 §15.1.x explicitly omits KeyboardInputModule
 * from the Cross-Module-Coupling-Matrix.
 *
 * @see net.devemperor.dictate.state.Action.KeyboardInputAction
 * @see net.devemperor.dictate.state.KeyboardInputState
 * @see net.devemperor.dictate.state.HostEditorState
 * @see net.devemperor.dictate.state.DictateOrchestrator
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md
 * @see docs/plans/2026-05-23 - dictate-enter-button-host-action/dictate-enter-button-host-action.md
 */
object KeyboardInputModule : DictateModule<KeyboardInputState, Action.KeyboardInputAction, KeyboardInputModule.Effect> {

    override val id: ModuleId = ModuleId.KeyboardInput
    override val actionClass: KClass<Action.KeyboardInputAction> = Action.KeyboardInputAction::class

    override fun read(global: DictateUiState): KeyboardInputState = global.keyboardInput
    override fun write(global: DictateUiState, sub: KeyboardInputState): DictateUiState =
        global.copy(keyboardInput = sub)
    override fun initialState(): KeyboardInputState = KeyboardInputState()

    /**
     * Module-local effect surface — one variant per input op.
     *
     * `SendEnter` was replaced by [PerformEnter] in the cutover plan —
     * the old single-shape effect (`commitText("\n", 1)`) was wrong for
     * every editor that declared an `imeOptions` action (Browser GO,
     * Chat SEND, Maps SEARCH, Form NEXT, Custom Action, …). The new
     * effect carries the [net.devemperor.dictate.state.layout.EnterButtonRole]
     * the reducer picked from [HostEditorState] plus the custom
     * actionId so the handler can branch between
     * `performEditorAction`, `commitText("\n", 1)`, and the
     * pre-bind `sendKeyEvent(KEYCODE_ENTER)` fallback in one place.
     */
    sealed interface Effect : SideEffect {
        data object SendBackspace : Effect
        data object SendSpace : Effect
        data class CopyToClipboard(val text: String) : Effect

        /**
         * Perform the Enter-button's semantic action against the current
         * `InputConnection`. The reducer pre-computes [role] from
         * [HostEditorState] (via
         * `net.devemperor.dictate.state.layout.resolveEnterRole`) and
         * [actionId] via `actionIdForEnter`, so the handler stays a
         * simple two-way branch.
         *
         * @property role what the user expects to happen — drives the
         *   branch in [runEffect]:
         *   `NEWLINE → commitText("\n", 1)`,
         *   any other role (GO/SEARCH/SEND/NEXT/PREVIOUS/DONE/CUSTOM)
         *   → `performEditorAction(actionId)`.
         * @property actionId payload for `performEditorAction` —
         *   `IME_ACTION_*` constant for the standard roles, the host
         *   editor's `EditorInfo.actionId` for `CUSTOM`. Ignored when
         *   [role] is `NEWLINE` (sentinel `0`).
         */
        data class PerformEnter(
            val role: net.devemperor.dictate.state.layout.EnterButtonRole,
            val actionId: Int,
        ) : Effect

        /**
         * Physical-keystroke fallback for the pre-bind window — the IME
         * has no `EditorInfo` yet so an action-routed Enter would have
         * no target. `sendKeyEvent(KEYCODE_ENTER)` also produces DOM
         * `keydown`/`keyup` events in WebViews, which is the closest
         * thing to "press the hardware Enter key" the IME can do.
         */
        data object SendPhysicalEnter : Effect
    }

    override fun reduce(
        state: KeyboardInputState,
        action: Action.KeyboardInputAction,
        ctx: ReducerContext,
    ): TransitionResult<KeyboardInputState, Effect>? = when (action) {
        Action.KeyboardInputAction.Backspace ->
            TransitionResult(nextState = state, sideEffects = listOf(Effect.SendBackspace))
        Action.KeyboardInputAction.EnterKey ->
            reduceEnterKey(state)
        Action.KeyboardInputAction.SpaceKey ->
            TransitionResult(nextState = state, sideEffects = listOf(Effect.SendSpace))
        is Action.KeyboardInputAction.CopyToClipboard ->
            TransitionResult(
                nextState = state,
                sideEffects = listOf(Effect.CopyToClipboard(action.text)),
            )
        is Action.KeyboardInputAction.HostEditorAttached ->
            TransitionResult(nextState = state.copy(hostEditor = action.state), sideEffects = emptyList())
        Action.KeyboardInputAction.HostEditorDetached ->
            TransitionResult(nextState = state.copy(hostEditor = HostEditorState()), sideEffects = emptyList())
    }

    /**
     * Picks the right [Effect] flavour for an `EnterKey` Action. The
     * Role is derived from the current [HostEditorState] via
     * [net.devemperor.dictate.state.layout.resolveEnterRole]; the
     * pre-bind fallback (no `EditorInfo` ever attached) emits the
     * physical-keystroke variant so the user can type Enter even
     * before the IME has been told about any editor.
     */
    private fun reduceEnterKey(state: KeyboardInputState): TransitionResult<KeyboardInputState, Effect> {
        val host = state.hostEditor
        val effect = if (!host.hasEditorInfo) {
            Effect.SendPhysicalEnter
        } else {
            val role = net.devemperor.dictate.state.layout.resolveEnterRole(host)
            val actionId = net.devemperor.dictate.state.layout.actionIdForEnter(role, host)
            Effect.PerformEnter(role, actionId)
        }
        return TransitionResult(nextState = state, sideEffects = listOf(effect))
    }

    // P4 keystroke-path migration: every host-IC write funnels through the
    // single InsertionService owner. KEYSTROKE policy reproduces the legacy
    // behaviour exactly (instant, no auto-enter, no host-guard, no audit, no
    // resume). A null provider (IME-View detached) is a no-op, identical to
    // the legacy `inputConnectionProvider()?.…` null behaviour.
    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        Effect.SendBackspace -> {
            services.insertionServiceProvider()?.control(
                net.devemperor.dictate.state.insertion.ControlOp.Backspace)
            Unit
        }
        Effect.SendSpace -> {
            services.insertionServiceProvider()?.insert(
                net.devemperor.dictate.state.insertion.InsertionRequest(
                    " ",
                    null,
                    net.devemperor.dictate.state.insertion.InsertionPolicy.KEYSTROKE,
                    null,
                    null,
                ))
            Unit
        }
        is Effect.CopyToClipboard -> {
            services.clipboard?.setPrimaryClip(ClipData.newPlainText("dictate", effect.text))
            Unit
        }
        is Effect.PerformEnter -> {
            services.insertionServiceProvider()?.control(
                net.devemperor.dictate.state.insertion.ControlOp.Enter(effect.role, effect.actionId))
            Unit
        }
        Effect.SendPhysicalEnter -> {
            services.insertionServiceProvider()?.control(
                net.devemperor.dictate.state.insertion.ControlOp.PhysicalEnter)
            Unit
        }
    }
}
