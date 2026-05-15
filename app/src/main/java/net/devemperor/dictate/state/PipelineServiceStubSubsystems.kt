package net.devemperor.dictate.state

import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * Test-only stubs + production fallbacks for the subsystem
 * interfaces in [ModuleServices].
 *
 * **Post-B3 reality (B3-VAL-W1 F-18 documentation refresh):** after
 * the C8 subsystem-adapter migration, this file retains only:
 *
 *  - **Two production-route stubs** that B3 intentionally leaves
 *    for B5/B6: [pipelineRunner] (orchestrator-side job submission
 *    lands when LayoutCatalog drives recordings end-to-end) and
 *    [notificationCoordinator] (the Spec 1 §7.4
 *    `PipelineNotificationCoordinator` class is unwritten — Spec 1
 *    §7.4-§7.5 will be implemented in B5/B6 alongside the action
 *    router).
 *  - **A defensive fallback** for [bluetoothSco] when the system
 *    `AudioManager` is `null` (Robolectric / stripped Context
 *    paths). Production hardware paths always wire the real
 *    [BluetoothScoSubsystemAdapter] in
 *    [net.devemperor.dictate.core.DictatePipelineService.onCreate].
 *  - **Deprecated** [sessionRepo] + [audioFileFactory] — superseded
 *    by `PipelineSessionRepoAdapter` (C10) and
 *    `CacheDirAudioFileFactory` (C11). Retained for test-only
 *    compile-compat.
 *  - **Test-only** [recordingHardware], [audioFocus],
 *    [recordingTimer], [amplitudeStream], [borderGlow] — the
 *    production adapters live in [net.devemperor.dictate.core.*Adapter]
 *    files and are wired directly into `ModuleServices` at service
 *    construction.
 *  - [realToastSink] is a **production-quality binding** to the
 *    Android Toast system.
 *
 * **Cross-reference for the post-B3 production wiring:** see
 * [net.devemperor.dictate.core.DictatePipelineService.onCreate]
 * Step 3 (subsystem adapters) and Step 4 (`ModuleServices`
 * construction).
 *
 * @see net.devemperor.dictate.state.ModuleServices
 * @see net.devemperor.dictate.core.DictatePipelineService
 */
internal object PipelineServiceStubSubsystems {

    private const val TAG = "PipelineServiceStub"
    private const val MESSAGE = "Test-only stub — real subsystem lives in the *Adapter classes in core/"

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
        override fun submitReprocess(sessionId: String, audioFile: File?, queue: List<Int>, language: String?) {
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
    @Deprecated(
        "Replaced by PipelineSessionRepoAdapter in C10 — kept for test-only compile-compat",
        level = DeprecationLevel.WARNING,
    )
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
     * Deprecated `AudioFileFactory` stub (C7 baseline). Block 4 / C11
     * replaced production wiring with [net.devemperor.dictate.core.CacheDirAudioFileFactory]
     * — `DictatePipelineService.onCreate` no longer references this
     * field. Retained only for test code that wants a deterministic
     * no-FS factory.
     */
    @Deprecated(
        "Replaced by CacheDirAudioFileFactory in C11 — kept for test-only compile-compat",
        level = DeprecationLevel.WARNING,
    )
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
        override fun show(resId: Int) {
            show(applicationContext.getString(resId))
        }
    }

/**
 * **C10 — deprecated (kept for compile-compat only).** Build a stub
 * [PipelineSessionRepoSubsystem] that returns empty for every query.
 *
 * Was the C7 wiring; the production
 * [net.devemperor.dictate.core.DictatePipelineService.onCreate] no
 * longer calls this — it constructs a real
 * [net.devemperor.dictate.state.PipelineSessionRepoAdapter] backed by
 * `DictateDatabase.sessionDao()` (Spec 1 §6.3 + §6.4 + KG-SST-2).
 *
 * Retained for two reasons:
 *
 *  1. Test code that wants a "no-database" baseline can import this
 *     function instead of constructing the underlying object directly.
 *  2. Fallback wiring during a future B5+ refactor if the DAO surface
 *     changes again — the type-stable function name reduces touch
 *     surface.
 *
 * @param sharedPrefs unused; preserved as a no-op signature parameter
 *   so callers that previously passed prefs don't break.
 */
@Suppress("UNUSED_PARAMETER", "DEPRECATION")
internal fun stubSessionRepo(sharedPrefs: SharedPreferences): PipelineSessionRepoSubsystem =
    PipelineServiceStubSubsystems.sessionRepo
