package net.devemperor.dictate.preferences

import android.content.SharedPreferences
import net.devemperor.dictate.preferences.versioned.MigrationFn
import net.devemperor.dictate.preferences.versioned.OnMissingMigration
import net.devemperor.dictate.preferences.versioned.StringListCodec
import net.devemperor.dictate.preferences.versioned.VersionedPlugin
import net.devemperor.dictate.preferences.versioned.VersionedPrefs

/**
 * The single curated language list (kuratiert), persisted as a versioned
 * envelope under the SharedPreferences key `net.devemperor.dictate.input_languages`.
 *
 * Versioning starts at **v1**: the legacy `Set<String>` payload under the same
 * key is bootstrapped into v1 by [InputLanguagesLegacyMigration] before
 * [VersionedPrefs] ever attempts to read.
 *
 * ### Registration
 *
 * Plugins do NOT self-register. Callers must explicitly call
 * `VersionedPluginRegistry.register(InputLanguagesPlugin)` — typically in
 * `Application.onCreate()`. This keeps the wiring greppable and avoids
 * dependence on Kotlin object class-load timing for app correctness
 * (Quality-Gate W-1 follow-up; was previously an `init { register(this) }`
 * block that required a class-load touch from the Application).
 *
 * ### Sanitize contract
 *
 * The persisted list is **always** label-sortiert and free of duplicates and
 * unknown codes. Empty input collapses to [defaultValue] (which is itself
 * sorted via the same path). This invariant lets every consumer (Cycle
 * button, PopupMenu, JobRequest) trust positional access without re-sorting.
 *
 * ### Error strategy
 *
 * `RESET_TO_DEFAULT` — Sprach-Daten sind nicht kritisch; ein zerstörter
 * Envelope sollte den User nicht in einen unbenutzbaren Zustand werfen.
 * Für zukünftige kritische Prefs (z.B. API-Keys in einem Envelope-Format)
 * wäre `THROW` die richtige Wahl (Design-Prinzip 5).
 */
object InputLanguagesPlugin : VersionedPlugin<List<String>>(
    name = "net.devemperor.dictate.input_languages",
    currentVersion = 1,
    defaultValue = listOf("detect", "en"),
    codec = StringListCodec,
    onMissingMigration = OnMissingMigration.RESET_TO_DEFAULT
) {
    override val migrations: Map<Int, MigrationFn> = emptyMap()

    /**
     * Strip duplicates and unknown ISO codes, then sort by display label.
     * Empty input falls back to [defaultValue] (also sorted) so the contract
     * holds for every persisted shape — there is no default-path loophole.
     */
    override fun sanitize(value: List<String>): List<String> {
        val allowed = LanguageLabelResolver.allowed()
        val clean = value.distinct().filter { it in allowed }
        return LanguageLabelResolver.sortByLabel(clean.ifEmpty { defaultValue })
    }
}

/**
 * Single source of truth for the InputLanguages save+pos-resync algorithm.
 *
 * Saves [codes] through [InputLanguagesPlugin] (so [InputLanguagesPlugin.sanitize]
 * runs: dedupe + allowlist filter + label sort), then re-reads the persisted
 * list and writes [Pref.InputLanguagePos] to the index of [preferActive] in
 * that post-sanitize shape — or `0` if [preferActive] is null or fell out of
 * the curated set.
 *
 * Used by:
 *  - [LanguageResolver.setLanguage] / [LanguageResolver.setCuratedLanguages]
 *    (Quality-Gate K-3 — keep pos in lock-step with the curated list
 *    across both permanent-write paths).
 *  - [InputLanguagesLegacyMigration.migrateFromLegacyStringSet] for the
 *    `Set<String>` → versioned-envelope bootstrap (re-anchor old pos to the
 *    code that was active before migration).
 *
 * Implementation lives next to [InputLanguagesPlugin] so the algorithm and
 * the plugin's sanitize contract stay in the same compilation unit; any
 * future change to one forces a re-read of the other.
 */
internal fun persistInputLanguagesAndPos(
    prefs: SharedPreferences,
    codes: List<String>,
    preferActive: String?
) {
    VersionedPrefs.save(prefs, InputLanguagesPlugin, codes)
    val persisted = VersionedPrefs.load(prefs, InputLanguagesPlugin)
    val newPos = preferActive
        ?.let { code -> persisted.indexOf(code).takeIf { idx -> idx >= 0 } }
        ?: 0
    prefs.edit().put(Pref.InputLanguagePos, newPos).apply()
}
