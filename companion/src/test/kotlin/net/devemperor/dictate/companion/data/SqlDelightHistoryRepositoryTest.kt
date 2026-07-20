package net.devemperor.dictate.companion.data

import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The two [net.devemperor.dictate.companion.domain.port.HistoryRepository] invariants, on the real SQL. */
class SqlDelightHistoryRepositoryTest {

    private val database = CompanionDatabase.inMemory()
    private val devices = SqlDelightDeviceRepository(database)
    private val history = SqlDelightHistoryRepository(database)

    @Before
    fun setUp() {
        devices.save(Device(deviceId = DEVICE_ID, name = "Pixel 8", secretHash = "hash", pairedAt = 1L))
    }

    @Test
    fun upsert_isIdempotentOverSessionId() {
        assertFalse(history.upsert(DEVICE_ID, upsert("s1", "hello"), receivedAt = 10L))
        assertTrue(history.upsert(DEVICE_ID, upsert("s1", "hello, edited"), receivedAt = 20L))

        assertEquals(1, history.count(null))
        assertEquals("hello, edited", history.findById("s1")?.text)
        assertEquals(20L, history.findById("s1")?.receivedAt)
    }

    @Test
    fun upsert_neverDowngradesADispatch() {
        history.upsert(DEVICE_ID, upsert("s1", "hello", dispatched = true), receivedAt = 10L)
        history.recordDispatch("s1", at = 11L, outcome = InsertionOutcome.TYPED_CTRL_V)

        // This is what the lazy sync does: it mirrors the phone's view, and the phone may still
        // believe this row was never dispatched. It must not erase the fact that the text was typed.
        history.upsert(DEVICE_ID, upsert("s1", "hello", dispatched = false), receivedAt = 20L)

        val row = history.findById("s1")!!
        assertTrue(row.dispatched)
        assertEquals("a sync writes no outcome — only an insertion does", InsertionOutcome.TYPED_CTRL_V, row.lastOutcome)
    }

    @Test
    fun recordDispatch_stampsInsertedAt_soTheArchiveMirrorsTheDispatchFlag() {
        // A row synced as *pending*: inserted_at NULL, dispatched 0 (upsertSyncSession).
        history.upsert(DEVICE_ID, upsert("s1", "hello", dispatched = false), receivedAt = 10L)
        assertNull(database.companionQueries.dictationSessionById("s1").executeAsOne().inserted_at)

        // Re-inserting it (DispatchService.reinsert -> recordDispatch) must stamp inserted_at, or the
        // dispatch flag and the archive would disagree — the cross-table invariant writeSyncRow documents.
        history.recordDispatch("s1", at = 30L, outcome = InsertionOutcome.TYPED_CTRL_V)

        assertEquals(30L, database.companionQueries.dictationSessionById("s1").executeAsOne().inserted_at)
        assertTrue(history.findById("s1")!!.dispatched)
    }

    @Test
    fun recordDispatch_neverDowngradesAnInsertedAtAlreadyStamped() {
        // Already dispatched — the dispatched upsert stamped inserted_at at receivedAt = 10.
        history.upsert(DEVICE_ID, upsert("s1", "hello", dispatched = true), receivedAt = 10L)
        assertEquals(10L, database.companionQueries.dictationSessionById("s1").executeAsOne().inserted_at)

        history.recordDispatch("s1", at = 30L, outcome = InsertionOutcome.TYPED_CTRL_V)

        // coalesce keeps the earliest real insertion — a later re-dispatch does not move it.
        assertEquals(10L, database.companionQueries.dictationSessionById("s1").executeAsOne().inserted_at)
    }

    @Test
    fun cursor_isTheGreatestCreatedAtThenSessionId() {
        assertNull("an empty database knows nothing — the phone resends from the beginning", history.cursor())

        history.upsert(DEVICE_ID, upsert("s-b", "second", createdAt = 100L), receivedAt = 1L)
        history.upsert(DEVICE_ID, upsert("s-a", "first", createdAt = 100L), receivedAt = 1L)
        history.upsert(DEVICE_ID, upsert("s-c", "older", createdAt = 99L), receivedAt = 1L)

        // Same millisecond, so the session id breaks the tie — exactly the total order the phone
        // pages along (ADR-0020). Without the tie-break a page boundary would skip or repeat a row.
        assertEquals(SyncCursor(lastCreatedAt = 100L, lastSessionId = "s-b"), history.cursor())
    }

    @Test
    fun page_isNewestFirst_andSearchesTheTextLiterally() {
        history.upsert(DEVICE_ID, upsert("s1", "Buy milk", createdAt = 10L), receivedAt = 1L)
        history.upsert(DEVICE_ID, upsert("s2", "Call the DENTIST", createdAt = 20L), receivedAt = 1L)
        history.upsert(DEVICE_ID, upsert("s3", "100% done", createdAt = 30L), receivedAt = 1L)

        assertEquals(listOf("s3", "s2", "s1"), history.page(null, limit = 10, offset = 0).map { it.sessionId })
        assertEquals(listOf("s2"), history.page("dentist", limit = 10, offset = 0).map { it.sessionId })
        assertEquals(1, history.count("dentist"))

        // A search term is a literal substring, not a pattern. Under LIKE, "100%" would read as
        // "starts with 100" and a bare "%" would match every row; under instr() both find exactly
        // the one row that really contains a percent sign.
        assertEquals(listOf("s3"), history.page("100%", limit = 10, offset = 0).map { it.sessionId })
        assertEquals(listOf("s3"), history.page("%", limit = 10, offset = 0).map { it.sessionId })
        assertEquals(1, history.count("%"))

        assertEquals(listOf("s2"), history.page(null, limit = 1, offset = 1).map { it.sessionId })
        assertEquals(3, history.count("  "))
    }

    @Test
    fun revokingADevice_takesItsTextsWithIt() {
        history.upsert(DEVICE_ID, upsert("s1", "hello"), receivedAt = 1L)

        devices.revoke(DEVICE_ID)

        // ON DELETE CASCADE — and it only works because the driver switches foreign keys on.
        assertEquals(0, history.count(null))
        assertNull(history.findById("s1"))
    }

    private fun upsert(
        sessionId: String,
        text: String,
        createdAt: Long = 42L,
        dispatched: Boolean = false,
    ) = SessionUpsert(
        sessionId = sessionId,
        text = text,
        createdAt = createdAt,
        origin = SessionOriginWire.KEYBOARD,
        dispatched = dispatched,
    )

    private companion object {
        const val DEVICE_ID = "test-device-0001"
    }
}
