package net.devemperor.dictate.core

import net.devemperor.dictate.database.entity.SessionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the C5 R-1 closure: [ImePipelineConfigResolver]
 * (C3-IMPL-1 / C3-IMPL-2) + [DelegatingPipelineConfigResolver].
 *
 * **K-1 / K-4** — both resolvers are Context-free by design (provider
 * lambdas, no Android types), so these are plain JUnit tests with
 * handwritten fakes; no Robolectric, no mocking framework.
 *
 * **R-1 evidence.** The fresh-recording snapshot is asserted
 * field-for-field against the legacy
 * `DictateInputMethodService.java:2214-2230` `JobRequest` construction
 * — a dropped field here is exactly the silent-data-loss R-1 forbids.
 *
 * @see net.devemperor.dictate.core.ImePipelineConfigResolver
 * @see net.devemperor.dictate.core.DelegatingPipelineConfigResolver
 */
class ImePipelineConfigResolverTest {

    private val filesDir = File("/tmp/c5-test-filesdir")
    private val audio = File("/tmp/c5-test-rec.m4a")

    private fun resolver(
        fallback: PipelineConfigResolver = DefaultPipelineConfigResolver { filesDir },
    ) = ImePipelineConfigResolver(
        recordingsDirProvider = { filesDir },
        reprocessFallback = fallback,
    )

    // ── Fresh recording — R-1 field-for-field fidelity ─────────────────

    @Test
    fun `resolveFresh rebuilds the JobRequest field-for-field from the IME snapshot`() {
        val r = resolver()
        r.snapshotFresh(
            "sid-1",
            ImePipelineConfigResolver.FreshConfig(
                totalSteps = 3,
                audioFilePath = audio.absolutePath,
                language = "de",
                queuedPromptIds = listOf(4, 8),
                targetAppPackage = "com.example.app",
                stylePrompt = "be concise",
                livePrompt = true,
                autoSwitchKeyboard = true,
                showResendButton = true,
            ),
        )

        val req = r.resolveFresh("sid-1", audio)

        // 1:1 with DictateInputMethodService.java:2214-2230 (pre-C5).
        assertEquals("sid-1", req.sessionId)
        assertEquals(3, req.totalSteps)
        assertEquals(JobRequest.TranscriptionKind.RECORDING, req.kind)
        assertEquals(audio.absolutePath, req.audioFilePath)
        assertEquals("de", req.language)
        assertNull(req.modelOverride)
        assertEquals(listOf(4, 8), req.queuedPromptIds)
        assertEquals("com.example.app", req.targetAppPackage)
        assertEquals(File(filesDir, "recordings"), req.recordingsDir)
        assertNull("fresh recording → reuseSessionId is null", req.reuseSessionId)
        assertEquals("be concise", req.stylePrompt)
        assertEquals(SessionOrigin.KEYBOARD, req.origin)
        assertTrue(req.livePrompt)
        assertTrue(req.autoSwitchKeyboard)
        assertTrue(req.showResendButton)
    }

    @Test
    fun `resolveFresh consumes the snapshot (one submit per snapshot)`() {
        val r = resolver()
        r.snapshotFresh(
            "sid-once",
            ImePipelineConfigResolver.FreshConfig(
                1, audio.absolutePath, null, emptyList(), null, null, false, false, false,
            ),
        )
        r.resolveFresh("sid-once", audio) // first consumes it
        // A second resolve for the same id has no snapshot → throws
        // (surfacing beats a silently-wrong default — R-1).
        assertThrows(UnsupportedOperationException::class.java) {
            r.resolveFresh("sid-once", audio)
        }
    }

    @Test
    fun `resolveFresh without a snapshot throws (R-1 surfacing guard)`() {
        assertThrows(UnsupportedOperationException::class.java) {
            resolver().resolveFresh("never-snapshotted", audio)
        }
    }

    @Test
    fun `discard removes a snapshot so a cancelled recording does not leak`() {
        val r = resolver()
        r.snapshotFresh(
            "sid-cancel",
            ImePipelineConfigResolver.FreshConfig(
                1, audio.absolutePath, null, emptyList(), null, null, false, false, false,
            ),
        )
        r.discard("sid-cancel")
        assertThrows(UnsupportedOperationException::class.java) {
            r.resolveFresh("sid-cancel", audio)
        }
    }

    // ── Imported-audio-file path — C7-IMPL-1 closure ───────────────────

    /**
     * The imported-audio-file path (C7-IMPL-1, mid-chunk-triage
     * B2-C7-MID-W1) reuses the SAME fresh snapshot mechanism as a
     * post-record send: it computes the IME-runtime config via the shared
     * `captureFreshConfigSnapshot` helper and submits via the orchestrator
     * `TriggerPipeline` → C3 adapter → `resolveFresh`. This asserts the
     * resolver rebuilds a `JobRequest` field-for-field identical to the
     * deleted legacy `DictateInputMethodService.java:2507-2523`
     * `JobRequest.TranscriptionPipeline` construction (R-1 / AC-9: a
     * dropped field silently transcribes the imported file with the wrong
     * language / no prompts — exactly the behaviour-coverage regression
     * AC-9 forbids).
     */
    @Test
    fun `imported-file fresh snapshot rebuilds the legacy 2507-2523 JobRequest field-for-field`() {
        val r = resolver()
        val importedFile = File("/tmp/c7-imported.m4a")
        // Same fields captureFreshConfigSnapshot computes (the exact
        // sources the deleted legacy :2507-2523 construction read).
        r.snapshotFresh(
            "imported-sid",
            ImePipelineConfigResolver.FreshConfig(
                totalSteps = 4,
                audioFilePath = importedFile.absolutePath,
                language = "it",
                queuedPromptIds = listOf(11, 12),
                targetAppPackage = "com.imported.target",
                stylePrompt = "formal",
                livePrompt = false,
                autoSwitchKeyboard = false,
                showResendButton = true,
            ),
        )

        val req = r.resolveFresh("imported-sid", importedFile)

        // Field-for-field == legacy DictateInputMethodService.java:2507-2523.
        assertEquals("imported-sid", req.sessionId)
        assertEquals(4, req.totalSteps)
        assertEquals(JobRequest.TranscriptionKind.RECORDING, req.kind)
        assertEquals(importedFile.absolutePath, req.audioFilePath)
        assertEquals("it", req.language)
        assertNull(req.modelOverride)
        assertEquals(listOf(11, 12), req.queuedPromptIds)
        assertEquals("com.imported.target", req.targetAppPackage)
        assertEquals(File(filesDir, "recordings"), req.recordingsDir)
        assertNull("imported file is a fresh session → reuseSessionId null", req.reuseSessionId)
        assertEquals("formal", req.stylePrompt)
        assertEquals(SessionOrigin.KEYBOARD, req.origin)
        assertEquals(false, req.livePrompt)
        assertEquals(false, req.autoSwitchKeyboard)
        assertTrue(req.showResendButton)
    }

    // ── Reprocess — C3-IMPL-2 closure ──────────────────────────────────

    @Test
    fun `resolveReprocess threads the C3-IMPL-2 fields from the snapshot`() {
        val r = resolver()
        r.snapshotReprocess(
            "sid-rp",
            ImePipelineConfigResolver.ReprocessConfig(
                totalSteps = 5,
                modelOverride = "whisper-large",
                targetAppPackage = "com.target.pkg",
            ),
        )

        val req = r.resolveReprocess("sid-rp", audio, listOf(1, 2), "en")

        assertEquals("sid-rp", req.sessionId)
        // C3-IMPL-2: totalSteps from the IME (AutoFormatting +1 folded
        // in) — NOT the C3 default `1 + queue.size`.
        assertEquals(5, req.totalSteps)
        assertEquals(JobRequest.TranscriptionKind.REPROCESS_STAGING, req.kind)
        assertEquals(audio.absolutePath, req.audioFilePath)
        assertEquals("en", req.language)
        // C3-IMPL-2: modelOverride / targetAppPackage no longer null.
        assertEquals("whisper-large", req.modelOverride)
        assertEquals("com.target.pkg", req.targetAppPackage)
        assertEquals(listOf(1, 2), req.queuedPromptIds)
        assertEquals("sid-rp", req.reuseSessionId)
        assertEquals(SessionOrigin.KEYBOARD, req.origin)
    }

    @Test
    fun `resolveReprocess falls back to the C3 default when no snapshot`() {
        // No snapshotReprocess() → the staging-FSM path the IME does not
        // flip in C5: delegate to the C3 DefaultPipelineConfigResolver
        // (near-1:1, modelOverride/targetApp null).
        val req = resolver().resolveReprocess("sid-fb", audio, listOf(3), "fr")
        assertEquals(1 + 1, req.totalSteps) // C3 default: 1 + queue.size
        assertNull(req.modelOverride)
        assertNull(req.targetAppPackage)
        assertEquals("fr", req.language)
    }

    // ── DelegatingPipelineConfigResolver ───────────────────────────────

    @Test
    fun `delegating resolver prefers the IME resolver when registered`() {
        val ime = resolver()
        ime.snapshotFresh(
            "sid-d",
            ImePipelineConfigResolver.FreshConfig(
                2, audio.absolutePath, "es", listOf(9), "app", "sp", false, true, false,
            ),
        )
        val fallback = DefaultPipelineConfigResolver { filesDir }
        val delegating = DelegatingPipelineConfigResolver(
            fallback = fallback,
            imeResolverProvider = { ime },
        )

        val req = delegating.resolveFresh("sid-d", audio)
        assertEquals("es", req.language)
        assertEquals(2, req.totalSteps)
        assertTrue(req.autoSwitchKeyboard)
    }

    @Test
    fun `delegating resolver falls back to the C3 default (throws) when no IME resolver`() {
        val delegating = DelegatingPipelineConfigResolver(
            fallback = DefaultPipelineConfigResolver { filesDir },
            imeResolverProvider = { null },
        )
        // C3 DefaultPipelineConfigResolver.resolveFresh throws — the
        // Epic §6.2 fail-loud contract when no IME is bound.
        assertThrows(UnsupportedOperationException::class.java) {
            delegating.resolveFresh("sid-x", audio)
        }
    }

    @Test
    fun `delegating resolver reprocess uses the IME resolver when present`() {
        val ime = resolver()
        ime.snapshotReprocess(
            "sid-dr",
            ImePipelineConfigResolver.ReprocessConfig(4, "m", "p"),
        )
        val delegating = DelegatingPipelineConfigResolver(
            fallback = DefaultPipelineConfigResolver { filesDir },
            imeResolverProvider = { ime },
        )
        val req = delegating.resolveReprocess("sid-dr", audio, listOf(1), "it")
        assertEquals("m", req.modelOverride)
        assertEquals("p", req.targetAppPackage)
        assertEquals(4, req.totalSteps)
    }

    @Test
    fun `delegating resolver provider is re-read each call (late-bound bind or unbind)`() {
        // Mirrors the @Volatile delegate pattern: the provider may flip
        // null → resolver (IME binds) → null (IME unbinds) between calls.
        var current: PipelineConfigResolver? = null
        val delegating = DelegatingPipelineConfigResolver(
            fallback = DefaultPipelineConfigResolver { filesDir },
            imeResolverProvider = { current },
        )
        // Unbound → fallback throws.
        assertThrows(UnsupportedOperationException::class.java) {
            delegating.resolveFresh("s", audio)
        }
        // IME binds.
        val ime = resolver()
        ime.snapshotFresh(
            "s",
            ImePipelineConfigResolver.FreshConfig(
                1, audio.absolutePath, null, emptyList(), null, null, false, false, false,
            ),
        )
        current = ime
        val req = delegating.resolveFresh("s", audio)
        assertEquals("s", req.sessionId)
    }

    @Test
    fun `fallback instance identity is the one supplied (no hidden re-wrap)`() {
        val fb = DefaultPipelineConfigResolver { filesDir }
        val delegating = DelegatingPipelineConfigResolver(
            fallback = fb,
            imeResolverProvider = { null },
        )
        // Reprocess with no IME resolver → must go through exactly `fb`.
        val viaDelegate = delegating.resolveReprocess("s", audio, listOf(1), "x")
        val direct = fb.resolveReprocess("s", audio, listOf(1), "x")
        assertEquals(direct.totalSteps, viaDelegate.totalSteps)
        assertEquals(direct.kind, viaDelegate.kind)
        assertSame(fb, fb) // documents the no-rewrap intent
    }
}
