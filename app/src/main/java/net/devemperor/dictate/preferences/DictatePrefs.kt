package net.devemperor.dictate.preferences

import android.content.SharedPreferences

/**
 * Type-safe registry of all SharedPreferences keys.
 *
 * Usage Kotlin: val vibration = sp.get(Pref.Vibration)
 * Usage Java:   boolean v = DictatePrefsKt.get(sp, Pref.Vibration.INSTANCE);
 *               OR:   sp.getBoolean(Pref.Vibration.INSTANCE.getKey(), Pref.Vibration.INSTANCE.getDefault());
 */
sealed class Pref<T>(val key: String, val default: T) {

    // ── User & System ──
    object UserId : Pref<String>("net.devemperor.dictate.user_id", "null")
    object OnboardingComplete : Pref<Boolean>("net.devemperor.dictate.onboarding_complete", false)
    object LastVersionCode : Pref<Int>("net.devemperor.dictate.last_version_code", 0)
    object FlagHasRated : Pref<Boolean>("net.devemperor.dictate.flag_has_rated_in_playstore", false)
    object FlagHasDonated : Pref<Boolean>("net.devemperor.dictate.flag_has_donated", false)

    // ── Feature Toggles ──
    object RewordingEnabled : Pref<Boolean>("net.devemperor.dictate.rewording_enabled", true)
    object AutoFormattingEnabled : Pref<Boolean>("net.devemperor.dictate.auto_formatting_enabled", false)

    /**
     * Post-processing ambiguity mode (ADR-0013). Stores the
     * [net.devemperor.dictate.preferences.AmbiguityMode] persistKey; read via
     * `AmbiguityMode.fromPersistKey(sp.get(Pref.AmbiguityMode))`. Default
     * `ALWAYS_INSERT` preserves the Paket-1 behaviour with no extra AI call.
     */
    object AmbiguityMode : Pref<String>("net.devemperor.dictate.ambiguity_mode", "ALWAYS_INSERT")
    object InstantOutput : Pref<Boolean>("net.devemperor.dictate.instant_output", true)
    object AutoEnter : Pref<Boolean>("net.devemperor.dictate.auto_enter", false)
    object AutoEnterDelay : Pref<Int>("net.devemperor.dictate.auto_enter_delay", 50)
    object InstantRecording : Pref<Boolean>("net.devemperor.dictate.instant_recording", false)
    object ResendButton : Pref<Boolean>("net.devemperor.dictate.resend_button", false)
    object Vibration : Pref<Boolean>("net.devemperor.dictate.vibration", true)
    object AudioFocus : Pref<Boolean>("net.devemperor.dictate.audio_focus", true)
    object UseBluetoothMic : Pref<Boolean>("net.devemperor.dictate.use_bluetooth_mic", false)
    object Animations : Pref<Boolean>("net.devemperor.dictate.animations", true)
    object SmallMode : Pref<Boolean>("net.devemperor.dictate.small_mode", false)
    object SingleRowMode : Pref<Boolean>("net.devemperor.dictate.single_row_mode", false)

    // ── Windows Dispatch ──
    /**
     * Auto-send mode (ADR-0019). When on, a completed pipeline's final text is sent to the
     * paired PC instead of being committed into the Android host field. A failed send falls
     * back to the existing pending-part path (ADR-0011). Gated on [WindowsDeviceSecret] being
     * non-empty — the toggle is disabled in the settings until the phone is paired.
     */
    object WindowsAutoSendEnabled : Pref<Boolean>("net.devemperor.dictate.windows_auto_send_enabled", false)

    /** Base URL of the companion, e.g. "http://vm-win.tailnet.ts.net:8756". Set by pairing. */
    object WindowsTargetUrl : Pref<String>("net.devemperor.dictate.windows_target_url", "")

    /** The long-lived device secret from the pairing handshake (ADR-0017). Empty = not paired. */
    object WindowsDeviceSecret : Pref<String>("net.devemperor.dictate.windows_device_secret", "")

    /** Stable per-install id (UUIDv4), generated once at the first pairing attempt. */
    object WindowsDeviceId : Pref<String>("net.devemperor.dictate.windows_device_id", "")

    /** Display name of the paired PC, for the settings summary. */
    object WindowsServerName : Pref<String>("net.devemperor.dictate.windows_server_name", "")

    // ── UI/Theme ──
    object Theme : Pref<String>("net.devemperor.dictate.theme", "system")
    object AccentColor : Pref<Int>("net.devemperor.dictate.accent_color", -14700810)
    object AppLanguage : Pref<String>("net.devemperor.dictate.app_language", "system")
    object OverlayCharacters : Pref<String>("net.devemperor.dictate.overlay_characters", "()-:!?,.")
    object OutputSpeed : Pref<Int>("net.devemperor.dictate.output_speed", 5)

    /**
     * Floating-overlay widget card opacity in percent (20..100).
     * 100 = fully opaque. The settings SeekBar enforces the 20 % floor
     * so the card never becomes invisible over matching content; the
     * render path clamps defensively (`OverlayBackend`).
     *
     * @see net.devemperor.dictate.state.render.overlay.OverlayBackend
     */
    object WidgetOpacity : Pref<Int>("net.devemperor.dictate.widget_opacity", 100)

    // ── Language Selection ──
    // InputLanguages (Set<String>) needs separate access – not via get()
    object InputLanguagePos : Pref<Int>("net.devemperor.dictate.input_language_pos", 0)

    // ── Transcription API ──
    object TranscriptionProvider : Pref<String>("net.devemperor.dictate.transcription_provider", "OPENAI")
    object TranscriptionApiKeyOpenAI : Pref<String>("net.devemperor.dictate.transcription_api_key_openai", "")
    object TranscriptionApiKeyGroq : Pref<String>("net.devemperor.dictate.transcription_api_key_groq", "")
    object TranscriptionApiKeyCustom : Pref<String>("net.devemperor.dictate.transcription_api_key_custom", "")
    object TranscriptionApiKeyOpenRouter : Pref<String>("net.devemperor.dictate.transcription_api_key_openrouter", "")
    object TranscriptionOpenAIModel : Pref<String>("net.devemperor.dictate.transcription_openai_model", "gpt-4o-mini-transcribe")
    object TranscriptionGroqModel : Pref<String>("net.devemperor.dictate.transcription_groq_model", "whisper-large-v3-turbo")
    object TranscriptionApiKeyElevenLabs : Pref<String>("net.devemperor.dictate.transcription_api_key_elevenlabs", "")
    object TranscriptionElevenLabsModel : Pref<String>("net.devemperor.dictate.transcription_elevenlabs_model", "scribe_v1")
    object TranscriptionCustomModel : Pref<String>("net.devemperor.dictate.transcription_custom_model", "")
    object TranscriptionCustomHost : Pref<String>("net.devemperor.dictate.transcription_custom_host", "")

    // ── ElevenLabs Key Terms ──
    object ElevenLabsKeytermsRaw : Pref<String>("net.devemperor.dictate.elevenlabs_keyterms_raw", "")
    object ElevenLabsKeytermsParsed : Pref<String>("net.devemperor.dictate.elevenlabs_keyterms_parsed", "[]")

    // ── Rewording/Completion API ──
    object RewordingProvider : Pref<String>("net.devemperor.dictate.rewording_provider", "OPENAI")
    object RewordingApiKeyOpenAI : Pref<String>("net.devemperor.dictate.rewording_api_key_openai", "")
    object RewordingApiKeyGroq : Pref<String>("net.devemperor.dictate.rewording_api_key_groq", "")
    object RewordingApiKeyAnthropic : Pref<String>("net.devemperor.dictate.rewording_api_key_anthropic", "")
    object RewordingApiKeyOpenRouter : Pref<String>("net.devemperor.dictate.rewording_api_key_openrouter", "")
    object RewordingApiKeyCustom : Pref<String>("net.devemperor.dictate.rewording_api_key_custom", "")
    object RewordingOpenAIModel : Pref<String>("net.devemperor.dictate.rewording_openai_model", "gpt-4o-mini")
    object RewordingGroqModel : Pref<String>("net.devemperor.dictate.rewording_groq_model", "llama-3.3-70b-versatile")
    object RewordingAnthropicModel : Pref<String>("net.devemperor.dictate.rewording_anthropic_model", "claude-sonnet-4-20250514")
    object RewordingOpenRouterModel : Pref<String>("net.devemperor.dictate.rewording_openrouter_model", "")
    object RewordingCustomModel : Pref<String>("net.devemperor.dictate.rewording_custom_model", "")
    object RewordingCustomHost : Pref<String>("net.devemperor.dictate.rewording_custom_host", "")

    // ── Prompts ──
    object StylePromptSelection : Pref<Int>("net.devemperor.dictate.style_prompt_selection", 1)
    object StylePromptCustomText : Pref<String>("net.devemperor.dictate.style_prompt_custom_text", "")
    object SystemPromptSelection : Pref<Int>("net.devemperor.dictate.system_prompt_selection", 1)
    object SystemPromptCustomText : Pref<String>("net.devemperor.dictate.system_prompt_custom_text", "")

    // ── Proxy ──
    object ProxyEnabled : Pref<Boolean>("net.devemperor.dictate.proxy_enabled", false)
    object ProxyHost : Pref<String>("net.devemperor.dictate.proxy_host", "")

    // ── Model Parameters (per provider, -1 / "" = server default) ──
    object TemperatureOpenAI : Pref<Float>("net.devemperor.dictate.param_temperature_openai", -1f)
    object TemperatureGroq : Pref<Float>("net.devemperor.dictate.param_temperature_groq", -1f)
    object TemperatureAnthropic : Pref<Float>("net.devemperor.dictate.param_temperature_anthropic", -1f)
    object TemperatureOpenRouter : Pref<Float>("net.devemperor.dictate.param_temperature_openrouter", -1f)
    object MaxTokensOpenAI : Pref<Int>("net.devemperor.dictate.param_max_tokens_openai", -1)
    object MaxTokensGroq : Pref<Int>("net.devemperor.dictate.param_max_tokens_groq", -1)
    object MaxTokensAnthropic : Pref<Int>("net.devemperor.dictate.param_max_tokens_anthropic", 4096)
    object MaxTokensOpenRouter : Pref<Int>("net.devemperor.dictate.param_max_tokens_openrouter", -1)
    object ReasoningEffortOpenAI : Pref<String>("net.devemperor.dictate.param_reasoning_effort_openai", "")

    // ── Internal State ──
    object LastFileName : Pref<String>("net.devemperor.dictate.last_file_name", "audio.m4a")
    object TranscriptionAudioFile : Pref<String>("net.devemperor.dictate.transcription_audio_file", "")
    object QueuedPromptIds : Pref<String>("net.devemperor.dictate.queued_prompt_ids", "")

    /**
     * Idempotence flag for [net.devemperor.dictate.migration.LegacyAudioFileMigration]
     * (B3-VAL-W1 F-7). The key is namespaced (project rule: prefs go
     * through this sealed-class registry, never raw strings); the
     * default `false` matches the migration semantic ("not yet run").
     *
     * @see net.devemperor.dictate.migration.LegacyAudioFileMigration
     */
    object LegacyAudioPurgedV4 :
        Pref<Boolean>("net.devemperor.dictate.legacy_audio_purged_v4", false)

    // ── Input Languages (Set<String>, separate access) ──
    object InputLanguages : Pref<String>("net.devemperor.dictate.input_languages", "")  // Sentinel, actually Set<String>

    // ── Overlay (F-4 — typed entries previously accessed via raw strings) ──
    // These keys keep the legacy (non-namespaced) names because
    // `OverlayModule.Effect.PersistOverlayPosition` and the C7
    // `PipelinePrefMirror` constants already shipped with the
    // un-namespaced names; renaming would invalidate user data.
    object OverlayPositionPortraitX : Pref<Float>("overlay_pos_portrait_x", 1.0f)
    object OverlayPositionPortraitY : Pref<Float>("overlay_pos_portrait_y", 0.1f)
    object OverlayPositionLandscapeX : Pref<Float>("overlay_pos_landscape_x", 1.0f)
    object OverlayPositionLandscapeY : Pref<Float>("overlay_pos_landscape_y", 0.1f)
    object OverlayOnboardingShown : Pref<Boolean>("overlay_onboarding_shown", false)
    object OverlayOnboardingDismissed : Pref<Boolean>("overlay_onboarding_dismissed", false)

    // ── Session-Cleanup-Policy (B3 §6.2 R.17 + §6.3.1 KG-SST-2) ──
    //
    // Grace-period for the idle-stop session cleanup: COMPLETED sessions whose
    // `inserted_at` timestamp is older than `now - SessionCleanupGracePeriodMs`
    // are eligible for `deleteInsertedOlderThan`, and FAILED/CANCELLED sessions
    // whose `created_at` is older than the same cutoff are eligible for
    // orphan-audio cleanup (KG-SST-2). Default: 7d + 1h safety buffer
    // = 7 * 24 * 3600 * 1000 + 3600 * 1000 = 608_400_000 ms.
    //
    // Stored as Long. The "safety hour" prevents a clean session that was just
    // inserted (`inserted_at = now`) from being deleted on the same idle-stop
    // cycle by a small clock skew.
    object SessionCleanupGracePeriodMs :
        Pref<Long>("net.devemperor.dictate.session_cleanup_grace_period_ms", 608_400_000L)

    // ── Pending-Insertion Freshness Floor (B3 §6.5 + B3-VAL-W1 F-2) ──
    //
    // After M4 backfilled `inserted_at = NULL` for all pre-existing COMPLETED
    // rows (the only way to keep them safe from `deleteInsertedOlderThan`),
    // `findPendingInsertion` would otherwise surface every legacy COMPLETED
    // row as a pending-paste candidate and trigger NotifyManualPasteNeeded
    // N times on first boot after upgrade. The freshness floor caps which
    // rows are considered "fresh enough" to surface — only sessions whose
    // `created_at` is within the last PendingInsertionFreshnessMs are
    // pending-insertion candidates.
    //
    // Default: 24h (anything older was either pasted long ago, or the user
    // has moved on — the M4 upgrade window is the canonical case the
    // freshness floor protects against).
    //
    // @see net.devemperor.dictate.database.dao.SessionDao.findPendingInsertion
    // @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/b3-cleanup-cascade-and-backfill-policy.md §4
    object PendingInsertionFreshnessMs :
        Pref<Long>("net.devemperor.dictate.pending_insertion_freshness_ms", 86_400_000L)

    // Rolling-Segments interval (ADR-0007 §"Activation + Rolling-Segments",
    // B1.3). The RecordingHardwareAdapter rolls to a new MediaRecorder
    // output file every N seconds during active recording so the previous
    // segment is finalised (moov-atom written) and survives a crash. At
    // worst, the user loses one rolling interval of audio when the
    // process dies mid-segment.
    //
    // Default: 15 s — halved from the historic ADR-0007 30 s estimate
    // after the on-device verification of recording-stack-completion
    // Block A on 2026-05-22. 15 s halves the worst-case crash-loss window
    // and keeps the multi-segment-mux overhead bounded: a typical 30-60 s
    // recording produces 2-4 segments (vs. 1-2 at 30 s), the MediaMuxer
    // concat at upload-time stays under 200 ms. Lower (e.g. 10 s) makes
    // multi-segment the default for *every* recording — more
    // setNextOutputFile race-warnings on Pixel/Samsung hardware,
    // proportionally more DB-writes via SyncAudioSegments. Higher (e.g.
    // 30 s) preserves more audio loss per crash than necessary.
    //
    // @see net.devemperor.dictate.core.RecordingHardwareAdapter
    object RollingSegmentIntervalSec :
        Pref<Long>("net.devemperor.dictate.rolling_segment_interval_sec", 15L)

    // Continuation freshness window — how long a RECORDING_INTERRUPTED
    // session stays eligible for auto-continuation on the next
    // Record-click (B2 / ADR-0008 §"Auto-Continuation"). Beyond this
    // window the next PipelineRecovery pass promotes it to FAILED and
    // deletes the audio. Default: 24 h (a comfortable single-day cap
    // — long enough for "I'll get back to this after lunch", short
    // enough that stale state doesn't accumulate forever).
    //
    // Unit: milliseconds (matches `System.currentTimeMillis()` math).
    object ContinuationFreshnessMs :
        Pref<Long>("net.devemperor.dictate.continuation_freshness_ms", 86_400_000L)

    // ── Cache-Audio-Cleanup Job (recording-stack-completion §4.5.2) ──
    //
    // The hybrid Application-onCreate + Service-onDestroy cleanup job
    // sweeps stale segment files (`sess_{sid}_seg{N}.m4a`) and
    // transient merged files (`sess_{sid}_merged.m4a`) out of
    // `cache/audio/`. Each invocation is gated by [CacheCleanupLastRunMs]
    // against [CacheCleanupIntervalMs] so the job runs at most once per
    // interval even with two trigger sites.
    //
    // Files are deleted only when (a) the owning session is no longer
    // in `SessionDao.findActiveSessionIds()` AND (b) the file's
    // `lastModified` is older than `now - CacheCleanupTtlMs`. The TTL
    // is kept independent from [SessionCleanupGracePeriodMs] because
    // they live on different layers (row-scope vs file-scope) and
    // could drift in the future.

    /**
     * Timestamp (ms epoch) of the last [CacheAudioCleanupJob] run.
     * Default 0L — meaning "never run" so the first scheduled tick
     * after install always executes.
     *
     * @see net.devemperor.dictate.audio.CacheAudioCleanupScheduler.scheduleIfDue
     */
    object CacheCleanupLastRunMs :
        Pref<Long>("net.devemperor.dictate.cache_cleanup_last_run_ms", 0L)

    /**
     * Minimum gap between two [CacheAudioCleanupJob] runs (ms).
     * Default 24 h. The scheduler short-circuits when
     * `now - CacheCleanupLastRunMs < CacheCleanupIntervalMs`.
     */
    object CacheCleanupIntervalMs :
        Pref<Long>(
            "net.devemperor.dictate.cache_cleanup_interval_ms",
            24L * 60L * 60L * 1000L,
        )

    /**
     * File-scope TTL — segment + merged files older than this and
     * belonging to a terminal session are deletion-candidates.
     * Default 7 d, mirroring [SessionCleanupGracePeriodMs]'s headline
     * value (but kept independent so the layers can drift if needed).
     */
    object CacheCleanupTtlMs :
        Pref<Long>(
            "net.devemperor.dictate.cache_cleanup_ttl_ms",
            7L * 24L * 60L * 60L * 1000L,
        )
}

// ── Extension Functions ──

@Suppress("UNCHECKED_CAST")
fun <T> SharedPreferences.get(pref: Pref<T>): T = when (pref.default) {
    is Boolean -> getBoolean(pref.key, pref.default) as T
    is Int -> getInt(pref.key, pref.default) as T
    is String -> (getString(pref.key, pref.default) ?: pref.default) as T
    is Long -> getLong(pref.key, pref.default) as T
    is Float -> getFloat(pref.key, pref.default) as T
    else -> throw IllegalArgumentException("Unsupported type: ${pref.default!!::class}")
}

fun <T> SharedPreferences.Editor.put(pref: Pref<T>, value: T): SharedPreferences.Editor = when (value) {
    is Boolean -> putBoolean(pref.key, value)
    is Int -> putInt(pref.key, value)
    is String -> putString(pref.key, value)
    is Long -> putLong(pref.key, value)
    is Float -> putFloat(pref.key, value)
    else -> throw IllegalArgumentException("Unsupported type: ${value!!::class}")
}

// ── Set<String> Support (for InputLanguages) ──
fun SharedPreferences.getStringSet(pref: Pref<String>, default: Set<String> = emptySet()): Set<String> =
    getStringSet(pref.key, default) ?: default

fun SharedPreferences.Editor.putStringSet(pref: Pref<String>, value: Set<String>): SharedPreferences.Editor =
    putStringSet(pref.key, value)
