package net.devemperor.dictate.preferences.versioned

import android.content.SharedPreferences
import android.util.Log

/**
 * Bridges [VersionedPlugin] values into Android [SharedPreferences].
 *
 * Each plugin owns one preference key (== [VersionedPlugin.name]) and the
 * value is stored as a JSON envelope string produced by [VersionedSerializer].
 *
 * ### Self-heal on read
 *
 * [load] re-serializes the just-decoded value and, if the result differs from
 * the on-disk JSON, writes it back. This collapses three classes of drift in
 * a single read:
 *
 * 1. **Migrated data** — older envelopes are persisted at the new version so
 *    later reads skip the migration chain.
 * 2. **Sanitized data** — `sanitize()`-trimmed values (allowlist filters,
 *    dedupe) are persisted so the normalised shape is on disk.
 * 3. **Raw-legacy upgrade** — pre-envelope payloads gain a `{version, value}`
 *    wrapper on first read.
 *
 * The compare-then-write keeps writes proportional to actual change; reads
 * with no drift do not write.
 *
 * ### Backup before self-heal (Quality-Gate W-8 — follow-up)
 *
 * The plan documents an opt-in backup of the original JSON under
 * `<key>__backup` before the first self-heal write, as a forensic reserve
 * for support-driven recovery. **That step is intentionally not implemented
 * in Phase 0** (per plan §0.8 "Implementierung als Follow-up dokumentiert").
 * The minimal-invasive insertion point is the `if (newJson != json)` branch
 * in [load].
 *
 * ### Threading
 *
 * `SharedPreferences` is documented as thread-safe; both [load] and [save]
 * inherit that. They may be called from any thread, including worker pools,
 * **for read access**. Write access is allowed from any thread but the
 * overall preference change should be coordinated by a domain owner (see
 * `LanguageResolver` for the input-languages envelope) to avoid
 * stale-derived-state bugs.
 */
object VersionedPrefs {
    private const val TAG = "VersionedPrefs"

    /**
     * Read the current value of [plugin] from [prefs], migrating and
     * self-healing if needed. Returns [VersionedPlugin.defaultValue] on any
     * unrecoverable error (with a `Log.w`).
     */
    fun <T> load(prefs: SharedPreferences, plugin: VersionedPlugin<T>): T {
        val json = prefs.getString(plugin.name, null) ?: return plugin.defaultValue
        val serializer = VersionedSerializer(plugin)
        return try {
            val value = serializer.deserialize(json)
            // Self-heal: persist migrated/sanitized form if it differs.
            val newJson = serializer.serialize(value)
            if (newJson != json) {
                save(prefs, plugin, value)
            }
            value
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load '${plugin.name}', using default", e)
            plugin.defaultValue
        }
    }

    /**
     * Persist [value] for [plugin] under its key.
     *
     * Returns `true` on success, `false` if serialization or the
     * `SharedPreferences.Editor.apply` path threw. The async `apply()` itself
     * does not surface write failures synchronously; this return only
     * captures pre-write errors.
     */
    fun <T> save(prefs: SharedPreferences, plugin: VersionedPlugin<T>, value: T): Boolean {
        val serializer = VersionedSerializer(plugin)
        return try {
            val json = serializer.serialize(value)
            prefs.edit().putString(plugin.name, json).apply()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save '${plugin.name}'", e)
            false
        }
    }
}
