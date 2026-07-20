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

    // Paired = the non-secret url + deviceId (WindowsTarget.isPaired); the secret is not a pref.
    private fun FakeSharedPreferences.pair() = apply {
        edit()
            .put(Pref.WindowsTargetUrl, "http://vm-win:8756")
            .put(Pref.WindowsDeviceId, "device-1")
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

    // ── pcOnly: the PC-only terminal mode (pc-dictation-activity) ──
    //
    // While the full-screen PC-dictation Activity owns the foreground, EVERY terminal diverts to
    // the PC source-independently — even STATIC_PROMPT, which the persistent auto-send path
    // excludes. There is no local IME host in the Activity, so a "local" insert would vanish.

    @Test
    fun `pcOnly diverts every source including static prompt`() {
        val off = sp() // auto-send OFF, not even paired
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.STATIC_PROMPT, off, pcOnly = true))
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.TRANSCRIPTION, off, pcOnly = true))
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.REWORDING, off, pcOnly = true))
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.QUEUED_PROMPT, off, pcOnly = true))
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.PENDING_PART, off, pcOnly = true))
    }

    @Test
    fun `pcOnly false is exactly the persistent auto-send gate`() {
        val on = onAndPaired()
        // pcOnly=false must be identical to the two-arg gate on every source.
        assertFalse(WindowsAutoSend.shouldDivertToPc(InsertionSource.STATIC_PROMPT, on, pcOnly = false))
        assertTrue(WindowsAutoSend.shouldDivertToPc(InsertionSource.TRANSCRIPTION, on, pcOnly = false))
        // and nothing diverts when both the toggle and pcOnly are off.
        assertFalse(WindowsAutoSend.shouldDivertToPc(InsertionSource.TRANSCRIPTION, sp(), pcOnly = false))
    }
}
