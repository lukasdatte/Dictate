package net.devemperor.dictate.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-20: IME-side `ServiceConnection` callback contract.
 *
 * The IME-side `pipelineConnection` (an anonymous inner class on
 * [DictateInputMethodService]) wires four callbacks to the
 * [DictatePipelineService.LocalBinder] singleton. The full IME service is
 * a 2000-LOC Java class with deep view dependencies — Option B per the
 * audit: exercise an isolated synthetic ServiceConnection that mirrors
 * the IME-side behaviour, so the four callback contracts have unit-test
 * coverage without inflating the IME.
 *
 * Each test pins one observable invariant:
 *  - `onServiceConnected` stores the binder (same-process cast).
 *  - `onServiceDisconnected` clears the binder reference.
 *  - `onBindingDied` releases + re-binds (Spec 1 §11.3.2 rebind path).
 *  - `onNullBinding` leaves the binder null + logs the regression.
 *
 * @see DictatePipelineService.LocalBinder
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PipelineServiceConnectionContractTest {

    /**
     * Mirrors the IME-side `pipelineConnection` semantics exactly — the
     * IME's anonymous class lives inside the 2000-LOC service, so this
     * fake re-implements the same four-callback contract for unit-test
     * purposes. If the IME-side implementation changes, this fake MUST
     * be kept in lockstep so the regression-guard remains accurate.
     */
    private class FakePipelineConnection(
        private val context: Context,
    ) : ServiceConnection {
        var binder: DictatePipelineService.LocalBinder? = null
            private set
        var rebindRequestCount: Int = 0
            private set
        var nullBindingObserved: Boolean = false
            private set

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? DictatePipelineService.LocalBinder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }

        override fun onBindingDied(name: ComponentName?) {
            try {
                context.unbindService(this)
            } catch (ignored: IllegalArgumentException) {
                // already unbound — no-op
            }
            binder = null
            val intent = Intent(context, DictatePipelineService::class.java)
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
            rebindRequestCount++
        }

        override fun onNullBinding(name: ComponentName?) {
            nullBindingObserved = true
            // binder stays null — defensive (IME-side logs E/ here).
        }
    }

    @Test
    fun onServiceConnected_storesBinder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = FakePipelineConnection(context)

        // Construct a real LocalBinder by building the service through
        // Robolectric — onBind returns the singleton.
        val serviceController = Robolectric.buildService(DictatePipelineService::class.java)
        serviceController.create()
        val realBinder = serviceController.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        connection.onServiceConnected(ComponentName(context, DictatePipelineService::class.java), realBinder)

        assertSame(
            "onServiceConnected must store the cast binder reference",
            realBinder,
            connection.binder,
        )

        serviceController.destroy()
    }

    @Test
    fun onServiceDisconnected_clearsBinder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = FakePipelineConnection(context)
        val serviceController = Robolectric.buildService(DictatePipelineService::class.java)
        serviceController.create()
        val binder = serviceController.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        // Establish + then drop the binding.
        connection.onServiceConnected(ComponentName(context, DictatePipelineService::class.java), binder)
        assertNotNull("Pre-condition: binder is set", connection.binder)

        connection.onServiceDisconnected(ComponentName(context, DictatePipelineService::class.java))

        assertNull(
            "onServiceDisconnected must null the binder so subsequent dispatches hit the not-ready guard",
            connection.binder,
        )

        serviceController.destroy()
    }

    @Test
    fun onBindingDied_attemptsRebind_andClearsBinder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = FakePipelineConnection(context)
        val serviceController = Robolectric.buildService(DictatePipelineService::class.java)
        serviceController.create()
        val binder = serviceController.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        // Establish first (so unbindService inside onBindingDied has
        // something to unbind — though IllegalArgumentException is also
        // tolerated per the production code).
        connection.onServiceConnected(ComponentName(context, DictatePipelineService::class.java), binder)
        // The Robolectric shadow doesn't actually invoke onServiceConnected
        // through context.bindService, but the connection still tracks the
        // assignment from above so we can assert the post-rebind reset.
        assertEquals(0, connection.rebindRequestCount)

        connection.onBindingDied(ComponentName(context, DictatePipelineService::class.java))

        assertNull(
            "onBindingDied must clear the stale binder reference before rebinding",
            connection.binder,
        )
        assertEquals(
            "onBindingDied must trigger exactly one rebind request",
            1,
            connection.rebindRequestCount,
        )

        serviceController.destroy()
    }

    @Test
    fun onNullBinding_keepsBinderNull_andFlagsRegression() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = FakePipelineConnection(context)

        assertFalse(
            "Pre-condition: nullBindingObserved should start as false",
            connection.nullBindingObserved,
        )
        assertNull("Pre-condition: binder must be null", connection.binder)

        connection.onNullBinding(ComponentName(context, DictatePipelineService::class.java))

        assertTrue(
            "onNullBinding must flag the regression so the IME log captures it",
            connection.nullBindingObserved,
        )
        assertNull(
            "onNullBinding must NOT populate the binder — production code logs E/ instead",
            connection.binder,
        )
    }
}
