package net.devemperor.dictate.companion.server

import net.devemperor.dictate.companion.CompanionContainer
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.data.SqlDelightDeviceRepository
import net.devemperor.dictate.companion.data.SqlDelightHistoryRepository
import net.devemperor.dictate.companion.domain.model.InsertionOutcome
import net.devemperor.dictate.companion.fakes.FakePhoneHistory
import net.devemperor.dictate.companion.fakes.FakeTextInserter
import net.devemperor.dictate.companion.fakes.MutableClock
import net.devemperor.dictate.shared.auth.AuthHeaders
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.DecodeResult
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.ErrorCode
import net.devemperor.dictate.shared.protocol.ErrorEnvelope
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.protocol.ProtocolCodec
import net.devemperor.dictate.shared.protocol.SyncCursor
import net.devemperor.dictate.shared.protocol.Validations
import net.devemperor.dictate.shared.sync.SyncClient
import net.devemperor.dictate.shared.sync.SyncOutcome
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The lazy sync, end to end: the **real** [SyncClient] paging against the **real** server over real
 * HTTP (ADR-0020).
 *
 * The client and the server hold two halves of one invariant — the phone pages by
 * `(createdAt, sessionId)` and the server answers with the watermark it actually stored — and the
 * only place those halves meet is over a socket. A fake server would be written from the same
 * misunderstanding as a broken real one.
 */
class SyncE2ETest {

    private val inserter = FakeTextInserter()
    private val clock = MutableClock()
    private val database = CompanionDatabase.inMemory()
    private val devices = SqlDelightDeviceRepository(database)
    private val history = SqlDelightHistoryRepository(database)

    private lateinit var container: CompanionContainer
    private lateinit var server: CompanionServer
    private lateinit var baseUrl: String
    private lateinit var credentials: Credentials

    @Before
    fun setUp() {
        container = CompanionContainer.forTest(inserter, clock, devices, history)
        server = CompanionServer(container, hosts = listOf("127.0.0.1"), port = 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.boundPort()}"

        val token = container.pairingService.issue().token
        val paired = (client().pair(token, DEVICE_ID, "Pixel 8") as DispatchResult.Success).value
        credentials = Credentials(paired.deviceId, paired.deviceSecret)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun anEmptyServer_saysItKnowsNothing_andGetsTheWholeHistory() {
        val phone = FakePhoneHistory.of(3)

        val outcome = syncClient(phone).sync()

        assertEquals(SyncOutcome.UpToDate(3), outcome)
        // The first page is asked for with a null cursor — that is the self-healing property: a
        // companion whose database was wiped is indistinguishable from a fresh one, and gets
        // everything back rather than staying silently empty for ever.
        assertEquals(null, phone.queries.first().first)
        assertEquals(3, history.count(null))
        assertEquals(phone.latestCursor(), history.cursor())
    }

    @Test
    fun aLargeHistory_isPagedUntilTheServerIsLevel() {
        val phone = FakePhoneHistory.of(250)

        val outcome = syncClient(phone, batchSize = 200).sync()

        assertEquals(SyncOutcome.UpToDate(250), outcome)
        assertEquals(250, history.count(null))
        assertEquals(phone.latestCursor(), history.cursor())

        // Two pages, and the second one asked from exactly the watermark the first one left behind.
        assertEquals(2, phone.queries.size)
        assertNull(phone.queries[0].first)
        assertEquals(SyncCursor(FakePhoneHistory.FIRST_CREATED_AT + 199, "session-00199"), phone.queries[1].first)
    }

    @Test
    fun aSecondRunWithNothingNew_sendsNothing() {
        val phone = FakePhoneHistory.of(5)
        syncClient(phone).sync()
        val cursorAfterFirstRun = history.cursor()

        val outcome = syncClient(phone).sync()

        assertEquals(SyncOutcome.UpToDate(0), outcome)
        assertEquals(5, history.count(null))
        assertEquals(cursorAfterFirstRun, history.cursor())
    }

    @Test
    fun aRunThatHitsThePageCap_isTruncated_andTheNextRunFinishesTheJob() {
        val phone = FakePhoneHistory.of(250)

        // maxBatches = 1: the phone's cap, standing in for "the executor was needed elsewhere".
        val first = syncClient(phone, batchSize = 200, maxBatches = 1).sync()

        assertEquals(SyncOutcome.Truncated(200), first)
        assertEquals(200, history.count(null))

        // Resuming needs no repair and no bookkeeping: the phone re-asks the server where it is.
        val second = syncClient(phone, batchSize = 200).sync()

        assertEquals(SyncOutcome.UpToDate(50), second)
        assertEquals("no duplicates across the resume boundary", 250, history.count(null))
    }

    @Test
    fun replayingTheSamePage_changesNothing() {
        val phone = FakePhoneHistory.of(5)
        syncClient(phone).sync()

        // What an interrupted run looks like from the server's side: the same rows, again.
        val replayed = client(credentials).sync(phone.sessionsAfter(null, 200))

        assertTrue(replayed is DispatchResult.Success)
        assertEquals(5, (replayed as DispatchResult.Success).value.accepted)
        assertEquals("an upsert is idempotent over its sessionId", 5, history.count(null))
    }

    @Test
    fun aSyncNeverDowngradesADispatchedRow() {
        // The text was dispatched — typed into the foreground window, recorded here.
        client(credentials).dispatch(
            DispatchRequest(
                sessionId = "session-00000",
                text = "dictation 0",
                createdAt = FakePhoneHistory.FIRST_CREATED_AT,
                origin = SessionOriginWire.KEYBOARD,
            ),
        )
        assertTrue(history.findById("session-00000")!!.dispatched)

        // The phone now syncs its history, where that same session may still look undispatched (the
        // phone sends what it has, not what it thinks we have). The row must not be downgraded.
        syncClient(FakePhoneHistory.of(3, dispatched = false)).sync()

        val row = history.findById("session-00000")!!
        assertTrue("a sync must not erase the fact that this text was typed here", row.dispatched)
        assertEquals(InsertionOutcome.TYPED_CTRL_V, row.lastOutcome)
        assertEquals(3, history.count(null))
    }

    @Test
    fun aPageLargerThanTheBatchCap_isRejectedByTheServer() {
        val oversize = FakePhoneHistory.of(Endpoints.MAX_SYNC_BATCH + 1).sessionsAfter(null, Int.MAX_VALUE)
        val body = ProtocolCodec.json.encodeToString(
            net.devemperor.dictate.shared.protocol.SyncRequest.serializer(),
            net.devemperor.dictate.shared.protocol.SyncRequest(items = oversize),
        )

        // Bypassing the client's own encode-validation on purpose: the cap has to hold on the server
        // too, or an oversized page from a buggy peer would be a memory problem, not a 400.
        val response = OkHttpDispatchTransport(baseUrl).post(Endpoints.SYNC, body, AuthHeaders.forDevice(credentials))

        assertEquals(400, response.status)
        val envelope = (
            ProtocolCodec.decode(response.body, ErrorEnvelope.serializer(), Validations.errorEnvelope)
                as DecodeResult.Ok
            ).value
        assertEquals(ErrorCode.VALIDATION_FAILED, envelope.code)
        assertEquals(listOf("items"), envelope.details.map { it.path })
        assertEquals(0, history.count(null))
    }

    @Test
    fun theSyncRoutesRequirePairing() {
        val wrong = Credentials(DEVICE_ID, "not-the-secret-but-long-enough-000")

        val outcome = SyncClient(client(wrong), FakePhoneHistory.of(3)).sync()

        // Failed, not Partial: the run could not even fetch the cursor, so nothing was sent.
        assertTrue("$outcome", outcome is SyncOutcome.Failed)
        assertEquals(0, history.count(null))
    }

    private fun client(credentials: Credentials? = null) =
        DispatchClient(OkHttpDispatchTransport(baseUrl), credentials = { credentials })

    private fun syncClient(
        phone: FakePhoneHistory,
        batchSize: Int = Endpoints.MAX_SYNC_BATCH,
        maxBatches: Int = 20,
    ) = SyncClient(client(credentials), phone, batchSize = batchSize, maxBatches = maxBatches)

    private companion object {
        const val DEVICE_ID = "test-device-0001"
    }
}
