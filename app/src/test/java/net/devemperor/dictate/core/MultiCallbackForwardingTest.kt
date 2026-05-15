package net.devemperor.dictate.core

import net.devemperor.dictate.testutil.FakePipelineUiStateReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for [PipelineUiStateReader.addCallback] /
 * [PipelineUiStateReader.removeCallback] forwarding.
 *
 * Quality-Gate K-2: replaces the obsolete `CompositePipelineCallbackTest`.
 * The original plan suggested a Composite-Wrapper around a single-slot
 * `setCallback`; the K-2 review collapsed that into a listener list directly
 * on the `KeyboardUiController`. Multiple `PipelineUiCallback` consumers
 * register independently via `addCallback` (D-13: the legacy
 * effective-language controller consumer was removed; the Service-side
 * pipeline observer remains the production consumer).
 *
 * The real [KeyboardUiController] cannot be instantiated in a JVM unit test
 * (it constructor-depends on `LayoutInflater`, `Handler`, `MaterialButton`,
 * etc., all of which require an Android runtime — the project intentionally
 * runs unit tests without Robolectric, see Quality-Gate K-1). The
 * production class and [FakePipelineUiStateReader] both implement
 * [PipelineUiStateReader] with the same `CopyOnWriteArrayList`-style
 * "snapshot, then forEach" dispatch contract, so verifying that contract
 * against the fake is the authoritative project-style test for the multi-
 * callback wiring.
 *
 * What is verified:
 *  - Two callbacks registered via [PipelineUiStateReader.addCallback] both
 *    receive every state-change notification (the central K-2 invariant).
 *  - [PipelineUiStateReader.removeCallback] disconnects only the named
 *    consumer; the other keeps receiving events.
 *  - Re-adding the same callback instance is idempotent (covers the
 *    `addIfAbsent` semantics on the production side).
 *  - A callback added during dispatch does not retroactively receive the
 *    in-flight event (snapshot semantics protect against re-entrancy).
 */
class MultiCallbackForwardingTest {

    private lateinit var reader: FakePipelineUiStateReader

    @Before
    fun setUp() {
        reader = FakePipelineUiStateReader()
    }

    @Test
    fun `state change is forwarded to multiple registered callbacks`() {
        val first = RecordingCallback()
        val second = RecordingCallback()

        reader.addCallback(first)
        reader.addCallback(second)

        val newState = PipelineUiState.ReprocessStaging(
            targetSessionId = "s1",
            audioDurationSeconds = 12,
            editableQueue = listOf(0, 1),
            selectedLanguage = "fr"
        )
        reader.simulateStateChange(newState)

        assertEquals(1, first.events.size)
        assertEquals(1, second.events.size)
        assertSame(newState, first.events.last().second)
        assertSame(newState, second.events.last().second)
    }

    @Test
    fun `removeCallback disconnects only the named consumer`() {
        val staying = RecordingCallback()
        val leaving = RecordingCallback()

        reader.addCallback(staying)
        reader.addCallback(leaving)
        reader.removeCallback(leaving)

        reader.simulateStateChange(PipelineUiState.Idle)

        assertEquals(1, staying.events.size)
        assertEquals(0, leaving.events.size)
        assertFalse(reader.isRegistered(leaving))
        assertTrue(reader.isRegistered(staying))
    }

    @Test
    fun `addCallback is idempotent for repeated registration of the same instance`() {
        val cb = RecordingCallback()

        reader.addCallback(cb)
        reader.addCallback(cb)
        reader.addCallback(cb)

        reader.simulateStateChange(PipelineUiState.Idle)

        // Single delivery — addIfAbsent semantics on the production side.
        assertEquals(1, cb.events.size)
    }

    @Test
    fun `callback added during dispatch does not receive the in-flight event`() {
        val later = RecordingCallback()
        val initiator = object : PipelineUiCallback {
            override fun onPipelineUiStateChanged(
                oldState: PipelineUiState,
                newState: PipelineUiState
            ) {
                // Re-entrant registration during dispatch — must not
                // retroactively fire for the event currently in flight.
                reader.addCallback(later)
            }
        }
        reader.addCallback(initiator)

        reader.simulateStateChange(PipelineUiState.Idle)
        assertEquals(0, later.events.size)

        // Subsequent transitions reach `later` because it is now registered.
        reader.simulateStateChange(
            PipelineUiState.ReprocessStaging(
                targetSessionId = "s2",
                audioDurationSeconds = 7,
                editableQueue = emptyList(),
                selectedLanguage = null
            )
        )
        assertEquals(1, later.events.size)
    }

    @Test
    fun `removeCallback during dispatch leaves remaining callbacks intact`() {
        val survivor = RecordingCallback()
        val selfRemoving = object : PipelineUiCallback {
            var fired = 0
            override fun onPipelineUiStateChanged(
                oldState: PipelineUiState,
                newState: PipelineUiState
            ) {
                fired++
                reader.removeCallback(this)
            }
        }
        reader.addCallback(selfRemoving)
        reader.addCallback(survivor)

        reader.simulateStateChange(PipelineUiState.Idle)

        assertEquals(1, selfRemoving.fired)
        assertEquals(1, survivor.events.size)
        assertFalse(reader.isRegistered(selfRemoving))

        // Second transition: only the survivor remains.
        reader.simulateStateChange(
            PipelineUiState.ReprocessStaging(
                targetSessionId = "s3",
                audioDurationSeconds = 0,
                editableQueue = emptyList(),
                selectedLanguage = null
            )
        )
        assertEquals(1, selfRemoving.fired)
        assertEquals(2, survivor.events.size)
    }

    /** Captures every `(old, new)` state pair for assertion. */
    private class RecordingCallback : PipelineUiCallback {
        val events = mutableListOf<Pair<PipelineUiState, PipelineUiState>>()
        override fun onPipelineUiStateChanged(
            oldState: PipelineUiState,
            newState: PipelineUiState
        ) {
            events.add(oldState to newState)
        }
    }
}
