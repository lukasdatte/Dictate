package net.devemperor.dictate.preferences

import android.content.SharedPreferences
import android.util.Log
import net.devemperor.dictate.preferences.versioned.VersionedPrefs

/**
 * SharedPreferences-backed single source of truth for the **permanent**
 * input language (curated list + active position).
 *
 * ## Why this exists (D-13 / Epic §4 Block C1, R-3)
 *
 * This object holds the pure, framework-light language-resolution and
 * permanent-write logic that used to live inside the deleted legacy
 * `core` language-controller (its effective-resolution + permanent-write
 * + curated-list accessors). That controller additionally resolved a
 * transient `PipelineUiState.ReprocessStaging` override and pushed
 * callbacks into the IME view; that responsibility moved to the
 * `LanguageModule` state axis (`LanguageState.override`), leaving this
 * object as the **pref-only** resolver.
 *
 * ## Boot-before-bind ordering (R-3, the load-bearing risk)
 *
 * `DictateApplication` is a process-global with a lifetime that is **not**
 * bound to the IME service; some consumers (the Settings UI's
 * `PreferencesFragment`, an IME render-tick before `pipelineBinder` is
 * non-null) run before — or entirely without — a bound
 * `DictateOrchestrator`. The legacy `DictateApplication`-singleton
 * language-controller papered over this with a no-op
 * `PipelineUiStateReader`.
 *
 * The replacement discipline (mirrors the parent plan's
 * `pipelineBinder != null` guard):
 *
 *  - **Unbound path** (no orchestrator, or before bind): resolve / write
 *    the permanent language **directly through this object**, which reads
 *    and writes the same `SharedPreferences` keys
 *    (`input_languages` versioned envelope + `Pref.InputLanguagePos`).
 *    There is no stale in-memory cache: every call re-reads the prefs, so
 *    a write from any process actor is immediately visible to the next
 *    read from any other actor. This eliminates the legacy
 *    per-instance `lastEffective` cross-instance staleness the old
 *    `inputLanguagesListener` bridge existed to patch.
 *  - **Bound path** (orchestrator available): the IME resolves the
 *    effective code via [effectiveLanguage] **before** dispatch
 *    (Pre-Dispatch-Resolution, Spec 1 §4.11) and dispatches
 *    `Action.LanguageAction.RefreshFromPref(code)`; the reducer writes it
 *    into `LanguageState.effective` so the RenderBackend (F-15) and the
 *    transcription-config snapshot read a live value.
 *
 * Because the permanent SoT is the prefs file (not an object held by
 * either lifetime), the boot-before-bind ordering is **safe by
 * construction**: a pre-bind read returns the persisted value, not a
 * `"system"` default or an NPE.
 *
 * ## Threading
 *
 * [VersionedPrefs.load] / [VersionedPrefs.save] are themselves
 * thread-safe. This object holds no mutable state, so all functions are
 * safe to call from any thread (the IME main thread, the Settings UI
 * thread, an SP-listener thread).
 *
 * @see net.devemperor.dictate.state.modules.LanguageModule
 * @see persistInputLanguagesAndPos
 * @see docs/plans/2026-05-15 - dictate-cutover-completion/dictate-cutover-completion.md §4 Block C1, §6 R-3
 */
object LanguageResolver {

    private const val TAG = "LanguageResolver"

    /**
     * The full curated list, label-sorted and free of duplicates / unknown
     * codes (the [InputLanguagesPlugin.sanitize] contract).
     */
    fun curatedLanguages(prefs: SharedPreferences): List<String> =
        VersionedPrefs.load(prefs, InputLanguagesPlugin)

    /**
     * The permanent effective language: the curated entry at
     * `Pref.InputLanguagePos`, position-clamped to the curated range.
     * Falls back to `"en"` when the curated list is empty (defensive — the
     * plugin's `sanitize` collapses an empty list to its default, so this
     * branch is reachable only with a corrupt envelope).
     *
     * This is the exact permanent-resolution algorithm the legacy
     * language-controller used; the transient ReprocessStaging override is
     * no longer resolved here — it is the `LanguageState.override` axis.
     */
    fun effectiveLanguage(prefs: SharedPreferences): String {
        val langs = curatedLanguages(prefs)
        if (langs.isEmpty()) return "en"
        val pos = prefs.get(Pref.InputLanguagePos).coerceIn(0, langs.size - 1)
        return langs[pos]
    }

    /**
     * Permanent-path write with auto-curation: if [code] is not yet in the
     * curated list it is appended before the save (sanitize then sorts it
     * into label order). The active position is re-anchored to [code].
     *
     * Defensive: an unknown code is logged but not rejected — sanitize
     * filters it anyway, and the logcat breadcrumb mirrors the legacy
     * permanent-write Quality-Gate N3 behaviour.
     */
    fun setLanguage(prefs: SharedPreferences, code: String) {
        if (code !in LanguageLabelResolver.allowed()) {
            Log.w(TAG, "setLanguage: unknown language code '$code' (will be filtered by sanitize)")
        }
        val curated = curatedLanguages(prefs).toMutableList()
        if (code !in curated) curated.add(code)
        persistInputLanguagesAndPos(prefs, curated, preferActive = code)
    }

    /**
     * Replace the curated list. [InputLanguagesPlugin.sanitize] dedupes,
     * filters, and re-sorts. [preferActive] (when given and still present
     * post-sanitize) anchors the position so the active language survives
     * the write; otherwise the previously-active code is tried, falling
     * back to position 0.
     *
     * Mirrors the legacy controller's curated-list write — the pos-resync
     * algorithm itself lives once in [persistInputLanguagesAndPos]
     * (Quality-Gate K-3).
     */
    fun setCuratedLanguages(
        prefs: SharedPreferences,
        codes: List<String>,
        preferActive: String? = null,
    ) {
        persistInputLanguagesAndPos(
            prefs,
            codes,
            preferActive ?: activeCodeOrNull(prefs),
        )
    }

    /**
     * The currently-active curated code, or `null` when the curated list
     * is empty or the position is out of range. Used as the default
     * pos-anchor for [setCuratedLanguages] (matches the legacy
     * `getEffectiveLanguageOrNull`).
     */
    private fun activeCodeOrNull(prefs: SharedPreferences): String? {
        val curated = curatedLanguages(prefs)
        if (curated.isEmpty()) return null
        val pos = prefs.get(Pref.InputLanguagePos)
        return if (pos in curated.indices) curated[pos] else null
    }
}
