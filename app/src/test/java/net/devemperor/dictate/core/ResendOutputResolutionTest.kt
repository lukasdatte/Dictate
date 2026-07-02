package net.devemperor.dictate.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the short-press resend "inserts nothing" bug.
 *
 * # Symptom
 *
 * Tapping the "insert last recording" (RESEND) button inserted nothing for
 * every plain dictation. `onResendClicked` fed
 * [ResendStatusDispatcher.decide] the raw denormalized
 * `sessions.final_output_text` column — but the transcription-only pipeline
 * never writes that column reliably (it is only a best-effort side-effect of
 * the IME insertion-audit callback, which is skipped once the tracker's
 * `currentSessionId` is cleared at end-of-run). So the column was empty, the
 * dispatcher returned NoOp/Resume, and no text reached the editor. The actual
 * transcription was sitting in the `transcriptions` table the whole time.
 *
 * # Guard
 *
 * The fix resolves the resend text through
 * [SessionManager.getFinalOutput] (step → transcription → denormalized
 * fallback). This test drives that resolution against a real in-memory Room
 * DB reproducing the on-device data shape — a COMPLETED keyboard session with
 * an empty `final_output_text` column but a real transcription row — and
 * asserts the resolved output feeds the dispatcher an [ResendAction.Insert]
 * (whereas the raw column would have produced [ResendAction.NoOp]).
 *
 * @see ResendStatusDispatcher
 * @see SessionManager.getFinalOutput
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResendOutputResolutionTest {

    private lateinit var db: DictateDatabase
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DictateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionManager = SessionManager(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertSession(id: String, status: SessionStatus, finalOutput: String?) {
        db.sessionDao().insert(
            SessionEntity(
                id = id,
                type = "RECORDING",
                createdAt = System.currentTimeMillis(),
                targetAppPackage = null,
                language = null,
                audioFilePath = null,
                status = status.name,
                origin = SessionOrigin.KEYBOARD.name,
                finalOutputText = finalOutput,
            )
        )
    }

    @Test
    fun `resolved output is the transcription when final_output_text column is empty`() {
        // On-device shape: COMPLETED keyboard session, empty denormalized
        // column, real transcription persisted in the transcriptions table.
        val sid = "completed-empty-column"
        insertSession(sid, SessionStatus.COMPLETED, finalOutput = "")
        sessionManager.addTranscriptionVersion(
            sid, "Dies ist ein Test.", "whisper-1", "OPENAI", durationMs = 10
        )

        val resolved = sessionManager.getFinalOutput(sid)
        assertEquals("Dies ist ein Test.", resolved)

        // The old code read the raw column ("") → dispatcher returns NoOp.
        assertTrue(
            "raw empty column must yield NoOp — proving the bug is real",
            ResendStatusDispatcher.decide(SessionStatus.COMPLETED, "", sid)
                === ResendAction.NoOp
        )

        // The fixed code feeds the resolved output → dispatcher returns Insert.
        val action = ResendStatusDispatcher.decide(SessionStatus.COMPLETED, resolved, sid)
        assertTrue(action is ResendAction.Insert)
        action as ResendAction.Insert
        assertEquals("Dies ist ein Test.", action.output)
        assertEquals(sid, action.sessionId)
    }

    @Test
    fun `RECORDED session with a transcription resolves to Insert, not Resume`() {
        // On-device shape: latest keyboard session was RECORDED (transcription
        // done, pipeline not marked COMPLETED) with an empty column. Text-first
        // must give the user their transcription back rather than re-running.
        val sid = "recorded-with-transcription"
        insertSession(sid, SessionStatus.RECORDED, finalOutput = null)
        sessionManager.addTranscriptionVersion(
            sid, "Eine Sache müsstest du noch fixen.", "whisper-1", "OPENAI", durationMs = 10
        )

        val resolved = sessionManager.getFinalOutput(sid)
        assertEquals("Eine Sache müsstest du noch fixen.", resolved)

        // Old path: null column + RECORDED → Resume (re-run, no insert).
        assertTrue(
            ResendStatusDispatcher.decide(SessionStatus.RECORDED, null, sid)
                is ResendAction.Resume
        )

        // Fixed path: resolved transcription → Insert.
        assertTrue(
            ResendStatusDispatcher.decide(SessionStatus.RECORDED, resolved, sid)
                is ResendAction.Insert
        )
    }
}
