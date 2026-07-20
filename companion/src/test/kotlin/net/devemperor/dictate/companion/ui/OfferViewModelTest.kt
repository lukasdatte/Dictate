package net.devemperor.dictate.companion.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.devemperor.dictate.companion.data.CompanionConfigRepository
import net.devemperor.dictate.companion.data.CompanionDatabase
import net.devemperor.dictate.companion.domain.port.CatalogAccess
import net.devemperor.dictate.companion.domain.port.CatalogAuditLog
import net.devemperor.dictate.companion.ui.peers.OfferViewModel
import net.devemperor.dictate.shared.config.PromptV3Entity
import net.devemperor.dictate.shared.config.Visibility
import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The offer view's brain (peer-katalog.md §8.2): rows over my own entities, the newest access-log
 * row as "last pickup", and the share toggle — which, being an envelope write, must never move the
 * content hash (un-sharing must not look like a content update to subscribers).
 */
class OfferViewModelTest {

    private val database = CompanionDatabase.inMemory()
    private val config = CompanionConfigRepository(database, now = { 42L })
    private val log = mutableListOf<CatalogAccess>()

    private val auditLog = object : CatalogAuditLog {
        override fun record(peerDeviceId: String, entityId: String, kind: CatalogEntityKindWire, at: Long) {
            log += CatalogAccess(peerDeviceId, entityId, kind, at)
        }
        override fun accessFor(entityId: String) = log.filter { it.entityId == entityId }.sortedByDescending { it.at }
        override fun all() = log.sortedByDescending { it.at }
    }

    private fun viewModel() = OfferViewModel(config, auditLog, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun rows_carryVisibilityAndLastPickup() {
        config.save(PromptV3Entity(id = "p-1", name = "Formal", text = "t", visibility = Visibility.SHARED))
        log += CatalogAccess("phone-a", "p-1", CatalogEntityKindWire.PROMPT, at = 10)
        log += CatalogAccess("phone-b", "p-1", CatalogEntityKindWire.PROMPT, at = 20)

        val row = viewModel().state.value.rows.single()

        assertEquals(Visibility.SHARED, row.visibility)
        assertEquals("phone-b", row.lastAccess?.peerDeviceId) // newest wins
        assertEquals(20L, row.lastAccess?.at)
    }

    @Test
    fun rows_privateEntityListed_withoutPickup() {
        config.save(PromptV3Entity(id = "p-1", name = "Private", text = "t"))

        val row = viewModel().state.value.rows.single()

        assertEquals(Visibility.PRIVATE, row.visibility)
        assertNull(row.lastAccess)
    }

    @Test
    fun setVisibility_flipsTheEntity_withoutMovingItsContentHash() {
        val saved = config.save(PromptV3Entity(id = "p-1", name = "Formal", text = "t"))
        val vm = viewModel()

        vm.setVisibility(vm.state.value.rows.single(), Visibility.SHARED)

        val after = config.prompt("p-1")!!
        assertEquals(Visibility.SHARED, after.visibility)
        assertEquals(saved.contentHash, after.contentHash)
        assertEquals(Visibility.SHARED, vm.state.value.rows.single().visibility)
    }
}
