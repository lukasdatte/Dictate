package net.devemperor.dictate.config

import android.content.SharedPreferences
import net.devemperor.dictate.config.ConfigWireMapping.toAmbiguityMode
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.preferences.AmbiguityMode
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.shared.config.ModelRefEntity
import net.devemperor.dictate.shared.config.ProfileEntity
import net.devemperor.dictate.shared.config.PromptSelectionMode
import net.devemperor.dictate.shared.config.ProviderType

/**
 * Read/write access to the ACTIVE profile (pointer: [Pref.ActiveProfileId], §13 D4) for the
 * non-settings consumers that used to read the migrated prefs directly — the IME's ambiguity mode
 * and the prompt-selection config. Every write goes through [ConfigRepository] (recompute-on-write).
 *
 * DB reads are plain Room queries; `DictateDatabase` allows main-thread queries (existing IME
 * pattern), and each accessor is a two-row lookup.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §10, §13 D4
 */
object ActiveProfile {

    /** The active profile DTO, or null when none is set/found (pre-migration or deleted profile). */
    @JvmStatic
    fun get(sp: SharedPreferences, db: DictateDatabase): ProfileEntity? {
        val id = sp.get(Pref.ActiveProfileId)
        if (id.isEmpty()) return null
        val row = db.profileDao().byId(id) ?: return null
        return ConfigEntityMapper.toDto(row, db.profileDao().promptsOf(id))
    }

    /**
     * The active profile's ambiguity mode — the entity-model successor of
     * `AmbiguityMode.fromPersistKey(sp.get(Pref.AmbiguityMode))`. Falls back to the same
     * ALWAYS_INSERT default when no profile is active.
     */
    @JvmStatic
    fun ambiguityMode(sp: SharedPreferences, db: DictateDatabase): AmbiguityMode =
        get(sp, db)?.ambiguityMode?.toAmbiguityMode() ?: AmbiguityMode.ALWAYS_INSERT

    /** Applies [mutation] to the active profile and persists it; no-op when none is active. */
    @JvmStatic
    fun update(sp: SharedPreferences, db: DictateDatabase, mutation: (ProfileEntity) -> ProfileEntity) {
        val current = get(sp, db) ?: return
        ConfigRepository(db).upsertProfile(mutation(current))
    }

    // ── Java-friendly accessors for SystemPromptsActivity (0=NONE, 1=PREDEFINED, 2=CUSTOM —
    //    the historical radio-group encoding, mapped onto PromptSelectionMode by ordinal) ──

    @JvmStatic
    fun stylePromptSelection(sp: SharedPreferences, db: DictateDatabase): Int =
        get(sp, db)?.stylePromptMode?.ordinal ?: PromptSelectionMode.PREDEFINED.ordinal

    @JvmStatic
    fun systemPromptSelection(sp: SharedPreferences, db: DictateDatabase): Int =
        get(sp, db)?.systemPromptMode?.ordinal ?: PromptSelectionMode.PREDEFINED.ordinal

    @JvmStatic
    fun stylePromptCustomText(sp: SharedPreferences, db: DictateDatabase): String =
        get(sp, db)?.stylePromptCustomText.orEmpty()

    @JvmStatic
    fun systemPromptCustomText(sp: SharedPreferences, db: DictateDatabase): String =
        get(sp, db)?.systemPromptCustomText.orEmpty()

    @JvmStatic
    fun setStylePromptSelection(sp: SharedPreferences, db: DictateDatabase, selection: Int) =
        update(sp, db) { it.copy(stylePromptMode = selectionMode(selection)) }

    @JvmStatic
    fun setSystemPromptSelection(sp: SharedPreferences, db: DictateDatabase, selection: Int) =
        update(sp, db) { it.copy(systemPromptMode = selectionMode(selection)) }

    @JvmStatic
    fun setStylePromptCustomText(sp: SharedPreferences, db: DictateDatabase, text: String) =
        update(sp, db) { it.copy(stylePromptCustomText = text) }

    @JvmStatic
    fun setSystemPromptCustomText(sp: SharedPreferences, db: DictateDatabase, text: String) =
        update(sp, db) { it.copy(systemPromptCustomText = text) }

    private fun selectionMode(selection: Int): PromptSelectionMode =
        PromptSelectionMode.values().getOrElse(selection) { PromptSelectionMode.PREDEFINED }

    // ── Transcription model ref of the active profile (keyterms live there, spec §4.5) ──

    /** The active profile's transcription model ref DTO, or null. */
    @JvmStatic
    fun transcriptionModelRef(sp: SharedPreferences, db: DictateDatabase): ModelRefEntity? {
        val id = get(sp, db)?.transcriptionModelRef ?: return null
        return db.modelRefDao().byId(id)?.let { ConfigEntityMapper.toDto(it) }
    }

    /** Provider type of the active transcription model ref, or null. */
    @JvmStatic
    fun transcriptionProviderType(sp: SharedPreferences, db: DictateDatabase): ProviderType? {
        val modelRef = transcriptionModelRef(sp, db) ?: return null
        return db.providerConfigDao().byId(modelRef.providerRef)?.providerTypeEnum
    }

    /**
     * Stores the parsed ElevenLabs keyterms JSON in the active transcription model ref's
     * `parameterDefaults["keyterms"]` (spec §4.5); empty JSON array removes the entry.
     *
     * Returns `false` (a no-op) when the active profile has no transcription model ref — keyterms
     * have nowhere to live without one, unlike the pref-based predecessor that persisted them
     * unconditionally. `SystemPromptsActivity` guards this path by disabling the keyterms field
     * unless an ElevenLabs `scribe_v2` transcription model ref is active (`updateKeytermsEnabled`),
     * so the `false` branch is defensive; the Boolean makes the no-op explicit and testable rather
     * than a silent early-return. Returns `true` once the value is persisted.
     */
    @JvmStatic
    fun setTranscriptionKeyterms(sp: SharedPreferences, db: DictateDatabase, parsedJson: String): Boolean {
        val modelRef = transcriptionModelRef(sp, db) ?: return false
        val params = modelRef.parameterDefaults.toMutableMap()
        if (parsedJson.isEmpty() || parsedJson == "[]") params.remove("keyterms") else params["keyterms"] = parsedJson
        ConfigRepository(db).upsertModelRef(modelRef.copy(parameterDefaults = params.toMap()))
        return true
    }
}
