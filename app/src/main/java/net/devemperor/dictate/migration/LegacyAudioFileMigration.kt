package net.devemperor.dictate.migration

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionStatus
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import java.io.File

/**
 * One-shot migration for the pre-refactor audio file layout
 * (Spec 1 §4.11.6.2, KG-AFF-2).
 *
 * **Background.** Before Block 4 the IME wrote every recording to a
 * single fixed path `cacheDir/audio.m4a` (see the historical
 * `DictateInputMethodService.startRecording`). Each new recording
 * overwrote the previous one — only the most recent file was usable.
 * DB rows produced before the refactor still reference that one path,
 * yet at most one of them can be replayed; the rest are ghost rows.
 *
 * After the refactor (this block), [CacheDirAudioFileFactory] writes
 * to `cacheDir/audio/rec_{ts}_{uuid8}.m4a`. The new orphan-cleanup
 * pass scopes to that sub-directory and therefore does **not** see the
 * legacy file in the cache root — it would otherwise survive on disk
 * indefinitely, and the referencing ghost rows would linger as
 * "pending" entries in the history forever (the recovery code only
 * promotes them to FAILED once the file goes away).
 *
 * **What this class does once per install/update:**
 *
 *  1. Deletes the legacy `cacheDir/audio.m4a` (best-effort).
 *  2. Promotes every DB session that still references the legacy path
 *     to `FAILED` with a specific reason string, **but only if the
 *     session is in a recoverable status** (`RECORDING`, `RECORDED`,
 *     `TRANSCRIBING`). The `WHERE status NOT IN (FAILED, CANCELLED,
 *     COMPLETED)` filter in
 *     [net.devemperor.dictate.database.dao.SessionDao.markLegacyAudioSessionsFailed]
 *     preserves the original `last_error_message` on already-terminal
 *     rows — historic error context survives a pref-wipe-and-rerun.
 *  3. Flips a SharedPreferences flag so subsequent boots no-op.
 *
 * **Idempotence layers (Phase-B S-7):**
 *
 *  - SharedPreferences flag [FLAG_PREF] — primary gate.
 *  - DAO `WHERE status NOT IN (...)` filter — secondary safety net if
 *    the flag is lost (App-Data clear, downgrade-upgrade cycle).
 *
 * **Threading.** Runs synchronously on the Main thread from
 * `DictatePipelineService.onCreate`. Three operations:
 *
 *  - `getDefaultSharedPreferences().getBoolean(...)` — < 5 ms.
 *  - `File.exists()` + `File.delete()` — < 10 ms.
 *  - One `UPDATE` against an indexed column — < 20 ms for typical
 *    session counts; up to ~100 ms in the >10 k row pathological case.
 *
 * The 5-second FGS start budget (§11.1.4) is therefore safe. If
 * telemetry later flags a hot device, the entire call wraps trivially
 * into a `serviceScope.launch(Dispatchers.IO) { … }`; idempotence
 * stays intact.
 *
 * @see net.devemperor.dictate.database.dao.SessionDao.markLegacyAudioSessionsFailed
 * @see net.devemperor.dictate.core.CacheDirAudioFileFactory
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §4.11.6.2
 */
object LegacyAudioFileMigration {

    private const val TAG = "LegacyAudioMigration"

    /**
     * Idempotence pref key — exposed for test-helper readability
     * (B3-VAL-W1 F-7). Production paths use [Pref.LegacyAudioPurgedV4]
     * via the typed [get] / [put] extensions; tests reach for this
     * constant when they need to wipe / pre-set the flag through
     * [PreferenceManager.getDefaultSharedPreferences].
     */
    internal fun flagPrefKey(): String = Pref.LegacyAudioPurgedV4.key

    /** Historical fixed-name file path (Spec 1 §4.11.6.2). */
    internal const val LEGACY_NAME = "audio.m4a"

    /** Stable reason string written into `last_error_message`. */
    internal const val REASON = "audio_file_path_legacy_purged"

    fun run(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.get(Pref.LegacyAudioPurgedV4)) return

        val legacy = File(context.cacheDir, LEGACY_NAME)
        if (legacy.exists()) {
            runCatching { legacy.delete() }
                .onFailure { Log.w(TAG, "delete of legacy $legacy failed", it) }
        }

        val dao = DictateDatabase.getInstance(context).sessionDao()
        val legacyPath = legacy.absolutePath
        runCatching {
            dao.markLegacyAudioSessionsFailed(
                legacyPath = legacyPath,
                reason = REASON,
                failedStatus = SessionStatus.FAILED.name,
            )
        }.onFailure { Log.w(TAG, "legacy-session FAILED-mark failed", it) }

        prefs.edit().put(Pref.LegacyAudioPurgedV4, true).apply()
    }
}
