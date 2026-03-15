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
    object InstantOutput : Pref<Boolean>("net.devemperor.dictate.instant_output", true)
    object AutoEnter : Pref<Boolean>("net.devemperor.dictate.auto_enter", false)
    object InstantRecording : Pref<Boolean>("net.devemperor.dictate.instant_recording", false)
    object ResendButton : Pref<Boolean>("net.devemperor.dictate.resend_button", false)
    object Vibration : Pref<Boolean>("net.devemperor.dictate.vibration", true)
    object AudioFocus : Pref<Boolean>("net.devemperor.dictate.audio_focus", true)
    object UseBluetoothMic : Pref<Boolean>("net.devemperor.dictate.use_bluetooth_mic", false)
    object Animations : Pref<Boolean>("net.devemperor.dictate.animations", true)
    object SmallMode : Pref<Boolean>("net.devemperor.dictate.small_mode", false)

    // ── UI/Theme ──
    object Theme : Pref<String>("net.devemperor.dictate.theme", "system")
    object AccentColor : Pref<Int>("net.devemperor.dictate.accent_color", -14700810)
    object AppLanguage : Pref<String>("net.devemperor.dictate.app_language", "system")
    object OverlayCharacters : Pref<String>("net.devemperor.dictate.overlay_characters", "()-:!?,.")
    object OutputSpeed : Pref<Int>("net.devemperor.dictate.output_speed", 5)

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

    // ── Input Languages (Set<String>, separate access) ──
    object InputLanguages : Pref<String>("net.devemperor.dictate.input_languages", "")  // Sentinel, actually Set<String>
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
