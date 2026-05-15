package net.devemperor.dictate.state

import android.content.SharedPreferences
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get

/**
 * SharedPreferences ↔ [DictateUiStateStore] mirror.
 *
 * **Phase 1 (today) — hardcoded.** [attach] writes a one-shot snapshot
 * of 19 UI-state-relevant prefs into the store on startup, then
 * registers an [SharedPreferences.OnSharedPreferenceChangeListener]
 * that translates every subsequent pref change into a focused
 * `store.update { … }` call on the matching sub-state axis. The
 * mapping is hand-rolled here because [DictateModule.prefBindings]
 * is Phase-2 surface — see [PrefBinding] KDoc.
 *
 * **The 19 mirrored prefs (Spec 1 §4.5):**
 *
 * | Axis | Count | Prefs |
 * |------|-------|-------|
 * | [LayoutState] | 3 | `SingleRowMode`, `SmallMode`, `Animations` |
 * | [AudioState] | 3 | `AudioFocus` (→ `audioFocusEnabledPref`), `UseBluetoothMic`, `Vibration` |
 * | [ResendState] | 1 | `ResendButton` (→ `resendEnabled`) |
 * | [FeatureToggles] | 4 | `RewordingEnabled`, `AutoFormattingEnabled`, `InstantOutput`, `AutoEnter` |
 * | [ThemingState] | 4 | `Theme`, `AccentColor`, `OverlayCharacters`, `OutputSpeed` |
 * | [OverlayState] | 4 | `overlay_pos_portrait_x/y`, `overlay_pos_landscape_x/y` (raw keys — no [Pref] entry today, owned by [OverlayModule.Effect.PersistOverlayPosition]) |
 *
 * **Lifecycle contract:** [attach] is called from
 * [DictateOrchestrator]'s `init { … }` block **synchronously**, before
 * the async [PipelineRecovery.recover] launch — this guarantees that
 * the IME-side `state.collect { … }` sees mirrored pref values rather
 * than `DictateUiState.initial()` defaults from the moment the binder
 * is returned (Spec 1 §11.2.2 Block-1b step 7 + Phase-B S-1
 * acceptance). [detach] is called from [DictateOrchestrator.shutdown]
 * before the per-module `terminate()` loop so no late SP-listener-fire
 * mutates the store after it logically ended its life.
 *
 * **Why a single hardcoded `sync(key)` switch (not a list of
 * [PrefBinding]s)?** In Phase 1 the modules are still being migrated;
 * adding the indirection now would force module-internal `prefBindings()`
 * surfaces before B3 wires the legacy controllers. Hardcoded mapping
 * keeps the change-set small and the wiring single-purpose. Phase 2
 * (Hauptplan §7.1 Out-of-Scope) replaces this body with a
 * `modules.flatMap { it.prefBindings() }` iterator — same call-site,
 * different body.
 *
 * **Threading:** [SharedPreferences.OnSharedPreferenceChangeListener]
 * fires on an arbitrary thread (the framework documents it as the
 * thread that called `commit`/`apply` — typically a background thread
 * for `apply` and the main thread for `commit`). [DictateUiStateStore.update]
 * is thread-safe (CAS-loop on `MutableStateFlow`), so the listener can
 * fire from any thread without coordination.
 *
 * @property sp the application's default `SharedPreferences`.
 *
 * @see net.devemperor.dictate.state.DictateOrchestrator
 * @see net.devemperor.dictate.state.PipelineRecovery
 * @see docs/decisions/0001-state-modular-orchestrator-pattern.md §"Required mechanics" item 7
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §4.5
 */
class PipelinePrefMirror(
    private val sp: SharedPreferences,
) {

    /** Set inside [attach]; cleared inside [detach]. `null` outside the lifecycle. */
    private var store: DictateUiStateStore? = null

    /**
     * Listener instance — stored as a field so [detach] can unregister
     * exactly the same instance. Constructed once in the primary
     * constructor (cheap, no captures beyond `this`).
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> sync(key) }

    /**
     * Snapshot all 19 prefs into [store], then register the change
     * listener. Idempotent against double-attach (a second [attach]
     * with a different [store] would silently replace — the listener
     * holds `this` not a per-store reference, so re-registering the
     * same listener is a no-op on Android).
     *
     * **Must be called from [DictateOrchestrator]'s `init { … }` block
     * before the async `recovery.recover` launch** so the initial
     * mirror is in place when the IME first reads `state.value`.
     */
    fun attach(store: DictateUiStateStore) {
        this.store = store
        store.update { initialMirror(it) }
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Unregister the listener and drop the [store] reference. Called
     * from [DictateOrchestrator.shutdown] before per-module
     * `terminate()` — ensures no late SP listener writes into a
     * logically-dead store.
     */
    fun detach() {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
        store = null
    }

    // ────────────────────────────────────────────────────────────────
    // Hardcoded 19-pref mirror (Spec 1 §4.5)
    // ────────────────────────────────────────────────────────────────

    /**
     * One-shot snapshot of all 19 prefs into [current], producing the
     * next [DictateUiState]. Called exactly once from [attach].
     *
     * Each axis is rebuilt via `current.<axis>.copy(...)` rather than a
     * fresh sub-state instance so unrelated sub-state fields (set by
     * recovery, module reducers, or other test paths before attach)
     * survive — defensive against future call-order edits.
     */
    private fun initialMirror(current: DictateUiState): DictateUiState = current.copy(
        layout = current.layout.copy(
            singleRowMode = sp.get(Pref.SingleRowMode),
            smallMode = sp.get(Pref.SmallMode),
            animationsEnabled = sp.get(Pref.Animations),
        ),
        audio = current.audio.copy(
            audioFocusEnabledPref = sp.get(Pref.AudioFocus),
            useBluetoothMic = sp.get(Pref.UseBluetoothMic),
            vibrationEnabled = sp.get(Pref.Vibration),
        ),
        resend = current.resend.copy(
            resendEnabled = sp.get(Pref.ResendButton),
        ),
        features = current.features.copy(
            rewordingEnabled = sp.get(Pref.RewordingEnabled),
            autoFormattingEnabled = sp.get(Pref.AutoFormattingEnabled),
            instantOutputEnabled = sp.get(Pref.InstantOutput),
            autoEnterEnabled = sp.get(Pref.AutoEnter),
        ),
        theming = current.theming.copy(
            theme = sp.get(Pref.Theme),
            accentColor = sp.get(Pref.AccentColor),
            overlayCharacters = sp.get(Pref.OverlayCharacters),
            outputSpeed = sp.get(Pref.OutputSpeed),
        ),
        overlay = current.overlay.copy(
            positionPortraitX = sp.getFloat(OVERLAY_POS_PORTRAIT_X_KEY, current.overlay.positionPortraitX),
            positionPortraitY = sp.getFloat(OVERLAY_POS_PORTRAIT_Y_KEY, current.overlay.positionPortraitY),
            positionLandscapeX = sp.getFloat(OVERLAY_POS_LANDSCAPE_X_KEY, current.overlay.positionLandscapeX),
            positionLandscapeY = sp.getFloat(OVERLAY_POS_LANDSCAPE_Y_KEY, current.overlay.positionLandscapeY),
        ),
    )

    /**
     * Translate a single pref-change event into a focused
     * `store.update { … }` call. Unknown keys are ignored — the
     * `else`-branch is a no-op (NOT a `Log.w`) because production prefs
     * include many keys we deliberately don't mirror (API keys, prompt
     * selection, etc.) and noisy logs would mask real bugs.
     *
     * **No coalescing / debouncing** — every change emits exactly one
     * state mutation. Coalescing would risk a race with the
     * `OverlayModule.Effect.PersistOverlayPosition` write-path (the
     * effect updates the SP within the same dispatch loop; debouncing
     * the listener would let one drag-end event coalesce with another
     * after the state already reflects the new position, masking real
     * regressions in tests).
     */
    private fun sync(key: String?) {
        val targetStore = store ?: return
        targetStore.update { current -> applyChange(current, key) }
    }

    /**
     * Pure mapping from `(currentState, key)` to `nextState`. Kept
     * package-private and `internal` so [PipelinePrefMirrorTest] can
     * exercise the switch without driving a [SharedPreferences]
     * listener (avoiding the framework-thread-dispatch flake).
     */
    internal fun applyChange(current: DictateUiState, key: String?): DictateUiState = when (key) {
        Pref.SingleRowMode.key ->
            current.copy(layout = current.layout.copy(singleRowMode = sp.get(Pref.SingleRowMode)))
        Pref.SmallMode.key ->
            current.copy(layout = current.layout.copy(smallMode = sp.get(Pref.SmallMode)))
        Pref.Animations.key ->
            current.copy(layout = current.layout.copy(animationsEnabled = sp.get(Pref.Animations)))

        Pref.AudioFocus.key ->
            current.copy(audio = current.audio.copy(audioFocusEnabledPref = sp.get(Pref.AudioFocus)))
        Pref.UseBluetoothMic.key ->
            current.copy(audio = current.audio.copy(useBluetoothMic = sp.get(Pref.UseBluetoothMic)))
        Pref.Vibration.key ->
            current.copy(audio = current.audio.copy(vibrationEnabled = sp.get(Pref.Vibration)))

        Pref.ResendButton.key ->
            current.copy(resend = current.resend.copy(resendEnabled = sp.get(Pref.ResendButton)))

        Pref.RewordingEnabled.key ->
            current.copy(features = current.features.copy(rewordingEnabled = sp.get(Pref.RewordingEnabled)))
        Pref.AutoFormattingEnabled.key ->
            current.copy(features = current.features.copy(autoFormattingEnabled = sp.get(Pref.AutoFormattingEnabled)))
        Pref.InstantOutput.key ->
            current.copy(features = current.features.copy(instantOutputEnabled = sp.get(Pref.InstantOutput)))
        Pref.AutoEnter.key ->
            current.copy(features = current.features.copy(autoEnterEnabled = sp.get(Pref.AutoEnter)))

        Pref.Theme.key ->
            current.copy(theming = current.theming.copy(theme = sp.get(Pref.Theme)))
        Pref.AccentColor.key ->
            current.copy(theming = current.theming.copy(accentColor = sp.get(Pref.AccentColor)))
        Pref.OverlayCharacters.key ->
            current.copy(theming = current.theming.copy(overlayCharacters = sp.get(Pref.OverlayCharacters)))
        Pref.OutputSpeed.key ->
            current.copy(theming = current.theming.copy(outputSpeed = sp.get(Pref.OutputSpeed)))

        OVERLAY_POS_PORTRAIT_X_KEY -> current.copy(
            overlay = current.overlay.copy(
                positionPortraitX = sp.getFloat(OVERLAY_POS_PORTRAIT_X_KEY, current.overlay.positionPortraitX),
            ),
        )
        OVERLAY_POS_PORTRAIT_Y_KEY -> current.copy(
            overlay = current.overlay.copy(
                positionPortraitY = sp.getFloat(OVERLAY_POS_PORTRAIT_Y_KEY, current.overlay.positionPortraitY),
            ),
        )
        OVERLAY_POS_LANDSCAPE_X_KEY -> current.copy(
            overlay = current.overlay.copy(
                positionLandscapeX = sp.getFloat(OVERLAY_POS_LANDSCAPE_X_KEY, current.overlay.positionLandscapeX),
            ),
        )
        OVERLAY_POS_LANDSCAPE_Y_KEY -> current.copy(
            overlay = current.overlay.copy(
                positionLandscapeY = sp.getFloat(OVERLAY_POS_LANDSCAPE_Y_KEY, current.overlay.positionLandscapeY),
            ),
        )

        else -> current
    }

    companion object {
        /**
         * Overlay-position pref keys — raw strings because
         * [net.devemperor.dictate.preferences.Pref] does not (yet)
         * have entries for them. The canonical write site is
         * [net.devemperor.dictate.state.modules.OverlayModule]
         * `Effect.PersistOverlayPosition`, which uses the same raw
         * strings. Promoting them into [Pref] is a Phase-2 cleanup
         * not in C7 scope.
         */
        const val OVERLAY_POS_PORTRAIT_X_KEY: String = "overlay_pos_portrait_x"
        const val OVERLAY_POS_PORTRAIT_Y_KEY: String = "overlay_pos_portrait_y"
        const val OVERLAY_POS_LANDSCAPE_X_KEY: String = "overlay_pos_landscape_x"
        const val OVERLAY_POS_LANDSCAPE_Y_KEY: String = "overlay_pos_landscape_y"
    }
}
