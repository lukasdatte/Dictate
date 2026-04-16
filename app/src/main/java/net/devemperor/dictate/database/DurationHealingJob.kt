package net.devemperor.dictate.database

import android.util.Log
import net.devemperor.dictate.ai.AIProviderException
import net.devemperor.dictate.core.RecordingRepository
import net.devemperor.dictate.database.dao.SessionDao
import net.devemperor.dictate.database.entity.SessionStatus
import java.io.File

/**
 * One-time healing job that runs on every app launch (from
 * [net.devemperor.dictate.DictateApplication.onCreate]).
 *
 * Finds legacy sessions with `audio_duration_seconds = 0` but a valid
 * `audio_file_path`, extracts the real duration via
 * [RecordingRepository.extractDurationSeconds] (the single source of duration
 * extraction — see Open Decision SA-1 resolved in Chunk 1), and updates the
 * DB.
 *
 * Sessions whose audio file no longer exists are promoted to
 * [SessionStatus.FAILED] with error type
 * [AIProviderException.ErrorType.UNKNOWN].
 *
 * The job is idempotent — once a row has a non-zero duration, it is ignored.
 *
 * NOTE (SA-2 / CA-2 / SEC-0-2): This MUST NOT be called from
 * [DictateDatabase]'s onOpen callback. onOpen runs while the database
 * singleton is still being constructed, so calling getInstance() again
 * re-enters the synchronized block and creates a second Room instance on
 * the same file. Always invoke from [net.devemperor.dictate.DictateApplication]
 * on a background thread AFTER getInstance() has returned.
 */
object DurationHealingJob {

    private const val TAG = "DurationHealingJob"

    fun heal(dao: SessionDao, recordingRepository: RecordingRepository) {
        val needsHealing = dao.findWithMissingDuration()
        if (needsHealing.isEmpty()) return

        Log.i(TAG, "Healing ${needsHealing.size} session(s) with missing duration")

        for (session in needsHealing) {
            val path = session.audioFilePath ?: continue
            val file = File(path)
            if (!file.exists()) {
                // Audio file is gone (e.g. was in cache/ which Android cleared,
                // or manual delete). Promote to FAILED with a specific error
                // type so the UI can render a meaningful message.
                //
                // Status transition: RECORDED -> FAILED
                // Error context: lastErrorType = UNKNOWN, lastErrorMessage
                // explains the cause. The user sees "Fehlgeschlagen" in the
                // history list and, in the detail view, "Audio file missing".
                // The reprocess buttons are hidden via
                // RecordingRepository.LoadResult.FileMissing.
                dao.updateStatus(session.id, SessionStatus.FAILED.name)
                dao.updateError(
                    session.id,
                    AIProviderException.ErrorType.UNKNOWN.name,
                    "Audio file not found during healing"
                )
                continue
            }

            val durationSec = recordingRepository.extractDurationSeconds(file)
            if (durationSec > 0) {
                dao.updateAudioDuration(session.id, durationSec)
            }
        }
    }
}
