package net.devemperor.dictate.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.PromptEntity
import net.devemperor.dictate.database.entity.PromptType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [PromptDao.getAutoApplyIds] against real SQLite.
 *
 * The auto-apply queue is primed from this query at record-start. A text pill
 * (PromptType.TEXT) must never be auto-applied — its literal content would be
 * sent to the model as an instruction. The query filters TEXT out defensively,
 * independent of the editor also hiding the auto-apply switch for text pills
 * (plan §4.2 "Queue-Härtung").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptDaoAutoApplyTest {

    private lateinit var db: DictateDatabase
    private lateinit var dao: PromptDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Fresh in-memory schema — not the seeded singleton.
        db = Room.inMemoryDatabaseBuilder(context, DictateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.promptDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getAutoApplyIds excludes TEXT pills`() {
        dao.insert(
            PromptEntity(
                id = 1, pos = 0, name = "Prompt", prompt = "do x",
                requiresSelection = false, autoApply = true, type = PromptType.PROMPT.name
            )
        )
        dao.insert(
            PromptEntity(
                id = 2, pos = 1, name = "Text", prompt = "literal snippet",
                requiresSelection = false, autoApply = true, type = PromptType.TEXT.name
            )
        )

        // Only the PROMPT pill is auto-applied; the TEXT pill is filtered out.
        assertEquals(listOf(1), dao.getAutoApplyIds())
    }

    @Test
    fun `getAutoApplyIds keeps auto-apply PROMPT pills in position order`() {
        dao.insert(PromptEntity(id = 1, pos = 5, name = "B", prompt = "b", requiresSelection = false, autoApply = true, type = PromptType.PROMPT.name))
        dao.insert(PromptEntity(id = 2, pos = 1, name = "A", prompt = "a", requiresSelection = false, autoApply = true, type = PromptType.PROMPT.name))
        dao.insert(PromptEntity(id = 3, pos = 3, name = "C", prompt = "c", requiresSelection = false, autoApply = false, type = PromptType.PROMPT.name))

        assertEquals(listOf(2, 1), dao.getAutoApplyIds())
    }
}
