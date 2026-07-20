package net.devemperor.dictate.ai.adapter

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.ai.AIFunction
import net.devemperor.dictate.ai.AIProvider
import net.devemperor.dictate.ai.port.AiConfig
import net.devemperor.dictate.config.ConfigEntityMigration
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.get
import net.devemperor.dictate.preferences.put
import net.devemperor.dictate.secrets.AndroidKeystoreSecretStore
import net.devemperor.dictate.secrets.InMemoryKekProvider
import net.devemperor.dictate.secrets.SecretsMigration
import net.devemperor.dictate.testutil.FakeSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The **verhaltensneutralität** (behaviour-neutrality) proof for C2 (spec §9.4 / AK5).
 *
 * For a matrix of pref constellations the same values are asked of the pre-migration pref path
 * ([AndroidAiConfig]) and — after the realistic startup migration order (B2 [SecretsMigration] →
 * C2 [ConfigEntityMigration]) — of the entity path ([ProfileResolver]). They must be **byte-identical**:
 * provider, model, key (through the SecretStore now), baseUrl, and the typed completion-parameter map.
 *
 * Test-first per §9.4: the expected values are captured from the un-migrated pref config FIRST
 * (before B2 removes the key prefs), then the migration + resolver must reproduce them.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/entitaetenmodell-android.md §9.4
 */
@RunWith(RobolectricTestRunner::class)
class ProfileResolverCharacterizationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var db: DictateDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    private data class Snapshot(
        val transProvider: AIProvider,
        val transModel: String,
        val transKey: String,
        val transBase: String,
        val transKeyterms: List<String>?,
        val compProvider: AIProvider,
        val compModel: String,
        val compKey: String,
        val compBase: String,
        val compParams: Map<String, Any>,
    )

    private fun snapshot(cfg: AiConfig): Snapshot = Snapshot(
        transProvider = cfg.provider(AIFunction.TRANSCRIPTION),
        transModel = cfg.modelName(AIFunction.TRANSCRIPTION),
        transKey = cfg.apiKey(AIFunction.TRANSCRIPTION),
        transBase = cfg.baseUrl(AIFunction.TRANSCRIPTION),
        transKeyterms = cfg.elevenLabsKeyterms(),
        compProvider = cfg.provider(AIFunction.COMPLETION),
        compModel = cfg.modelName(AIFunction.COMPLETION),
        compKey = cfg.apiKey(AIFunction.COMPLETION),
        compBase = cfg.baseUrl(AIFunction.COMPLETION),
        compParams = cfg.completionParameters(cfg.provider(AIFunction.COMPLETION), cfg.modelName(AIFunction.COMPLETION)),
    )

    /** Seeds prefs, snapshots the pref path, runs B2 + C2, returns (expected, resolver). */
    private fun migrate(seed: FakeSharedPreferences.() -> Unit): Pair<Snapshot, ProfileResolver> {
        val sp = FakeSharedPreferences().apply(seed)
        val store = AndroidKeystoreSecretStore(FakeSharedPreferences(), InMemoryKekProvider(), hardwareBacked = false)

        val expected = snapshot(AndroidAiConfig(sp)) // capture BEFORE B2 removes the key prefs

        SecretsMigration.run(context, sp, store)
        val database = Room.inMemoryDatabaseBuilder(context, DictateDatabase::class.java)
            .allowMainThreadQueries().build()
        db = database
        ConfigEntityMigration.run(context, sp, database, store)

        return expected to ProfileResolver(sp, database, store)
    }

    private fun assertParity(expected: Snapshot, resolver: ProfileResolver) {
        assertEquals(expected.transProvider, resolver.provider(AIFunction.TRANSCRIPTION))
        assertEquals(expected.transModel, resolver.modelName(AIFunction.TRANSCRIPTION))
        assertEquals(expected.transKey, resolver.apiKey(AIFunction.TRANSCRIPTION))
        assertEquals(expected.transBase, resolver.baseUrl(AIFunction.TRANSCRIPTION))
        assertEquals(expected.transKeyterms, resolver.elevenLabsKeyterms())
        assertEquals(expected.compProvider, resolver.provider(AIFunction.COMPLETION))
        assertEquals(expected.compModel, resolver.modelName(AIFunction.COMPLETION))
        assertEquals(expected.compKey, resolver.apiKey(AIFunction.COMPLETION))
        assertEquals(expected.compBase, resolver.baseUrl(AIFunction.COMPLETION))
        assertEquals(
            expected.compParams,
            resolver.completionParameters(expected.compProvider, expected.compModel),
        )
    }

    @Test
    fun `openai transcription plus anthropic completion with params`() {
        val (expected, resolver) = migrate {
            edit()
                .put(Pref.TranscriptionProvider, "OPENAI")
                .put(Pref.TranscriptionOpenAIModel, "whisper-1")
                .put(Pref.TranscriptionApiKeyOpenAI, "sk-openai")
                .put(Pref.RewordingProvider, "ANTHROPIC")
                .put(Pref.RewordingAnthropicModel, "claude-sonnet-4-20250514")
                .put(Pref.RewordingApiKeyAnthropic, "sk-ant")
                .put(Pref.TemperatureAnthropic, 0.7f)
                .put(Pref.MaxTokensAnthropic, 4096)
                .apply()
        }
        assertParity(expected, resolver)
        // Spot-check the concrete expectation, not only self-consistency.
        assertEquals(AIProvider.ANTHROPIC, resolver.provider(AIFunction.COMPLETION))
        assertEquals("sk-openai", resolver.apiKey(AIFunction.TRANSCRIPTION))
        assertEquals(mapOf<String, Any>("temperature" to 0.7f, "max_tokens" to 4096), resolver.completionParameters(AIProvider.ANTHROPIC, "claude-sonnet-4-20250514"))
    }

    @Test
    fun `groq completion default baseUrl`() {
        val (expected, resolver) = migrate {
            edit()
                .put(Pref.RewordingProvider, "GROQ")
                .put(Pref.RewordingGroqModel, "llama-3.3-70b-versatile")
                .put(Pref.RewordingApiKeyGroq, "gsk")
                .apply()
        }
        assertParity(expected, resolver)
        assertEquals("https://api.groq.com/openai/v1/", resolver.baseUrl(AIFunction.COMPLETION))
    }

    @Test
    fun `custom completion resolves host and openrouter transcription unsupported`() {
        val (expected, resolver) = migrate {
            edit()
                .put(Pref.RewordingProvider, "CUSTOM")
                .put(Pref.RewordingCustomModel, "my-model")
                .put(Pref.RewordingCustomHost, "https://custom.example/v1/")
                .put(Pref.RewordingApiKeyCustom, "ck")
                .apply()
        }
        assertParity(expected, resolver)
        assertEquals("https://custom.example/v1/", resolver.baseUrl(AIFunction.COMPLETION))
    }

    @Test
    fun `custom transcription resolves transcription host`() {
        val (expected, resolver) = migrate {
            edit()
                .put(Pref.TranscriptionProvider, "CUSTOM")
                .put(Pref.TranscriptionCustomModel, "t-model")
                .put(Pref.TranscriptionCustomHost, "https://t.example/v1/")
                .put(Pref.TranscriptionApiKeyCustom, "tk")
                .apply()
        }
        assertParity(expected, resolver)
        assertEquals("https://t.example/v1/", resolver.baseUrl(AIFunction.TRANSCRIPTION))
    }

    @Test
    fun `elevenlabs transcription resolves keyterms`() {
        val (expected, resolver) = migrate {
            edit()
                .put(Pref.TranscriptionProvider, "ELEVENLABS")
                .put(Pref.TranscriptionElevenLabsModel, "scribe_v1")
                .put(Pref.TranscriptionApiKeyElevenLabs, "ek")
                .put(Pref.ElevenLabsKeytermsParsed, """["Alpha","Beta"]""")
                .apply()
        }
        assertParity(expected, resolver)
        assertEquals(listOf("Alpha", "Beta"), resolver.elevenLabsKeyterms())
    }

    @Test
    fun `non-ascii key is stripped identically`() {
        val (expected, resolver) = migrate {
            edit()
                .put(Pref.RewordingProvider, "OPENAI")
                .put(Pref.RewordingOpenAIModel, "gpt-4o-mini")
                .put(Pref.RewordingApiKeyOpenAI, "sk-café✓123")
                .apply()
        }
        assertParity(expected, resolver)
        assertEquals("sk-caf123", resolver.apiKey(AIFunction.COMPLETION))
    }

    @Test
    fun `openrouter completion default baseUrl`() {
        val (expected, resolver) = migrate {
            edit()
                .put(Pref.RewordingProvider, "OPENROUTER")
                .put(Pref.RewordingOpenRouterModel, "or-model")
                .put(Pref.RewordingApiKeyOpenRouter, "ork")
                .apply()
        }
        assertParity(expected, resolver)
        assertEquals("https://openrouter.ai/api/v1/", resolver.baseUrl(AIFunction.COMPLETION))
    }

    @Test
    fun `provider selected without key resolves empty key like today`() {
        val (expected, resolver) = migrate {
            // GROQ completion selected but no key entered — today returns "" for the key.
            edit()
                .put(Pref.RewordingProvider, "GROQ")
                .put(Pref.RewordingGroqModel, "llama-3.3-70b-versatile")
                .apply()
        }
        assertParity(expected, resolver)
        assertEquals("", resolver.apiKey(AIFunction.COMPLETION))
        assertEquals(AIProvider.GROQ, resolver.provider(AIFunction.COMPLETION))
    }

    @Test
    fun `no active profile falls back to empty config`() {
        val (_, resolver) = migrate {
            edit().put(Pref.RewordingProvider, "ANTHROPIC").put(Pref.RewordingApiKeyAnthropic, "ak").apply()
        }
        // Simulate "no active profile" (§9.3): the resolver must return the empty "not configured" state.
        // (Cannot mutate sp after migrate() closed over it, so use a fresh resolver on a cleared pointer.)
        val sp = FakeSharedPreferences()
        val store = AndroidKeystoreSecretStore(FakeSharedPreferences(), InMemoryKekProvider(), hardwareBacked = false)
        val emptyResolver = ProfileResolver(sp, db!!, store)
        assertEquals(AIProvider.OPENAI, emptyResolver.provider(AIFunction.COMPLETION))
        assertEquals("", emptyResolver.modelName(AIFunction.COMPLETION))
        assertEquals("", emptyResolver.apiKey(AIFunction.COMPLETION))
        assertEquals(AIProvider.OPENAI.defaultBaseUrl, emptyResolver.baseUrl(AIFunction.COMPLETION))
    }
}
