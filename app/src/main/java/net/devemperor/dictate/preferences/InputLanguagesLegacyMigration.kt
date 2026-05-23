package net.devemperor.dictate.preferences

import android.content.SharedPreferences
import android.util.Log
import net.devemperor.dictate.preferences.versioned.VersionedPrefs

/**
 * Bootstrap helper that converts the legacy `Set<String>` value at
 * `net.devemperor.dictate.input_languages` into the v1 versioned envelope
 * managed by [InputLanguagesPlugin].
 *
 * This is **not** a normal `MigrationFn` — those work on already-decoded
 * envelope payloads. The legacy value here lives at the same key but as a
 * different SharedPreferences type (`StringSet`, not `String`). The XML
 * layer of SharedPreferences uses different elements (`<set>` vs `<string>`),
 * so the type-cast must happen before [VersionedPrefs.load] is even invoked.
 *
 * ### Idempotence
 *
 * Safe to call on every `Application.onCreate()`. After the first run the
 * key holds a `String` value (the envelope JSON). On a second invocation
 * `getString` succeeds, the helper returns early, and no work is done.
 *
 * ### Pos preservation (best-effort)
 *
 * The legacy code stored an `Int` index (`Pref.InputLanguagePos`) into the
 * `StringSet`'s **iteration order**. `Set` iteration order is not guaranteed
 * stable across JVM releases, but on a given device it is deterministic for
 * a given hash configuration — which is the only signal available. We
 * resolve it through `legacySet.toList()[oldPos]`, then look that code up in
 * the new label-sorted list. If the lookup fails (rare, e.g. invalid index),
 * the new pos defaults to 0.
 */
object InputLanguagesLegacyMigration {
    private const val TAG = "InputLanguagesLegacy"
    private const val KEY = "net.devemperor.dictate.input_languages"

    /**
     * Run the one-shot StringSet → versioned-envelope migration on [prefs].
     * Idempotent and safe on a fresh install (no key, no work).
     */
    fun migrateFromLegacyStringSet(prefs: SharedPreferences) {
        // Step 1 — already migrated? `getString` succeeds when the value is a String.
        val alreadyMigrated = try {
            prefs.getString(KEY, null) != null
        } catch (_: ClassCastException) {
            false
        }
        if (alreadyMigrated) return

        // Step 2 — try to read the legacy StringSet. ClassCastException means the
        // key holds something other than a Set; fresh install just returns null.
        val legacySet: Set<String>? = try {
            prefs.getStringSet(KEY, null)
        } catch (_: ClassCastException) {
            null
        }
        if (legacySet == null) return

        // Step 3 — locate the code that the old pos pointed at. Set iteration
        // order is hash-based but deterministic per-process. This is the only
        // available signal for "which language was active before migration".
        val oldPos = prefs.get(Pref.InputLanguagePos)
        val legacyList = legacySet.toList()
        val oldActive: String? = legacyList.getOrNull(oldPos)

        // Step 4 — drop the legacy entry, then delegate save+pos-resync to
        // the shared helper [persistInputLanguagesAndPos] so this migration
        // and the LanguageResolver permanent-write paths stay in lock-step
        // on sanitize behaviour and pos handling (Quality-Gate W3 / DRY).
        prefs.edit().remove(KEY).apply()
        persistInputLanguagesAndPos(prefs, legacySet.toList(), oldActive)

        val newPos = prefs.get(Pref.InputLanguagePos)
        Log.i(
            TAG,
            "Migrated ${legacySet.size} languages from StringSet to versioned envelope; " +
                "pos $oldPos ($oldActive) -> $newPos"
        )
    }
}
