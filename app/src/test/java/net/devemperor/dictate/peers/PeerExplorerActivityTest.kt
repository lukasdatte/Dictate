package net.devemperor.dictate.peers

import android.widget.LinearLayout
import android.widget.TextView
import net.devemperor.dictate.R
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.peers.ui.PeerExplorerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E3c Robolectric smoke (peer-katalog.md §12): the read-only Peer Explorer settings page renders —
 * empty state without provenance, one group with rows once a subscribed copy exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerExplorerActivityTest {

    @After
    fun tearDown() {
        DictateDatabase.resetForTest(RuntimeEnvironment.getApplication())
    }

    @Test
    fun rendersEmptyState_withoutAnySubscribedCopy() {
        val activity = Robolectric.buildActivity(PeerExplorerActivity::class.java).setup().get()

        val list = activity.findViewById<LinearLayout>(R.id.peer_explorer_list)
        val empty = activity.findViewById<TextView>(R.id.peer_explorer_empty)
        assertEquals(0, list.childCount)
        assertEquals(android.view.View.VISIBLE, empty.visibility)
    }

    @Test
    fun rendersPeerGroupWithRows_forASubscribedCopy() {
        val db = DictateDatabase.getInstance(RuntimeEnvironment.getApplication())
        db.promptDao().insert(
            PromptEntity(
                id = 0,
                pos = 0,
                name = "Formal tone",
                prompt = "Rewrite formally.",
                sourcePeerId = "heim-pc",
                subscriptionMode = "SUBSCRIBE",
                uuid = "p-uuid-1",
            ),
        )

        val activity = Robolectric.buildActivity(PeerExplorerActivity::class.java).setup().get()

        val list = activity.findViewById<LinearLayout>(R.id.peer_explorer_list)
        assertEquals(2, list.childCount) // peer header + one copy row
        assertEquals("heim-pc", (list.getChildAt(0) as TextView).text.toString())
        val row = (list.getChildAt(1) as TextView).text.toString()
        assertTrue(row.contains("Formal tone"))
        assertTrue(row.contains("PROMPT"))
    }
}
