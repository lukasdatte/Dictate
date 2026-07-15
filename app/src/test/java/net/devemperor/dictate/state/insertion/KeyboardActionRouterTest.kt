package net.devemperor.dictate.state.insertion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exclusivity contract of the router (§4.1, Akzeptanzkriterium 1): in PC-mode **only** the PC
 * sink is touched, otherwise **only** the local sink — a keyboard action never reaches both fields.
 */
class KeyboardActionRouterTest {

    private class RecordingSink(val name: String) : KeyboardActionSink {
        val received = mutableListOf<KeyboardAction>()
        override fun submit(action: KeyboardAction): SubmitResult {
            received += action
            return SubmitResult.Accepted
        }
    }

    private val local = RecordingSink("local")
    private val pc = RecordingSink("pc")
    private var pcMode = false
    private val router = KeyboardActionRouter(local, pc, pcModeActive = { pcMode })

    private val action = KeyboardAction.Control(ControlOp.DeleteGrapheme)

    @Test
    fun inLocalMode_onlyTheLocalSinkSeesIt() {
        pcMode = false

        router.submit(action)

        assertEquals(listOf(action), local.received)
        assertTrue("PC sink must be untouched in local mode", pc.received.isEmpty())
    }

    @Test
    fun inPcMode_onlyThePcSinkSeesIt() {
        pcMode = true

        router.submit(action)

        assertEquals(listOf(action), pc.received)
        assertTrue("local IME must be untouched in PC mode", local.received.isEmpty())
    }

    @Test
    fun theModeIsReadPerSubmit_soTogglingTakesEffectImmediately() {
        pcMode = false
        router.submit(action)
        pcMode = true
        router.submit(action)

        assertEquals(1, local.received.size)
        assertEquals(1, pc.received.size)
    }

    @Test
    fun theSinkResultIsReturnedVerbatim() {
        pcMode = true
        assertSame(SubmitResult.Accepted, router.submit(action))
    }
}
