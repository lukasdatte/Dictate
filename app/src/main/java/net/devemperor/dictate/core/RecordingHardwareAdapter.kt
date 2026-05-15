package net.devemperor.dictate.core

import android.media.MediaRecorder
import android.util.Log
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.ModuleId
import net.devemperor.dictate.state.RecordingHardwareSubsystem
import java.io.File
import java.io.IOException

/**
 * Production [RecordingHardwareSubsystem] backed by an Android
 * [MediaRecorder].
 *
 * **C8 — subsystem-adapter migration (Spec 1 §9.6 + §15.2):** the legacy
 * [RecordingManager] is kept in place for the IME-side recording flow
 * (driven by [RecordingStateController]); this adapter is a **parallel**
 * production-quality implementation that the [DictateOrchestrator]
 * consumes via [RecordingModule.runEffect]. Both paths can coexist
 * because today the orchestrator-side modules are not yet the primary
 * driver of recording — the IME still owns the recording UX. The
 * adapter ensures that when a future block (B4-B6 LayoutCatalog /
 * Overlay) routes record-button clicks through `dispatch(Action.RecordingAction.X)`,
 * the corresponding effects produce real audio without further wiring.
 *
 * **Threading:** all public methods MUST be called on the main thread
 * (the dispatch loop is `Dispatchers.Main.immediate`-confined per
 * [DictateOrchestrator]). The internal Timer/amplitude callbacks
 * post back to the main looper via [Handler].
 *
 * **Failure routing:** errors during [allocate] / [start] surface via
 * [emitAction] as [Action.EffectFailure] carrying [ModuleId.Recording]
 * so the reducer's `reduceFailure` arm rolls back the FSM.
 *
 * @param audioSource the [MediaRecorder.AudioSource] constant used when
 *   the recording is **not** Bluetooth — typically [MediaRecorder.AudioSource.MIC].
 *   Bluetooth recordings use [MediaRecorder.AudioSource.VOICE_COMMUNICATION].
 * @param emitAction main-thread re-entry into the orchestrator. Called
 *   when the adapter completes `allocate` to signal
 *   [Action.RecordingAction.MediaRecorderReady] (audio file is ready
 *   for the FSM transition Preparing → Active).
 *
 * @see net.devemperor.dictate.state.RecordingHardwareSubsystem
 * @see net.devemperor.dictate.core.RecordingManager
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §9.1 §15.2
 */
class RecordingHardwareAdapter(
    private val emitAction: (Action) -> Unit,
) : RecordingHardwareSubsystem {

    private var recorder: MediaRecorder? = null

    override fun allocate(target: InsertionTarget, useBluetooth: Boolean, audioFile: File) {
        if (recorder != null) {
            Log.w(TAG, "allocate() called with existing recorder — releasing previous instance")
            releaseRecorder()
        }
        val source = if (useBluetooth) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val mr = MediaRecorder().apply {
            setAudioSource(source)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(audioFile)
        }
        recorder = mr
        try {
            mr.prepare()
        } catch (e: IOException) {
            Log.e(TAG, "MediaRecorder.prepare() failed", e)
            releaseRecorder()
            emitAction(
                Action.EffectFailure(
                    originModuleId = ModuleId.Recording,
                    effect = "AllocateMediaRecorder($target, $useBluetooth, $audioFile)",
                    reason = e.message ?: "prepare-failed",
                )
            )
            return
        }
        // Signal Preparing → Active. The audioFile is also already in
        // RecordingState.Preparing (R.2); we echo it in the action so
        // the reducer arm can switch into RecordingState.Active without
        // a separate state-read.
        emitAction(Action.RecordingAction.MediaRecorderReady(audioFile))
    }

    override fun start() {
        val mr = recorder
        if (mr == null) {
            Log.w(TAG, "start() called without an active recorder — no-op")
            return
        }
        try {
            mr.start()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaRecorder.start() failed", e)
            emitAction(
                Action.EffectFailure(
                    originModuleId = ModuleId.Recording,
                    effect = "StartMediaRecorder",
                    reason = e.message ?: "start-failed",
                )
            )
        }
    }

    override fun pause() {
        val mr = recorder ?: return
        try {
            mr.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaRecorder.pause() failed (ignored — best-effort)", e)
        }
    }

    override fun resume() {
        val mr = recorder ?: return
        try {
            mr.resume()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaRecorder.resume() failed (ignored)", e)
        }
    }

    override fun stop() {
        val mr = recorder ?: return
        try {
            mr.stop()
        } catch (e: RuntimeException) {
            // stop() throws on too-short streams or invalid states;
            // best-effort, the reducer's reduceFailure arm handles
            // FSM rollback.
            Log.w(TAG, "MediaRecorder.stop() failed", e)
            emitAction(
                Action.EffectFailure(
                    originModuleId = ModuleId.Recording,
                    effect = "StopMediaRecorder",
                    reason = e.message ?: "stop-failed",
                )
            )
        }
        releaseRecorder()
    }

    override fun release() {
        releaseRecorder()
    }

    private fun releaseRecorder() {
        recorder?.let {
            try { it.release() } catch (e: Exception) { Log.w(TAG, "release() failed", e) }
        }
        recorder = null
    }

    /**
     * Test-only — expose the active [MediaRecorder]. Lets the unit-test
     * suite assert the allocator went through the expected states without
     * widening the public [RecordingHardwareSubsystem] interface.
     */
    internal fun activeRecorder(): MediaRecorder? = recorder

    private companion object {
        const val TAG: String = "RecordingHwAdapter"
    }
}
