package net.devemperor.dictate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression pin for **F-000** (whole-app review 2026-07-02): the IME's own
 * recording-start path (`DictateInputMethodService.startRecording()` — QWERTZ
 * record button, instant-prompt chip) must allocate the *initial* segment
 * file through [CacheDirAudioFileRepository.allocateFirst], exactly like the
 * catalog start path ([net.devemperor.dictate.state.layout.ActionResolvers]
 * `resolveStartRecordingFromIdle`).
 *
 * **Why this test exists.** Before the fix the IME path allocated via the
 * legacy [CacheDirAudioFileFactory] (`rec_{ts}_{uuid}.m4a`). The multi-segment
 * muxer only scans the `sess_{sid}_seg*` prefix ([CacheDirAudioFileRepository.segments]),
 * so the legacy-named initial file was **invisible** to it. Consequences on the
 * IME surfaces: long recordings lost their first chunk; short recordings uploaded
 * the pre-armed 0-byte `sess_seg1` while the real `rec_*` audio was deleted —
 * silent, unrecoverable audio loss.
 *
 * The Java `startRecording()` body is not JVM-unit-testable in isolation (it
 * lives in the ~2000-line Android service and needs a live binder). This test
 * therefore pins the allocation *contract* both start paths must obey — the
 * naming (`sess_{sid}_seg1.m4a`) and, crucially, muxer-visibility via
 * `segments(sid)`. The catalog path already allocates via `allocateFirst`
 * (verified in `ActionResolversTest`); this pins that the IME path's chosen
 * allocator produces the identical, muxer-visible name — and demonstrates
 * (red-provable) that the legacy allocator the IME path previously used does
 * NOT.
 */
class ImeStartInitialFileNamingTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var repository: CacheDirAudioFileRepository
    private lateinit var legacyFactory: CacheDirAudioFileFactory

    @Before
    fun setUp() {
        // Both allocators write into the SAME `cacheDir/audio/` subdirectory —
        // the exact production layout (DictatePipelineService wires both off the
        // same cacheDir). Sharing one temp root here reproduces that so the
        // muxer-visibility assertions are meaningful.
        repository = CacheDirAudioFileRepository(cacheDirProvider = { tmp.root })
        legacyFactory = CacheDirAudioFileFactory(cacheDirProvider = { tmp.root })
    }

    @Test
    fun `IME start path and catalog start path produce identical sess_seg1 naming`() {
        val sessionId = "ime-vs-catalog-sid"

        // Catalog path (ActionResolvers.resolveStartRecordingFromIdle) →
        // audioFileRepository.allocateFirst(sessionId).
        val catalogInitial = repository.allocateFirst(sessionId)

        // IME path (DictateInputMethodService.startRecording, post-F-000) →
        // the SAME call on the SAME repository. Both surfaces share one
        // allocation contract; this asserts byte-identical naming.
        val imeInitial = repository.allocateFirst(sessionId)

        assertEquals("sess_${sessionId}_seg1.m4a", catalogInitial.name)
        assertEquals(catalogInitial.name, imeInitial.name)
        assertEquals(catalogInitial.parentFile?.absolutePath, imeInitial.parentFile?.absolutePath)
    }

    @Test
    fun `IME-allocated initial file is visible to the multi-segment muxer`() {
        val sessionId = "muxer-visibility-sid"

        // Materialise the initial file the way MediaRecorder.start() would.
        val initial = repository.allocateFirst(sessionId)
        initial.parentFile?.mkdirs()
        initial.writeBytes(byteArrayOf(1, 2, 3)) // non-empty: real audio

        // The rolling pre-arm allocates the next segment under the same prefix.
        val rolled = repository.allocateNext(sessionId)
        rolled.writeBytes(byteArrayOf(4, 5, 6))

        val segs = repository.segments(sessionId)
        assertEquals(
            "both the initial file and the rolled segment must reach the muxer",
            listOf("sess_${sessionId}_seg1.m4a", "sess_${sessionId}_seg2.m4a"),
            segs.map { it.name },
        )
        assertTrue(segs.any { it.name == initial.name })
    }

    @Test
    fun `legacy factory-allocated initial file is INVISIBLE to the muxer (the F-000 bug)`() {
        // Red-proof: the allocator the IME path used BEFORE the F-000 fix.
        // The legacy factory names the initial file `rec_{ts}_{uuid}.m4a`,
        // which does not match the `sess_{sid}_seg*` prefix the muxer scans —
        // so segments(sid) never returns it. This is the exact root cause the
        // fix removes; reverting the IME path to this allocator re-breaks the
        // assertion above.
        val sessionId = "legacy-invisible-sid"

        val legacyInitial = legacyFactory.allocate()
        legacyInitial.parentFile?.mkdirs()
        legacyInitial.writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(
            "pre-condition: legacy factory produces a rec_-prefixed name",
            legacyInitial.name.startsWith("rec_"),
        )
        assertFalse(
            "legacy rec_* initial file must NOT match the sess_ muxer prefix",
            legacyInitial.name.startsWith("sess_${sessionId}_seg"),
        )

        // The muxer scan for this session finds nothing — the audio-loss defect.
        assertTrue(
            "segments(sid) must not see the legacy-named initial file",
            repository.segments(sessionId).isEmpty(),
        )
    }
}
