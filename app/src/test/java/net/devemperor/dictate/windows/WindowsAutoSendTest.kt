package net.devemperor.dictate.windows

import net.devemperor.dictate.database.entity.InsertionSource
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WindowsAutoSend] — the single "should this session go to the PC?" gate
 * (ADR-0019). Auto-send needs BOTH the toggle on AND a paired PC.
 */
class WindowsAutoSendTest {

    private fun sp() = FakeSharedPreferences()

    private fun FakeSharedPreferences.pair() = apply {
        edit()
            .put(Pref.WindowsTargetUrl, "http://vm-win:8756")
            .put(Pref.WindowsDeviceId, "device-1")
            .put(Pref.WindowsDeviceSecret, "s3cr3t")
            .apply()
    }

    @Test
    fun `off by default`() {
        assertFalse(WindowsAutoSend.shouldAutoSend(sp()))
    }

    @Test
    fun `toggle on but not paired is off`() {
        val sp = sp().apply { edit().put(Pref.WindowsAutoSendEnabled, true).apply() }
        assertFalse(WindowsAutoSend.shouldAutoSend(sp))
    }

    @Test
    fun `paired but toggle off is off`() {
        assertFalse(WindowsAutoSend.shouldAutoSend(sp().pair()))
    }

    @Test
    fun `toggle on AND paired is on`() {
        val sp = sp().pair().apply { edit().put(Pref.WindowsAutoSendEnabled, true).apply() }
        assertTrue(WindowsAutoSend.shouldAutoSend(sp))
    }

    // ── shouldDivertToPc: source-aware routing (Block C regression, guards commit 27b91b3) ──
    //
    // 27b91b3 diverted EVERY completed pipeline to the PC when auto-send was active, ignoring
    // the InsertionSource. A long-pressed text-only ("pure text") pill produces a STATIC_PROMPT
    // completion that must be inserted 1:1 into the host field — never sent to the PC — otherwise
    // its text vanishes locally (the divert branch also skips the pending-part fallback).

    private fun onAndPaired() =
        sp().pair().apply { edit().put(Pref.WindowsAutoSendEnabled, true).apply() }

    @Test
    fun `static prompt pill stays local even when auto-send active`() {
        assertFalse(
            "A long-pressed text-only (STATIC_PROMPT) pill must be inserted locally, never diverted",
            WindowsAutoSend.shouldDivertToPc(InsertionSource.STATIC_PROMPT, onAndPaired()),
        )
    }

    @Test
    fun `dictation outputs divert to pc when auto-send active`() {
        val sp = onAndPaired()
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.TRANSCRIPTION, sp))
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.REWORDING, sp))
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.QUEUED_PROMPT, sp))
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.PENDING_PART, sp))
    }

    @Test
    fun `nothing diverts when auto-send off`() {
        val paired = sp().pair() // toggle OFF
        assertFalse(WindowsAutoSend.shouldDivertToPc(InsertionSource.STATIC_PROMPT, paired))
        assertFalse(WindowsAutoSend.shouldDivertToPc(InsertionSource.TRANSCRIPTION, paired))
    }
}
