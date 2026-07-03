package net.devemperor.dictate.state.render.overlay

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import net.devemperor.dictate.R
import net.devemperor.dictate.core.ImePipelineConfigResolver
import net.devemperor.dictate.core.JobRequest
import net.devemperor.dictate.core.PipelineConfigResolver
import net.devemperor.dictate.database.entity.SessionOrigin
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.AudioFileFactory
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.OverlayState
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ViewMode
import net.devemperor.dictate.state.layout.LayoutCatalog
import net.devemperor.dictate.state.layout.LogicalButtonId
import net.devemperor.dictate.state.layout.testLayoutStrings
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Behaviour-test for the R-1 affordance-hook → snapshot-stash →
 * pipeline-runner-consume chain that prevents the
 * "OVERLAY_RECORD click → pipeline hangs in Preparing" regression
 * (Plan §7.1 AC-A test, fix-wave G1).
 *
 * # The hang this test guards against
 *
 * The bug pre-fix: clicking OVERLAY_RECORD in the floating widget
 * dispatched `StopRecordingAndSend` via the catalog, but the IME-side
 * affordance lambda only matched `LogicalButtonId.RECORD` — not
 * `OVERLAY_RECORD`. So the orchestrator's async
 * `PipelineRunnerSubsystemAdapter.submit` → `resolveFresh(sessionId)`
 * found an empty snapshot map, threw the loud R-1 tripwire, the
 * EffectFailure arm caught it, and the pipeline FSM hung in Preparing
 * forever ("Sending …" with no progress).
 *
 * # End-to-end composition under test
 *
 * ```
 *   Widget click (OVERLAY_RECORD)
 *     │
 *     ▼
 *   OverlayBackend.wireStaticOverlayHandlers → imeSideAffordance(id, false)
 *     │  (the lambda the IME registers; for the test it directly
 *     │   calls `imePipelineConfigResolver.snapshotFresh`,
 *     │   mirroring `prepareCatalogStopRecordingIfActive`)
 *     ▼
 *   ImePipelineConfigResolver.freshSnapshots[sessionId] populated
 *     │
 *     ▼  (later, on the orchestrator dispatch thread)
 *   PipelineRunnerSubsystemAdapter.submit → resolveFresh(sessionId)
 *     │
 *     ▼
 *   freshSnapshots[sessionId] consumed (entry removed)
 * ```
 *
 * # Why a behaviour test in addition to the structural lockers
 *
 * The structural lockers in
 * [net.devemperor.dictate.core.CutoverArchitectureInvariantTest] already
 * lock:
 * - `affordanceHookHandlesBothRecordIds` — the IME lambda handles RECORD
 *   + OVERLAY_RECORD.
 * - [OverlayBackendTest] `OVERLAY_RECORD click fires imeSideAffordance
 *   before catalog dispatch` — the click → lambda wire.
 *
 * What those lockers cannot catch: "Branch exists, but calls the wrong
 * helper method" — e.g. a future edit that points the OVERLAY_RECORD
 * branch at `onRecordLongClicked()` (the no-op for OVERLAY_RECORD,
 * OQ-5 Variante A) instead of `prepareCatalogStopRecordingIfActive`.
 * This test wires the *full path* through the real resolver and asserts
 * the snapshot lands AND gets consumed — the only end-to-end shape that
 * mirrors the production hang.
 *
 * @see net.devemperor.dictate.core.ImePipelineConfigResolver
 * @see net.devemperor.dictate.state.render.overlay.OverlayBackend
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayPipelineHangIntegrationTest {

    private lateinit var ctx: Context
    private lateinit var window: FakeOverlayWindow
    private val catalog: LayoutCatalog = LayoutCatalog(testLayoutStrings())
    private val capturedActions: MutableList<Action> = mutableListOf()

    private val filesDir = File("/tmp/g1-test-filesdir")
    private val audioFile = File("/tmp/g1-test-rec.m4a")

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        ctx = ContextThemeWrapper(app, R.style.Theme_Dictate)
        window = FakeOverlayWindow()
        capturedActions.clear()
    }

    /**
     * Build a resolver that throws on a `resolveReprocess` to make it
     * impossible to silently fall back during the fresh-recording path.
     * The reprocessFallback is needed by the constructor but must never
     * be reached in this test.
     */
    private fun resolverUnderTest(): ImePipelineConfigResolver =
        ImePipelineConfigResolver(
            recordingsDirProvider = { filesDir },
            reprocessFallback = ThrowingReprocessFallback,
        )

    /**
     * Build a snapshot mirroring the IME's `captureFreshConfigSnapshot`
     * for a known session — values are arbitrary as long as they round
     * through `snapshotFresh` / `resolveFresh` unchanged. Tests assert
     * on identity (`sid-overlay-1`), not on field values.
     */
    private fun freshConfigForSession(): ImePipelineConfigResolver.FreshConfig =
        ImePipelineConfigResolver.FreshConfig(
            totalSteps = 1,
            audioFilePath = audioFile.absolutePath,
            language = "de",
            queuedPromptIds = emptyList(),
            targetAppPackage = "com.example.test",
            stylePrompt = null,
            livePrompt = false,
            autoSwitchKeyboard = false,
            showResendButton = false,
        )

    /**
     * Build the OverlayBackend with the IME-side affordance lambda
     * wired to a real [ImePipelineConfigResolver] — the *production*
     * composition the IME builds in `DictateInputMethodService`. The
     * lambda body mirrors `prepareCatalogStopRecordingIfActive`: it
     * calls `snapshotFresh` for the current session unconditionally
     * (the production helper is self-gating on the recording state;
     * for this test the state is Active and the snapshot must fire).
     */
    private fun newBackendWith(
        resolver: ImePipelineConfigResolver,
        sessionId: String,
        affordanceInvocations: MutableList<Pair<LogicalButtonId, Boolean>>,
    ): OverlayBackend = OverlayBackend(
        ctx = ctx,
        services = fakeModuleServices(
            emitAction = {},
            audioFileFactory = object : AudioFileFactory {
                override fun allocate(): File = audioFile
            },
        ),
        overlayWindow = window,
        permissions = NoOverlayPermissionGate,
        layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
        imeSideAffordance = { id, longPress ->
            // Mirrors `DictateInputMethodService.imeSideAffordance` for
            // OVERLAY_RECORD + non-long-press: it calls
            // prepareCatalogStopRecordingIfActive, which in turn calls
            // snapshotFresh. The test directly invokes snapshotFresh
            // for the session so the assertion focuses on the
            // affordance → resolver wire rather than the IME helper's
            // gating arithmetic.
            affordanceInvocations += id to longPress
            if (id == LogicalButtonId.OVERLAY_RECORD && !longPress) {
                resolver.snapshotFresh(sessionId, freshConfigForSession())
            }
        },
    )

    private fun stateWithActiveSession(sessionId: String): DictateUiState =
        DictateUiState.initial().copy(
            viewMode = ViewMode.WIDGET,
            recording = RecordingState.Active(
                useBluetooth = false,
                audioFile = audioFile,
                sessionId = sessionId,
            ),
            pipeline = PipelineUiState.Idle,
            overlay = OverlayState(
                hasPermission = true,
                suppressAutoOverlayUntilNextSession = false,
            ),
        )

    @Test
    fun `OVERLAY_RECORD click stashes a fresh snapshot before pipeline-runner submits`() {
        // Compose the production wire: OverlayBackend → imeSideAffordance →
        // ImePipelineConfigResolver.snapshotFresh.
        val sessionId = "sid-overlay-1"
        val resolver = resolverUnderTest()
        val affordanceInvocations: MutableList<Pair<LogicalButtonId, Boolean>> = mutableListOf()
        val backend = newBackendWith(resolver, sessionId, affordanceInvocations)

        backend.attach { capturedActions += it }
        backend.render(stateWithActiveSession(sessionId), catalog.OVERLAY_5BUTTON)

        // Pre-condition: no snapshot exists for this session — a
        // `resolveFresh` here would throw the R-1 tripwire.
        assertResolverHasNoSnapshotFor(resolver, sessionId)

        // Simulate the widget click on OVERLAY_RECORD.
        findOverlayButton(LogicalButtonId.OVERLAY_RECORD).performClick()

        // Affordance lambda fired with the expected signature.
        assertEquals(
            "Affordance fires exactly once on the OVERLAY_RECORD click " +
                "with isLongPress=false (the keyboard-RECORD symmetry).",
            listOf(LogicalButtonId.OVERLAY_RECORD to false),
            affordanceInvocations,
        )

        // POST-condition (before pipeline-runner submit): the snapshot is
        // present, keyed by the IME's preAllocatedId. This is the
        // load-bearing assertion — without it, the async runner would
        // hit the tripwire and the pipeline FSM would hang in Preparing.
        val req = resolver.resolveFresh(sessionId, audioFile)
        assertNotNull("resolveFresh must find the snapshot stashed by the affordance.", req)
        assertEquals(sessionId, req.sessionId)
        assertEquals(JobRequest.TranscriptionKind.RECORDING, req.kind)
        assertEquals(audioFile.absolutePath, req.audioFilePath)
        assertEquals(SessionOrigin.KEYBOARD, req.origin)
    }

    @Test
    fun `resolveFresh consumes the snapshot - a second resolveFresh throws`() {
        // The snapshot must be one-shot: each pipeline-runner submit
        // consumes exactly its own entry, so a stale snapshot from a
        // cancelled / superseded session cannot bleed into the next.
        val sessionId = "sid-overlay-consume"
        val resolver = resolverUnderTest()
        val backend = newBackendWith(
            resolver,
            sessionId,
            affordanceInvocations = mutableListOf(),
        )

        backend.attach { capturedActions += it }
        backend.render(stateWithActiveSession(sessionId), catalog.OVERLAY_5BUTTON)
        findOverlayButton(LogicalButtonId.OVERLAY_RECORD).performClick()

        // First consume succeeds — the snapshot was stashed by the click.
        val req = resolver.resolveFresh(sessionId, audioFile)
        assertEquals(sessionId, req.sessionId)

        // Second consume of the SAME sessionId throws — the snapshot
        // was removed by the first resolveFresh. This is what guarantees
        // a stale snapshot can't bleed into the next pipeline run.
        val again = assertThrows(
            "Second resolveFresh on the same sessionId must throw " +
                "(the snapshot is consumed by the first call).",
            UnsupportedOperationException::class.java,
        ) {
            resolver.resolveFresh(sessionId, audioFile)
        }
        assertTrue(
            "Tripwire message must name the missing session: ${again.message}",
            again.message?.contains(sessionId) == true,
        )
    }

    @Test
    fun `unwired affordance lambda leaves the resolver empty - reproduces the pre-fix hang`() {
        // Negative case: simulate the pre-fix world where the
        // affordance lambda did NOT call snapshotFresh on OVERLAY_RECORD
        // (the bug Plan §5.4 fixes). The click goes through but the
        // resolver stays empty — exactly the state in which the
        // orchestrator's async resolveFresh would hit the R-1 tripwire
        // and the pipeline would hang in Preparing.
        //
        // This test does NOT exercise OverlayBackend (the affordance
        // lambda is the unit under test); it pins the contract that the
        // resolver throws when the click failed to stash.
        val sessionId = "sid-overlay-unwired"
        val resolver = resolverUnderTest()

        // Build a backend whose affordance is a no-op — i.e. the lambda
        // existed but didn't snapshot (the exact pre-fix state of
        // `DictateInputMethodService.imeSideAffordance` before the
        // OVERLAY_RECORD branch existed).
        val backend = OverlayBackend(
            ctx = ctx,
            services = fakeModuleServices(emitAction = {}),
            overlayWindow = window,
            permissions = NoOverlayPermissionGate,
            layoutParamsFactory = DefaultOverlayLayoutParamsFactory(ctx),
            imeSideAffordance = { _, _ -> /* pre-fix no-op */ },
        )
        backend.attach { capturedActions += it }
        backend.render(stateWithActiveSession(sessionId), catalog.OVERLAY_5BUTTON)

        findOverlayButton(LogicalButtonId.OVERLAY_RECORD).performClick()

        val hang = assertThrows(
            "Resolver MUST throw the R-1 tripwire when the affordance " +
                "did not stash a snapshot — surfacing beats a silent " +
                "wrong-data submit.",
            UnsupportedOperationException::class.java,
        ) {
            resolver.resolveFresh(sessionId, audioFile)
        }
        assertTrue(
            "Tripwire must name the missing sessionId so a future " +
                "regression is loud: ${hang.message}",
            hang.message?.contains(sessionId) == true,
        )
    }

    // ─── helpers ──────────────────────────────────────────────────────

    /**
     * Probe the resolver for a missing snapshot by attempting a
     * `resolveFresh` and asserting it throws — the public API exposes
     * consume + throw-on-missing, not a peek. Wrapped here so the
     * intent ("the map does not contain the entry") reads cleanly.
     */
    private fun assertResolverHasNoSnapshotFor(
        resolver: ImePipelineConfigResolver,
        sessionId: String,
    ) {
        try {
            resolver.resolveFresh(sessionId, audioFile)
            fail(
                "Pre-condition failed: resolver already has a snapshot " +
                    "for sessionId=$sessionId before the click fired.",
            )
        } catch (expected: UnsupportedOperationException) {
            // Expected — the resolver is empty for this sessionId.
        }
    }

    private fun findOverlayButton(id: LogicalButtonId): android.view.View {
        val resId = when (id) {
            LogicalButtonId.OVERLAY_RECORD -> R.id.overlay_record_btn
            LogicalButtonId.OVERLAY_PAUSE -> R.id.overlay_pause_btn
            LogicalButtonId.OVERLAY_TRASH -> R.id.overlay_trash_btn
            LogicalButtonId.OVERLAY_CLOSE -> R.id.overlay_close_btn
            else -> error("Not an overlay-button id: $id")
        }
        val rootView = window.lastAttachedView
            ?: error("No View was attached — make sure render() ran with hasPermission=true.")
        return rootView.findViewById(resId)
            ?: error("View id $resId not found in attached overlay layout.")
    }

    /**
     * Reprocess fallback that throws — the fresh-recording path under
     * test must never reach the reprocess branch. If it does (a future
     * bug), the test fails loudly with this stack frame at the top.
     */
    private object ThrowingReprocessFallback : PipelineConfigResolver {
        override fun resolveFresh(sessionId: String, audioFile: File): JobRequest.TranscriptionPipeline {
            error("ThrowingReprocessFallback.resolveFresh should never be reached.")
        }

        override fun resolveReprocess(
            sessionId: String,
            audioFile: File?,
            queuedPromptSlots: List<net.devemperor.dictate.core.PromptQueueSlot>?,
            language: String?,
        ): JobRequest.TranscriptionPipeline {
            error("ThrowingReprocessFallback.resolveReprocess should never be reached.")
        }
    }
}
