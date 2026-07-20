package net.devemperor.dictate.companion.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.fakes.assertCheckFailure
import net.devemperor.dictate.companion.fakes.exec
import net.devemperor.dictate.companion.fakes.names
import net.devemperor.dictate.shared.config.AmbiguityModeValue
import net.devemperor.dictate.shared.config.ModelFunction
import net.devemperor.dictate.shared.config.PromptSelectionMode
import net.devemperor.dictate.shared.config.ProviderKind
import net.devemperor.dictate.shared.config.ProviderType
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.config.Visibility
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The Double-Enum rule for the config-entity mirror (desktop-host.md §9, peer-katalog.md §5.2, D5.b).
 *
 * These tables reuse the SAME `:shared.config` enums that C2 Room uses (not a parallel `catalog.*Wire`
 * copy), so "share one source so Android-Room and the companion cannot drift" is true by construction.
 * That makes this test the enum-parity gate:
 *
 * **(a) CHECK acceptance / rejection.** Every value the C1 enum can produce MUST be insertable, and a
 * value the enum cannot produce (`'TELEPATHY'`) MUST be rejected — the second half is what makes the
 * first worth anything.
 *
 * **(b) C1 vocabulary parity.** Each column's CHECK vocabulary equals the enum's `.name` set. If C1
 * ever grows/renames a value without the CHECK following (or vice versa), a `.name` set diverges and
 * this turns red with a diff. The `EnumColumnAdapter` writing an unknown `.name` would otherwise be
 * caught only at runtime by the CHECK — this pins it at build time.
 */
class ConfigEntityCheckParityTest {

    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @Before
    fun setUp() {
        driver.exec("PRAGMA foreign_keys = ON")
        SchemaMigrator.migrate(driver)
    }

    // ── (b) C1 vocabulary parity ────────────────────────────────────────────────────────────────

    @Test
    fun checkVocabularies_matchTheC1Enums() {
        assertEquals(
            setOf("OPENAI", "GROQ", "ANTHROPIC", "ELEVENLABS", "OPENROUTER", "CUSTOM"),
            ProviderType.entries.names(),
        )
        assertEquals(setOf("LOCAL", "GATEWAY"), ProviderKind.entries.names())
        assertEquals(setOf("TRANSCRIPTION", "COMPLETION"), ModelFunction.entries.names())
        assertEquals(setOf("NONE", "PREDEFINED", "CUSTOM"), PromptSelectionMode.entries.names())
        assertEquals(setOf("ALWAYS_INSERT", "AUTO", "ALWAYS_REVIEW"), AmbiguityModeValue.entries.names())
        assertEquals(setOf("PRIVATE", "SHARED"), Visibility.entries.names())
        assertEquals(setOf("LOCAL", "SUBSCRIBE", "ONE_SHOT"), SubscriptionMode.entries.names())
    }

    // ── (a) CHECK acceptance ────────────────────────────────────────────────────────────────────

    @Test
    fun everyProviderConfigEnumValue_isAccepted() {
        ProviderType.entries.forEach { insertProvider("pt-${it.name}", providerType = it.name) }
        ProviderKind.entries.forEach { insertProvider("pk-${it.name}", kind = it.name) }
        Visibility.entries.forEach { insertProvider("vi-${it.name}", visibility = it.name) }
        SubscriptionMode.entries.forEach { insertProvider("sm-${it.name}", subscriptionMode = it.name) }
    }

    @Test
    fun everyModelRefFunction_isAccepted() {
        ModelFunction.entries.forEach { insertModelRef("mf-${it.name}", function = it.name) }
    }

    @Test
    fun everyProfileEnumValue_isAccepted() {
        PromptSelectionMode.entries.forEach { insertProfile("ps-${it.name}", stylePromptMode = it.name) }
        PromptSelectionMode.entries.forEach { insertProfile("sy-${it.name}", systemPromptMode = it.name) }
        AmbiguityModeValue.entries.forEach { insertProfile("am-${it.name}", ambiguityMode = it.name) }
    }

    @Test
    fun promptVisibilityAndSubscription_areAccepted() {
        Visibility.entries.forEach { insertPrompt("pv-${it.name}", visibility = it.name) }
        SubscriptionMode.entries.forEach { insertPrompt("psm-${it.name}", subscriptionMode = it.name) }
    }

    // ── (a) CHECK rejection ─────────────────────────────────────────────────────────────────────

    @Test
    fun providerType_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertProvider("x", providerType = "TELEPATHY") }

    @Test
    fun providerKind_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertProvider("x", kind = "TELEPATHY") }

    @Test
    fun modelFunction_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertModelRef("x", function = "TELEPATHY") }

    @Test
    fun ambiguityMode_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertProfile("x", ambiguityMode = "TELEPATHY") }

    @Test
    fun stylePromptMode_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertProfile("x", stylePromptMode = "TELEPATHY") }

    @Test
    fun visibility_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertProvider("x", visibility = "TELEPATHY") }

    @Test
    fun subscriptionMode_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertProvider("x", subscriptionMode = "TELEPATHY") }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────

    private fun insertProvider(
        id: String,
        providerType: String = "OPENAI",
        kind: String = "LOCAL",
        visibility: String = "PRIVATE",
        subscriptionMode: String = "LOCAL",
    ) = driver.exec(
        "INSERT INTO provider_configs(id, provider_type, kind, label, visibility, subscription_mode, " +
            "content_hash, updated_at) " +
            "VALUES ('$id', '$providerType', '$kind', 'l', '$visibility', '$subscriptionMode', 'h', 1)",
    )

    private fun insertModelRef(id: String, function: String = "COMPLETION") = driver.exec(
        "INSERT INTO model_refs(id, provider_ref, model_id, function, content_hash, updated_at) " +
            "VALUES ('$id', 'p', 'gpt', '$function', 'h', 1)",
    )

    private fun insertPrompt(
        id: String,
        visibility: String = "PRIVATE",
        subscriptionMode: String = "LOCAL",
    ) = driver.exec(
        "INSERT INTO prompts(id, name, text, visibility, subscription_mode, content_hash, updated_at) " +
            "VALUES ('$id', 'n', 't', '$visibility', '$subscriptionMode', 'h', 1)",
    )

    private fun insertProfile(
        id: String,
        stylePromptMode: String = "PREDEFINED",
        systemPromptMode: String = "PREDEFINED",
        ambiguityMode: String = "ALWAYS_INSERT",
    ) = driver.exec(
        "INSERT INTO profiles(id, name, style_prompt_mode, system_prompt_mode, ambiguity_mode, " +
            "content_hash, updated_at) " +
            "VALUES ('$id', 'n', '$stylePromptMode', '$systemPromptMode', '$ambiguityMode', 'h', 1)",
    )
}
