// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import android.content.ClipData
import kotlin.reflect.KClass

/**
 * Forwards direct IME-input actions (Backspace / Enter / Space /
 * Clipboard-Copy) as side-effects on the `InputConnection` and the
 * system clipboard. **Owns no sub-state** — the type parameter `S` is
 * `Unit` because the operations live outside the [DictateUiState] (in
 * the system InputConnection buffer and clipboard).
 *
 * **Why have a module at all? (Spec 1 §15.6):**
 *
 * The F-8 Single-Dispatch invariant says every state-mutating Action
 * MUST flow through [DictateOrchestrator.dispatch]. Without a module
 * owner for [Action.KeyboardInputAction], Backspace / Enter / Space
 * clicks would be silently dropped as `DispatchOutcome.Unrouted` —
 * resolver code (`ButtonSlot.actionResolver`) is required to return
 * `Action?`, so it can't directly call the IME service. Routing
 * through this module keeps the resolver layer trivial and the
 * dispatch invariant intact.
 *
 * **Trivial reducer:** each Action is translated 1:1 into the
 * corresponding [Effect]; `state` stays `Unit`, only the
 * `sideEffects` list propagates.
 *
 * **Failure handling:** the effect handler returns no-op when the
 * `InputConnection` is `null` (Editor lost focus) — this is the
 * documented "no input target" state, not an error condition. No
 * `EffectFailure` is emitted, matching legacy behaviour (the existing
 * `DictateInputMethodService` already treats null InputConnection as
 * no-op).
 *
 * **No cross-module observer:** Unit-state, no inbound coupling, no
 * outbound coupling. Spec 1 §15.1.x explicitly omits KeyboardInputModule
 * from the Cross-Module-Coupling-Matrix.
 *
 * @see net.devemperor.dictate.state.Action.KeyboardInputAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.6
 */
object KeyboardInputModule : DictateModule<Unit, Action.KeyboardInputAction, KeyboardInputModule.Effect> {

    override val id: ModuleId = ModuleId.KeyboardInput
    override val actionClass: KClass<Action.KeyboardInputAction> = Action.KeyboardInputAction::class

    override fun read(global: DictateUiState) = Unit
    override fun write(global: DictateUiState, sub: Unit): DictateUiState = global
    override fun initialState() = Unit

    /**
     * Module-local effect surface — one variant per input op.
     * `data object` for the simple keystrokes; `data class` for
     * clipboard since it carries a payload (effect-identifier note:
     * `effect.toString()` includes the args, so any future
     * `reduceFailure` arm would use `startsWith("CopyToClipboard(")`).
     */
    sealed interface Effect : SideEffect {
        data object SendBackspace : Effect
        data object SendEnter : Effect
        data object SendSpace : Effect
        data class CopyToClipboard(val text: String) : Effect
    }

    override fun reduce(
        state: Unit,
        action: Action.KeyboardInputAction,
        ctx: ReducerContext,
    ): TransitionResult<Unit, Effect>? = when (action) {
        Action.KeyboardInputAction.Backspace ->
            TransitionResult(nextState = Unit, sideEffects = listOf(Effect.SendBackspace))
        Action.KeyboardInputAction.EnterKey ->
            TransitionResult(nextState = Unit, sideEffects = listOf(Effect.SendEnter))
        Action.KeyboardInputAction.SpaceKey ->
            TransitionResult(nextState = Unit, sideEffects = listOf(Effect.SendSpace))
        is Action.KeyboardInputAction.CopyToClipboard ->
            TransitionResult(
                nextState = Unit,
                sideEffects = listOf(Effect.CopyToClipboard(action.text)),
            )
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        Effect.SendBackspace -> {
            services.inputConnectionProvider()?.deleteSurroundingText(1, 0)
            Unit
        }
        Effect.SendEnter -> {
            services.inputConnectionProvider()?.commitText("\n", 1)
            Unit
        }
        Effect.SendSpace -> {
            services.inputConnectionProvider()?.commitText(" ", 1)
            Unit
        }
        is Effect.CopyToClipboard -> {
            services.clipboard?.setPrimaryClip(ClipData.newPlainText("dictate", effect.text))
            Unit
        }
    }
}
