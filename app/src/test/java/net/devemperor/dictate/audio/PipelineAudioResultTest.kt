package net.devemperor.dictate.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the [PipelineAudioResult] sealed class — the
 * sub-type construction contracts, esp. the [PipelineAudioResult.PartialRecovery]
 * non-empty-ignored-list invariant.
 *
 * MediaMuxer-driven Partial-Recovery scenarios (real-file concat with
 * a deliberately corrupted segment) live in `app/src/androidTest/`
 * (planned `CacheDirAudioFileRepositoryConcatTest`).
 */
class PipelineAudioResultTest {

    private val dummyFile = File("dummy-not-touched.m4a")

    @Test
    fun `Complete exposes the file unchanged`() {
        val result = PipelineAudioResult.Complete(dummyFile)
        assertEquals(dummyFile, result.file)
    }

    @Test
    fun `PartialRecovery accepts a non-empty ignored list`() {
        val result = PipelineAudioResult.PartialRecovery(
            file = dummyFile,
            ignoredSegmentIndices = listOf(2, 4),
            estimatedLostSeconds = 60.0,
        )
        assertEquals(dummyFile, result.file)
        assertEquals(listOf(2, 4), result.ignoredSegmentIndices)
        assertEquals(60.0, result.estimatedLostSeconds, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PartialRecovery with empty ignored list throws`() {
        PipelineAudioResult.PartialRecovery(
            file = dummyFile,
            ignoredSegmentIndices = emptyList(),
            estimatedLostSeconds = 0.0,
        )
    }

    @Test
    fun `Complete and PartialRecovery are distinguishable via type check`() {
        val complete: PipelineAudioResult = PipelineAudioResult.Complete(dummyFile)
        val partial: PipelineAudioResult = PipelineAudioResult.PartialRecovery(
            file = dummyFile,
            ignoredSegmentIndices = listOf(0),
            estimatedLostSeconds = 30.0,
        )
        assertTrue(complete is PipelineAudioResult.Complete)
        assertTrue(partial is PipelineAudioResult.PartialRecovery)
        assertTrue(complete !is PipelineAudioResult.PartialRecovery)
        assertTrue(partial !is PipelineAudioResult.Complete)
    }
}
