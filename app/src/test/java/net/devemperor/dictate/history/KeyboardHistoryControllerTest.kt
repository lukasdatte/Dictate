package net.devemperor.dictate.history

import androidx.paging.PagingData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.devemperor.dictate.database.entity.SessionEntity
import net.devemperor.dictate.testutil.EmptySessionDao
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lifecycle tests for [KeyboardHistoryController] (ADR-0014 §9b) — the Paging
 * scope/collector state machine, independent of the Paging internals (the
 * injected flow never emits, so `submitData` is never reached; we assert the
 * collector job's liveness).
 *
 * Robolectric only because constructing the real [KeyboardHistoryAdapter]
 * (a `RecyclerView.Adapter`) touches Android observer internals; the collector
 * logic itself is plain coroutines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardHistoryControllerTest {

    /** A pager whose flow stays hot but never emits — the collector stays alive. */
    private class NeverPager :
        KeyboardHistoryPager(EmptySessionDao) {
        override fun flow(scope: CoroutineScope): Flow<PagingData<SessionEntity>> =
            MutableSharedFlow()
    }

    private fun controller(dispatcher: CoroutineDispatcher) =
        KeyboardHistoryController(NeverPager(), KeyboardHistoryAdapter(NoopCallback), dispatcher)

    private object NoopCallback : KeyboardHistoryAdapter.Callback {
        override fun onInsert(session: SessionEntity, pending: Boolean) = Unit
        override fun onSendToWindows(session: SessionEntity, pending: Boolean) = Unit
        override fun onOpenDetail(session: SessionEntity) = Unit
    }

    @Test
    fun `open starts the collector, close stops it, reopen restarts`() = runTest {
        val c = controller(UnconfinedTestDispatcher(testScheduler))
        c.onViewCreated()
        assertFalse("not collecting before open", c.isCollecting())

        c.onPanelOpen()
        assertTrue("collecting after open", c.isCollecting())

        c.onPanelClosed()
        assertFalse("not collecting after close", c.isCollecting())

        c.onPanelOpen()
        assertTrue("collecting after reopen", c.isCollecting())

        c.onViewDestroyed()
        assertFalse("not collecting after destroy", c.isCollecting())
    }

    @Test
    fun `open is idempotent (no double collect)`() = runTest {
        val c = controller(UnconfinedTestDispatcher(testScheduler))
        c.onViewCreated()
        c.onPanelOpen()
        c.onPanelOpen()
        // Still exactly one live collection; a second call must not replace it.
        assertTrue(c.isCollecting())
        c.onViewDestroyed()
    }

    @Test
    fun `open before onViewCreated is a no-op`() = runTest {
        val c = controller(UnconfinedTestDispatcher(testScheduler))
        c.onPanelOpen() // no scope yet
        assertFalse(c.isCollecting())
    }
}
