package net.devemperor.dictate.companion.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.devemperor.dictate.companion.fakes.assertCheckFailure
import net.devemperor.dictate.companion.fakes.exec
import net.devemperor.dictate.companion.fakes.names
import net.devemperor.dictate.shared.config.SubscriptionMode
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The Double-Enum rule for the E1 catalog tables (peer-katalog.md §5.3/§5.4).
 *
 * `subscriptions.kind` and `catalog_access_log.kind` are backed by the `:shared.protocol`
 * [CatalogEntityKindWire]; `subscriptions.mode` reuses the C1 `:shared.config` [SubscriptionMode] (the
 * SAME source D3's entity tables use, not a parallel `catalog.*Wire` copy — Companion.sq header) but
 * its CHECK is deliberately the SUBSET `('SUBSCRIBE','ONE_SHOT')`: a subscription row is always an
 * ACTIVE binding, never LOCAL.
 *
 * **(a) kind parity.** Each `kind` CHECK vocabulary equals `CatalogEntityKindWire.name` set, and each
 * value is insertable; a value the enum cannot produce is refused.
 *
 * **(b) mode subset.** `SUBSCRIBE`/`ONE_SHOT` are accepted; `LOCAL` (a real enum value) is refused by
 * the subset CHECK; a value the enum cannot produce is refused too.
 */
class CatalogCheckConstraintParityTest {

    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @Before
    fun setUp() {
        driver.exec("PRAGMA foreign_keys = ON")
        SchemaMigrator.migrate(driver)
        // subscriptions.peer_id has an FK to peers — seed one so the enum inserts are not FK-rejected.
        driver.exec("INSERT INTO peers(peer_id, display_name, address, device_id, secret_ref, added_at) VALUES ('peer-1','pc','a:1','d','r',1)")
    }

    // ── (a) kind parity ──────────────────────────────────────────────────────────────────────────

    @Test
    fun kindVocabulary_matchesTheWireEnum() {
        assertEquals(
            setOf("PROVIDER_CONFIG", "MODEL_REF", "PROMPT", "PROFILE", "CREDENTIAL", "UNKNOWN"),
            CatalogEntityKindWire.entries.names(),
        )
    }

    @Test
    fun everyKind_isAcceptedInAccessLog() {
        CatalogEntityKindWire.entries.forEach { insertAccess("al-${it.name}", kind = it.name) }
    }

    @Test
    fun everyKind_isAcceptedInSubscriptions() {
        CatalogEntityKindWire.entries.forEach { insertSubscription("sub-${it.name}", kind = it.name) }
    }

    @Test
    fun accessLogKind_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertAccess("x", kind = "TELEPATHY") }

    @Test
    fun subscriptionsKind_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertSubscription("x", kind = "TELEPATHY") }

    // ── (b) mode subset ────────────────────────────────────────────────────────────────────────

    @Test
    fun subscriptionMode_acceptsTheActiveBindingValues() {
        insertSubscription("sub-sub", mode = "SUBSCRIBE")
        insertSubscription("sub-one", mode = "ONE_SHOT")
    }

    @Test
    fun subscriptionMode_rejectsLocal_eventhoughItIsARealEnumValue() {
        // LOCAL is a valid SubscriptionMode.name, but a subscription row must be an active binding.
        assertEquals(true, SubscriptionMode.entries.names().contains("LOCAL"))
        assertCheckFailure { insertSubscription("x", mode = "LOCAL") }
    }

    @Test
    fun subscriptionMode_rejectsAValueTheEnumCannotProduce() =
        assertCheckFailure { insertSubscription("x", mode = "TELEPATHY") }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────

    private fun insertAccess(id: String, kind: String = "PROMPT") = driver.exec(
        "INSERT INTO catalog_access_log(peer_device_id, entity_id, kind, at) VALUES ('d', '$id', '$kind', 1)",
    )

    private fun insertSubscription(id: String, kind: String = "PROMPT", mode: String = "SUBSCRIBE") = driver.exec(
        "INSERT INTO subscriptions(local_entity_id, peer_id, source_entity_id, kind, mode, last_hash) " +
            "VALUES ('$id', 'peer-1', 'src', '$kind', '$mode', 'h')",
    )
}
