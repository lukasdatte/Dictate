package net.devemperor.dictate.history

import net.devemperor.dictate.history.SegmentPlaylistPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [SegmentPlaylistPolicy] state machine
 * (F-113 playback + F-115 completion reset, spec D5).
 */
class SegmentPlaylistPolicyTest {

    @Test
    fun `starts idle`() {
        val p = SegmentPlaylistPolicy(3)
        assertEquals(State.Idle, p.state)
        assertFalse(p.isPlaying)
        assertNull(p.currentIndex)
    }

    @Test
    fun `toggle from idle starts playing segment zero`() {
        val p = SegmentPlaylistPolicy(3)
        p.toggle()
        assertEquals(State.Playing(0), p.state)
        assertTrue(p.isPlaying)
        assertEquals(0, p.currentIndex)
    }

    @Test
    fun `toggle from idle with no segments stays idle`() {
        val p = SegmentPlaylistPolicy(0)
        p.toggle()
        assertEquals(State.Idle, p.state)
        assertFalse(p.isPlaying)
    }

    @Test
    fun `toggle pauses and resumes the same segment`() {
        val p = SegmentPlaylistPolicy(3)
        p.toggle()                      // Playing(0)
        p.toggle()                      // Paused(0)
        assertEquals(State.Paused(0), p.state)
        assertFalse(p.isPlaying)
        assertEquals(0, p.currentIndex)

        p.toggle()                      // Playing(0)
        assertEquals(State.Playing(0), p.state)
        assertTrue(p.isPlaying)
    }

    @Test
    fun `advance auto-advances to the next segment`() {
        val p = SegmentPlaylistPolicy(3)
        p.toggle()                      // Playing(0)
        p.advance()                     // Playing(1)
        assertEquals(State.Playing(1), p.state)
        p.advance()                     // Playing(2)
        assertEquals(State.Playing(2), p.state)
    }

    @Test
    fun `advance past the last segment resets to idle`() {
        val p = SegmentPlaylistPolicy(2)
        p.toggle()                      // Playing(0)
        p.advance()                     // Playing(1)
        p.advance()                     // completion of last -> Idle
        assertEquals(State.Idle, p.state)
        assertFalse(p.isPlaying)
        assertNull(p.currentIndex)
    }

    @Test
    fun `single segment advance resets to idle`() {
        val p = SegmentPlaylistPolicy(1)
        p.toggle()                      // Playing(0)
        p.advance()                     // Idle (F-115 completion reset)
        assertEquals(State.Idle, p.state)
    }

    @Test
    fun `advance while paused is a no-op`() {
        val p = SegmentPlaylistPolicy(3)
        p.toggle()                      // Playing(0)
        p.toggle()                      // Paused(0)
        p.advance()                     // ignored — not playing
        assertEquals(State.Paused(0), p.state)
    }

    @Test
    fun `advance while idle is a no-op`() {
        val p = SegmentPlaylistPolicy(3)
        p.advance()
        assertEquals(State.Idle, p.state)
    }

    @Test
    fun `reset returns to idle from any state`() {
        val p = SegmentPlaylistPolicy(3)
        p.toggle()                      // Playing(0)
        p.advance()                     // Playing(1)
        p.reset()
        assertEquals(State.Idle, p.state)
    }
}
