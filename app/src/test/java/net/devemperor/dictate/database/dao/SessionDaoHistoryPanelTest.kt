package net.devemperor.dictate.database.dao

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.devemperor.dictate.database.DictateDatabase
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.database.entity.SessionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for `SessionDao.pagedHistoryPanel` (Paket 3 / ADR-0014).
 *
 * Real in-memory Room: the pending-first ORDER-BY key is evaluated by SQLite,
 * so an in-memory fake would test the fake (K-4 exception — real SQL is the
 * subject). Verifies pending-first ordering and the newest-first fallback.
 *
 * The `origin != 'REVIEW_REFINEMENT'` exclusion is covered in
 * `MigrationTo9Test` / a dedicated case once the origin exists (the v8 CHECK
 * rejects the value, so it cannot be inserted here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDaoHistoryPanelTest {

    private lateinit var db: DictateDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DictateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insert(
        id: String,
        createdAt: Long,
        status: SessionStatus = SessionStatus.COMPLETED,
        finalOutput: String? = "out",
        insertedAt: Long? = null,
    ) = dao.insert(
        SessionEntity(
            id = id,
            type = "RECORDING",
            createdAt = createdAt,
            targetAppPackage = null,
            language = null,
            audioFilePath = null,
            status = status.name,
            finalOutputText = finalOutput,
            inputText = null,
            insertedAt = insertedAt,
        )
    )

    private fun loadAll(): List<String> = runBlocking {
        val source = dao.pagedHistoryPanel()
        val page = source.load(
            PagingSource.LoadParams.Refresh(null, 50, false)
        ) as PagingSource.LoadResult.Page
        page.data.map { it.id }
    }

    @Test
    fun `pending sessions sort first, then newest-first`() {
        // Older pending (uninserted, completed, has text) must outrank a newer
        // already-inserted session — the whole point of the panel ordering.
        insert("inserted-new", createdAt = 100, insertedAt = 100)
        insert("pending-old", createdAt = 10, insertedAt = null)
        insert("inserted-old", createdAt = 5, insertedAt = 5)
        insert("pending-new", createdAt = 50, insertedAt = null)

        assertEquals(
            listOf("pending-new", "pending-old", "inserted-new", "inserted-old"),
            loadAll(),
        )
    }

    @Test
    fun `completed-without-text and non-completed are not pending`() {
        // Neither counts as pending → they sort by created_at among the rest.
        insert("no-text", createdAt = 30, finalOutput = null)
        insert("failed", createdAt = 20, status = SessionStatus.FAILED)
        insert("pending", createdAt = 10, insertedAt = null, finalOutput = "x")

        assertEquals(listOf("pending", "no-text", "failed"), loadAll())
    }

    @Test
    fun `marking inserted moves a row out of the pending group`() {
        insert("a", createdAt = 10, insertedAt = null)
        insert("b", createdAt = 5, insertedAt = null)
        assertEquals(listOf("a", "b"), loadAll())

        dao.markInserted("a", 999)

        // 'a' is no longer pending → 'b' (still pending) rises above it.
        assertEquals(listOf("b", "a"), loadAll())
    }
}
