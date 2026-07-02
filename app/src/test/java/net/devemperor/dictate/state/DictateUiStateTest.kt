package net.devemperor.dictate.state

import kotlinx.collections.immutable.persistentListOf
import net.devemperor.dictate.core.ContentArea
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure type-level tests for [DictateUiState] and its sub-state types.
 *
 * No reducer logic, no orchestrator — just `data class` invariants:
 * equality, copy semantics, sub-state copy isolation, PersistentList
 * mutation idiom, and structural defaults.
 */
class DictateUiStateTest {

    // ────────────────────────────────────────────────────────────────
    // Initial state
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `initial state has Idle recording and Idle pipeline and KEYBOARD viewMode`() {
        val s = DictateUiState.initial()

        assertSame(RecordingState.Idle, s.recording)
        assertSame(PipelineUiState.Idle, s.pipeline)
        assertEquals(ViewMode.KEYBOARD, s.viewMode)
    }

    @Test
    fun `initial state has empty pendingSessions and no recorded interruption`() {
        val s = DictateUiState.initial()

        assertTrue(s.pendingSessions.isEmpty())
        assertEquals(InterruptionState(), s.interruption)
        assertNull(s.interruption.lastInterruption)
        // F-1 — flag now lives on ResendState (was top-level pre-F-1).
        assertFalse(s.resend.lastResultNeedsManualPaste)
    }

    @Test
    fun `initial sub-states use their data-class defaults`() {
        val s = DictateUiState.initial()

        assertEquals(LayoutState(), s.layout)
        assertEquals(OverlayState(), s.overlay)
        assertEquals(AudioState(), s.audio)
        assertEquals(ResendState(), s.resend)
        assertEquals(LivePromptState(), s.livePrompt)
        assertEquals(FeatureToggles(), s.features)
        assertEquals(ThemingState(), s.theming)
    }

    @Test
    fun `initial language is system`() {
        assertEquals("system", DictateUiState.initial().language.effective)
    }

    // ────────────────────────────────────────────────────────────────
    // data-class equality + copy
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `two initial states are equal`() {
        assertEquals(DictateUiState.initial(), DictateUiState.initial())
    }

    @Test
    fun `copy produces a new instance with the requested mutation`() {
        val a = DictateUiState.initial()
        val b = a.copy(viewMode = ViewMode.WIDGET)

        assertNotEquals(a, b)
        assertEquals(ViewMode.KEYBOARD, a.viewMode)    // a unchanged
        assertEquals(ViewMode.WIDGET, b.viewMode)
    }

    @Test
    fun `copy preserves untouched sub-states by reference`() {
        // Structural sharing — the layout sub-state is the same instance
        // because copy(viewMode = …) only swaps the viewMode field.
        val a = DictateUiState.initial()
        val b = a.copy(viewMode = ViewMode.WIDGET)

        assertSame(a.layout, b.layout)
        assertSame(a.overlay, b.overlay)
        assertSame(a.audio, b.audio)
    }

    @Test
    fun `sub-state copy is structural — outer state is unchanged`() {
        val a = DictateUiState.initial()
        val newLayout = a.layout.copy(smallMode = true)
        val b = a.copy(layout = newLayout)

        assertFalse(a.layout.smallMode)              // outer a unchanged
        assertTrue(b.layout.smallMode)
        assertNotSame(a.layout, b.layout)            // sub-state replaced
        assertSame(a.overlay, b.overlay)             // other axes unchanged
    }

    // ────────────────────────────────────────────────────────────────
    // RecordingState sealed hierarchy
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `RecordingState Idle is a singleton`() {
        // data object → reference-equal across reads
        assertSame(RecordingState.Idle, RecordingState.Idle)
    }

    @Test
    fun `RecordingState Preparing carries useBluetooth + audioFile`() {
        val f = File("/cache/audio.m4a")
        val p = RecordingState.Preparing(useBluetooth = true, audioFile = f, sessionId = "sid-test")

        assertTrue(p.useBluetooth)
        assertEquals(f, p.audioFile)
        // Equality by content (data class)
        assertEquals(p, RecordingState.Preparing(true, f, sessionId = "sid-test"))
    }

    @Test
    fun `RecordingState Active and Paused are distinct types with same payload shape`() {
        val f = File("/cache/audio.m4a")
        val active = RecordingState.Active(useBluetooth = false, audioFile = f, sessionId = "sid-test")
        val paused = RecordingState.Paused(useBluetooth = false, audioFile = f, sessionId = "sid-test")

        assertNotEquals(active as RecordingState, paused as RecordingState)
    }

    // ────────────────────────────────────────────────────────────────
    // RecordingState.audioFileOrNull (D-14 / C9-C2)
    //
    // Post-cutover the IME's removed `audioFile` field is sourced from
    // this canonical accessor: the orchestrator state is the single
    // authoritative source for the in-flight recording's audio file.
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `audioFileOrNull returns the file for Preparing`() {
        val f = File("/cache/audio/abc.m4a")
        val state: RecordingState =
            RecordingState.Preparing(useBluetooth = true, audioFile = f, sessionId = "sid")

        assertSame(f, state.audioFileOrNull)
    }

    @Test
    fun `audioFileOrNull returns the file for Active`() {
        val f = File("/cache/audio/abc.m4a")
        val state: RecordingState =
            RecordingState.Active(useBluetooth = false, audioFile = f, sessionId = "sid")

        assertSame(f, state.audioFileOrNull)
    }

    @Test
    fun `audioFileOrNull returns the file for Paused`() {
        val f = File("/cache/audio/abc.m4a")
        val state: RecordingState =
            RecordingState.Paused(useBluetooth = false, audioFile = f, sessionId = "sid")

        assertSame(f, state.audioFileOrNull)
    }

    @Test
    fun `audioFileOrNull is null for Idle`() {
        val state: RecordingState = RecordingState.Idle

        assertNull(state.audioFileOrNull)
    }

    @Test
    fun `audioFileOrNull preserves the exact handle minted at StartRecording`() {
        // The IME mints the file once (factory.allocate / import) and the
        // FSM carries it Preparing → Active → Paused unchanged — the
        // send-tap must read back the SAME handle, not a re-derived path
        // (R-5: a wrong source silently breaks the transcription).
        val minted = File("/cache/audio/de305d54-75b4.m4a")
        val preparing: RecordingState =
            RecordingState.Preparing(useBluetooth = true, audioFile = minted, sessionId = "s")
        val active: RecordingState =
            RecordingState.Active(useBluetooth = true, audioFile = minted, sessionId = "s")
        val paused: RecordingState =
            RecordingState.Paused(useBluetooth = true, audioFile = minted, sessionId = "s")

        assertSame(minted, preparing.audioFileOrNull)
        assertSame(minted, active.audioFileOrNull)
        assertSame(minted, paused.audioFileOrNull)
    }

    // ────────────────────────────────────────────────────────────────
    // PipelineUiState sealed hierarchy
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `PipelineUiState Idle is a singleton`() {
        assertSame(PipelineUiState.Idle, PipelineUiState.Idle)
    }

    @Test
    fun `PipelineUiState Running carries sessionId + target + autoEnter`() {
        val r = PipelineUiState.Running(
            sessionId = "abc",
            target = InsertionTarget.INPUT_CONNECTION,
            autoEnterActive = true,
        )

        assertEquals("abc", r.sessionId)
        assertEquals(InsertionTarget.INPUT_CONNECTION, r.target)
        assertTrue(r.autoEnterActive)
    }

    @Test
    fun `PipelineUiState Running autoEnterActive defaults to false`() {
        val r = PipelineUiState.Running(
            sessionId = "abc",
            target = InsertionTarget.INPUT_CONNECTION,
        )

        assertFalse(r.autoEnterActive)
    }

    @Test
    fun `PipelineUiState ReprocessStaging carries sessionId + transcript`() {
        val rs = PipelineUiState.ReprocessStaging(sessionId = "xyz", transcript = "hello")

        assertEquals("xyz", rs.sessionId)
        assertEquals("hello", rs.transcript)
    }

    // ────────────────────────────────────────────────────────────────
    // PersistentList idiom
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `pendingSessions PersistentList add preserves structural sharing`() {
        val base = DictateUiState.initial()
        val session = PendingSession(
            sessionId = "s1",
            status = SessionStatus.RECORDED,
            transcribedText = null,
            createdAt = 1L,
        )
        val next = base.copy(pendingSessions = base.pendingSessions.add(session))

        assertEquals(0, base.pendingSessions.size)    // original unchanged
        assertEquals(1, next.pendingSessions.size)
        assertEquals(session, next.pendingSessions.first())
    }

    @Test
    fun `pendingSessions removeAll by predicate keeps non-matching entries`() {
        val s1 = PendingSession("s1", SessionStatus.RECORDED, null, 1L)
        val s2 = PendingSession("s2", SessionStatus.COMPLETED, "text", 2L)
        val base = DictateUiState.initial().copy(pendingSessions = persistentListOf(s1, s2))

        val filtered = base.copy(
            pendingSessions = base.pendingSessions.removeAll { it.sessionId == "s1" }
        )

        assertEquals(1, filtered.pendingSessions.size)
        assertEquals("s2", filtered.pendingSessions.first().sessionId)
    }

    // ────────────────────────────────────────────────────────────────
    // Sub-state defaults
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `LayoutState defaults to MAIN_BUTTONS and animations enabled`() {
        val l = LayoutState()

        assertEquals(ContentArea.MAIN_BUTTONS, l.contentArea)
        assertFalse(l.singleRowMode)
        assertFalse(l.smallMode)
        assertTrue(l.animationsEnabled)
    }

    @Test
    fun `OverlayState defaults are right-aligned + 10pct top + no permission`() {
        val o = OverlayState()

        assertEquals(1.0f, o.positionPortraitX, 0.0f)
        assertEquals(0.1f, o.positionPortraitY, 0.0f)
        assertFalse(o.hasPermission)
        assertFalse(o.userPrefersWidget)
        assertFalse(o.suppressAutoOverlayUntilNextSession)
    }

    @Test
    fun `AudioState defaults — focus enabled, granted false, BTSco disconnected`() {
        val a = AudioState()

        assertTrue(a.audioFocusEnabledPref)
        assertFalse(a.audioFocusGranted)
        assertEquals(ScoPhase.Disconnected, a.bluetoothSco.phase)
        assertNull(a.bluetoothSco.failureReason)
        assertFalse(a.useBluetoothMic)
        assertTrue(a.vibrationEnabled)
    }

    @Test
    fun `ResendState defaults — no audio, disabled, no cooldown`() {
        val r = ResendState()

        assertFalse(r.lastAudioExists)
        assertFalse(r.resendEnabled)
        assertFalse(r.resendCooldown)
    }

    @Test
    fun `FeatureToggles defaults — rewording + instant on, autoFormat + autoEnter off`() {
        val f = FeatureToggles()

        assertTrue(f.rewordingEnabled)
        assertFalse(f.autoFormattingEnabled)
        assertTrue(f.instantOutputEnabled)
        assertFalse(f.autoEnterEnabled)
    }

    // ────────────────────────────────────────────────────────────────
    // ResendState.lastResultNeedsManualPaste (F-1 relocation)
    //
    // The flag used to live as a top-level `DictateUiState` field. Per
    // F-1 + `research/manual-paste-field-architecture.md` it's now a
    // sibling of `lastAudioExists` on `ResendState`. ResendModule owns
    // both the field and the two action leaves
    // (`NotifyManualPasteNeeded` / `ClearManualPasteFlag`).
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `resend lastResultNeedsManualPaste defaults to false`() {
        assertFalse(DictateUiState.initial().resend.lastResultNeedsManualPaste)
    }

    @Test
    fun `resend lastResultNeedsManualPaste can be set via copy without touching other axes`() {
        val a = DictateUiState.initial()
        val b = a.copy(resend = a.resend.copy(lastResultNeedsManualPaste = true))

        assertTrue(b.resend.lastResultNeedsManualPaste)
        assertSame(a.recording, b.recording)
        assertSame(a.pipeline, b.pipeline)
    }

    // ────────────────────────────────────────────────────────────────
    // canCommitToHost — host-commit guard predicate (2026-05-22)
    //
    // Regression guard: the IME's `canCommitToHost()` guard used to key
    // on the `widget` axis (`widget instanceof Visible` → block). The
    // widget axis is orthogonal to `imeViewVisible` — a widget Send with
    // the keyboard still on screen must reach the focused field. The
    // predicate now keys on `imeViewVisible` alone.
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `canCommitToHost true when imeViewVisible true`() {
        assertTrue(DictateUiState.initial().copy(imeViewVisible = true).canCommitToHost)
    }

    @Test
    fun `canCommitToHost false when imeViewVisible false`() {
        assertFalse(DictateUiState.initial().copy(imeViewVisible = false).canCommitToHost)
    }

    @Test
    fun `canCommitToHost true when widget Visible but imeViewVisible true — widget-Send regression`() {
        // The keyboard and the floating widget are both on screen, so a
        // widget Send must commit into the focused field. The pre-fix
        // guard returned false here (`widget instanceof Visible`) and
        // silently deferred the transcript to Pending-Insert.
        val s = DictateUiState.initial().copy(
            widget = WidgetState.Visible(WidgetOrigin.USER),
            imeViewVisible = true,
        )
        assertTrue(s.canCommitToHost)
    }

    @Test
    fun `canCommitToHost false when imeViewVisible false regardless of widget`() {
        // The widget axis must not rescue a hidden IME-View either —
        // imeViewVisible is the sole axis in both directions.
        assertFalse(
            DictateUiState.initial().copy(
                widget = WidgetState.Visible(WidgetOrigin.PIPELINE),
                imeViewVisible = false,
            ).canCommitToHost,
        )
        assertFalse(
            DictateUiState.initial().copy(
                widget = WidgetState.Hidden,
                imeViewVisible = false,
            ).canCommitToHost,
        )
    }
}
