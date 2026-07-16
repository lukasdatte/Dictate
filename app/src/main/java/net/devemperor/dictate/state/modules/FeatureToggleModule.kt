// Lives in the `modules/` sub-directory but in the **parent package**
// `net.devemperor.dictate.state` because `DictateModule` is a
// `sealed interface` — Kotlin's sealed-type rule restricts implementations
// to the same package.
package net.devemperor.dictate.state

import net.devemperor.dictate.preferences.Pref
import kotlin.reflect.KClass

/**
 * Owns the [FeatureToggles] axis — the user-toggle booleans
 * (`rewordingEnabled`, `autoFormattingEnabled`, `instantOutputEnabled`,
 * `autoEnterEnabled`, plus PC send-mode and screen context). All are
 * Pref-mirrored via `PipelinePrefMirror` (C7) except
 * `screenContextAvailable`, which mirrors a SYSTEM setting the app can only
 * observe — the IME pushes it in via
 * [Action.FeatureToggleAction.SetScreenContextAvailable].
 *
 * **`ToggleVibration` deviation note (Phase 1):**
 *
 * [Action.FeatureToggleAction.ToggleVibration] exists in the action
 * hierarchy because the legacy UI groups all five toggles together,
 * but `vibrationEnabled` lives on [AudioState] (owned by
 * [AudioModule]), not on [FeatureToggles]. Cross-axis writes are
 * forbidden by the lens (ADR-0001 §"Pure-Reducer Invariant"). For
 * Phase 1 the reducer returns `null` for `ToggleVibration`
 * (`DispatchOutcome.Rejected("reducer-null")`) — the legacy UI keeps
 * its own SP-write path until the action surface is corrected to
 * dispatch [Action.AudioAction] for vibration. The five-action
 * grouping is preserved so the Action hierarchy is stable across
 * phases.
 *
 * **No cross-module observer:** Spec 1 §15.1 explicitly lists this
 * module as observer-free. Pipeline reads `features.autoEnterEnabled`
 * during the auto-enter flow (Coupling-Matrix §15.1.x row
 * `FeatureToggle × Pipeline`) — that's a Pipeline-side read, not a
 * FeatureToggle cascade.
 *
 * **Effects — one, and why only one.** The four original toggles have no
 * effects: their only surface is the settings screen, which writes
 * SharedPreferences directly and lets `PipelinePrefMirror` carry the value
 * back into state (C7). `ToggleWindowsAutoSend` and `ToggleScreenContext` are
 * different — their primary surface is a keyboard button, so nothing else
 * would perform the write. They persist via [Effect.PersistWindowsAutoSend] /
 * [Effect.PersistScreenContext], the same shape `AudioModule` uses for
 * `ToggleAudioFocusPref`.
 *
 * @see net.devemperor.dictate.state.FeatureToggles
 * @see net.devemperor.dictate.state.Action.FeatureToggleAction
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §15.1
 */
object FeatureToggleModule : DictateModule<FeatureToggles, Action.FeatureToggleAction, FeatureToggleModule.Effect> {

    override val id: ModuleId = ModuleId.FeatureToggle
    override val actionClass: KClass<Action.FeatureToggleAction> = Action.FeatureToggleAction::class

    override fun read(global: DictateUiState): FeatureToggles = global.features
    override fun write(global: DictateUiState, sub: FeatureToggles): DictateUiState =
        global.copy(features = sub)

    override fun initialState(): FeatureToggles = FeatureToggles()

    /**
     * See the "Effects — one, and why only one" note in the module KDoc.
     */
    sealed interface Effect : SideEffect {
        /** Persist `Pref.WindowsAutoSendEnabled`; the mirror re-derives state. */
        data class PersistWindowsAutoSend(val value: Boolean) : Effect

        /** Persist `Pref.AccessibilityContextEnabled`; the mirror re-derives state. */
        data class PersistScreenContext(val value: Boolean) : Effect
    }

    override fun reduce(
        state: FeatureToggles,
        action: Action.FeatureToggleAction,
        ctx: ReducerContext,
    ): TransitionResult<FeatureToggles, Effect>? = when (action) {

        Action.FeatureToggleAction.ToggleRewording -> TransitionResult(
            nextState = state.copy(rewordingEnabled = !state.rewordingEnabled),
            sideEffects = emptyList(),
        )

        Action.FeatureToggleAction.ToggleAutoFormatting -> TransitionResult(
            nextState = state.copy(autoFormattingEnabled = !state.autoFormattingEnabled),
            sideEffects = emptyList(),
        )

        Action.FeatureToggleAction.ToggleInstantOutput -> TransitionResult(
            nextState = state.copy(instantOutputEnabled = !state.instantOutputEnabled),
            sideEffects = emptyList(),
        )

        Action.FeatureToggleAction.ToggleAutoEnter -> TransitionResult(
            nextState = state.copy(autoEnterEnabled = !state.autoEnterEnabled),
            sideEffects = emptyList(),
        )

        Action.FeatureToggleAction.ToggleWindowsAutoSend -> {
            // No pairing → reject. Lighting the button while every dictation
            // still lands in the host field would be a lie, and ADR-0019's
            // gate would veto the send anyway. The button is disabled without
            // a pairing, so this is the backstop, not the primary guard.
            if (!state.windowsPaired) {
                null
            } else {
                // Paired ⇒ `windowsAutoSendActive == Pref.WindowsAutoSendEnabled`
                // (the pairing half of the predicate is satisfied), so
                // inverting the effective flag is the same as inverting the
                // pref — no need to carry the raw toggle as a second field.
                val next = !state.windowsAutoSendActive
                TransitionResult(
                    // Flip optimistically so the button lights on this frame;
                    // the mirror re-derives the same value from the predicate
                    // once the SP write lands (idempotent, absorbed as a no-op).
                    nextState = state.copy(windowsAutoSendActive = next),
                    sideEffects = listOf(Effect.PersistWindowsAutoSend(next)),
                )
            }
        }

        Action.FeatureToggleAction.ToggleScreenContext -> {
            // No service → reject. The button would claim the model can see the
            // screen while every read returns null.
            if (!state.screenContextAvailable) {
                null
            } else {
                val next = !state.screenContextEnabled
                TransitionResult(
                    nextState = state.copy(screenContextEnabled = next),
                    sideEffects = listOf(Effect.PersistScreenContext(next)),
                )
            }
        }

        is Action.FeatureToggleAction.SetScreenContextAvailable ->
            if (state.screenContextAvailable == action.available) {
                // Pushed on every keyboard-visible tick; only a real change is
                // worth a state emit.
                null
            } else {
                TransitionResult(
                    nextState = state.copy(screenContextAvailable = action.available),
                    sideEffects = emptyList(),
                )
            }

        is Action.FeatureToggleAction.SetPcOnly ->
            if (state.pcOnly == action.active) {
                // The Activity may re-push on every resume/rebind; only a real
                // change is worth a state emit. No effect — pcOnly is purely
                // transient (never persisted), owned by the Activity lifecycle.
                null
            } else {
                TransitionResult(
                    nextState = state.copy(pcOnly = action.active),
                    sideEffects = emptyList(),
                )
            }

        // See "ToggleVibration deviation" in the module KDoc.
        Action.FeatureToggleAction.ToggleVibration -> null
    }

    override fun runEffect(effect: Effect, services: ModuleServices) = when (effect) {
        is Effect.PersistWindowsAutoSend ->
            services.prefs.persist(Pref.WindowsAutoSendEnabled, effect.value)

        is Effect.PersistScreenContext ->
            services.prefs.persist(Pref.AccessibilityContextEnabled, effect.value)
    }
}
