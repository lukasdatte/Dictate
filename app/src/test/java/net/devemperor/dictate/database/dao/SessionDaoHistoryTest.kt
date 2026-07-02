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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the paged history DAO surface
 * (F-054 / history-pagination-and-scale §3).
 *
 * These run against a real in-memory Room database on purpose: the
 * paging-window arithmetic lives in Room's generated
 * `LimitOffsetPagingSource` and the `LIKE ... ESCAPE '\'` semantics
 * live in SQLite — an in-memory fake would test the fake
 * (K-4 exception: real SQL behaviour is the subject under test).
 *
 * Coverage:
 *  - paging window correctness: order, page boundaries, no
 *    overlap/gap across consecutive loads, terminal `nextKey == null`;
 *  - type-filter and combined type+search paging;
 *  - ESCAPE behaviour: literal `%`, `_` and `\` in user input are
 *    found literally (and, as a control, that the unescaped input
 *    would over-match — the original defect);
 *  - `deleteCancelledOlderThan` retention semantics against real SQL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDaoHistoryTest {

    private lateinit var db: DictateDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Fresh in-memory schema per test — deliberately NOT the
        // DictateDatabase singleton (no cross-test state, no
        // default-prompt seeding callback).
        db = Room.inMemoryDatabaseBuilder(context, DictateDatabase::class.java)
            .allowMainThreadQueries() // test-harness convenience only; production paging loads run on Room's executor
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
        type: String = "RECORDING",
        status: SessionStatus = SessionStatus.COMPLETED,
        finalOutput: String? = null,
        input: String? = null,
    ) = dao.insert(
        SessionEntity(
            id = id,
            type = type,
            createdAt = createdAt,
            targetAppPackage = null,
            language = null,
            audioFilePath = null,
            status = status.name,
            finalOutputText = finalOutput,
            inputText = input,
        )
    )

    /** Loads one page and asserts the result is a successful Page. */
    private fun loadPage(
        source: PagingSource<Int, SessionEntity>,
        key: Int?,
        loadSize: Int,
    ): PagingSource.LoadResult.Page<Int, SessionEntity> = runBlocking {
        val params = if (key == null) {
            PagingSource.LoadParams.Refresh<Int>(null, loadSize, false)
        } else {
            PagingSource.LoadParams.Append(key, loadSize, false)
        }
        source.load(params) as PagingSource.LoadResult.Page
    }

    // ── Paging window correctness ─────────────────────────────────

    @Test
    fun `pages are newest-first, contiguous and terminate with null nextKey`() {
        (1..25).forEach { i -> insert(id = "s%02d".format(i), createdAt = i.toLong()) }

        val source = dao.pagedHistory(type = null, searchPattern = null)

        val page1 = loadPage(source, key = null, loadSize = 10)
        assertEquals((25 downTo 16).map { "s%02d".format(it) }, page1.data.map { it.id })
        assertNotNull("more rows exist → nextKey set", page1.nextKey)

        val page2 = loadPage(source, key = page1.nextKey, loadSize = 10)
        assertEquals((15 downTo 6).map { "s%02d".format(it) }, page2.data.map { it.id })

        val page3 = loadPage(source, key = page2.nextKey, loadSize = 10)
        assertEquals((5 downTo 1).map { "s%02d".format(it) }, page3.data.map { it.id })
        assertNull("end of data → nextKey null", page3.nextKey)

        // No overlap, no gap: the three windows partition the table.
        val union = (page1.data + page2.data + page3.data).map { it.id }
        assertEquals(25, union.size)
        assertEquals(25, union.toSet().size)
    }

    @Test
    fun `type filter pages contain only the requested type in descending order`() {
        (1..6).forEach { i -> insert(id = "rec$i", createdAt = i * 10L, type = "RECORDING") }
        (1..6).forEach { i -> insert(id = "rew$i", createdAt = i * 10L + 1, type = "REWORDING") }

        val source = dao.pagedHistory(type = "REWORDING", searchPattern = null)
        val page1 = loadPage(source, key = null, loadSize = 4)
        assertEquals(listOf("rew6", "rew5", "rew4", "rew3"), page1.data.map { it.id })

        val page2 = loadPage(source, key = page1.nextKey, loadSize = 4)
        assertEquals(listOf("rew2", "rew1"), page2.data.map { it.id })
        assertNull(page2.nextKey)
    }

    // ── ESCAPE behaviour ──────────────────────────────────────────

    @Test
    fun `literal percent in search matches literally after escaping`() {
        insert("with-percent", createdAt = 3, finalOutput = "progress 100% done")
        insert("without-percent", createdAt = 2, finalOutput = "progress 1009 done")
        insert("unrelated", createdAt = 1, finalOutput = "misc")

        // Control — the original defect: unescaped '%' is a wildcard
        // and over-matches ("100" + anything + " done").
        val unescaped = loadPage(dao.pagedHistory(null, "100% done"), null, 10)
        assertEquals(
            setOf("with-percent", "without-percent"),
            unescaped.data.map { it.id }.toSet(),
        )

        val escaped = loadPage(dao.pagedHistory(null, LikeEscape.escape("100% done")), null, 10)
        assertEquals(listOf("with-percent"), escaped.data.map { it.id })
    }

    @Test
    fun `literal underscore in search matches literally after escaping`() {
        insert("with-underscore", createdAt = 2, finalOutput = "var a_b set")
        insert("wildcard-bait", createdAt = 1, finalOutput = "var aXb set")

        val unescaped = loadPage(dao.pagedHistory(null, "a_b"), null, 10)
        assertEquals(
            setOf("with-underscore", "wildcard-bait"),
            unescaped.data.map { it.id }.toSet(),
        )

        val escaped = loadPage(dao.pagedHistory(null, LikeEscape.escape("a_b")), null, 10)
        assertEquals(listOf("with-underscore"), escaped.data.map { it.id })
    }

    @Test
    fun `literal backslash in search matches literally after escaping`() {
        insert("with-backslash", createdAt = 2, finalOutput = """path C:\temp end""")
        insert("no-backslash", createdAt = 1, finalOutput = "path C:temp end")

        val escaped = loadPage(dao.pagedHistory(null, LikeEscape.escape("""C:\temp""")), null, 10)
        assertEquals(listOf("with-backslash"), escaped.data.map { it.id })
    }

    @Test
    fun `search matches input_text when final_output_text is null`() {
        insert("input-only", createdAt = 2, finalOutput = null, input = "reword me please")
        insert("other", createdAt = 1, finalOutput = "something else")

        val page = loadPage(dao.pagedHistory(null, LikeEscape.escape("reword me")), null, 10)
        assertEquals(listOf("input-only"), page.data.map { it.id })
    }

    @Test
    fun `type filter and search combine`() {
        insert("rec-match", createdAt = 3, type = "RECORDING", finalOutput = "hello world")
        insert("rew-match", createdAt = 2, type = "REWORDING", finalOutput = "hello world")
        insert("rec-nomatch", createdAt = 1, type = "RECORDING", finalOutput = "bye")

        val page = loadPage(
            dao.pagedHistory(type = "RECORDING", searchPattern = LikeEscape.escape("hello")),
            null,
            10,
        )
        assertEquals(listOf("rec-match"), page.data.map { it.id })
    }

    // ── Retention SQL ─────────────────────────────────────────────

    @Test
    fun `deleteCancelledOlderThan removes only past-horizon CANCELLED rows`() {
        insert("cxl-old", createdAt = 1_000, status = SessionStatus.CANCELLED)
        insert("cxl-fresh", createdAt = 9_000, status = SessionStatus.CANCELLED)
        insert("failed-old", createdAt = 1_000, status = SessionStatus.FAILED)
        insert("compl-old", createdAt = 1_000, status = SessionStatus.COMPLETED)

        val deleted = dao.deleteCancelledOlderThan(cutoff = 5_000)

        assertEquals(1, deleted)
        assertNull("past-horizon CANCELLED deleted", dao.getById("cxl-old"))
        assertNotNull("in-window CANCELLED kept", dao.getById("cxl-fresh"))
        assertNotNull("FAILED kept forever", dao.getById("failed-old"))
        assertNotNull("COMPLETED untouched", dao.getById("compl-old"))
        assertTrue("re-run is a no-op", dao.deleteCancelledOlderThan(cutoff = 5_000) == 0)
    }
}
