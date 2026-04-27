package net.devemperor.dictate.core

import android.content.SharedPreferences
import android.util.Log
import net.devemperor.dictate.preferences.InputLanguagesPlugin
import net.devemperor.dictate.preferences.LanguageLabelResolver
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.persistInputLanguagesAndPos
import net.devemperor.dictate.preferences.versioned.VersionedPrefs

/**
 * Service-/Controller-Layer single-source-of-truth for "the current input
 * language."
 *
 * Resolves the effective language from two sources, transparently:
 *  - **Temporary override** during [PipelineUiState.ReprocessStaging] —
 *    `selectedLanguage` carried inside the state.
 *  - **Permanent preference** via [InputLanguagesPlugin] (curated list) plus
 *    [Pref.InputLanguagePos] (current index in the sorted curated list).
 *
 * The controller does **not** duplicate override state — it reads it from
 * [pipelineUiStateReader.state] every time. Writes are routed to the
 * appropriate target depending on the active state.
 *
 * Threading: main-thread-only (matches [KeyboardUiController],
 * [RecordingStateController]). [VersionedPrefs.load]/[save] is itself
 * thread-safe but the controller's `lastEffective` cache assumes serialized
 * access.
 *
 * Quality-Gates:
 *  - **K-2 + W-4** — depends on [PipelineUiStateReader] interface, not on
 *    the concrete `KeyboardUiController`. Self-registers via
 *    [PipelineUiStateReader.addCallback] in `init { }` and deregisters in
 *    [dispose] to avoid leaking across view re-creates.
 *  - **K-3** — pos resync after every save is centralised in
 *    [persistCuratedAndPos] so the three writers (writePermanent,
 *    setCuratedLanguages, legacy migration) share one implementation.
 *  - **W-11** — [setLanguage] always calls [notifyIfChanged] after the
 *    if/else, idempotent through the `lastEffective` guard. Robust against
 *    callback-path failures inside `updateReprocessLanguage`.
 */
class LanguageController(
    private val prefs: SharedPreferences,
    private val pipelineUiStateReader: PipelineUiStateReader
) : PipelineUiCallback {

    private companion object {
        private const val TAG = "LanguageController"
    }

    /** Notified when the **derived** effective language changes. */
    interface Callback {
        fun onEffectiveLanguageChanged(oldCode: String, newCode: String)
    }

    private var callback: Callback? = null

    /**
     * Cached last-known effective language. Updated lazily by
     * [notifyIfChanged] after every state-change or write. Used as a guard
     * to make the callback idempotent (no spurious fires when nothing
     * actually changed).
     */
    private var lastEffective: String = computeEffective()

    init {
        // Quality-Gate K-2: direct self-registration on the reader. The
        // reader's CopyOnWriteArrayList allows additional consumers (the
        // Service's own pipeline callback, for example) to coexist without
        // needing a Composite-Wrapper.
        pipelineUiStateReader.addCallback(this)
    }

    /** Register the consumer for effective-language changes. */
    fun setCallback(cb: Callback) {
        this.callback = cb
    }

    /**
     * Detach from [PipelineUiStateReader] so the controller can be GC'd
     * when the surrounding view is recreated. Service code calls this
     * before constructing a fresh controller in `onCreateInputView`.
     */
    fun dispose() {
        pipelineUiStateReader.removeCallback(this)
    }

    /** Effective language, considering temporary override + permanent pref. */
    fun getEffectiveLanguage(): String = computeEffective()

    /**
     * Re-reads the underlying [SharedPreferences] and fires the callback if
     * the effective language changed. Use after an external write to the
     * `input_languages` / `input_language_pos` keys (e.g. from the Settings
     * activity, which holds an Application-singleton controller while the
     * IME owns a per-view instance — both write the same prefs but neither
     * is wired to invalidate the other's `lastEffective` cache).
     *
     * Idempotent through the `lastEffective` guard inside [notifyIfChanged]:
     * if nothing actually changed (e.g. Settings opened and closed without
     * a real edit), no callback fires.
     */
    fun refreshFromPrefs() {
        notifyIfChanged()
    }

    /** The full curated list, label-sorted (per [InputLanguagesPlugin] contract). */
    fun getCuratedLanguages(): List<String> = VersionedPrefs.load(prefs, InputLanguagesPlugin)

    /**
     * Replace the curated list. The plugin's sanitize hook deduplicates,
     * filters, and re-sorts. [preferActive] (when given and still present
     * after sanitize) anchors the pos so the active language survives the
     * write; otherwise the controller tries the previously active code, and
     * falls back to pos 0.
     */
    fun setCuratedLanguages(codes: List<String>, preferActive: String? = null) {
        persistCuratedAndPos(codes, preferActive ?: getEffectiveLanguageOrNull())
        notifyIfChanged()
    }

    /**
     * Set the active language. Routes to the right target based on the
     * pipeline state:
     *  - `ReprocessStaging` → temporary override via the reader.
     *  - any other state → permanent write (and auto-curation if the code
     *    is not yet in the curated list).
     */
    fun setLanguage(code: String) {
        val state = pipelineUiStateReader.state
        if (state is PipelineUiState.ReprocessStaging) {
            pipelineUiStateReader.updateReprocessLanguage(code)
        } else {
            writePermanent(code)
        }
        // Quality-Gate W-11: unconditional notify; idempotent through
        // lastEffective guard. Robust against the reader's
        // updateReprocessLanguage failing silently to fire onPipelineUiStateChanged.
        notifyIfChanged()
    }

    // ── PipelineUiCallback ──

    override fun onPipelineUiStateChanged(
        oldState: PipelineUiState,
        newState: PipelineUiState
    ) {
        notifyIfChanged()
    }

    // ── Internals ──

    private fun computeEffective(): String {
        val state = pipelineUiStateReader.state
        if (state is PipelineUiState.ReprocessStaging) {
            val override = state.selectedLanguage
            if (!override.isNullOrBlank()) return override
        }
        return readPermanent()
    }

    private fun getEffectiveLanguageOrNull(): String? {
        val curated = getCuratedLanguages()
        if (curated.isEmpty()) return null
        val pos = prefs.get(Pref.InputLanguagePos)
        return if (pos in curated.indices) curated[pos] else null
    }

    private fun readPermanent(): String {
        val langs = getCuratedLanguages()
        if (langs.isEmpty()) return "en"
        val pos = prefs.get(Pref.InputLanguagePos).coerceIn(0, langs.size - 1)
        return langs[pos]
    }

    /**
     * Permanent-path write with auto-curation: if [code] is not yet in the
     * curated list it gets appended before the save (sanitize will then
     * sort it into label order).
     *
     * Defensive: an unknown code is logged but not rejected — sanitize will
     * filter it out anyway, and we want a breadcrumb in logcat for callers
     * that pass codes outside the resolver's allowlist (Quality-Gate N3).
     */
    private fun writePermanent(code: String) {
        if (code !in LanguageLabelResolver.allowed()) {
            Log.w(TAG, "writePermanent: unknown language code '$code' (will be filtered by sanitize)")
        }
        val curated = getCuratedLanguages().toMutableList()
        if (code !in curated) curated.add(code)
        persistCuratedAndPos(curated, preferActive = code)
    }

    /**
     * Quality-Gate K-3: persist curated list + resync pos in one place.
     *
     * Delegates to [persistInputLanguagesAndPos] — the single source of truth
     * for the save+pos-resync algorithm, shared with
     * [net.devemperor.dictate.preferences.InputLanguagesLegacyMigration] so
     * both writers stay in lock-step on sanitize behaviour and pos handling.
     */
    private fun persistCuratedAndPos(codes: List<String>, preferActive: String?) {
        persistInputLanguagesAndPos(prefs, codes, preferActive)
    }

    private fun notifyIfChanged() {
        val new = computeEffective()
        if (new != lastEffective) {
            val old = lastEffective
            lastEffective = new
            callback?.onEffectiveLanguageChanged(old, new)
        }
    }
}
