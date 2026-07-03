package net.devemperor.dictate.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric + real Room test for [RecordingRepository.deleteBySessionId]
 * (F-113 delete side, ADR-0007).
 *
 * **Red-first evidence:** run against the pre-fix single-file delete
 * (`session.audioFilePath` only), the multi-segment assertions here FAIL —
 * segments 2..N and the persistent `recordings/{sid}.m4a` copy stay on disk
 * (that was the orphan bug). The fixed implementation sweeps every referenced
 * file, so all `assertFalse(exists)` checks pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingRepositoryDeleteTest {

    private lateinit var context: Context
    private lateinit var db: DictateDatabase
    private lateinit var repo: RecordingRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Deterministic pre-state regardless of Robolectric fork co-location.
        DictateDatabase.resetForTest(context)
        db = DictateDatabase.getInstance(context)
        repo = RecordingRepository(context)
    }

    @After
    fun tearDown() {
        DictateDatabase.resetForTest(context)
    }

    private fun newFile(dir: File, name: String): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeText("audio-bytes")
        }

    @Test
    fun `deletes all segments plus legacy plus persistent copy`() {
        val sid = "sid-multi"
        val cacheDir = context.cacheDir

        // Multi-segment set (ADR-0007) — the read/delete surface.
        val seg1 = newFile(cacheDir, "sess_${sid}_seg1.m4a")
        val seg2 = newFile(cacheDir, "sess_${sid}_seg2.m4a")
        val seg3 = newFile(cacheDir, "sess_${sid}_seg3.m4a")
        // Legacy single-file column (commonly mirrors seg1, here distinct to
        // prove the legacy path is swept independently).
        val legacy = newFile(cacheDir, "legacy_${sid}.m4a")
        // Persistent promoted copy: filesDir/recordings/{sid}.m4a.
        val persistent = newFile(File(context.filesDir, "recordings"), "$sid.m4a")

        db.sessionDao().insert(
            SessionEntity(
                id = sid,
                type = "RECORDING",
                createdAt = 1L,
                targetAppPackage = null,
                language = "en",
                audioFilePath = legacy.absolutePath,
                audioFilePaths = listOf(seg1.absolutePath, seg2.absolutePath, seg3.absolutePath),
                status = SessionStatus.RECORDED.name,
            )
        )

        val ok = repo.deleteBySessionId(sid)

        assertTrue(ok)
        assertFalse("seg1 orphaned", seg1.exists())
        assertFalse("seg2 orphaned", seg2.exists())
        assertFalse("seg3 orphaned", seg3.exists())
        assertFalse("legacy orphaned", legacy.exists())
        assertFalse("persistent copy orphaned", persistent.exists())

        // Both audio columns cleared.
        val row = db.sessionDao().getById(sid)!!
        assertNull(row.audioFilePath)
        assertTrue(row.audioFilePaths.isEmpty())
    }

    @Test
    fun `single-segment session with only a persistent copy is fully cleared`() {
        val sid = "sid-single"
        val persistent = newFile(File(context.filesDir, "recordings"), "$sid.m4a")

        db.sessionDao().insert(
            SessionEntity(
                id = sid,
                type = "RECORDING",
                createdAt = 1L,
                targetAppPackage = null,
                language = "en",
                audioFilePath = persistent.absolutePath,
                audioFilePaths = listOf(persistent.absolutePath),
                status = SessionStatus.COMPLETED.name,
            )
        )

        assertTrue(repo.deleteBySessionId(sid))
        assertFalse(persistent.exists())
    }

    @Test
    fun `unknown session id returns false`() {
        assertFalse(repo.deleteBySessionId("nope"))
    }
}
