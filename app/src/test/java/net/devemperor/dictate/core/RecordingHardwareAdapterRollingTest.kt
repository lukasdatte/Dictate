package net.devemperor.dictate.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.devemperor.dictate.audio.AudioFileRepository
import net.devemperor.dictate.audio.PipelineAudioResult
import net.devemperor.dictate.state.InsertionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests the Rolling-Segments (L2) loop in [RecordingHardwareAdapter].
 *
 * Verifies that the loop:
 *   1. Starts on `start()` (only when both a repository and a
 *      session-id are present).
 *   2. Cancels on `pause()` and re-arms on `resume()`.
 *   3. Stops on `stop()` and `release()`.
 *   4. Calls `audioFileRepository.allocateNext(sessionId)` on each
 *      tick — at the configured interval.
 *
 * Uses Robolectric for the [android.media.MediaRecorder] shadow + a
 * [TestScope] with [StandardTestDispatcher] so `delay()` advances
 * deterministically via [advanceTimeBy].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingHardwareAdapterRollingTest {

    private class FakeRepo : AudioFileRepository {
        val allocateNextCalls = mutableListOf<String>()
        override fun allocateFirst(sessionId: String): File =
            File("ignored-allocateFirst-$sessionId.m4a")
        override fun allocateNext(sessionId: String): File {
            allocateNextCalls.add(sessionId)
            // Return a temp file so MediaRecorder.setNextOutputFile
            // does not immediately fail on path validation.
            return File.createTempFile("rolling-test-$sessionId-", ".m4a").also {
                it.deleteOnExit()
            }
        }
        override fun segments(sessionId: String): List<File> = emptyList()
        override suspend fun readForPipeline(sessionId: String): PipelineAudioResult? = null
        override fun deleteAll(sessionId: String) = Unit
        override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> = emptySet()
    }

    private fun buildAdapter(
        scope: TestScope,
        repo: AudioFileRepository,
        intervalMs: Long = 1_000L,
    ): RecordingHardwareAdapter = RecordingHardwareAdapter(
        emitAction = { /* swallow */ },
        audioFileRepository = repo,
        scope = scope,
        rollingIntervalMs = intervalMs,
    )

    private fun newAudioFile(name: String = "test-rolling.m4a"): File =
        File.createTempFile(name, ".m4a").also { it.deleteOnExit() }

    @Test
    fun `start without session-id does not start the rolling loop`() = runTest {
        val repo = FakeRepo()
        val adapter = buildAdapter(this, repo)
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = null,
        )
        adapter.start()
        advanceTimeBy(5_000)
        assertTrue("No allocateNext when sessionId is null", repo.allocateNextCalls.isEmpty())
        adapter.release()
    }

    @Test
    fun `start with session-id rolls on each interval tick`() = runTest {
        val repo = FakeRepo()
        val adapter = buildAdapter(this, repo, intervalMs = 1_000L)
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "test-session-id",
        )
        adapter.start()
        advanceTimeBy(3_500)  // 3 full intervals plus 500 ms
        assertEquals(
            "Should have rolled three times in 3.5 s at 1 s interval",
            3, repo.allocateNextCalls.size,
        )
        assertTrue(
            "All calls must carry the active session-id",
            repo.allocateNextCalls.all { it == "test-session-id" },
        )
        adapter.release()
    }

    @Test
    fun `pause cancels the rolling loop`() = runTest {
        val repo = FakeRepo()
        val adapter = buildAdapter(this, repo, intervalMs = 1_000L)
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "session-pause",
        )
        adapter.start()
        advanceTimeBy(2_500)  // 2 ticks
        adapter.pause()
        val countAfterPause = repo.allocateNextCalls.size
        advanceTimeBy(5_000)  // 5 s while paused
        assertEquals(
            "Paused timer must not produce new rolls",
            countAfterPause, repo.allocateNextCalls.size,
        )
        adapter.release()
    }

    @Test
    fun `resume re-arms the rolling loop`() = runTest {
        val repo = FakeRepo()
        val adapter = buildAdapter(this, repo, intervalMs = 1_000L)
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "session-resume",
        )
        adapter.start()
        advanceTimeBy(1_500)
        adapter.pause()
        val countAfterPause = repo.allocateNextCalls.size
        advanceTimeBy(3_000)
        adapter.resume()
        advanceTimeBy(2_500)
        assertTrue(
            "Resume must produce at least one new roll (was $countAfterPause, now ${repo.allocateNextCalls.size})",
            repo.allocateNextCalls.size > countAfterPause,
        )
        adapter.release()
    }

    @Test
    fun `release cancels the rolling loop`() = runTest {
        val repo = FakeRepo()
        val adapter = buildAdapter(this, repo, intervalMs = 1_000L)
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "session-release",
        )
        adapter.start()
        advanceTimeBy(1_500)
        val countBeforeRelease = repo.allocateNextCalls.size
        adapter.release()
        advanceTimeBy(5_000)
        assertEquals(
            "Released adapter must not continue rolling",
            countBeforeRelease, repo.allocateNextCalls.size,
        )
    }

    @Test
    fun `adapter without repository does nothing rolling-related`() = runTest {
        // Construct without `audioFileRepository` — the historic
        // single-segment behaviour must remain untouched.
        val adapter = RecordingHardwareAdapter(
            emitAction = { /* swallow */ },
            audioFileRepository = null,
            scope = this,
            rollingIntervalMs = 100L,
        )
        adapter.allocate(
            target = InsertionTarget.INPUT_CONNECTION,
            useBluetooth = false,
            audioFile = newAudioFile(),
            codecParams = null,
            sessionId = "session-no-repo",
        )
        adapter.start()
        advanceTimeBy(5_000)
        // No assertion needed beyond "no crash" — the rolling job was
        // simply never started. Release for symmetry.
        adapter.release()
    }
}
