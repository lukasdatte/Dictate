package net.devemperor.dictate.shared.sync

import net.devemperor.dictate.shared.protocol.CatalogEntityKindWire
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The platform-neutral [SyncNotification.summary] the simplest tray adapter ([AwtNotificationPort])
 * hands straight to `displayMessage`. It must count updated vs. removed separately and never render
 * a zero-count clause.
 */
class SyncNotificationTest {

    private fun updated(id: String) = CatalogChange.Updated(id, CatalogEntityKindWire.PROMPT, "L")
    private fun removed(id: String) = CatalogChange.SourceRemoved(id, CatalogEntityKindWire.PROMPT)

    @Test
    fun countsUpdatedAndRemovedSeparately() {
        val n = SyncNotification("Heim-PC", listOf(updated("a"), updated("b"), removed("c")))
        assertEquals("2 updated, 1 removed", n.summary)
    }

    @Test
    fun onlyUpdated_hasNoRemovedClause() {
        val n = SyncNotification("Heim-PC", listOf(updated("a")))
        assertEquals("1 updated", n.summary)
    }

    @Test
    fun onlyRemoved_hasNoUpdatedClause() {
        val n = SyncNotification("Heim-PC", listOf(removed("a")))
        assertEquals("1 removed", n.summary)
    }
}
