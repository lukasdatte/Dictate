package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.InsertionOutcomeWire
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DispatchServiceTest {

    private val inserter = FakeTextInserter()

    // The same database for both repositories — the foreign key from received_texts to devices is
    // real, and a history row for an unknown device must fail here just as it would in production.
    private val database = CompanionDatabase.inMemory()
    private val devices = SqlDelightDeviceRepository(database)
    private val history = SqlDelightHistoryRepository(database)
    private val clock = MutableClock()
    private val dispatch = DispatchService(inserter, history, devices, clock)

    private val device = Device(deviceId = "test-device-0001", name = "Pixel 8", secretHash = "hash", pairedAt = 1L)

    @Before
    fun setUp() {
        devices.save(device)
    }

    @Test
    fun dispatch_insertsFirst_thenPersists() {
        val response = dispatch.dispatch(device, request("session-1", "hello"))

        assertEquals(listOf("hello"), inserter.inserted)
        assertTrue(response.delivered)
        assertFalse(response.duplicate)
        assertEquals(InsertionOutcomeWire.TYPED_CTRL_V, response.outcome)

        val row = history.findById("session-1")!!
        assertTrue(row.dispatched)
        assertEquals(InsertionOutcome.TYPED_CTRL_V, row.lastOutcome)
        assertEquals(MutableClock.START, row.receivedAt)
        assertEquals(MutableClock.START, devices.findById(device.deviceId)?.lastSeenAt)
    }

    @Test
    fun dispatch_whenInsertionFails_persistsNothing() {
        inserter.nextOutcome = InsertionOutcome.FAILED

        val failure = runCatching { dispatch.dispatch(device, request("session-1", "hello")) }.exceptionOrNull()

        assertTrue("$failure", failure is CompanionException.InsertionFailedException)
        // The contract of the whole package: no row, no 200 — the phone keeps the text.
        assertNull(history.findById("session-1"))
        assertEquals(0, history.count(null))
    }

    @Test
    fun dispatch_sameSessionTwice_isIdempotent() {
        dispatch.dispatch(device, request("session-1", "hello"))
        val second = dispatch.dispatch(device, request("session-1", "hello"))

        assertTrue(second.duplicate)
        assertEquals(1, history.count(null))
    }

    @Test
    fun dispatch_clipboardOnly_isStillDelivered() {
        inserter.nextOutcome = InsertionOutcome.CLIPBOARD_ONLY

        val response = dispatch.dispatch(device, request("session-1", "hello"))

        assertTrue(response.delivered)
        assertEquals(InsertionOutcomeWire.CLIPBOARD_ONLY, response.outcome)
        assertEquals(InsertionOutcome.CLIPBOARD_ONLY, history.findById("session-1")?.lastOutcome)
    }

    @Test
    fun reinsert_goesThroughTheSameInserter_andUpdatesTheOutcome() {
        history.upsert(
            deviceId = device.deviceId,
            item = SessionUpsert("session-9", "synced text", 5L, SessionOriginWire.KEYBOARD, dispatched = false),
            receivedAt = 5L,
        )
        inserter.nextOutcome = InsertionOutcome.CLIPBOARD_ONLY
        clock.advance(1_000)

        val outcome = dispatch.reinsert("session-9")

        assertEquals(InsertionOutcome.CLIPBOARD_ONLY, outcome)
        assertEquals(listOf("synced text"), inserter.inserted)
        val row = history.findById("session-9")!!
        assertTrue("a re-insert makes a synced row a dispatched one", row.dispatched)
        assertEquals(InsertionOutcome.CLIPBOARD_ONLY, row.lastOutcome)
        assertEquals(MutableClock.START + 1_000, row.receivedAt)
    }

    @Test
    fun reinsert_ofAnUnknownSession_isNull() {
        assertNull(dispatch.reinsert("nope"))
        assertEquals(emptyList<String>(), inserter.inserted)
    }

    @Test
    fun aSyncedRowNeverDowngradesADispatchedOne() {
        dispatch.dispatch(device, request("session-1", "hello"))

        // What the lazy sync does: it mirrors the phone's view, where this row may still look
        // undispatched. It must not erase the fact that the text was typed here.
        history.upsert(
            deviceId = device.deviceId,
            item = SessionUpsert("session-1", "hello", 42L, SessionOriginWire.KEYBOARD, dispatched = false),
            receivedAt = 99L,
        )

        assertTrue(history.findById("session-1")!!.dispatched)
    }

    private fun request(sessionId: String, text: String) = DispatchRequest(
        sessionId = sessionId,
        text = text,
        createdAt = 42L,
        origin = SessionOriginWire.KEYBOARD,
    )
}
