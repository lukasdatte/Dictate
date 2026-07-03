package net.devemperor.dictate.state

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.testutil.FakeSessionDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the full §6.3 recovery algorithm implemented in C10
 * by [PipelineRecovery] (replacing the C7 baseline whose tests live in
 * [PipelineRecoveryTest]).
 *
 * Covers the 6 KG-SST-2 / §6.3 + §11.6 recovery cases plus the SF-4
 * manual-paste dispatch wiring:
 *
 *  1. **Happy boot** — no candidate rows → store remains empty, no dispatch.
 *  2. **RECORDING → FAILED** — promote, opportunistic file-delete,
 *     clear path.
 *  3. **TRANSCRIBING with audio → RECORDED** — downgrade, clear stale errors.
 *  4. **TRANSCRIBING without audio → FAILED** — promote, set vanished-reason.
 *  5. **Ghost RECORDED (audio missing) → FAILED**.
 *  6. **COMPLETED + pending insertion** — surfaced in `pendingSessions`,
 *     SF-4 action dispatched for the manual-paste hint.
 *  7. **Mixed-orphan recovery** — combines several cases in one boot pass.
 *  8. **Merge contract** — existing in-memory entries survive recovery
 *     (no override), duplicate session-ids are deduplicated.
 *  9. **Failure path** — DAO throws → recovery degrades gracefully without
 *     mutating the store further.
 */
class PipelineRecoveryFullTest {

    private val dao = FakeSessionDao()
    private val adapter = PipelineSessionRepoAdapter(dao)
    private val dispatchedActions = mutableListOf<Action>()

    private fun recovery(
        // F-005 — cold-boot RESEND-button seed probe. Defaults to the
        // production default (false = no resendable session).
        resendableSeedProbe: suspend () -> Boolean = { false },
    ) = PipelineRecovery(
        sessionDao = dao,
        sessionRepo = adapter,
        emitAction = { dispatchedActions += it },
        ioContext = EmptyCoroutineContext,  // run on caller's dispatcher (test-deterministic)
        resendableSeedProbe = resendableSeedProbe,
    )

    private fun seed(
        id: String,
        status: SessionStatus,
        audioFilePath: String? = null,
        finalOutputText: String? = null,
        insertedAt: Long? = null,
        lastErrorType: String? = null,
        lastErrorMessage: String? = null,
    ) = dao.seed(
        SessionEntity(
            id = id,
            type = "RECORDING",
            createdAt = 0L,
            targetAppPackage = null,
            language = null,
            audioFilePath = audioFilePath,
            // Block A3 — mirror legacy path into the multi-segment column
            // so the post-MIGRATION_6_7 row shape is reflected. Without
            // this PipelineRecovery's `audioFilePaths`-based check fails.
            audioFilePaths = listOfNotNull(audioFilePath),
            status = status.name,
            finalOutputText = finalOutputText,
            insertedAt = insertedAt,
            lastErrorType = lastErrorType,
            lastErrorMessage = lastErrorMessage,
        )
    )

    // ─── Case 1: happy boot ──────────────────────────────────────────

    @Test
    fun `recover happy boot - no pending rows leaves store empty and no SF-4 dispatch`() {
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery().recover(store) }

        assertTrue(store.snapshot.pendingSessions.isEmpty())
        // F-005: the Phase-6 RESEND-seed is emitted unconditionally
        // (false here — no resendable session), so the only expected
        // dispatch is MarkLastAudio(exists=false). No SF-4, no surfacing.
        assertEquals(
            "expected only the F-005 seed dispatch, got $dispatchedActions",
            listOf<Action>(Action.ResendAction.MarkLastAudio(exists = false)),
            dispatchedActions,
        )
    }

    // ─── Case 2: RECORDING with audio present → RECORDING_INTERRUPTED ──

    @Test
    fun `recover promotes RECORDING with audio present to RECORDING_INTERRUPTED keeping the file`() {
        // B2 / ADR-0008 — non-destructive recovery. The Rolling-Segments
        // machinery finalised at least one segment; the user can resume
        // on the next Record-click via Continuation. No error marker,
        // no file deletion.
        val tmpFile = File.createTempFile("dictate-rec", ".m4a").also { it.deleteOnExit() }
        seed("rec-1", SessionStatus.RECORDING, audioFilePath = tmpFile.absolutePath)
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery().recover(store) }

        val row = dao.getById("rec-1")
        assertNotNull(row)
        assertEquals(SessionStatus.RECORDING_INTERRUPTED.name, row!!.status)
        assertNull("no error marker on RECORDING_INTERRUPTED", row.lastErrorType)
        assertNull("no error message on RECORDING_INTERRUPTED", row.lastErrorMessage)
        assertEquals(
            "audio_file_path must be preserved for Continuation",
            tmpFile.absolutePath, row.audioFilePath,
        )
        assertTrue("audio file must NOT be deleted", tmpFile.exists())
    }

    @Test
    fun `recover promotes RECORDING with NULL audio path - no file-delete attempted, status still FAILED`() {
        seed("rec-null", SessionStatus.RECORDING, audioFilePath = null)
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery().recover(store) }

        val row = dao.getById("rec-null")!!
        assertEquals(SessionStatus.FAILED.name, row.status)
        assertEquals(AIProviderException.ErrorType.UNKNOWN.name, row.lastErrorType)
    }

    // ─── Case 3: TRANSCRIBING (audio exists) → RECORDED ──────────────

    @Test
    fun `recover downgrades TRANSCRIBING with extant audio to RECORDED and clears stale errors`() {
        val tmpFile = File.createTempFile("dictate-trans", ".m4a").also {
            it.deleteOnExit()
        }
        seed(
            id = "trans-1",
            status = SessionStatus.TRANSCRIBING,
            audioFilePath = tmpFile.absolutePath,
            lastErrorType = AIProviderException.ErrorType.RATE_LIMITED.name,
            lastErrorMessage = "stale: api quota exceeded",
        )
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery().recover(store) }

        val row = dao.getById("trans-1")!!
        assertEquals(SessionStatus.RECORDED.name, row.status)
        // Stale errors cleared per §6.3 Z. 3389-3394.
        assertNull(row.lastErrorType)
        assertNull(row.lastErrorMessage)
        // RECORDED-with-audio → surfaced in pending list.
        assertEquals(listOf("trans-1"), store.snapshot.pendingSessions.map { it.sessionId })
    }

    // ─── Case 4: TRANSCRIBING (audio missing) → FAILED ───────────────

    @Test
    fun `recover promotes TRANSCRIBING with missing audio to FAILED with vanished reason`() {
        seed(
            id = "trans-ghost",
            status = SessionStatus.TRANSCRIBING,
            audioFilePath = "/tmp/never-existed-${System.nanoTime()}.m4a",
        )
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery().recover(store) }

        val row = dao.getById("trans-ghost")!!
        assertEquals(SessionStatus.FAILED.name, row.status)
        assertEquals(AIProviderException.ErrorType.UNKNOWN.name, row.lastErrorType)
        assertEquals("audio file vanished before transcription", row.lastErrorMessage)
        assertNull(row.audioFilePath)
        assertTrue(store.snapshot.pendingSessions.isEmpty())
    }

    // ─── Case 5: Ghost RECORDED → FAILED ─────────────────────────────

    @Test
    fun `recover promotes RECORDED with missing audio to FAILED ghost-handler`() {
        seed(
            id = "ghost",
            status = SessionStatus.RECORDED,
            audioFilePath = "/tmp/never-existed-ghost-${System.nanoTime()}.m4a",
        )
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery().recover(store) }

        val row = dao.getById("ghost")!!
        assertEquals(SessionStatus.FAILED.name, row.status)
        assertEquals(AIProviderException.ErrorType.UNKNOWN.name, row.lastErrorType)
        assertEquals("audio file vanished", row.lastErrorMessage)
        assertNull(row.audioFilePath)
        assertTrue(store.snapshot.pendingSessions.isEmpty())
    }

    // ─── Case 6: COMPLETED + pending insertion → SF-4 wiring ────────

    @Test
    fun `recover dispatches NotifyManualPasteNeeded for COMPLETED rows with pending insertion`() {
        seed(
            id = "complete-pending",
            status = SessionStatus.COMPLETED,
            finalOutputText = "user-facing result",
            insertedAt = null,
        )
        seed(
            id = "complete-already-inserted",
            status = SessionStatus.COMPLETED,
            finalOutputText = "old result",
            insertedAt = 100L,
        )
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery().recover(store) }

        // Pending list hydrates with the not-yet-inserted row.
        assertEquals(
            listOf("complete-pending"),
            store.snapshot.pendingSessions.map { it.sessionId },
        )
        // SF-4 — exactly one NotifyManualPasteNeeded dispatched for the pending row.
        val pasteActions = dispatchedActions.filterIsInstance<Action.ResendAction.NotifyManualPasteNeeded>()
        assertEquals(1, pasteActions.size)
        assertEquals("complete-pending", pasteActions[0].sessionId)
    }

    @Test
    fun `recover does NOT dispatch NotifyManualPasteNeeded for already-inserted COMPLETED rows`() {
        seed(
            id = "complete-old",
            status = SessionStatus.COMPLETED,
            finalOutputText = "text",
            insertedAt = 500L,
        )
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery().recover(store) }

        assertTrue(
            "no SF-4 dispatch expected for inserted-row",
            dispatchedActions.filterIsInstance<Action.ResendAction.NotifyManualPasteNeeded>().isEmpty(),
        )
        assertTrue(store.snapshot.pendingSessions.isEmpty())
    }

    // ─── Case 7: mixed-orphan recovery ────────────────────────────────

    @Test
    fun `recover processes mixed RECORDING + TRANSCRIBING + RECORDED + COMPLETED in one pass`() {
        val recAudio = File.createTempFile("dictate-mix-rec", ".m4a").also { it.deleteOnExit() }
        val transAudio = File.createTempFile("dictate-mix-trans", ".m4a").also { it.deleteOnExit() }
        val recordedAudio = File.createTempFile("dictate-mix-rd", ".m4a").also { it.deleteOnExit() }

        seed("rec", SessionStatus.RECORDING, audioFilePath = recAudio.absolutePath)
        seed("trans-ok", SessionStatus.TRANSCRIBING, audioFilePath = transAudio.absolutePath)
        seed("recorded-ok", SessionStatus.RECORDED, audioFilePath = recordedAudio.absolutePath)
        seed(
            id = "compl-pending",
            status = SessionStatus.COMPLETED,
            finalOutputText = "x",
            insertedAt = null,
        )

        val store = DictateUiStateStore(DictateUiState.initial())
        runBlocking { recovery().recover(store) }

        // RECORDING with audio present → RECORDING_INTERRUPTED (B2)
        assertEquals(SessionStatus.RECORDING_INTERRUPTED.name, dao.getById("rec")!!.status)
        // TRANSCRIBING (with file) → RECORDED
        assertEquals(SessionStatus.RECORDED.name, dao.getById("trans-ok")!!.status)
        // RECORDED stays
        assertEquals(SessionStatus.RECORDED.name, dao.getById("recorded-ok")!!.status)
        // COMPLETED stays
        assertEquals(SessionStatus.COMPLETED.name, dao.getById("compl-pending")!!.status)

        // Pending set: rec (now RECORDING_INTERRUPTED, surfaced by
        // recording-stack-completion §4.5.3 for the Trash-Btn-Discard
        // path) + trans-ok (just downgraded to RECORDED with file) +
        // recorded-ok + compl-pending.
        val pendingIds = store.snapshot.pendingSessions.map { it.sessionId }.toSet()
        assertEquals(setOf("rec", "trans-ok", "recorded-ok", "compl-pending"), pendingIds)

        // SF-4 fired for compl-pending only.
        assertEquals(
            listOf("compl-pending"),
            dispatchedActions
                .filterIsInstance<Action.ResendAction.NotifyManualPasteNeeded>()
                .map { it.sessionId },
        )
    }

    // ─── Case 8: merge contract ──────────────────────────────────────

    @Test
    fun `recover merges with pre-existing in-memory pendingSessions (no override)`() {
        val tmp = File.createTempFile("dictate-merge", ".m4a").also { it.deleteOnExit() }
        seed("from-db", SessionStatus.RECORDED, audioFilePath = tmp.absolutePath)

        // Simulate a parallel recording that arrived between service-onCreate
        // and recovery.recover()'s store.update.
        val preExisting = PendingSession("parallel", SessionStatus.RECORDED, null, 0L)
        val store = DictateUiStateStore(
            DictateUiState.initial().copy(
                pendingSessions = kotlinx.collections.immutable.persistentListOf(preExisting),
            )
        )

        runBlocking { recovery().recover(store) }

        val ids = store.snapshot.pendingSessions.map { it.sessionId }
        // Both the in-memory entry AND the DB entry survive.
        assertTrue("parallel survived", ids.contains("parallel"))
        assertTrue("from-db merged", ids.contains("from-db"))
        assertEquals(2, ids.size)
    }

    @Test
    fun `recover deduplicates on sessionId during merge`() {
        val tmp = File.createTempFile("dictate-dedup", ".m4a").also { it.deleteOnExit() }
        seed("dup", SessionStatus.RECORDED, audioFilePath = tmp.absolutePath)

        val sameId = PendingSession("dup", SessionStatus.RECORDED, null, 999L)
        val store = DictateUiStateStore(
            DictateUiState.initial().copy(
                pendingSessions = kotlinx.collections.immutable.persistentListOf(sameId),
            )
        )

        runBlocking { recovery().recover(store) }

        // Only one "dup" row exists — the pre-existing in-memory entry wins
        // (sessions added during merge are filtered by `seen`).
        val matches = store.snapshot.pendingSessions.filter { it.sessionId == "dup" }
        assertEquals(1, matches.size)
        assertEquals(999L, matches[0].createdAt)
    }

    // ─── Case 9: idempotence ──────────────────────────────────────────

    @Test
    fun `recover is idempotent across repeated runs`() {
        val tmp = File.createTempFile("dictate-idem", ".m4a").also { it.deleteOnExit() }
        seed("idem", SessionStatus.RECORDED, audioFilePath = tmp.absolutePath)

        val store = DictateUiStateStore(DictateUiState.initial())
        runBlocking {
            recovery().recover(store)
            recovery().recover(store)
        }

        // After two runs, the pending list contains exactly one entry.
        assertEquals(1, store.snapshot.pendingSessions.size)
    }

    // ─── Failure path: DAO throws ─────────────────────────────────────

    @Test
    fun `recover degrades gracefully when DAO throws during status promotion`() {
        // Replace the DAO with one that throws on getSessionsByStatuses.
        val throwingDao = object : net.devemperor.dictate.database.dao.SessionDao by FakeSessionDao() {
            override fun getSessionsByStatuses(statuses: List<String>): List<SessionEntity> {
                throw RuntimeException("simulated DAO failure")
            }
        }
        val rec = PipelineRecovery(
            sessionDao = throwingDao,
            sessionRepo = PipelineSessionRepoAdapter(throwingDao),
            emitAction = { dispatchedActions += it },
            ioContext = EmptyCoroutineContext,
        )
        val store = DictateUiStateStore(DictateUiState.initial())

        // Should not throw — recovery catches and logs.
        runBlocking { rec.recover(store) }

        // Store is unchanged; pending list is empty.
        assertTrue(store.snapshot.pendingSessions.isEmpty())
    }

    // ─── Phase 5: auto-surface the interrupted recording (2026-05-22) ──

    @Test
    fun `recover Phase 5 surfaces a fresh interrupted recording via SurfaceInterruptedRecording`() {
        // A keyboard-origin RECORDING_INTERRUPTED row → Phase 5 dispatches
        // SurfaceInterruptedRecording so the next keyboard open shows the
        // cut-off recording "as if briefly paused" instead of waiting for
        // a Record-tap.
        val tmp = File.createTempFile("dictate-int", ".m4a").also { it.deleteOnExit() }
        seed("interrupted-1", SessionStatus.RECORDING_INTERRUPTED, audioFilePath = tmp.absolutePath)
        val store = DictateUiStateStore(DictateUiState.initial())
        val recovery = PipelineRecovery(
            sessionDao = dao,
            sessionRepo = adapter,
            emitAction = { dispatchedActions += it },
            ioContext = EmptyCoroutineContext,
            // Huge freshness window so the createdAt=0 seed row is "fresh".
            continuationFreshnessMs = { Long.MAX_VALUE / 2 },
            interruptedRecordingElapsedMsProvider = { 8_000L },
        )

        runBlocking { recovery.recover(store) }

        val surface = dispatchedActions
            .filterIsInstance<Action.RecordingAction.SurfaceInterruptedRecording>()
            .single()
        assertEquals("interrupted-1", surface.sessionId)
        assertEquals(8_000L, surface.elapsedMs)
    }

    @Test
    fun `recover Phase 5 surfaces a fresh RECORDED row via SurfaceInterruptedRecording (2026-05-23)`() {
        // findLatestUnfinishedRecording extension — RECORDED rows now
        // share the same surfacing path as RECORDING_INTERRUPTED so the
        // user gets one consistent "unfinished recording" affordance set
        // (keyboard record/trash buttons) regardless of why the audio
        // was left over.
        val tmp = File.createTempFile("dictate-recorded", ".m4a").also { it.deleteOnExit() }
        seed("recorded-1", SessionStatus.RECORDED, audioFilePath = tmp.absolutePath)
        val store = DictateUiStateStore(DictateUiState.initial())
        val recovery = PipelineRecovery(
            sessionDao = dao,
            sessionRepo = adapter,
            emitAction = { dispatchedActions += it },
            ioContext = EmptyCoroutineContext,
            continuationFreshnessMs = { Long.MAX_VALUE / 2 },
            interruptedRecordingElapsedMsProvider = { 4_200L },
        )

        runBlocking { recovery.recover(store) }

        val surface = dispatchedActions
            .filterIsInstance<Action.RecordingAction.SurfaceInterruptedRecording>()
            .single()
        assertEquals("recorded-1", surface.sessionId)
        assertEquals(4_200L, surface.elapsedMs)
    }

    @Test
    fun `recover Phase 5 does not surface when only non-unfinished rows exist`() {
        // COMPLETED is terminal — it never surfaces as an unfinished
        // recording. Sanity-check the negative case so a future query
        // regression that pulls COMPLETED into the unfinished set fails
        // here rather than silently surfacing finished sessions as
        // "tap to continue".
        val tmp = File.createTempFile("dictate-completed", ".m4a").also { it.deleteOnExit() }
        seed("completed-only", SessionStatus.COMPLETED, audioFilePath = tmp.absolutePath)
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery().recover(store) }

        assertTrue(
            "no unfinished recording → no SurfaceInterruptedRecording dispatch",
            dispatchedActions.none {
                it is Action.RecordingAction.SurfaceInterruptedRecording
            },
        )
    }

    // ─── Phase 6: F-005 — cold-boot RESEND-button seed ────────────────
    //
    // `ResendState.lastAudioExists` defaults to false and pre-fix was
    // flipped true ONLY by post-pipeline events, so after a process
    // restart the RESEND button stayed hidden despite a resendable
    // session on disk. Phase 6 emits MarkLastAudio(seed) at recovery.
    // These tests are red on the pre-fix PipelineRecovery (recover()
    // never emitted MarkLastAudio at all) and green on the fixed one.

    @Test
    fun `recover Phase 6 seeds MarkLastAudio true when a resendable session exists`() {
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery(resendableSeedProbe = { true }).recover(store) }

        val seed = dispatchedActions
            .filterIsInstance<Action.ResendAction.MarkLastAudio>()
            .single()
        assertTrue("resendable session → button reappears after restart", seed.exists)
    }

    @Test
    fun `recover Phase 6 seeds MarkLastAudio false when no resendable session exists`() {
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking { recovery(resendableSeedProbe = { false }).recover(store) }

        val seed = dispatchedActions
            .filterIsInstance<Action.ResendAction.MarkLastAudio>()
            .single()
        assertFalse("no resendable session → button stays hidden", seed.exists)
    }

    @Test
    fun `recover Phase 6 seeds MarkLastAudio false when the probe throws (fail-soft)`() {
        // The probe reads the DB via SessionManager in production — a DAO
        // failure must not kill recovery, and the seed degrades to false
        // (button hidden, same as pre-seed behaviour).
        val store = DictateUiStateStore(DictateUiState.initial())

        runBlocking {
            recovery(resendableSeedProbe = { error("dao exploded") }).recover(store)
        }

        val seed = dispatchedActions
            .filterIsInstance<Action.ResendAction.MarkLastAudio>()
            .single()
        assertFalse("probe failure degrades to hidden button", seed.exists)
    }
}
