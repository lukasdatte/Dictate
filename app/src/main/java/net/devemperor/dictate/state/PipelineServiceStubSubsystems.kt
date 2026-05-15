package net.devemperor.dictate.state

import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * Production-side **no-op stub subsystems** that the
 * [net.devemperor.dictate.core.DictatePipelineService] uses to
 * construct a [ModuleServices] instance in chunk C7.
 *
 * **Why these exist:** the [ModuleServices] DI container (Spec 1 §4.7)
 * lists 14 subsystem interfaces (`RecordingHardwareSubsystem`,
 * `BluetoothScoSubsystem`, `AudioFocusSubsystem`, `RecordingTimerSubsystem`,
 * `AmplitudeStreamSubsystem`, `BorderGlowSubsystem`, `PipelineRunnerSubsystem`,
 * `PipelineSessionRepoSubsystem`, `PipelineNotificationCoordinatorSubsystem`,
 * `ToastSink`, `AudioFileFactory`). The real Android-backed
 * implementations land in Block 3 (subsystem-adapter migration,
 * chunk C8) — pre-existing legacy classes (`RecordingManager`,
 * `BluetoothScoManager`, …) get re-fronted by adapter shims at that
 * point.
 *
 * In C7 we still need a working [ModuleServices] so the orchestrator
 * can be constructed at service-onCreate time and `runEffect` calls
 * don't NPE. These stubs are **deliberately log-only**: when a module
 * emits an Effect, the stub logs it at WARN with a clear "B3 fills
 * this" message, then returns. No state change, no real hardware
 * call.
 *
 * **B3 replacement contract:** when Block 3 lands the real adapter
 * classes, the wiring in
 * [net.devemperor.dictate.core.DictatePipelineService.onCreate] swaps
 * each `Stub*` constant reference for the real adapter. This file
 * stays around as a documentation anchor for the contract surface
 * (and a regression net — running the orchestrator with the real
 * adapters but a stub for one subsystem should fail visibly).
 *
 * @see net.devemperor.dictate.state.ModuleServices
 * @see net.devemperor.dictate.core.DictatePipelineService
 */
internal object PipelineServiceStubSubsystems {

    private const val TAG = "PipelineServiceStub"
    private const val MESSAGE = "B3 fills this — module emitted an effect that the stub absorbs"

    // Note: `Log.w(...)` returns `Int` in the Android SDK; wrapping each
    // override in a block-body keeps the function return type `Unit`
    // (expression-bodied `= Log.w(...)` would compile-error against the
    // interface contract).

    /** Recording-hardware adapter stub. Logs and discards every call. */
    val recordingHardware: RecordingHardwareSubsystem = object : RecordingHardwareSubsystem {
        override fun allocate(target: InsertionTarget, useBluetooth: Boolean, audioFile: File) {
            Log.w(TAG, "recordingHardware.allocate($target, $useBluetooth, $audioFile): $MESSAGE")
        }
        override fun start() { Log.w(TAG, "recordingHardware.start(): $MESSAGE") }
        override fun pause() { Log.w(TAG, "recordingHardware.pause(): $MESSAGE") }
        override fun resume() { Log.w(TAG, "recordingHardware.resume(): $MESSAGE") }
        override fun stop() { Log.w(TAG, "recordingHardware.stop(): $MESSAGE") }
        override fun release() { Log.w(TAG, "recordingHardware.release(): $MESSAGE") }
    }

    /** Bluetooth-SCO mic-route subsystem stub. */
    val bluetoothSco: BluetoothScoSubsystem = object : BluetoothScoSubsystem {
        override fun start() { Log.w(TAG, "bluetoothSco.start(): $MESSAGE") }
        override fun stop() { Log.w(TAG, "bluetoothSco.stop(): $MESSAGE") }
    }

    /** System AudioFocus subsystem stub. */
    val audioFocus: AudioFocusSubsystem = object : AudioFocusSubsystem {
        override fun request() { Log.w(TAG, "audioFocus.request(): $MESSAGE") }
        override fun release() { Log.w(TAG, "audioFocus.release(): $MESSAGE") }
    }

    /** Recording-duration timer stub. */
    val recordingTimer: RecordingTimerSubsystem = object : RecordingTimerSubsystem {
        override fun start() { Log.w(TAG, "recordingTimer.start(): $MESSAGE") }
        override fun pause() { Log.w(TAG, "recordingTimer.pause(): $MESSAGE") }
        override fun resume() { Log.w(TAG, "recordingTimer.resume(): $MESSAGE") }
        override fun reset() { Log.w(TAG, "recordingTimer.reset(): $MESSAGE") }
    }

    /** Live-amplitude sampler stub. */
    val amplitudeStream: AmplitudeStreamSubsystem = object : AmplitudeStreamSubsystem {
        override fun start() { Log.w(TAG, "amplitudeStream.start(): $MESSAGE") }
        override fun stop() { Log.w(TAG, "amplitudeStream.stop(): $MESSAGE") }
    }

    /** Keyboard-border glow animation driver stub. */
    val borderGlow: BorderGlowSubsystem = object : BorderGlowSubsystem {
        override fun start() { Log.w(TAG, "borderGlow.start(): $MESSAGE") }
        override fun stop() { Log.w(TAG, "borderGlow.stop(): $MESSAGE") }
    }

    /** Pipeline-job submission + cancellation stub. */
    val pipelineRunner: PipelineRunnerSubsystem = object : PipelineRunnerSubsystem {
        override fun submit(sessionId: String, audioFile: File) {
            Log.w(TAG, "pipelineRunner.submit($sessionId, $audioFile): $MESSAGE")
        }
        override fun submitReprocess(sessionId: String, audioFile: File, queue: List<Int>, language: String?) {
            Log.w(TAG, "pipelineRunner.submitReprocess($sessionId, …): $MESSAGE")
        }
        override fun cancel(sessionId: String) {
            Log.w(TAG, "pipelineRunner.cancel($sessionId): $MESSAGE")
        }
        override fun isRunning(sessionId: String): Boolean = false
        override fun activeJobCount(): Int = 0
    }

    /**
     * `PendingSession` repo stub.
     *
     * `loadPending()` returns an empty list — the real recovery in B3
     * + C9/C10 (DB-persistence) reads from `sessionDao()`. Empty list
     * is the correct C7 baseline: with no DB-replay yet,
     * [DictateUiState.pendingSessions] starts empty after recovery.
     *
     * `pendingFlow()` returns an empty flow so the
     * `PendingSessionsModule` observer (when wired in B3) does not
     * NPE.
     */
    val sessionRepo: PipelineSessionRepoSubsystem = object : PipelineSessionRepoSubsystem {
        override suspend fun loadPending(): List<PendingSession> = emptyList()
        override suspend fun markInserted(sessionId: String, at: Long) {
            Log.w(TAG, "sessionRepo.markInserted($sessionId, $at): $MESSAGE")
        }
        override suspend fun markFailed(sessionId: String, reason: String) {
            Log.w(TAG, "sessionRepo.markFailed($sessionId, $reason): $MESSAGE")
        }
        override fun pendingFlow(): Flow<List<PendingSession>> = emptyFlow()
    }

    /** Notification coordinator stub — Block 1b will wire the real `PipelineNotificationCoordinator`. */
    val notificationCoordinator: PipelineNotificationCoordinatorSubsystem =
        object : PipelineNotificationCoordinatorSubsystem {
            override fun show(status: NotificationStatus) {
                Log.w(TAG, "notificationCoordinator.show($status): $MESSAGE")
            }
            override fun dismiss() { Log.w(TAG, "notificationCoordinator.dismiss(): $MESSAGE") }
        }

    /**
     * `ToastSink` stub that ALSO calls the system Toast if a
     * [android.content.Context] is provided. Modules signalling
     * user-visible errors (storage-full, mic-permission-denied, …)
     * still surface them in C7 — only the silenced flow is the
     * adapter-internal call-paths.
     *
     * For the production wiring, [PipelineServiceStubs.toastSink]
     * function below builds an instance bound to the service Context.
     */
    val toastSink: ToastSink = object : ToastSink {
        override fun show(message: CharSequence) {
            Log.w(TAG, "toastSink.show($message): $MESSAGE")
        }
        override fun showError(message: CharSequence) {
            Log.e(TAG, "toastSink.showError($message): $MESSAGE")
        }
    }

    // ToastSink uses block-body methods already (Log.e returns Int);
    // ditto sessionRepo's suspend methods (already block bodies).

    /**
     * Lazy `AudioFileFactory` stub that returns a cache-dir path.
     * Block 4 (`CacheDirAudioFileFactory`) supplies the real
     * pre-dispatch allocator. The stub returns a constant filename so
     * effect-handler regressions land predictably.
     */
    val audioFileFactory: AudioFileFactory = object : AudioFileFactory {
        override fun allocate(): File {
            Log.w(TAG, "audioFileFactory.allocate(): $MESSAGE — returning /tmp/dictate-stub-audio.m4a")
            return File("/tmp/dictate-stub-audio.m4a")
        }
    }
}

/**
 * Build a [ToastSink] wired to a real [android.content.Context] so
 * the toasts modules emit during C7 actually surface to the user.
 *
 * Block 3 replaces this with a permanent `ToastSink` adapter in the
 * subsystem package; for C7 we keep it inline to minimise the touch
 * surface.
 */
internal fun realToastSink(applicationContext: android.content.Context): ToastSink =
    object : ToastSink {
        override fun show(message: CharSequence) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
        override fun showError(message: CharSequence) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

/**
 * Build a [PipelineSessionRepoSubsystem] for the C7 baseline.
 *
 * Currently delegates to [PipelineServiceStubSubsystems.sessionRepo]
 * (empty-list `loadPending`). Block 3 / chunks C9+C10 replace this
 * with a real DAO-backed adapter that reads from
 * `DictateDatabase.sessionDao()` and applies the §6.3 recovery rules.
 *
 * @param sharedPrefs unused today; the parameter exists so B3 can
 *   inline-fix a smaller diff if it needs the prefs for session
 *   migration on the first read.
 */
@Suppress("UNUSED_PARAMETER")
internal fun stubSessionRepo(sharedPrefs: SharedPreferences): PipelineSessionRepoSubsystem =
    PipelineServiceStubSubsystems.sessionRepo
