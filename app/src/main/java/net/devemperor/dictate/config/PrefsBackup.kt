package net.devemperor.dictate.config

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The pre-migration SharedPreferences snapshot (spec §8.4) — the documented rollback path for the
 * hard, coexistence-flag-free config-entity migration (F22/D4.7).
 *
 * Before any entity is written, [write] dumps **all** prefs verbatim to
 * `filesDir/backups/prefs-backup-v11-<epochMillis>.json` as a flat `{ "<key>": <value> }` object
 * over `sp.all` (Boolean/Int/Long/Float/String/Set<String>). It is a readable snapshot, not an
 * auto-restore — recovery is manual.
 *
 * ## Write-once idempotency
 * The migration is retried on abort (Done flag stays low), so [write] must not accumulate one
 * backup per attempt. If any `prefs-backup-v11-*.json` already exists it is treated as the complete
 * snapshot and left untouched — the first attempt's dump is authoritative.
 *
 * > The dump mirrors the prefs 1:1, so it may still contain plaintext keys if it runs before Block
 * > B2 moved them. It lives in app-private `filesDir` (never exported/shared) and may be deleted
 * > after the migration is verified (§8.4, §14 Gap 4).
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §8.4
 */
object PrefsBackup {

    internal const val BACKUP_DIR = "backups"
    private const val PREFIX = "prefs-backup-v11-"
    private const val SUFFIX = ".json"

    /**
     * Writes the snapshot once. Returns the backup file (the freshly written one, or a pre-existing
     * one from an earlier attempt). Never throws on a missing dir — it is created.
     */
    fun write(sp: SharedPreferences, filesDir: File, now: Long = System.currentTimeMillis()): File {
        val dir = File(filesDir, BACKUP_DIR)
        dir.mkdirs()

        existingBackup(dir)?.let { return it }

        val json = JSONObject()
        for ((key, value) in sp.all) {
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is String -> json.put(key, value)
                is Set<*> -> json.put(key, JSONArray(value.map { it?.toString() }))
                else -> json.put(key, value.toString())
            }
        }

        val out = File(dir, "$PREFIX$now$SUFFIX")
        out.writeText(json.toString(2), Charsets.UTF_8)
        return out
    }

    /** The single pre-existing backup file, or null. Used for the write-once guard + tests. */
    fun existingBackup(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.minByOrNull { it.name }
}
