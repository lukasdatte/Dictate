package net.devemperor.dictate.core

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric wiring test for the C11 [AudioFileFactory] composition
 * (Spec 1 §4.11.5.3).
 *
 * **What we verify:**
 *
 *  - `onCreate` constructs a real [CacheDirAudioFileFactory] (no longer
 *    the C7 stub from `PipelineServiceStubSubsystems`).
 *  - The factory is exposed via [DictatePipelineService.LocalBinder.audioFileFactory]
 *    so the IME's `startRecording` resolver can perform Pre-Dispatch
 *    allocation (R.2 Pure-Reducer invariant — Hardware-IO lives in the
 *    View-layer, not in the reducer).
 *  - `allocate()` returns a path under the application cache directory.
 *
 * The C7 path-shape (`/tmp/dictate-stub-audio.m4a`) would betray the
 * stub still being wired — these tests catch a regression of that swap.
 *
 * @see net.devemperor.dictate.core.DictatePipelineService
 * @see net.devemperor.dictate.core.CacheDirAudioFileFactory
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictatePipelineServiceAudioFileFactoryWiringTest {

    private val controller = Robolectric.buildService(DictatePipelineService::class.java)

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: Throwable) {
        }
        JobExecutor.resetForTest()
    }

    @Test
    fun `onCreate exposes a real CacheDirAudioFileFactory via the binder`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder
        val factory = binder.audioFileFactory

        assertNotNull("AudioFileFactory must be exposed via the binder", factory)
        assertTrue(
            "binder.audioFileFactory should be a CacheDirAudioFileFactory (C11 swap)",
            factory is CacheDirAudioFileFactory,
        )
    }

    @Test
    fun `binder allocate() returns a path under the application cache directory`() {
        controller.create()
        val binder = controller.get().onBind(Intent()) as DictatePipelineService.LocalBinder

        val cacheRoot = controller.get().applicationContext.cacheDir.absolutePath
        val allocated = binder.audioFileFactory.allocate()

        assertTrue(
            "allocated path '${allocated.absolutePath}' must live under cacheDir '$cacheRoot'",
            allocated.absolutePath.startsWith(cacheRoot),
        )
        // The C7 stub returned a /tmp/ path — assert we're definitely not
        // in stub-land any more.
        assertTrue(
            "C7-stub regression: path should not contain '/tmp/dictate-stub-audio'",
            !allocated.absolutePath.contains("dictate-stub-audio"),
        )
    }
}
