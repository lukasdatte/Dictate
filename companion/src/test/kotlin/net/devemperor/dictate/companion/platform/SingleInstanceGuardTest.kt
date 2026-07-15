package net.devemperor.dictate.companion.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The lock and the signal are the whole guard, and both are exercised here in one JVM.
 *
 * The two-processes case is faked the honest way: a second [SingleInstanceGuard.acquire] in the same
 * JVM hits `OverlappingFileLockException`, which the guard maps to the same "already running" outcome
 * a real second process's `tryLock() == null` produces — so the branch under test is the production
 * one. The show-signal then travels over the real loopback socket to the primary's real listener.
 */
class SingleInstanceGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun firstAcquireIsPrimary_andReleaseLetsTheNextOneWin() {
        val first = SingleInstanceGuard.acquire(tmp.root.toPath())
        assertTrue("first launch must own the instance", first is SingleInstanceGuard.Acquisition.Primary)

        (first as SingleInstanceGuard.Acquisition.Primary).guard.release()

        val second = SingleInstanceGuard.acquire(tmp.root.toPath())
        assertTrue("after release the lock is free again", second is SingleInstanceGuard.Acquisition.Primary)
        (second as SingleInstanceGuard.Acquisition.Primary).guard.release()
    }

    @Test
    fun secondAcquireIsAlreadyRunning_andItsShowRequestReachesThePrimary() {
        val first = SingleInstanceGuard.acquire(tmp.root.toPath()) as SingleInstanceGuard.Acquisition.Primary
        val shown = CountDownLatch(1)
        first.guard.onShowRequested { shown.countDown() }

        val second = SingleInstanceGuard.acquire(tmp.root.toPath())
        assertTrue("a second launch must not become primary", second is SingleInstanceGuard.Acquisition.AlreadyRunning)

        val delivered = (second as SingleInstanceGuard.Acquisition.AlreadyRunning).requestShow()
        assertTrue("the show request must be delivered to the primary", delivered)
        assertTrue("the primary must receive the show signal", shown.await(3, TimeUnit.SECONDS))

        first.guard.release()
    }

    @Test
    fun showRequestAgainstNoPrimary_reportsUndelivered() {
        // A port file pointing at a port nobody listens on — the "primary vanished" shape.
        val portFile = tmp.root.toPath().resolve("companion.signal-port")
        java.nio.file.Files.writeString(portFile, "1") // port 1: not bindable, not listening

        assertFalse("signalling a dead primary must report failure, not throw", SingleInstanceGuard.signalPrimary(portFile))
    }
}
