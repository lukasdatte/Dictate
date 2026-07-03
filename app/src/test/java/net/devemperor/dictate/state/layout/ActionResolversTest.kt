package net.devemperor.dictate.state.layout

import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.AudioFileFactory
import net.devemperor.dictate.state.DictateUiState
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.PipelineUiState
import net.devemperor.dictate.state.RecordingState
import net.devemperor.dictate.state.ToastSink
import net.devemperor.dictate.testutil.NoopAudioFileFactory
import net.devemperor.dictate.testutil.NoopToastSink
import net.devemperor.dictate.testutil.fakeModuleServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Tests for the nullable-action resolvers in [ActionResolvers].
 *
 * The contract under test is R.3 (Spec 2 §3.2): a `null` resolver return
 * means "click is structurally meaningless in the current state" and the
 * IME click handler short-circuits silently — no
 * `DispatchOutcome.Rejected` log, no toast.
 *
 * # IOException side-channel
 *
 * `resolveRecordAction` is the one resolver that may call
 * `services.audioFileFactory.allocate()` (Pre-Dispatch Allocation, Spec 1
 * §4.11). When the factory throws `IOException`, the resolver MUST:
 *
 * 1. Show a toast via `services.toastSink.showError(...)`.
 * 2. Log the failure (asserted indirectly — no crash).
 * 3. Return `null` so the dispatch path stays clean.
 *
 * The handwritten [FailingAudioFileFactory] and [RecordingToastSink]
 * fixtures verify the full side-channel.
 */
class ActionResolversTest {

    private val state = DictateUiState.initial()

    // ─── resolveRecordAction ───────────────────────────────────────────

    @Test
    fun `resolveRecordAction returns null while recording is Preparing`() {
        val s = state.copy(
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertNull(resolveRecordAction(s, fakeModuleServices()))
    }

    @Test
    fun `resolveRecordAction emits StartRecording from Idle with allocated file`() {
        val recordingFile = File("/tmp/dictate-test-1.m4a")
        val factory = FixedAudioFileFactory(recordingFile)
        val services = fakeModuleServices(audioFileRepository = factory)
        val s = state.copy(recording = RecordingState.Idle)

        val action = resolveRecordAction(s, services) as? Action.RecordingAction.StartRecording
            ?: error("Expected StartRecording, got ${resolveRecordAction(s, services)}")

        assertEquals(recordingFile, action.audioFile)
        assertEquals(InsertionTarget.INPUT_CONNECTION, action.target)
        assertEquals(1, factory.allocateCallCount)
        // F-10 — the resolver mints a real (non-empty, UUID-shaped)
        // sessionId; no empty-string sentinel.
        assertTrue(action.sessionId.isNotEmpty())
        assertTrue(action.sessionId.matches(Regex("[0-9a-fA-F-]{36}")))
    }

    @Test
    fun `F-10 resolveRecordAction mints a fresh sessionId on each StartRecording`() {
        val services = fakeModuleServices(audioFileRepository =FixedAudioFileFactory(File("/tmp/r.m4a")))
        val s = state.copy(recording = RecordingState.Idle)
        val a = resolveRecordAction(s, services) as Action.RecordingAction.StartRecording
        val b = resolveRecordAction(s, services) as Action.RecordingAction.StartRecording
        // Distinct clicks get distinct ids (UUID per session).
        assertTrue(a.sessionId != b.sessionId)
    }

    @Test
    fun `resolveRecordAction emits StopRecordingAndSend from Active`() {
        val s = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertTrue(resolveRecordAction(s, fakeModuleServices()) is Action.RecordingAction.StopRecordingAndSend)
    }

    @Test
    fun `resolveRecordAction emits StopRecordingAndSend from Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertTrue(resolveRecordAction(s, fakeModuleServices()) is Action.RecordingAction.StopRecordingAndSend)
    }

    // ─── resolveSecondaryRecordAction (ADR-0009) ────────────────────────

    @Test
    fun `resolveSecondaryRecordAction emits StartRecording from Idle with allocated file`() {
        // ADR-0009 / spec §3.4 criterion 2: the secondary mic button arms a
        // fresh recording exactly like the primary button's Idle arm.
        val recordingFile = File("/tmp/dictate-test-secondary.m4a")
        val factory = FixedAudioFileFactory(recordingFile)
        val services = fakeModuleServices(audioFileRepository = factory)
        val s = state.copy(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Running(
                sessionId = "active-run",
                target = InsertionTarget.INPUT_CONNECTION,
            ),
        )

        val action = resolveSecondaryRecordAction(s, services)
            as? Action.RecordingAction.StartRecording
            ?: error("Expected StartRecording, got ${resolveSecondaryRecordAction(s, services)}")

        assertEquals(recordingFile, action.audioFile)
        assertEquals(InsertionTarget.INPUT_CONNECTION, action.target)
        assertEquals(1, factory.allocateCallCount)
        assertTrue(action.sessionId.isNotEmpty())
        assertTrue(action.sessionId.matches(Regex("[0-9a-fA-F-]{36}")))
    }

    @Test
    fun `resolveSecondaryRecordAction produces the same action shape as the primary Idle arm`() {
        // Parity pin (spec §3.4): both resolvers delegate to the shared
        // resolveStartRecordingFromIdle helper — same allocation contract,
        // same target, fresh UUID each. Guards against the copy-paste
        // drift the extraction exists to prevent.
        val factory = FixedAudioFileFactory(File("/tmp/parity.m4a"))
        val services = fakeModuleServices(audioFileRepository = factory)
        val s = state.copy(recording = RecordingState.Idle)

        val primary = resolveRecordAction(s, services) as Action.RecordingAction.StartRecording
        val secondary = resolveSecondaryRecordAction(s, services) as Action.RecordingAction.StartRecording

        assertEquals(primary.target, secondary.target)
        assertEquals(primary.audioFile, secondary.audioFile)
        assertEquals(2, factory.allocateCallCount)
        assertTrue(primary.sessionId != secondary.sessionId) // fresh UUID per tap
    }

    @Test
    fun `resolveSecondaryRecordAction returns null for every non-Idle recording state`() {
        // Single-MediaRecorder gate (spec criterion 4): a secondary
        // recording may never start while another recording is in flight.
        val nonIdle = listOf(
            RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid"),
            RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid"),
            RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid"),
            RecordingState.Interrupted(sessionId = "sid", elapsedMs = 1_000L),
        )
        for (rec in nonIdle) {
            val s = state.copy(recording = rec)
            assertNull(
                "secondary record must be null for ${rec::class.simpleName}",
                resolveSecondaryRecordAction(s, fakeModuleServices()),
            )
        }
    }

    @Test
    fun `resolveRecordAction emits StartRecordingContinuation when lookup returns a candidate`() {
        // B2 / ADR-0008 §"Auto-Continuation". Eligible continuation must
        // skip the fresh allocate (would orphan a file the user could
        // see in cleanup logs) AND the fresh UUID mint (would lose the
        // reused session-id). The lookup's returned next-segment file
        // and codec params must be threaded into the action verbatim.
        val nextSegment = File("/tmp/sess_existing_seg2.m4a")
        val codecParams = net.devemperor.dictate.audio.CodecParams(
            sampleRate = 22050,
            channelCount = 1,
            bitRate = 32000,
            mimeType = "audio/opus",
        )
        val factory = FixedAudioFileFactory(File("/tmp/fresh-WOULD-BE-WRONG.m4a"))
        val lookup = StubContinuationLookup(
            net.devemperor.dictate.state.EligibleContinuation(
                sessionId = "existing-sid-42",
                nextSegmentFile = nextSegment,
                codecParams = codecParams,
            ),
        )
        val services = fakeModuleServices(
            audioFileRepository =factory,
            continuationLookup = lookup,
        )
        val s = state.copy(recording = RecordingState.Idle)

        val action = resolveRecordAction(s, services)
            as? Action.RecordingAction.StartRecordingContinuation
            ?: error("Expected StartRecordingContinuation, got ${resolveRecordAction(s, services)}")

        assertEquals("existing-sid-42", action.sessionId)
        assertEquals(nextSegment, action.audioFile)
        assertEquals(codecParams, action.codecParams)
        assertEquals(InsertionTarget.INPUT_CONNECTION, action.target)

        // Crucial — the resolver must NOT have asked AudioFileFactory
        // to mint a fresh path. allocateNext already handed back the
        // correct one via the lookup.
        assertEquals(
            "fresh allocate() must be skipped on the continuation path " +
                "— otherwise the orphaned file appears in cleanup logs",
            0, factory.allocateCallCount,
        )
        assertEquals(1, lookup.lookupCallCount)
    }

    @Test
    fun `resolveRecordAction falls back to StartRecording when lookup returns null`() {
        // Negative path — no continuation candidate, no IO mutation
        // from the lookup, standard fresh-session resolution.
        val recordingFile = File("/tmp/dictate-test-1.m4a")
        val factory = FixedAudioFileFactory(recordingFile)
        val lookup = StubContinuationLookup(eligibility = null)
        val services = fakeModuleServices(
            audioFileRepository =factory,
            continuationLookup = lookup,
        )
        val s = state.copy(recording = RecordingState.Idle)

        val action = resolveRecordAction(s, services)
        assertTrue(action is Action.RecordingAction.StartRecording)
        assertEquals(1, lookup.lookupCallCount)
        assertEquals(1, factory.allocateCallCount)
    }

    @Test
    fun `resolveRecordAction returns null and shows toast on IOException`() {
        val toast = RecordingToastSink()
        val services = fakeModuleServices(
            audioFileRepository =FailingAudioFileFactory(IOException("disk full")),
            toastSink = toast,
        )
        val s = state.copy(recording = RecordingState.Idle)

        // R.3 contract: null is the silent-no-op path, never throws.
        val result = resolveRecordAction(s, services)
        assertNull(result)

        // Toast must surface the failure so the user knows the click didn't take.
        // B4-VAL F-4: switched from showError(literal) to show(@StringRes) so the
        // user-visible string goes through Android i18n.
        assertEquals(listOf(net.devemperor.dictate.R.string.dictate_storage_full), toast.resourceIds)
        assertTrue("Should not use error-channel String overload", toast.errorMessages.isEmpty())
    }

    // ─── resolveRecordLongPressAction (G2 / CR1 / A1) ──────────────────

    @Test
    fun `resolveRecordLongPressAction emits OnRecordLongPress from Active`() {
        val s = state.copy(
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test",
            ),
        )
        assertEquals(
            Action.RecordingAction.OnRecordLongPress,
            resolveRecordLongPressAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveRecordLongPressAction emits OnRecordLongPress from Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test",
            ),
        )
        assertEquals(
            Action.RecordingAction.OnRecordLongPress,
            resolveRecordLongPressAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveRecordLongPressAction returns null from Idle (R-3 — Idle launch is IME-side, A1)`() {
        val s = state.copy(recording = RecordingState.Idle)
        // R.3: the Idle Settings+file-picker launch is an IME-side
        // affordance wired in CR4, NOT a reducer transition — the resolver
        // short-circuits so no pointless action reaches the orchestrator.
        assertNull(resolveRecordLongPressAction(s, fakeModuleServices()))
    }

    @Test
    fun `resolveRecordLongPressAction returns null while Preparing`() {
        val s = state.copy(
            recording = RecordingState.Preparing(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test",
            ),
        )
        assertNull(resolveRecordLongPressAction(s, fakeModuleServices()))
    }

    // ─── resolveRecordActionPipeline ──────────────────────────────────

    @Test
    fun `resolveRecordActionPipeline returns ToggleRunningAutoEnter while Running (post-cutover #AE)`() {
        // Per-run flag (distinct from the global Pref.AutoEnter pref
        // that FeatureToggleAction.ToggleAutoEnter would write). The
        // catalog must dispatch the in-pipeline action so the second
        // SEND-tap during Running does NOT silently flip the global
        // setting for every future dictation.
        val s = state.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
            ),
        )
        assertEquals(
            Action.PipelineAction.ToggleRunningAutoEnter,
            resolveRecordActionPipeline(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveRecordActionPipeline returns ToggleRunningAutoEnter while Preparing (#AE-DEEP2)`() {
        // The upload window (Preparing) is the most common landing zone
        // for the double-tap — pre-#AE-DEEP2 the resolver returned null
        // here and the catalog click was silently swallowed.
        val s = state.copy(pipeline = PipelineUiState.Preparing("s1"))
        assertEquals(
            Action.PipelineAction.ToggleRunningAutoEnter,
            resolveRecordActionPipeline(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveRecordActionPipeline returns null when pipeline is Idle`() {
        val s = state.copy(pipeline = PipelineUiState.Idle)
        assertNull(resolveRecordActionPipeline(s, fakeModuleServices()))
    }

    // ─── resolveTrashAction ───────────────────────────────────────────

    @Test
    fun `resolveTrashAction returns CancelReprocessStaging while staging`() {
        val s = state.copy(pipeline = PipelineUiState.ReprocessStaging("s42", "txt"))
        val result = resolveTrashAction(s, fakeModuleServices()) as? Action.PipelineAction.CancelReprocessStaging
            ?: error("Expected CancelReprocessStaging, got ${resolveTrashAction(s, fakeModuleServices())}")
        assertEquals("s42", result.sessionId)
    }

    @Test
    fun `resolveTrashAction returns null in pure-idle state`() {
        val s = state.copy(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
        )
        assertNull(resolveTrashAction(s, fakeModuleServices()))
    }

    @Test
    fun `resolveTrashAction returns CancelRecording while recording`() {
        val s = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(
            Action.RecordingAction.CancelRecording,
            resolveTrashAction(s, fakeModuleServices()),
        )
    }

    // recording-stack-completion §4.5.3 — Trash-Btn surfaces
    // DiscardInterruptedSession for a RECORDING_INTERRUPTED row at Idle.
    @Test
    fun `resolveTrashAction returns DiscardInterruptedSession at Idle when interrupted session present`() {
        val interruptedSid = "interrupted-sid"
        val s = state.copy(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
            pendingSessions = kotlinx.collections.immutable.persistentListOf(
                net.devemperor.dictate.state.PendingSession(
                    sessionId = interruptedSid,
                    status = net.devemperor.dictate.database.entity.SessionStatus.RECORDING_INTERRUPTED,
                    transcribedText = null,
                    createdAt = 0L,
                ),
            ),
        )
        val result = resolveTrashAction(s, fakeModuleServices())
            as? Action.RecordingAction.DiscardInterruptedSession
            ?: error("Expected DiscardInterruptedSession, got ${resolveTrashAction(s, fakeModuleServices())}")
        assertEquals(interruptedSid, result.sessionId)
    }

    @Test
    fun `resolveTrashAction returns null at Idle when only RECORDED and COMPLETED present`() {
        val s = state.copy(
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
            pendingSessions = kotlinx.collections.immutable.persistentListOf(
                net.devemperor.dictate.state.PendingSession(
                    sessionId = "completed-sid",
                    status = net.devemperor.dictate.database.entity.SessionStatus.COMPLETED,
                    transcribedText = "hi",
                    createdAt = 0L,
                ),
                net.devemperor.dictate.state.PendingSession(
                    sessionId = "recorded-sid",
                    status = net.devemperor.dictate.database.entity.SessionStatus.RECORDED,
                    transcribedText = null,
                    createdAt = 0L,
                ),
            ),
        )
        assertNull(resolveTrashAction(s, fakeModuleServices()))
    }

    // ─── resolvePauseAction ───────────────────────────────────────────

    @Test
    fun `resolvePauseAction returns PauseRecording while Active`() {
        val s = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(
            Action.RecordingAction.PauseRecording,
            resolvePauseAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolvePauseAction returns ResumeRecording while Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(
            Action.RecordingAction.ResumeRecording,
            resolvePauseAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolvePauseAction returns null outside Active and Paused`() {
        assertNull(resolvePauseAction(state.copy(recording = RecordingState.Idle), fakeModuleServices()))
        assertNull(
            resolvePauseAction(
                state.copy(
                    recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
                ),
                fakeModuleServices(),
            ),
        )
    }

    // ─── Staging resolvers ────────────────────────────────────────────

    @Test
    fun `resolveSendStagingAction returns null even in staging (F-001 - IME affordance hook owns the dispatch)`() {
        // F-001 regression lock: the catalog resolver dispatching a
        // queue-less SendStaging was exactly what discarded the staged
        // edits (the reducer arm emitted SubmitReprocess(queue=emptyList)
        // → live-queue fallback) and consumed the reprocess snapshot
        // before the IME's correctly-parameterised submit could run. The
        // staged payload lives in the IME mirror fields, so only the
        // IME-side affordance hook (handleReprocessSend) can build the
        // real SendStaging — the catalog resolver must stay silent.
        val s = state.copy(pipeline = PipelineUiState.ReprocessStaging("s99", "txt"))
        assertNull(resolveSendStagingAction(s, fakeModuleServices()))
    }

    @Test
    fun `resolveSendStagingAction returns null when pipeline is not staging`() {
        assertNull(resolveSendStagingAction(state.copy(pipeline = PipelineUiState.Idle), fakeModuleServices()))
    }

    @Test
    fun `resolveCancelStagingAction reads sessionId from current state`() {
        val s = state.copy(pipeline = PipelineUiState.ReprocessStaging("sX", "txt"))
        val result = resolveCancelStagingAction(s, fakeModuleServices()) as? Action.PipelineAction.CancelReprocessStaging
            ?: error("Expected CancelReprocessStaging, got ${resolveCancelStagingAction(s, fakeModuleServices())}")
        assertEquals("sX", result.sessionId)
    }

    @Test
    fun `resolveCancelStagingAction returns null when pipeline is not staging`() {
        assertNull(resolveCancelStagingAction(state.copy(pipeline = PipelineUiState.Idle), fakeModuleServices()))
    }

    // ─── Icon resolvers ───────────────────────────────────────────────

    @Test
    fun `resolveAudioFocusIcon returns volume_off when enabled (legacy semantics)`() {
        // enabled=true → AudioFocus IS held → other audio is muted →
        // icon depicts the effect on others (silenced) → volume_off.
        // Matches MainButtonsController.refreshAudioFocusIcon semantics.
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_volume_off_24, resolveAudioFocusIcon(enabled = true))
    }

    @Test
    fun `resolveAudioFocusIcon returns volume_up when disabled (legacy semantics)`() {
        // enabled=false → AudioFocus NOT held → other audio plays normally
        // → icon depicts other-audio-audible → volume_up.
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_volume_up_24, resolveAudioFocusIcon(enabled = false))
    }

    @Test
    fun `resolvePauseIcon returns mic icon while Paused`() {
        val s = state.copy(
            recording = RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_mic_24, resolvePauseIcon(s))
    }

    @Test
    fun `resolvePauseIcon returns pause icon otherwise`() {
        val active = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        val idle = state.copy(recording = RecordingState.Idle)
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_pause_24, resolvePauseIcon(active))
        assertEquals(net.devemperor.dictate.R.drawable.ic_baseline_pause_24, resolvePauseIcon(idle))
    }

    // ─── Text resolvers ───────────────────────────────────────────────

    @Test
    fun `resolveRecordButtonText returns send text in Active`() {
        val strings = testLayoutStrings()
        val s = state.copy(
            recording = RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(strings.send, resolveRecordButtonText(s, strings))
    }

    @Test
    fun `resolveRecordButtonText returns dictateButtonText in Idle`() {
        val strings = testLayoutStrings()
        val s = state.copy(recording = RecordingState.Idle)
        // F-15 — the resolver feeds the effective-language code into the
        // provider; the default initial state is "system".
        assertEquals(
            strings.dictateButtonText(s.language.effective),
            resolveRecordButtonText(s, strings),
        )
    }

    @Test
    fun `F-15 resolveRecordButtonText label differs across two effective languages`() {
        val strings = testLayoutStrings()
        val en = state.copy(
            recording = RecordingState.Idle,
            language = state.language.copy(effective = "en"),
        )
        val de = state.copy(
            recording = RecordingState.Idle,
            language = state.language.copy(effective = "de"),
        )
        val enLabel = resolveRecordButtonText(en, strings)
        val deLabel = resolveRecordButtonText(de, strings)
        assertEquals("Dictate (en)", enLabel)
        assertEquals("Dictate (de)", deLabel)
        // Core F-15 acceptance: the label is language-sensitive.
        assertTrue(enLabel != deLabel)
    }

    @Test
    fun `resolveRecordButtonText returns record string while Preparing`() {
        val strings = testLayoutStrings()
        val s = state.copy(
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-test"),
        )
        assertEquals(strings.record, resolveRecordButtonText(s, strings))
    }

    @Test
    fun `resolveRecordButtonTextPipeline returns formatPreparingLabel while Preparing (#AE-DEEP2)`() {
        // The Preparing arm now reads `autoEnterActive` so a double-tap
        // during the upload window gets visual confirmation. The flag-blind
        // `strings.sending` literal is no longer the right answer — it's
        // the `false`-branch of `formatPreparingLabel` (which the default
        // test formatter renders as "Sending …", same surface text).
        val strings = testLayoutStrings()
        val s = state.copy(pipeline = PipelineUiState.Preparing("s1", autoEnterActive = false))
        assertEquals(strings.formatPreparingLabel(false), resolveRecordButtonTextPipeline(s, strings))
    }

    @Test
    fun `resolveRecordButtonTextPipeline appends ↵ during Preparing with autoEnterActive (#AE-DEEP2)`() {
        // Direct regression for the "tap toggles but no visual feedback"
        // bug — pre-fix this returned plain "Sending …" regardless of the
        // flag, post-fix it returns the autoEnter-decorated label.
        val strings = testLayoutStrings()
        val s = state.copy(pipeline = PipelineUiState.Preparing("s1", autoEnterActive = true))
        assertEquals(strings.formatPreparingLabel(true), resolveRecordButtonTextPipeline(s, strings))
    }

    @Test
    fun `F-13 resolveRecordButtonTextPipeline renders real Running counters not placeholders`() {
        // Regression for the B4-resolver placeholder (`0, 0, …, 0L`):
        // the live label must reflect the actual Running progress fields.
        val strings = testLayoutStrings()
        val s = state.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
                autoEnterActive = true,
                completedSteps = 2,
                totalSteps = 3,
                elapsedMs = 8_000L,
            ),
        )
        // testLayoutStrings().formatPipelineLabel: "$done/$total$mark  ${elapsedMs}ms"
        assertEquals("2/3 ↵  8000ms", resolveRecordButtonTextPipeline(s, strings))
    }

    @Test
    fun `F-13 resolveRecordButtonTextPipeline reflects autoEnter false in label`() {
        val strings = testLayoutStrings()
        val s = state.copy(
            pipeline = PipelineUiState.Running(
                sessionId = "s1",
                target = InsertionTarget.INPUT_CONNECTION,
                autoEnterActive = false,
                completedSteps = 0,
                totalSteps = 1,
                elapsedMs = 0L,
            ),
        )
        assertEquals("0/1  0ms", resolveRecordButtonTextPipeline(s, strings))
    }

    @Test
    fun `resolveRecordButtonTextStaging returns empty string outside staging`() {
        val strings = testLayoutStrings()
        val s = state.copy(pipeline = PipelineUiState.Idle)
        assertEquals("", resolveRecordButtonTextStaging(s, strings))
    }

    // ─── F-2 WIDGET_TOGGLE permission-aware resolver ─────────────────

    @Test
    fun `resolveWidgetToggleAction with permission returns ToggleViewModeWidget`() {
        val s = state.copy(overlay = state.overlay.copy(hasPermission = true))
        assertEquals(
            Action.ViewModeAction.ToggleViewModeWidget,
            resolveWidgetToggleAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveWidgetToggleAction without permission returns ShowOverlayOnboarding`() {
        val s = state.copy(overlay = state.overlay.copy(hasPermission = false))
        assertEquals(
            Action.OverlayAction.ShowOverlayOnboarding,
            resolveWidgetToggleAction(s, fakeModuleServices()),
        )
    }

    // ─── resolveOverlayRecordAction (Variante 2a, dictate-widget-integration §8.2 Chunk 2.2) ──
    //
    // 2026-05-22 — overlay record-btn spec (post-Widget-Pause refactor):
    //   • IME visible: Klick = Send (StopRecordingAndSend), Start (Idle), or
    //     auto-enter toggle (pipeline live) — same as the keyboard surface.
    //   • IME hidden + Active/Paused: Klick = disabled (return null). The
    //     dedicated OVERLAY_PAUSE slot owns the pause UI in that mode.
    //   • IME hidden + Idle: Klick = StartRecording (allowed — the whole
    //     point of the widget is starting without unfolding the IME).
    //   • IME hidden + pipeline live: Klick = auto-enter toggle (no-op
    //     against a missing InputConnection, but the toggle itself is
    //     valid state-machine signal).

    @Test
    fun `resolveOverlayRecordAction IME-hidden + Active returns null (Senden verboten)`() {
        val s = state.copy(
            imeViewVisible = false,
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-h"
            ),
        )
        assertNull(
            "IME hidden + Active must yield null (User-Req: Senden verboten ohne InputConnection)",
            resolveOverlayRecordAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveOverlayRecordAction IME-hidden + Paused returns null (Senden verboten)`() {
        val s = state.copy(
            imeViewVisible = false,
            recording = RecordingState.Paused(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-h"
            ),
        )
        assertNull(
            "IME hidden + Paused must yield null (User-Req: Senden verboten ohne InputConnection)",
            resolveOverlayRecordAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveOverlayRecordAction WIDGET-Active emits StopRecordingAndSend`() {
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-w"
            ),
        )
        assertTrue(
            resolveOverlayRecordAction(s, fakeModuleServices())
                is Action.RecordingAction.StopRecordingAndSend,
        )
    }

    @Test
    fun `resolveOverlayRecordAction WIDGET-Paused emits StopRecordingAndSend`() {
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            recording = RecordingState.Paused(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-w"
            ),
        )
        assertTrue(
            resolveOverlayRecordAction(s, fakeModuleServices())
                is Action.RecordingAction.StopRecordingAndSend,
        )
    }

    // ─── 2026-05-22 Widget-Pause-Refactor ────────────────────────
    //
    // The previous B3.4 "Send-button morphs into Pause-Toggle while widget
    // is visible" rule is superseded. The dedicated OVERLAY_PAUSE slot
    // now owns the pause UI; the record-btn keeps its start/send role on
    // both surfaces (gated by `imeViewVisible` for the Active/Paused →
    // Send case).

    @Test
    fun `Widget-Active + IME visible emits StopRecordingAndSend (post-Widget-Pause-refactor)`() {
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            widget = net.devemperor.dictate.state.WidgetState.Visible(
                net.devemperor.dictate.state.WidgetOrigin.USER
            ),
            imeViewVisible = true,
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-w"
            ),
        )
        assertTrue(
            "Active + widget visible + IME visible → StopRecordingAndSend (Send role restored)",
            resolveOverlayRecordAction(s, fakeModuleServices())
                is Action.RecordingAction.StopRecordingAndSend,
        )
    }

    @Test
    fun `Widget-Paused + IME visible emits StopRecordingAndSend (post-Widget-Pause-refactor)`() {
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            widget = net.devemperor.dictate.state.WidgetState.Visible(
                net.devemperor.dictate.state.WidgetOrigin.PIPELINE
            ),
            imeViewVisible = true,
            recording = RecordingState.Paused(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-w"
            ),
        )
        assertTrue(
            "Paused + widget visible + IME visible → StopRecordingAndSend (Send role restored)",
            resolveOverlayRecordAction(s, fakeModuleServices())
                is Action.RecordingAction.StopRecordingAndSend,
        )
    }

    @Test
    fun `B3-4 pipeline Running takes precedence over Pause-Toggle (auto-enter still owns the btn)`() {
        // When the pipeline is live the button is the per-run auto-enter
        // toggle, regardless of widget-state. Pause/Resume only kicks
        // in for non-live-pipeline recording sub-states.
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            widget = net.devemperor.dictate.state.WidgetState.Visible(
                net.devemperor.dictate.state.WidgetOrigin.USER
            ),
            recording = RecordingState.Active(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-w"
            ),
            pipeline = PipelineUiState.Running(
                sessionId = "sid-pipe",
                target = net.devemperor.dictate.state.InsertionTarget.INPUT_CONNECTION,
                totalSteps = 1,
                elapsedMs = 0L,
                autoEnterActive = false,
            ),
        )
        assertTrue(
            "Pipeline.Running + widget visible + Active → ToggleRunningAutoEnter (not Pause)",
            resolveOverlayRecordAction(s, fakeModuleServices())
                is Action.PipelineAction.ToggleRunningAutoEnter,
        )
    }

    @Test
    fun `B3-4 WIDGET-Idle + widget Visible still goes through StartRecording path`() {
        // Continuation-Lookup runs in resolveRecordAction (B2); the
        // overlay resolver falls through to it for the Idle case
        // regardless of widget-state — Pause/Resume only meaningful
        // when recording is in flight.
        val recordingFile = File("/tmp/overlay-idle-widget.m4a")
        val factory = FixedAudioFileFactory(recordingFile)
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            widget = net.devemperor.dictate.state.WidgetState.Visible(
                net.devemperor.dictate.state.WidgetOrigin.USER
            ),
            recording = RecordingState.Idle,
        )
        val action = resolveOverlayRecordAction(s, fakeModuleServices(audioFileRepository = factory))
        assertTrue(action is Action.RecordingAction.StartRecording)
        assertEquals(1, factory.allocateCallCount)
    }

    @Test
    fun `resolveOverlayRecordAction WIDGET-Idle emits StartRecording with allocated file`() {
        val recordingFile = File("/tmp/overlay-idle.m4a")
        val factory = FixedAudioFileFactory(recordingFile)
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            recording = RecordingState.Idle,
            pipeline = PipelineUiState.Idle,
        )
        val action = resolveOverlayRecordAction(s, fakeModuleServices(audioFileRepository = factory))
            as? Action.RecordingAction.StartRecording
            ?: error("Expected StartRecording")
        assertEquals(recordingFile, action.audioFile)
        assertEquals(1, factory.allocateCallCount)
    }

    @Test
    fun `resolveOverlayRecordAction WIDGET-Preparing emits ToggleRunningAutoEnter`() {
        // Pipeline Preparing (the 500ms-2s upload window) — the merged
        // RECORD+SEND slot acts as a per-run auto-enter toggle, matching
        // the keyboard SEND_MODE behaviour. #AE-DEEP2 contract: a
        // double-tap during Preparing must flip the autoEnterActive bit.
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            pipeline = PipelineUiState.Preparing("sid-w"),
        )
        assertEquals(
            Action.PipelineAction.ToggleRunningAutoEnter,
            resolveOverlayRecordAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveOverlayRecordAction WIDGET-Running emits ToggleRunningAutoEnter`() {
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            pipeline = PipelineUiState.Running(
                sessionId = "sid-w", target = InsertionTarget.INPUT_CONNECTION,
            ),
        )
        assertEquals(
            Action.PipelineAction.ToggleRunningAutoEnter,
            resolveOverlayRecordAction(s, fakeModuleServices()),
        )
    }

    @Test
    fun `resolveOverlayRecordAction WIDGET-Preparing-recording returns null (recorder warming up)`() {
        // Recording-Preparing is the <100ms window between StartRecording
        // and MediaRecorderReady. Mirrors resolveRecordAction's null.
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            recording = RecordingState.Preparing(
                useBluetooth = false, audioFile = stubAudioFile(), sessionId = "sid-w"
            ),
            pipeline = PipelineUiState.Idle,
        )
        assertNull(resolveOverlayRecordAction(s, fakeModuleServices()))
    }

    // ─── resolveOverlayRecordEnabled (Variante 2a, §8.2 Chunk 2.3) ────

    @Test
    fun `resolveOverlayRecordEnabled IME-hidden is false only for Active+Paused (post-Widget-Pause-refactor)`() {
        // 2026-05-22 — new spec: IME-hidden disables the record-btn ONLY
        // for Active/Paused (the "Senden ohne InputConnection ist verboten"
        // gate). Idle stays enabled (Start is allowed without IME). The
        // <100ms Preparing window is always disabled (recorder warming up).
        val cases = listOf(
            "Idle" to (RecordingState.Idle to true),
            "Active" to (RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "x") to false),
            "Paused" to (RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "x") to false),
            "Preparing" to (RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "x") to false),
        )
        cases.forEach { (name, pair) ->
            val (rs, expected) = pair
            val s = state.copy(imeViewVisible = false, recording = rs)
            assertEquals(
                "IME hidden + $name: expected enabled=$expected",
                expected,
                resolveOverlayRecordEnabled(s),
            )
        }
    }

    @Test
    fun `resolveOverlayRecordEnabled WIDGET is true for Idle Active Paused`() {
        val cases = listOf(
            "Idle" to RecordingState.Idle,
            "Active" to RecordingState.Active(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "x"),
            "Paused" to RecordingState.Paused(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "x"),
        )
        cases.forEach { (label, rs) ->
            val s = state.copy(
                viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
                recording = rs,
                pipeline = PipelineUiState.Idle,
            )
            assertEquals(
                "WIDGET must enable the button in $label",
                true,
                resolveOverlayRecordEnabled(s),
            )
        }
    }

    @Test
    fun `resolveOverlayRecordEnabled WIDGET is false during Preparing-recording`() {
        val s = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            recording = RecordingState.Preparing(useBluetooth = false, audioFile = stubAudioFile(), sessionId = "x"),
            pipeline = PipelineUiState.Idle,
        )
        assertEquals(false, resolveOverlayRecordEnabled(s))
    }

    @Test
    fun `resolveOverlayRecordEnabled WIDGET is true during live pipeline (auto-enter reachable)`() {
        // #AE-OPTIK2 / #AE-DEEP2 — the button stays enabled during the
        // live pipeline so the double-tap-to-toggle auto-enter is
        // reachable. Mirrors the keyboard SEND_MODE record slot.
        val preparing = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            pipeline = PipelineUiState.Preparing("x"),
        )
        val running = state.copy(
            viewMode = net.devemperor.dictate.state.ViewMode.WIDGET,
            pipeline = PipelineUiState.Running(sessionId = "x", target = InsertionTarget.INPUT_CONNECTION),
        )
        assertEquals(true, resolveOverlayRecordEnabled(preparing))
        assertEquals(true, resolveOverlayRecordEnabled(running))
    }
}

// ─── Hand-rolled fakes (K-1) ─────────────────────────────────────────

/**
 * Always returns the same [file] from `allocateFirst`. Tracks the number
 * of calls (kept compatible with the old `FixedAudioFileFactory` assertion
 * surface so the Initial-File-Cutover (Block A4) didn't require renaming
 * every test). Other methods of the repository contract throw — none of
 * these tests exercise rolling-segments or pipeline-reads.
 */
private class FixedAudioFileFactory(private val file: File) :
    net.devemperor.dictate.audio.AudioFileRepository {
    var allocateCallCount: Int = 0
        private set

    override fun allocateFirst(sessionId: String): File {
        allocateCallCount++
        return file
    }

    override fun allocateNext(sessionId: String): File =
        error("FixedAudioFileFactory.allocateNext not exercised by resolver tests")
    override fun segments(sessionId: String): List<File> = emptyList()
    override suspend fun readForPipeline(
        sessionId: String,
    ): net.devemperor.dictate.audio.PipelineAudioResult? = null
    override fun deleteAll(sessionId: String) = Unit
    override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> = emptySet()
    override fun listAllOwnedFiles(): Map<String, List<File>> = emptyMap()
}

/** Throws [thrown] on every `allocateFirst` call. */
private class FailingAudioFileFactory(private val thrown: IOException) :
    net.devemperor.dictate.audio.AudioFileRepository {
    override fun allocateFirst(sessionId: String): File = throw thrown
    override fun allocateNext(sessionId: String): File = throw thrown
    override fun segments(sessionId: String): List<File> = emptyList()
    override suspend fun readForPipeline(
        sessionId: String,
    ): net.devemperor.dictate.audio.PipelineAudioResult? = null
    override fun deleteAll(sessionId: String) = Unit
    override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> = emptySet()
    override fun listAllOwnedFiles(): Map<String, List<File>> = emptyMap()
}

/** Captures every toast call so tests can assert on the side-channel. */
private class RecordingToastSink : ToastSink {
    val messages: MutableList<CharSequence> = mutableListOf()
    val errorMessages: MutableList<CharSequence> = mutableListOf()
    val resourceIds: MutableList<Int> = mutableListOf()

    override fun show(message: CharSequence) {
        messages.add(message)
    }

    override fun showError(message: CharSequence) {
        errorMessages.add(message)
    }

    override fun show(resId: Int) {
        resourceIds.add(resId)
    }
}

/**
 * Hand-rolled [net.devemperor.dictate.state.ContinuationLookup] that
 * returns a fixed [eligibility] on every call and counts invocations.
 * Used to drive the B2 continuation-resolver branch without spinning
 * up SessionTracker + AudioFileRepository + AudioCodecReader.
 */
private class StubContinuationLookup(
    private val eligibility: net.devemperor.dictate.state.EligibleContinuation?,
) : net.devemperor.dictate.state.ContinuationLookup {
    var lookupCallCount: Int = 0
        private set

    override fun lookup(): net.devemperor.dictate.state.EligibleContinuation? {
        lookupCallCount++
        return eligibility
    }
}

// Keeps `NoopAudioFileFactory` + `NoopToastSink` imported so future
// expansions of the test surface (e.g. a "default services" case) read
// from the shared no-op fixtures rather than reinventing them.
@Suppress("unused") private val _noopFactoryAnchor: AudioFileFactory = NoopAudioFileFactory
@Suppress("unused") private val _noopToastAnchor: ToastSink = NoopToastSink
