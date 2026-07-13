package net.devemperor.dictate.shared.sync

import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchError
import net.devemperor.dictate.shared.fakes.FakeSyncCompanion
import net.devemperor.dictate.shared.fakes.FakeSyncSource
import net.devemperor.dictate.shared.fakes.FakeTransport
import net.devemperor.dictate.shared.protocol.CursorResponse
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.SessionUpsert
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.shared.protocol.SyncResponse
import net.devemperor.dictate.shared.protocol.Validations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Paging, resumption and idempotency of the lazy sync (ADR-0020).
 *
 * Most of these run against [FakeSyncCompanion] — an in-memory server that really stores the rows
 * and really derives its cursor from them. Canned responses would let a paging bug pass unnoticed;
 * only a stateful counterpart can show that a page boundary neither skips nor repeats a session.
 */
class SyncClientTest {

    private fun client(transport: net.devemperor.dictate.shared.transport.DispatchTransport) =
        DispatchClient(transport) { Credentials("device-1", "secret-1") }

    private fun syncClient(
        companion: FakeSyncCompanion,
        source: FakeSyncSource,
        batchSize: Int = 200,
        maxBatches: Int = 20,
        log: (String) -> Unit = {},
    ) = SyncClient(client(companion), source, batchSize = batchSize, maxBatches = maxBatches, log = log)

    // ── Paging ──────────────────────────────────────────────────────────────────────────

    @Test
    fun sync_moreThanOnePage_pushesEveryRowExactlyOnce() {
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(250)

        val outcome = syncClient(companion, source).sync()

        assertEquals(SyncOutcome.UpToDate(250), outcome)
        assertEquals(listOf(200, 50), companion.pushedPages.map { it.size })
        assertEquals(250, companion.rows.size)
        // The boundary is where a paging bug shows: no session may be pushed twice or skipped.
        val pushedIds = companion.pushedPages.flatten().map { it.sessionId }
        assertEquals(pushedIds.size, pushedIds.toSet().size)
        assertEquals(source.latestCursor(), companion.cursor())
    }

    @Test
    fun sync_pageSizeExactMultiple_needsOneMoreEmptyRoundTripToKnowItIsDone() {
        // 400 rows / 200 per page: the second page is FULL, so the client cannot yet know it is
        // finished — it asks once more and gets nothing back.
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(400)

        val outcome = syncClient(companion, source).sync()

        assertEquals(SyncOutcome.UpToDate(400), outcome)
        assertEquals(listOf(200, 200), companion.pushedPages.map { it.size })
        assertEquals(3, source.queries.size)
    }

    @Test
    fun sync_emptyDelta_isOneRoundTripAndSendsNothing() {
        val companion = FakeSyncCompanion()

        val outcome = syncClient(companion, FakeSyncSource()).sync()

        assertEquals(SyncOutcome.UpToDate(0), outcome)
        assertEquals(emptyList<Any>(), companion.pushedPages)
    }

    @Test
    fun sync_secondRunWithNothingNew_sendsNothing() {
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(30)
        syncClient(companion, source).sync()

        val outcome = syncClient(companion, source).sync()

        assertEquals(SyncOutcome.UpToDate(0), outcome)
        assertEquals(1, companion.pushedPages.size)
        assertEquals(30, companion.rows.size)
    }

    @Test
    fun sync_onlyTheNewSessions_areSentOnASecondRun() {
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(30)
        syncClient(companion, source).sync()

        source.add(SessionUpsert("session-99999", "brand new", 1_700_000_099_999L, SessionOriginWire.KEYBOARD, dispatched = true))
        val outcome = syncClient(companion, source).sync()

        assertEquals(SyncOutcome.UpToDate(1), outcome)
        assertEquals(listOf("session-99999"), companion.pushedPages.last().map { it.sessionId })
        assertEquals(31, companion.rows.size)
    }

    // ── Interruption and resumption ─────────────────────────────────────────────────────

    @Test
    fun sync_failureOnTheSecondPage_isPartial_andTheFirstPageStaysAcknowledged() {
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(250)

        val first = syncClient(companion, source, batchSize = 100).sync()
        assertEquals(SyncOutcome.UpToDate(250), first)

        // Now a fresh history with more rows, and the network drops on the next push.
        val grown = FakeSyncSource.of(400)
        companion.failNextSyncWith = IOException("network lost")

        val outcome = syncClient(companion, grown, batchSize = 100).sync()

        assertTrue(outcome.toString(), outcome is SyncOutcome.Partial)
        assertEquals(0, (outcome as SyncOutcome.Partial).sent)
        assertTrue(outcome.error.toString(), outcome.error is DispatchError.Unreachable)
        // What got through before the failure is still there — the server's cursor is the receipt.
        assertEquals(250, companion.rows.size)
    }

    @Test
    fun sync_resumingAfterAPartialRun_deliversExactlyTheMissingRows_withNoDuplicates() {
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(300)

        // First run dies after page 1 (100 rows).
        val syncing = SyncClient(client(companion), source, batchSize = 100, maxBatches = 20)
        companion.pushedPages.clear()
        val firstPage = source.sessionsAfter(null, 100)
        client(companion).sync(firstPage)
        assertEquals(100, companion.rows.size)

        val outcome = syncing.sync()

        assertEquals(SyncOutcome.UpToDate(200), outcome)
        assertEquals(300, companion.rows.size)
        // Every one of the 300 sessions is on the server exactly once, and nothing was resent.
        assertEquals(300, companion.rows.map { it.sessionId }.toSet().size)
        assertEquals(listOf(100, 100), companion.pushedPages.drop(1).map { it.size })
    }

    @Test
    fun sync_resendingTheSamePage_isANoOp_becauseUpsertsAreIdempotent() {
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(50)
        val page = source.sessionsAfter(null, 50)

        client(companion).sync(page)
        client(companion).sync(page)

        assertEquals(50, companion.rows.size)
    }

    @Test
    fun sync_serverDatabaseWiped_sendsTheHistoryAgainFromTheBeginning() {
        // The reason the SERVER holds the cursor: if the phone held it, a wiped companion would be
        // invisible and its history would stay empty for ever.
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(30)
        syncClient(companion, source).sync()
        assertEquals(30, companion.rows.size)

        companion.wipe()
        val queriesBeforeSecondRun = source.queries.size
        val outcome = syncClient(companion, source).sync()

        assertEquals(SyncOutcome.UpToDate(30), outcome)
        assertEquals(30, companion.rows.size)
        // The second run starts from scratch, because the server admitted it knows nothing.
        assertNull(source.queries[queriesBeforeSecondRun].first)
    }

    // ── Caps and pathological servers ───────────────────────────────────────────────────

    @Test
    fun sync_pageCapReached_isTruncated_andSaysSoOutLoud() {
        val logged = mutableListOf<String>()
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(100)

        val outcome = syncClient(companion, source, batchSize = 10, maxBatches = 3, log = logged::add).sync()

        assertEquals(SyncOutcome.Truncated(30), outcome)
        assertEquals(30, companion.rows.size)
        // "No silent caps" — a run that stopped early must be visible in the log.
        assertTrue(logged.toString(), logged.any { it.contains("cap") })
    }

    @Test
    fun sync_nextTriggerAfterTruncation_continuesWhereItStopped() {
        val companion = FakeSyncCompanion()
        val source = FakeSyncSource.of(100)
        syncClient(companion, source, batchSize = 10, maxBatches = 3).sync()

        val outcome = syncClient(companion, source, batchSize = 10, maxBatches = 20).sync()

        assertEquals(SyncOutcome.UpToDate(70), outcome)
        assertEquals(100, companion.rows.size)
        assertEquals(100, companion.rows.map { it.sessionId }.toSet().size)
    }

    @Test
    fun sync_serverCursorDoesNotAdvance_isStalled_ratherThanAnInfiniteResend() {
        // A server that accepts a page but reports the same watermark would have us push the same
        // rows until the cap. Stop instead, and say why.
        val logged = mutableListOf<String>()
        val staleCursor = ProtocolCodec.encode(
            SyncResponse(accepted = 2, cursor = SyncCursor(lastCreatedAt = 0L, lastSessionId = "")),
            SyncResponse.serializer(),
            Validations.syncResponse,
        )
        val transport = FakeTransport()
            .respond(
                Endpoints.SYNC_CURSOR,
                200,
                ProtocolCodec.encode(CursorResponse(cursor = null), CursorResponse.serializer(), Validations.cursorResponse),
            )
            .respond(Endpoints.SYNC, 200, staleCursor)
        val source = FakeSyncSource.of(50)

        val outcome = SyncClient(client(transport), source, batchSize = 10, maxBatches = 20, log = logged::add).sync()

        assertTrue(outcome.toString(), outcome is SyncOutcome.Stalled)
        assertEquals(2, (outcome as SyncOutcome.Stalled).sent)
        // Exactly one page was pushed — not twenty.
        assertEquals(1, transport.calls.count { it.path == Endpoints.SYNC })
        assertTrue(logged.toString(), logged.any { it.contains("did not advance") })
    }

    @Test
    fun sync_serverReturnsNoCursorAfterAcceptingRows_isStalled() {
        val transport = FakeTransport()
            .respond(
                Endpoints.SYNC_CURSOR,
                200,
                ProtocolCodec.encode(CursorResponse(cursor = null), CursorResponse.serializer(), Validations.cursorResponse),
            )
            .respond(
                Endpoints.SYNC,
                200,
                ProtocolCodec.encode(SyncResponse(accepted = 5, cursor = null), SyncResponse.serializer(), Validations.syncResponse),
            )

        val outcome = SyncClient(client(transport), FakeSyncSource.of(50), batchSize = 10).sync()

        assertEquals(SyncOutcome.Stalled(5, null), outcome)
    }

    @Test
    fun sync_cursorCallFails_isFailed_andNothingIsSent() {
        val companion = FakeSyncCompanion()
        companion.failCursorWith = IOException("network unreachable")

        val outcome = syncClient(companion, FakeSyncSource.of(10)).sync()

        assertTrue(outcome.toString(), outcome is SyncOutcome.Failed)
        assertTrue((outcome as SyncOutcome.Failed).error is DispatchError.Unreachable)
        assertEquals(emptyList<Any>(), companion.pushedPages)
    }

    @Test
    fun sync_neverSendsAPageLargerThanTheProtocolAllows() {
        val companion = FakeSyncCompanion()

        val outcome = syncClient(companion, FakeSyncSource.of(500)).sync()

        assertEquals(SyncOutcome.UpToDate(500), outcome)
        assertTrue(companion.pushedPages.all { it.size <= Endpoints.MAX_SYNC_BATCH })
    }

    @Test
    fun constructor_rejectsABatchSizeTheProtocolWouldReject() {
        val companion = FakeSyncCompanion()

        val thrown = try {
            SyncClient(client(companion), FakeSyncSource(), batchSize = Endpoints.MAX_SYNC_BATCH + 1)
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertTrue("expected the constructor to reject an oversized batch", thrown != null)
    }
}
