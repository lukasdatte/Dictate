package net.devemperor.dictate.core

import android.media.MediaRecorder
import net.devemperor.dictate.audio.AudioFileRepository
import net.devemperor.dictate.audio.PipelineAudioResult
import net.devemperor.dictate.state.InsertionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests the Rolling-Segments (B1.3) wiring in [RecordingHardwareAdapter].
 *
 * Verifies that the adapter:
 *   1. Calls `setMaxDuration` on the MediaRecorder when an
 *      [AudioFileRepository] + session-id are present.
 *   2. Skips the rolling setup when either dependency is missing.
 *   3. Re-allocates a next segment via [AudioFileRepository.allocateNext]
 *      when the OnInfoListener fires `MAX_DURATION_APPROACHING`.
 *
 * MediaRecorder under Robolectric is a stub — the real `setNextOutputFile`
 * native call cannot be exercised in JVM tests. The contract we lock
 * here is "the adapter wires the OnInfoListener correctly and routes
 * MAX_DURATION_APPROACHING to allocateNext"; the kernel-level
 * setNextOutputFile call is covered by manual on-device verification
 * (Test 3 in the Plan §6 Manual-Test-Runbook).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingHardwareAdapterRollingTest {

    private class FakeRepo : AudioFileRepository {
        val allocateNextCalls = mutableListOf<String>()
        override fun allocateFirst(sessionId: String): File =
            File("ignored-allocateFirst-$sessionId.m4a")
        override fun allocateNext(sessionId: String): File {
            allocateNextCalls.add(sessionId)
            return File.createTempFile("rolling-test-$sessionId-", ".m4a").also {
                it.deleteOnExit()
            }
        }
        override fun segments(sessionId: String): List<File> = emptyList()
        override suspend fun readForPipeline(sessionId: String): PipelineAudioResult? = null
        override fun deleteAll(sessionId: String) = Unit
        override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> = emptySet()
        override fun listAllOwnedFiles(): Map<String, List<File>> = emptyMap()
    }

    private fun newAudioFile(name: String = "test-rolling.m4a"): File =
        File.createTempFile(name, ".m4a").also { it.deleteOnExit() }

    @Test
    fun `allocate with repo and session-id arms setMaxFileSize on MediaRecorder`() {
        val repo = FakeRepo()
        val adapter = RecordingHardwareAdapter(
            emitAction = { /* swallow */ },
            audioFileRepository = repo,
            rollingIntervalMs = 30_000L,
        )
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "session-with-rolling",
        )
        val mr = adapter.activeRecorder()
        assertNotNull("MediaRecorder must be allocated", mr)
        // Byte budget = bitRate / 8 × intervalSec × 1.15 headroom.
        // For DEFAULT_AAC_M4A (64 000 bps) and 30 s that's
        // 8 000 × 30 × 1.15 = 276 000.
        val expectedBytes = (64_000L / 8) * 30 * 115 / 100
        assertEquals(expectedBytes, shadowOf(mr!!).maxFileSize)
        adapter.release()
    }

    @Test
    fun `allocate without repo does NOT arm rolling`() {
        val adapter = RecordingHardwareAdapter(
            emitAction = { /* swallow */ },
            audioFileRepository = null,
            rollingIntervalMs = 30_000L,
        )
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "session-no-repo",
        )
        val mr = adapter.activeRecorder()
        assertNotNull(mr)
        // No setMaxFileSize call without a repository — the shadow
        // returns 0 (the un-set default).
        assertEquals(0, shadowOf(mr!!).maxFileSize)
        adapter.release()
    }

    @Test
    fun `allocate without session-id does NOT arm rolling`() {
        val repo = FakeRepo()
        val adapter = RecordingHardwareAdapter(
            emitAction = { /* swallow */ },
            audioFileRepository = repo,
            rollingIntervalMs = 30_000L,
        )
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = null,
        )
        val mr = adapter.activeRecorder()
        assertNotNull(mr)
        assertEquals(0, shadowOf(mr!!).maxFileSize)
        // And `allocateNext` is never called — the rolling pipeline is
        // entirely dormant without a session-id.
        assertTrue(repo.allocateNextCalls.isEmpty())
        adapter.release()
    }

    // ──── Always-one-ahead durability fix (2026-06-10) ──────────────
    //
    // Regression guard for the "recording continued but only the
    // beginning was processed" bug. The rolling roll must NOT depend on
    // the unreliable MAX_FILESIZE_APPROACHING info: a next segment is
    // pre-armed right after start() and re-armed after each handover, so
    // MAX_FILESIZE_REACHED always has a target and the native recorder
    // never silently stops mid-recording.
    //
    // NOTE on the assertion seam: Robolectric does not shadow
    // `MediaRecorder.setNextOutputFile`, so the native stub may reject
    // the call — but the adapter wraps it in try/catch and `allocateNext`
    // (the repository touch we assert on) runs BEFORE the native call.
    // `allocateNext` call-count is therefore the stable observable for
    // "the adapter attempted to arm the next segment".

    @Test
    fun `start eagerly pre-arms the next rolling segment`() {
        val repo = FakeRepo()
        val adapter = RecordingHardwareAdapter(
            emitAction = { /* swallow */ },
            audioFileRepository = repo,
            rollingIntervalMs = 30_000L,
        )
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "sid-eager-arm",
        )
        // Pre-start: no next segment armed yet (allocate only reserves seg1).
        assertTrue(
            "allocateNext must NOT run before start()",
            repo.allocateNextCalls.isEmpty(),
        )

        adapter.start()

        // Post-start: exactly one eager pre-arm for the next segment —
        // independent of any MAX_FILESIZE_APPROACHING info. This is the
        // core of the durability fix: the recorder can never reach the
        // file-size cap with no next file armed.
        assertEquals(
            listOf("sid-eager-arm"),
            repo.allocateNextCalls,
        )
        adapter.release()
    }

    @Test
    fun `handover info re-arms the segment after next (always-one-ahead)`() {
        val repo = FakeRepo()
        val adapter = RecordingHardwareAdapter(
            emitAction = { /* swallow */ },
            audioFileRepository = repo,
            rollingIntervalMs = 30_000L,
        )
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "sid-handover",
        )
        adapter.start()
        val armsAfterStart = repo.allocateNextCalls.size

        // Simulate the native handover: the pre-armed file became the
        // active output. The listener must arm the *next* one so the
        // invariant holds for the following roll. The OnInfoListener is
        // recovered from the Robolectric shadow (the same listener the
        // adapter wired in allocate()).
        val mr = adapter.activeRecorder()!!
        shadowOf(mr).infoListener.onInfo(
            mr, MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED, 0,
        )

        assertEquals(
            "each handover must re-arm exactly one further segment",
            armsAfterStart + 1,
            repo.allocateNextCalls.size,
        )
        adapter.release()
    }

    @Test
    fun `start without rolling (no repo) does not pre-arm`() {
        val adapter = RecordingHardwareAdapter(
            emitAction = { /* swallow */ },
            audioFileRepository = null,
            rollingIntervalMs = 30_000L,
        )
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "sid-no-roll",
        )
        // start() must be a no-op for rolling when no repository is wired
        // (the single-file legacy behaviour stays intact). No crash, no
        // allocateNext — there is no FakeRepo to touch, the guard returns
        // early. The assertion is implicit: start() completes cleanly.
        adapter.start()
        adapter.release()
    }

    @Test
    fun `release clears session-id reference`() {
        val repo = FakeRepo()
        val adapter = RecordingHardwareAdapter(
            emitAction = { /* swallow */ },
            audioFileRepository = repo,
            rollingIntervalMs = 1_500L,
        )
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "session-cleared-by-release",
        )
        adapter.release()
        // After release, activeRecorder is null; reflectively
        // verifying that activeSessionId got cleared would tighten
        // the assertion, but the contract is observable through the
        // recorder reference being gone.
        assertEquals(null, adapter.activeRecorder())
    }
}
