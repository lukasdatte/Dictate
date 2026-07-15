package net.devemperor.dictate.companion

import kotlinx.coroutines.CancellationException
import net.devemperor.dictate.companion.domain.CompanionSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException

/**
 * The message the [BootFailed] screen shows must be actionable — this is the regression guard for the
 * bug where a failed boot showed an endless spinner instead of a reason. The port-conflict shapes
 * (a [BindException] in the chain, or the [CancellationException] Ktor throws when it cancels its
 * start job over a taken port) must both resolve to the "port in use" hint; everything else keeps its
 * own message.
 */
class BootFailureMessageTest {

    @Test
    fun bindExceptionInChain_pointsAtThePort() {
        val failure = CompanionBootFailure(BindException("Address already in use"))

        val message = describeBootFailure(failure)

        assertTrue(message, message.contains(CompanionSettings.DEFAULT_PORT.toString()))
    }

    @Test
    fun ktorStartCancellation_pointsAtThePort() {
        // What a port-conflict actually looks like from Ktor CIO: the engine cancels its start job.
        val failure = CompanionBootFailure(CancellationException("LazyStandaloneCoroutine is cancelling"))

        val message = describeBootFailure(failure)

        assertTrue(message, message.contains(CompanionSettings.DEFAULT_PORT.toString()))
    }

    @Test
    fun otherFailure_keepsItsOwnMessage() {
        val failure = CompanionBootFailure(IllegalStateException("database file is corrupt"))

        val message = describeBootFailure(failure)

        assertTrue(message, message.contains("database file is corrupt"))
    }
}
