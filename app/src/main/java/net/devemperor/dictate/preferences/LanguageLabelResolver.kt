package net.devemperor.dictate.preferences

import android.content.Context
import androidx.annotation.VisibleForTesting
import net.devemperor.dictate.R

/**
 * Single source of truth for ISO-code ↔ display-label translation and
 * the language allowlist.
 *
 * Reads the parallel resource arrays:
 *  - `R.array.dictate_input_languages_values` — ISO codes in resource order
 *  - `R.array.dictate_input_languages` — labels parallel to the codes
 *  - `R.array.dictate_record_different_languages` — short forms used on the
 *    record button when the active language is not "detect"
 *
 * Resource location: package `preferences/` (not `core/`) — Quality-Gate W-4.
 * `InputLanguagesPlugin` (also in `preferences/`) needs the allowlist + label
 * sort and must not pull in a `core/` import; the resolver is a Resource-Adapter,
 * which conceptually belongs next to the persistence code that uses it.
 *
 * Thread-safety: initialized exactly once during [Application.onCreate]
 * (see `DictateApplication`). After init, all reads are pure lookups
 * against immutable arrays/maps. Concurrent reads are safe.
 *
 * Defense-in-Depth (Quality-Gate W-1): every accessor `check`s the init
 * state. Worker threads (e.g. `PipelineOrchestrator` on `dbExecutor`) may
 * read curated languages via `VersionedPrefs.load(prefs, InputLanguagesPlugin)`
 * before the IME's `onCreateInputView` runs — `Application.onCreate()` runs
 * before any other component, so the resolver is always ready by the time
 * `sanitize()` calls into it.
 */
object LanguageLabelResolver {
    private lateinit var codes: Array<String>
    private lateinit var labels: Array<String>
    private lateinit var recordLabels: Array<String>
    private lateinit var allowedSet: Set<String>
    private lateinit var labelByCode: Map<String, String>
    private lateinit var recordLabelByCode: Map<String, String>
    private lateinit var codeToIndex: Map<String, Int>

    /**
     * Initialize from Android resources. Idempotent: a second call with the
     * same context-backed resources is a no-op.
     */
    fun initialize(context: Context) {
        if (::codes.isInitialized) return
        val res = context.resources
        val resCodes = res.getStringArray(R.array.dictate_input_languages_values)
        val resLabels = res.getStringArray(R.array.dictate_input_languages)
        val resRecordLabels = res.getStringArray(R.array.dictate_record_different_languages)
        check(resCodes.size == resLabels.size) {
            "dictate_input_languages_values (${resCodes.size}) and dictate_input_languages " +
                "(${resLabels.size}) must have same size"
        }
        check(resCodes.size == resRecordLabels.size) {
            "dictate_input_languages_values (${resCodes.size}) and " +
                "dictate_record_different_languages (${resRecordLabels.size}) must have same size"
        }
        applyArrays(resCodes, resLabels, resRecordLabels)
    }

    /**
     * All known ISO codes in resource order. Use [sortByLabel] when you need
     * label-alphabetical order instead.
     */
    fun allCodes(): List<String> {
        check(::codes.isInitialized) {
            "LanguageLabelResolver.initialize(context) must be called first"
        }
        return codes.toList()
    }

    /** Allowlist of ISO codes for fast `contains` checks. */
    fun allowed(): Set<String> {
        check(::codes.isInitialized) {
            "LanguageLabelResolver.initialize(context) must be called first"
        }
        return allowedSet
    }

    /** Display label for [code], or [code] itself when unknown (graceful fallback). */
    fun resolveLabel(code: String): String {
        check(::codes.isInitialized) {
            "LanguageLabelResolver.initialize(context) must be called first"
        }
        return labelByCode[code] ?: code
    }

    /**
     * Short label used on the record button (typically the ISO code or a
     * locale-specific abbreviation). Falls back to [code] when unknown.
     */
    fun recordLabelFor(code: String): String {
        check(::codes.isInitialized) {
            "LanguageLabelResolver.initialize(context) must be called first"
        }
        return recordLabelByCode[code] ?: code
    }

    /**
     * Compact 2-letter pill label derived directly from the ISO code. This
     * is the abbreviation shown on the always-visible language chip in the
     * prompts bar — the long label is reserved for the popup menu.
     *
     * Rules:
     *  - `"detect"` → `"Auto"` (special case; `take(2)` would collide with
     *    `"DE"` for German).
     *  - Hyphenated codes (`"zh-CN"`, `"yue-HK"`) drop the region suffix and
     *    use the primary subtag uppercased: `"zh-CN"` → `"ZH"`,
     *    `"yue-HK"` → `"YU"`. Region info is visible in the long popup label.
     *  - Standard codes return their first two characters in uppercase:
     *    `"en"` → `"EN"`, `"de"` → `"DE"`.
     *
     * No `init`-check: the function is purely string-arithmetic and does not
     * touch the lateinit fields, so it can be called before
     * [initialize] (e.g. in unit tests that don't bother seeding the
     * resource arrays).
     */
    fun resolveShortLabel(code: String): String {
        if (code == "detect") return "Auto"
        return code.substringBefore('-').take(2).uppercase()
    }

    /**
     * Resource-array index of [code], or `-1` when unknown. Useful when
     * mapping legacy positional state (e.g. `Pref.InputLanguagePos`) onto a
     * specific code.
     */
    fun indexOfCode(code: String): Int {
        check(::codes.isInitialized) {
            "LanguageLabelResolver.initialize(context) must be called first"
        }
        return codeToIndex[code] ?: -1
    }

    /**
     * Returns [input] sorted by display label, case-insensitively. Stable for
     * codes with identical labels (rare but possible across locales).
     */
    fun sortByLabel(input: Collection<String>): List<String> {
        check(::codes.isInitialized) {
            "LanguageLabelResolver.initialize(context) must be called first"
        }
        return input.sortedBy { resolveLabel(it).lowercase() }
    }

    /**
     * All ISO codes that are NOT in [curated], label-sorted. Used by the
     * grouped PopupMenu (Phase 2) for the lower block.
     */
    fun othersThan(curated: Collection<String>): List<String> {
        check(::codes.isInitialized) {
            "LanguageLabelResolver.initialize(context) must be called first"
        }
        val curatedSet = curated.toSet()
        return sortByLabel(codes.filter { it !in curatedSet })
    }

    /**
     * Test seam — bypasses the [Context] dependency by accepting raw arrays.
     * Resets internal state so each test starts deterministically.
     *
     * The optional [recordLabels] parameter defaults to copies of [labels]
     * because most logic-tests don't care about the record-button form.
     */
    @VisibleForTesting
    fun initializeForTest(
        codes: Array<String>,
        labels: Array<String>,
        recordLabels: Array<String> = labels.copyOf()
    ) {
        require(codes.size == labels.size) {
            "codes (${codes.size}) and labels (${labels.size}) must have same size"
        }
        require(codes.size == recordLabels.size) {
            "codes (${codes.size}) and recordLabels (${recordLabels.size}) must have same size"
        }
        applyArrays(codes, labels, recordLabels)
    }

    /**
     * Test-only helper: returns whether the resolver has been initialized.
     * Lets a test that wants to assert "throws when un-init" guard itself
     * against earlier tests in the same JVM that already populated the
     * lateinit fields (Kotlin's `lateinit` cannot be un-set).
     */
    @VisibleForTesting
    fun isInitializedForTest(): Boolean = ::codes.isInitialized

    private fun applyArrays(
        newCodes: Array<String>,
        newLabels: Array<String>,
        newRecordLabels: Array<String>
    ) {
        codes = newCodes
        labels = newLabels
        recordLabels = newRecordLabels
        allowedSet = newCodes.toSet()
        labelByCode = newCodes.zip(newLabels).toMap()
        recordLabelByCode = newCodes.zip(newRecordLabels).toMap()
        codeToIndex = newCodes.mapIndexed { i, c -> c to i }.toMap()
    }
}
