package net.devemperor.dictate.state

import android.content.SharedPreferences
import net.devemperor.dictate.preferences.LanguageResolver
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.windows.WindowsAutoSend

/**
 * Sink the mirror calls to apply a state mutation that **also runs
 * cross-module observers** (review-fix G3, 2026-05-21).
 *
 * The mirror used to call `store.update { applyChange(...) }` directly,
 * which mutated the store but bypassed `DictateOrchestrator.dispatchInternal`
 * Step 5 (cross-module cascade). The result was a latent regression:
 * `AudioModule.onCrossModuleStateChange` already contains an
 * `audioFocusEnabledPref-delta && Active → ApplyAudioFocusRuntimeFromPref`
 * cascade (Chunk 3.5 D-1), but that cascade was unreachable from external
 * SP writes — the very R-5 scenario the plan called out.
 *
 * This abstraction lets the orchestrator own the cascade-engine without
 * leaking the store to the mirror. The orchestrator implements it as
 * a method that mirrors `dispatchInternal` Steps 3 (state write) + 5/6
 * (observer cascade + recursive dispatch), starting at `depth = 0` (an
 * external SP change is a fresh pass, not a continuation of an in-flight
 * action dispatch).
 *
 * @see PipelinePrefMirror.sync
 * @see DictateOrchestrator.runMirrorSync
 */
fun interface MirrorSyncDispatcher {
    /**
     * Apply [reducer] to the current state, then run cross-module
     * observers against the resulting (prev, next) tuple and dispatch
     * any cascade actions through the orchestrator's normal pipeline.
     *
     * Idempotent against value-equal mutations: if `reducer(prev) == prev`
     * the observers still run, but the
     * `prev != next` gates in `onCrossModuleStateChange` arms suppress
     * any cascade work.
     */
    fun apply(reducer: (DictateUiState) -> DictateUiState)
}

/**
 * SharedPreferences ↔ [DictateUiStateStore] mirror.
 *
 * **Phase 1 (today) — hardcoded.** [attach] writes a one-shot snapshot
 * of 20 UI-state-relevant prefs into the store on startup, then
 * registers an [SharedPreferences.OnSharedPreferenceChangeListener]
 * that translates every subsequent pref change into a focused
 * `dispatcher.apply { … }` call on the matching sub-state axis (which
 * runs cross-module observers — see [MirrorSyncDispatcher] KDoc). The
 * mapping is hand-rolled here because [DictateModule.prefBindings]
 * is Phase-2 surface — see [PrefBinding] KDoc.
 *
 * **The 20 mirrored prefs (Spec 1 §4.5 + F-118 widget opacity):**
 *
 * | Axis | Count | Prefs |
 * |------|-------|-------|
 * | [LayoutState] | 3 | `SingleRowMode`, `SmallMode`, `Animations` |
 * | [AudioState] | 3 | `AudioFocus` (→ `audioFocusEnabledPref`), `UseBluetoothMic`, `Vibration` |
 * | [ResendState] | 1 | `ResendButton` (→ `resendEnabled`) |
 * | [FeatureToggles] | 4 | `RewordingEnabled`, `AutoFormattingEnabled`, `InstantOutput`, `AutoEnter` |
 * | [ThemingState] | 5 | `Theme`, `AccentColor`, `OverlayCharacters`, `OutputSpeed`, `WidgetOpacity` |
 * | [OverlayState] | 4 | `Pref.OverlayPositionPortraitX/Y`, `Pref.OverlayPositionLandscapeX/Y` (typed since F-4; mirrors [OverlayModule.Effect.PersistOverlayPosition] write site) |
 * | [LanguageState] | 2 (computed) | `Pref.InputLanguages` + `Pref.InputLanguagePos` together resolve to `LanguageState.effective` via `LanguageResolver.effectiveLanguage(sp)` (indirection-cleanup 2026-05-21, OQ-1 Option A). Both keys fan into the same `effective`-write because either changes the resolved code. |
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

    /**
     * Set inside [attach]; cleared inside [detach]. `null` outside the lifecycle.
     *
     * **`@Volatile` (F-5):** `attach`/`detach` are called from the Main
     * thread (per [DictateOrchestrator.shutdown]'s contract), but
     * [sync] reads this field from arbitrary threads — Android's
     * `OnSharedPreferenceChangeListener` fires on the thread that
     * called `apply()`/`commit()`, typically a background disk
     * thread for `apply()`. Without a publication barrier the JVM
     * memory model doesn't guarantee `detach()`'s `null` write is
     * visible to a concurrent reader, which could let a late
     * listener-fire mutate a logically-dead store. The
     * `currentDispatcher = dispatcher ?: return` read-once-into-local
     * pattern in [sync] is already correct under the `@Volatile`
     * guarantee.
     *
     * **Review-fix G3 (2026-05-21):** field renamed from `store` to
     * `dispatcher`. The mirror no longer holds a raw store reference —
     * runtime sync goes through [MirrorSyncDispatcher] so cross-module
     * observers fire on external SP writes. [attach] still receives a
     * [DictateUiStateStore] for the initial-snapshot one-shot write,
     * which is held in a separate local + then released — observers do
     * not need to fire for the boot-time snapshot (nothing else is
     * subscribed yet, and recovery / module reducers have not run).
     */
    @Volatile private var dispatcher: MirrorSyncDispatcher? = null

    /**
     * Listener instance — stored as a field so [detach] can unregister
     * exactly the same instance. Constructed once in the primary
     * constructor (cheap, no captures beyond `this`).
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> sync(key) }

    /**
     * Snapshot all 19 prefs into [store] (one-shot), wire [dispatcher]
     * for runtime sync, then register the change listener. Idempotent
     * against double-attach (a second [attach] with a different
     * dispatcher silently replaces — the listener holds `this`, not a
     * per-dispatcher reference, so re-registering the same listener is
     * a no-op on Android).
     *
     * **Must be called from [DictateOrchestrator]'s `init { … }` block
     * before the async `recovery.recover` launch** so the initial
     * mirror is in place when the IME first reads `state.value`.
     *
     * **G3 (2026-05-21):** the boot-snapshot mutation goes directly via
     * `store.update` (no cascade) because nothing else is subscribed yet
     * — observer cascades on the snapshot would be a no-op against the
     * initial state. Runtime [sync] mutations route through [dispatcher]
     * so cross-module observers fire on external SP writes (Settings
     * Activity etc.).
     *
     * @param store the [DictateUiStateStore] for the one-shot
     *   initial-snapshot write. Not retained — the mirror only keeps
     *   the [dispatcher] reference for the lifecycle.
     * @param dispatcher the orchestrator's mirror-sink. Every runtime
     *   listener-fire is routed through this so cross-module observers
     *   run against the (prev, next) tuple.
     */
    fun attach(store: DictateUiStateStore, dispatcher: MirrorSyncDispatcher) {
        this.dispatcher = dispatcher
        store.update { initialMirror(it) }
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * Unregister the listener and drop the [dispatcher] reference.
     * Called from [DictateOrchestrator.shutdown] before per-module
     * `terminate()` — ensures no late SP listener writes into a
     * logically-dead store.
     */
    fun detach() {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
        dispatcher = null
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
            windowsAutoSendActive = WindowsAutoSend.shouldAutoSend(sp),
            windowsPaired = WindowsTarget.from(sp) != null,
            screenContextEnabled = sp.get(Pref.AccessibilityContextEnabled),
        ),
        theming = current.theming.copy(
            theme = sp.get(Pref.Theme),
            accentColor = sp.get(Pref.AccentColor),
            overlayCharacters = sp.get(Pref.OverlayCharacters),
            outputSpeed = sp.get(Pref.OutputSpeed),
            widgetOpacity = sp.get(Pref.WidgetOpacity),
        ),
        overlay = current.overlay.copy(
            positionPortraitX = sp.get(Pref.OverlayPositionPortraitX),
            positionPortraitY = sp.get(Pref.OverlayPositionPortraitY),
            positionLandscapeX = sp.get(Pref.OverlayPositionLandscapeX),
            positionLandscapeY = sp.get(Pref.OverlayPositionLandscapeY),
        ),
        // 2026-05-21 indirection-cleanup Chunk 4.5a (OQ-1 Option A) —
        // computed mirror: Pref.InputLanguages + Pref.InputLanguagePos
        // resolve via LanguageResolver.effectiveLanguage to
        // state.language.effective. Mirrors the SP→State direction
        // formerly held by the IME-side `inputLanguagesListener` (which
        // dispatched RefreshFromPref); now an integral part of the
        // mirror so external Settings-Activity writes are picked up
        // without a custom listener.
        language = current.language.copy(
            effective = LanguageResolver.effectiveLanguage(sp),
        ),
    )

    /**
     * Translate a single pref-change event into a focused
     * `dispatcher.apply { … }` call. Unknown keys are ignored — the
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
     *
     * **G3 (2026-05-21):** routed through [MirrorSyncDispatcher] so the
     * orchestrator's cross-module observer cascade fires against the
     * (prev, next) state tuple. Without this, an external Settings-
     * Activity write to a pref like `Pref.AudioFocus` mid-recording would
     * update the state but never reach `AudioModule.onCrossModuleStateChange`
     * → no `ApplyAudioFocusRuntimeFromPref` cascade → stale live
     * `AudioManager` (the latent regression R-5 described in plan §6.1).
     */
    private fun sync(key: String?) {
        val currentDispatcher = dispatcher ?: return
        currentDispatcher.apply { current -> applyChange(current, key) }
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
        // Only the opt-in is a pref. Whether the service is actually enabled is
        // a SYSTEM setting with no key here — the IME pushes that in via
        // SetScreenContextAvailable.
        Pref.AccessibilityContextEnabled.key ->
            current.copy(
                features = current.features.copy(
                    screenContextEnabled = sp.get(Pref.AccessibilityContextEnabled),
                ),
            )

        // PC send-mode (ADR-0019). FOUR keys collapse onto ONE field because
        // the answer is a predicate over all of them ("toggle on AND paired").
        // ADR-0019 makes WindowsAutoSend.shouldAutoSend the single owner of
        // that predicate and forbids copying it, so the mirror *calls* it
        // instead of re-deriving `toggle && paired` here — that is what keeps
        // the lit button and the actual send destination from ever disagreeing.
        // Any pairing key changing (pair, unpair, re-pair) re-derives both
        // fields, so an unpair silently drops PC-mode in the UI exactly as it
        // does in the pipeline.
        Pref.WindowsAutoSendEnabled.key,
        Pref.WindowsTargetUrl.key,
        Pref.WindowsDeviceId.key,
        Pref.WindowsDeviceSecret.key,
        ->
            current.copy(
                features = current.features.copy(
                    windowsAutoSendActive = WindowsAutoSend.shouldAutoSend(sp),
                    windowsPaired = WindowsTarget.from(sp) != null,
                ),
            )

        Pref.Theme.key ->
            current.copy(theming = current.theming.copy(theme = sp.get(Pref.Theme)))
        Pref.AccentColor.key ->
            current.copy(theming = current.theming.copy(accentColor = sp.get(Pref.AccentColor)))
        Pref.OverlayCharacters.key ->
            current.copy(theming = current.theming.copy(overlayCharacters = sp.get(Pref.OverlayCharacters)))
        Pref.OutputSpeed.key ->
            current.copy(theming = current.theming.copy(outputSpeed = sp.get(Pref.OutputSpeed)))
        Pref.WidgetOpacity.key ->
            current.copy(theming = current.theming.copy(widgetOpacity = sp.get(Pref.WidgetOpacity)))

        Pref.OverlayPositionPortraitX.key -> current.copy(
            overlay = current.overlay.copy(
                positionPortraitX = sp.get(Pref.OverlayPositionPortraitX),
            ),
        )
        Pref.OverlayPositionPortraitY.key -> current.copy(
            overlay = current.overlay.copy(
                positionPortraitY = sp.get(Pref.OverlayPositionPortraitY),
            ),
        )
        Pref.OverlayPositionLandscapeX.key -> current.copy(
            overlay = current.overlay.copy(
                positionLandscapeX = sp.get(Pref.OverlayPositionLandscapeX),
            ),
        )
        Pref.OverlayPositionLandscapeY.key -> current.copy(
            overlay = current.overlay.copy(
                positionLandscapeY = sp.get(Pref.OverlayPositionLandscapeY),
            ),
        )

        // 2026-05-21 indirection-cleanup Chunk 4.5a (OQ-1 Option A) —
        // computed mirror for the curated-language axis. `InputLanguages`
        // (the Set<String>) and `InputLanguagePos` (the Int) together
        // resolve to a single `effective: String` via
        // `LanguageResolver.effectiveLanguage(sp)` (the same algorithm
        // the IME and Settings-Activity use directly). Both keys fan
        // into the same write because either side can change the
        // effective code (e.g. a new language added to the set, or the
        // position moved within an unchanged set). The reducer's
        // distinct-emission contract suppresses re-emits when the
        // effective code is unchanged after the SP write
        // (LanguageModule.reduce on RefreshFromPref also gates).
        Pref.InputLanguages.key, Pref.InputLanguagePos.key -> current.copy(
            language = current.language.copy(
                effective = LanguageResolver.effectiveLanguage(sp),
            ),
        )

        else -> current
    }

    companion object {
        /**
         * Overlay-position pref keys — legacy aliases retained for
         * backward compatibility with any test that imported the
         * raw-string constants before F-4. New code should access
         * the typed [Pref.OverlayPositionPortraitX] / `…Y` /
         * `Landscape*` entries directly. The constants forward to
         * the same key strings; SharedPreferences storage shape is
         * unchanged.
         */
        const val OVERLAY_POS_PORTRAIT_X_KEY: String = "overlay_pos_portrait_x"
        const val OVERLAY_POS_PORTRAIT_Y_KEY: String = "overlay_pos_portrait_y"
        const val OVERLAY_POS_LANDSCAPE_X_KEY: String = "overlay_pos_landscape_x"
        const val OVERLAY_POS_LANDSCAPE_Y_KEY: String = "overlay_pos_landscape_y"
    }
}
