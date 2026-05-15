package net.devemperor.dictate.state

import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [PipelinePrefMirror].
 *
 * Covers Spec 1 §4.5 — 19 prefs mirrored from [FakeSharedPreferences]
 * into the store's sub-states, both for the initial-snapshot path
 * (`attach`) and for the listener path (`applyChange` driven by
 * `SharedPreferences.Editor.apply()`).
 *
 * @see net.devemperor.dictate.state.PipelinePrefMirror
 */
class PipelinePrefMirrorTest {

    // ────────────────────────────────────────────────────────────────
    // attach() — initial snapshot of 19 prefs
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `attach with empty prefs leaves the initial sub-state defaults intact`() {
        val sp = FakeSharedPreferences()
        val store = DictateUiStateStore(DictateUiState.initial())

        PipelinePrefMirror(sp).attach(store)

        // Pref defaults match the initial-state defaults — verifying
        // this guards against drift between `DictateUiState.initial()`
        // and the `Pref.<X>.default` values.
        val s = store.snapshot
        assertEquals(false, s.layout.singleRowMode)
        assertEquals(false, s.layout.smallMode)
        assertEquals(true, s.layout.animationsEnabled)
        assertEquals(true, s.audio.audioFocusEnabledPref)
        assertEquals(false, s.audio.useBluetoothMic)
        assertEquals(true, s.audio.vibrationEnabled)
        assertEquals(false, s.resend.resendEnabled)
        assertEquals(true, s.features.rewordingEnabled)
        assertEquals(false, s.features.autoFormattingEnabled)
        assertEquals(true, s.features.instantOutputEnabled)
        assertEquals(false, s.features.autoEnterEnabled)
        assertEquals("system", s.theming.theme)
        assertEquals(-14700810, s.theming.accentColor)
        assertEquals("()-:!?,.", s.theming.overlayCharacters)
        assertEquals(5, s.theming.outputSpeed)
        assertEquals(1.0f, s.overlay.positionPortraitX, 0.0001f)
        assertEquals(0.1f, s.overlay.positionPortraitY, 0.0001f)
        assertEquals(1.0f, s.overlay.positionLandscapeX, 0.0001f)
        assertEquals(0.1f, s.overlay.positionLandscapeY, 0.0001f)
    }

    @Test
    fun `attach reads all 3 layout-axis prefs from SP into LayoutState`() {
        val sp = FakeSharedPreferences()
        sp.edit().put(Pref.SingleRowMode, true).put(Pref.SmallMode, true).put(Pref.Animations, false).apply()
        val store = DictateUiStateStore(DictateUiState.initial())

        PipelinePrefMirror(sp).attach(store)

        val s = store.snapshot
        assertTrue(s.layout.singleRowMode)
        assertTrue(s.layout.smallMode)
        assertFalse(s.layout.animationsEnabled)
    }

    @Test
    fun `attach reads all 3 audio-axis prefs from SP into AudioState`() {
        val sp = FakeSharedPreferences()
        sp.edit().put(Pref.AudioFocus, false).put(Pref.UseBluetoothMic, true).put(Pref.Vibration, false).apply()
        val store = DictateUiStateStore(DictateUiState.initial())

        PipelinePrefMirror(sp).attach(store)

        val s = store.snapshot
        assertFalse(s.audio.audioFocusEnabledPref)
        assertTrue(s.audio.useBluetoothMic)
        assertFalse(s.audio.vibrationEnabled)
    }

    @Test
    fun `attach reads ResendButton pref into ResendState`() {
        val sp = FakeSharedPreferences()
        sp.edit().put(Pref.ResendButton, true).apply()
        val store = DictateUiStateStore(DictateUiState.initial())

        PipelinePrefMirror(sp).attach(store)

        assertTrue(store.snapshot.resend.resendEnabled)
    }

    @Test
    fun `attach reads all 4 feature-toggle prefs into FeatureToggles`() {
        val sp = FakeSharedPreferences()
        sp.edit()
            .put(Pref.RewordingEnabled, false)
            .put(Pref.AutoFormattingEnabled, true)
            .put(Pref.InstantOutput, false)
            .put(Pref.AutoEnter, true)
            .apply()
        val store = DictateUiStateStore(DictateUiState.initial())

        PipelinePrefMirror(sp).attach(store)

        val s = store.snapshot
        assertFalse(s.features.rewordingEnabled)
        assertTrue(s.features.autoFormattingEnabled)
        assertFalse(s.features.instantOutputEnabled)
        assertTrue(s.features.autoEnterEnabled)
    }

    @Test
    fun `attach reads all 4 theming prefs into ThemingState`() {
        val sp = FakeSharedPreferences()
        sp.edit()
            .put(Pref.Theme, "dark")
            .put(Pref.AccentColor, -1)
            .put(Pref.OverlayCharacters, ".")
            .put(Pref.OutputSpeed, 9)
            .apply()
        val store = DictateUiStateStore(DictateUiState.initial())

        PipelinePrefMirror(sp).attach(store)

        val s = store.snapshot
        assertEquals("dark", s.theming.theme)
        assertEquals(-1, s.theming.accentColor)
        assertEquals(".", s.theming.overlayCharacters)
        assertEquals(9, s.theming.outputSpeed)
    }

    @Test
    fun `attach reads all 4 overlay position raw-keys into OverlayState`() {
        val sp = FakeSharedPreferences()
        sp.edit()
            .putFloat(PipelinePrefMirror.OVERLAY_POS_PORTRAIT_X_KEY, 0.25f)
            .putFloat(PipelinePrefMirror.OVERLAY_POS_PORTRAIT_Y_KEY, 0.75f)
            .putFloat(PipelinePrefMirror.OVERLAY_POS_LANDSCAPE_X_KEY, 0.5f)
            .putFloat(PipelinePrefMirror.OVERLAY_POS_LANDSCAPE_Y_KEY, 0.6f)
            .apply()
        val store = DictateUiStateStore(DictateUiState.initial())

        PipelinePrefMirror(sp).attach(store)

        val o = store.snapshot.overlay
        assertEquals(0.25f, o.positionPortraitX, 0.0001f)
        assertEquals(0.75f, o.positionPortraitY, 0.0001f)
        assertEquals(0.5f, o.positionLandscapeX, 0.0001f)
        assertEquals(0.6f, o.positionLandscapeY, 0.0001f)
    }

    // ────────────────────────────────────────────────────────────────
    // applyChange() — per-key listener path
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `applyChange mirrors SingleRowMode flip into LayoutState only`() {
        val sp = FakeSharedPreferences()
        sp.edit().put(Pref.SingleRowMode, true).apply()
        val mirror = PipelinePrefMirror(sp)

        val initial = DictateUiState.initial()
        val next = mirror.applyChange(initial, Pref.SingleRowMode.key)

        assertTrue(next.layout.singleRowMode)
        // Other sub-states must be unchanged (same identity — `data
        // class.copy(layout = …)` passes through other fields by
        // reference).
        assertSame(initial.audio, next.audio)
        assertSame(initial.features, next.features)
        assertSame(initial.overlay, next.overlay)
        assertSame(initial.theming, next.theming)
        assertSame(initial.resend, next.resend)
    }

    @Test
    fun `applyChange routes each of the 15 typed pref keys to its own sub-state axis`() {
        val sp = FakeSharedPreferences()
        sp.edit()
            // Layout
            .put(Pref.SingleRowMode, true)
            .put(Pref.SmallMode, true)
            .put(Pref.Animations, false)
            // Audio
            .put(Pref.AudioFocus, false)
            .put(Pref.UseBluetoothMic, true)
            .put(Pref.Vibration, false)
            // Resend
            .put(Pref.ResendButton, true)
            // Features
            .put(Pref.RewordingEnabled, false)
            .put(Pref.AutoFormattingEnabled, true)
            .put(Pref.InstantOutput, false)
            .put(Pref.AutoEnter, true)
            // Theming
            .put(Pref.Theme, "dark")
            .put(Pref.AccentColor, -1)
            .put(Pref.OverlayCharacters, ".")
            .put(Pref.OutputSpeed, 9)
            .apply()
        val mirror = PipelinePrefMirror(sp)

        // Each key is exercised individually; final state should still
        // come out fully mirrored even though applyChange is called per-key.
        var current = DictateUiState.initial()
        listOf(
            Pref.SingleRowMode.key, Pref.SmallMode.key, Pref.Animations.key,
            Pref.AudioFocus.key, Pref.UseBluetoothMic.key, Pref.Vibration.key,
            Pref.ResendButton.key,
            Pref.RewordingEnabled.key, Pref.AutoFormattingEnabled.key,
            Pref.InstantOutput.key, Pref.AutoEnter.key,
            Pref.Theme.key, Pref.AccentColor.key,
            Pref.OverlayCharacters.key, Pref.OutputSpeed.key,
        ).forEach { key ->
            current = mirror.applyChange(current, key)
        }

        // All 15 axes carry the new values.
        assertTrue(current.layout.singleRowMode)
        assertTrue(current.layout.smallMode)
        assertFalse(current.layout.animationsEnabled)
        assertFalse(current.audio.audioFocusEnabledPref)
        assertTrue(current.audio.useBluetoothMic)
        assertFalse(current.audio.vibrationEnabled)
        assertTrue(current.resend.resendEnabled)
        assertFalse(current.features.rewordingEnabled)
        assertTrue(current.features.autoFormattingEnabled)
        assertFalse(current.features.instantOutputEnabled)
        assertTrue(current.features.autoEnterEnabled)
        assertEquals("dark", current.theming.theme)
        assertEquals(-1, current.theming.accentColor)
        assertEquals(".", current.theming.overlayCharacters)
        assertEquals(9, current.theming.outputSpeed)
    }

    @Test
    fun `applyChange routes all 4 overlay-position raw keys into OverlayState`() {
        val sp = FakeSharedPreferences()
        sp.edit()
            .putFloat(PipelinePrefMirror.OVERLAY_POS_PORTRAIT_X_KEY, 0.3f)
            .putFloat(PipelinePrefMirror.OVERLAY_POS_PORTRAIT_Y_KEY, 0.4f)
            .putFloat(PipelinePrefMirror.OVERLAY_POS_LANDSCAPE_X_KEY, 0.5f)
            .putFloat(PipelinePrefMirror.OVERLAY_POS_LANDSCAPE_Y_KEY, 0.6f)
            .apply()
        val mirror = PipelinePrefMirror(sp)

        var current = DictateUiState.initial()
        listOf(
            PipelinePrefMirror.OVERLAY_POS_PORTRAIT_X_KEY,
            PipelinePrefMirror.OVERLAY_POS_PORTRAIT_Y_KEY,
            PipelinePrefMirror.OVERLAY_POS_LANDSCAPE_X_KEY,
            PipelinePrefMirror.OVERLAY_POS_LANDSCAPE_Y_KEY,
        ).forEach { current = mirror.applyChange(current, it) }

        val o = current.overlay
        assertEquals(0.3f, o.positionPortraitX, 0.0001f)
        assertEquals(0.4f, o.positionPortraitY, 0.0001f)
        assertEquals(0.5f, o.positionLandscapeX, 0.0001f)
        assertEquals(0.6f, o.positionLandscapeY, 0.0001f)
    }

    @Test
    fun `applyChange is a no-op for unknown keys`() {
        val sp = FakeSharedPreferences()
        sp.edit().putString("unknown_key", "x").apply()
        val mirror = PipelinePrefMirror(sp)

        val initial = DictateUiState.initial()
        val next = mirror.applyChange(initial, "unknown_key")

        // Same instance — no copy() invocation for unknown keys.
        assertSame(initial, next)
    }

    @Test
    fun `applyChange is a no-op for null key`() {
        val sp = FakeSharedPreferences()
        val mirror = PipelinePrefMirror(sp)
        val initial = DictateUiState.initial()

        assertSame(initial, mirror.applyChange(initial, null))
    }

    // ────────────────────────────────────────────────────────────────
    // Listener path (FakeSharedPreferences C7 update — listeners fire on apply)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `listener fires sync on Editor apply and updates the store`() {
        val sp = FakeSharedPreferences()
        val store = DictateUiStateStore(DictateUiState.initial())
        PipelinePrefMirror(sp).attach(store)

        // Sanity: initial mirror produced default vibration = true.
        assertTrue(store.snapshot.audio.vibrationEnabled)

        sp.edit().put(Pref.Vibration, false).apply()

        // Listener path: store reflects the new pref.
        assertFalse(store.snapshot.audio.vibrationEnabled)
    }

    @Test
    fun `detach unregisters the listener so further pref-writes do not mutate the store`() {
        val sp = FakeSharedPreferences()
        val store = DictateUiStateStore(DictateUiState.initial())
        val mirror = PipelinePrefMirror(sp)
        mirror.attach(store)
        mirror.detach()

        sp.edit().put(Pref.Vibration, false).apply()

        // Vibration default = true and the detach happened, so the
        // store still holds the initial-mirror value.
        assertTrue(store.snapshot.audio.vibrationEnabled)
    }

    // ────────────────────────────────────────────────────────────────
    // Defensive: initialMirror via attach preserves unrelated sub-state
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `attach preserves non-mirror sub-states like recording and pipeline`() {
        // pendingSessions, recording, pipeline, viewMode, livePrompt,
        // language: NONE of these are mirrored. If attach() touched them
        // we would lose recovery-supplied + module-supplied state.
        val sp = FakeSharedPreferences()
        val store = DictateUiStateStore(DictateUiState.initial())

        val before = store.snapshot
        PipelinePrefMirror(sp).attach(store)
        val after = store.snapshot

        // Same identity for the un-mirrored axes (data-class copy()
        // returns the same field reference if the value is unchanged).
        assertSame(before.recording, after.recording)
        assertSame(before.pipeline, after.pipeline)
        assertEquals(before.viewMode, after.viewMode)
        assertSame(before.language, after.language)
        assertSame(before.livePrompt, after.livePrompt)
        assertSame(before.pendingSessions, after.pendingSessions)
        // F-1 — `lastResultNeedsManualPaste` moved from a top-level
        // field to `ResendState.lastResultNeedsManualPaste`. PrefMirror
        // does not touch this field; the resend axis as a whole is
        // mirrored (resendEnabled), so we compare the manual-paste flag
        // explicitly to confirm PrefMirror leaves it alone.
        assertEquals(before.resend.lastResultNeedsManualPaste, after.resend.lastResultNeedsManualPaste)
    }

    @Test
    fun `attach with empty SP produces a value-equal LayoutState`() {
        // Sanity counterpart of the un-mirrored-identity test: when SP
        // is empty (pref defaults match LayoutState defaults), attach
        // still issues a `store.update {}` and the layout sub-state
        // ends up value-equal to the boot default. Identity is NOT
        // asserted because Kotlin's data class `copy(...)` is allowed
        // to return the same instance when all fields are unchanged
        // (compiler optimisation), and that behaviour is not part of
        // the PipelinePrefMirror contract.
        val sp = FakeSharedPreferences()
        val store = DictateUiStateStore(DictateUiState.initial())

        val before = store.snapshot.layout
        PipelinePrefMirror(sp).attach(store)
        val after = store.snapshot.layout

        assertEquals(before, after)
    }
}
