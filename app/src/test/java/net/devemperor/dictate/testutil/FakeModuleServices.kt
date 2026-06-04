package net.devemperor.dictate.testutil

import android.content.ClipboardManager
import android.content.SharedPreferences
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.devemperor.dictate.state.Action
import net.devemperor.dictate.state.AmplitudeStreamSubsystem
import net.devemperor.dictate.state.AudioFileFactory
import net.devemperor.dictate.state.AudioFocusSubsystem
import net.devemperor.dictate.state.BluetoothScoSubsystem
import net.devemperor.dictate.state.BorderGlowSubsystem
import net.devemperor.dictate.state.ContinuationLookup
import net.devemperor.dictate.state.InsertionTarget
import net.devemperor.dictate.state.ModuleServices
import net.devemperor.dictate.state.NoopContinuationLookup
import net.devemperor.dictate.state.NotificationStatus
import net.devemperor.dictate.state.PendingSession
import net.devemperor.dictate.state.PipelineNotificationCoordinatorSubsystem
import net.devemperor.dictate.state.PipelineRunnerSubsystem
import net.devemperor.dictate.state.PipelineSessionRepoSubsystem
import net.devemperor.dictate.state.PrefPersistenceService
import net.devemperor.dictate.state.RecordingHardwareSubsystem
import net.devemperor.dictate.state.SharedPrefsPersistenceService
import net.devemperor.dictate.state.RecordingTimerSubsystem
import net.devemperor.dictate.state.ToastSink
import java.io.File

/**
 * Test fixture: a [ModuleServices] populated with no-op fakes for every
 * subsystem.
 *
 * Tests that need to invoke `DictateOrchestrator.dispatch(...)` build
 * the orchestrator with this fixture; the no-op fakes mean
 * `runEffect(...)` never throws and never actually touches Android. For
 * tests that care about a specific subsystem's interactions, use the
 * named factory parameter to swap the matching field with a counting
 * fake (handwritten K-1, no mocking framework).
 *
 * **Why not Mockito?** Per K-1 (the test conventions) — Dictate's tests
 * are mockito-free. Hand-rolled fakes are precise (they record what
 * matters) and they survive Kotlin's null-safety + sealed-types
 * constraints better than reflective stubs.
 *
 * @see net.devemperor.dictate.state.ModuleServices
 */
fun fakeModuleServices(
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    emitAction: (Action) -> Unit = {},
    recordingHardware: RecordingHardwareSubsystem = NoopRecordingHardware,
    bluetoothSco: BluetoothScoSubsystem = NoopBluetoothSco,
    audioFocus: AudioFocusSubsystem = NoopAudioFocus,
    recordingTimer: RecordingTimerSubsystem = NoopRecordingTimer,
    amplitudeStream: AmplitudeStreamSubsystem = NoopAmplitudeStream,
    borderGlow: BorderGlowSubsystem = NoopBorderGlow,
    pipelineRunner: PipelineRunnerSubsystem = NoopPipelineRunner,
    sessionRepo: PipelineSessionRepoSubsystem = NoopSessionRepo,
    notificationCoordinator: PipelineNotificationCoordinatorSubsystem = NoopNotificationCoordinator,
    inputConnectionProvider: () -> InputConnection? = { null },
    insertionServiceProvider: () -> net.devemperor.dictate.state.insertion.InsertionService? = { null },
    clipboard: ClipboardManager? = null,
    sharedPrefs: SharedPreferences = FakeSharedPreferences(),
    // Chunk 3.0 — defaults to a service backed by the same `sharedPrefs`
    // so tests that don't care about persistence keep working unchanged
    // (writes round-trip through the same fake). Tests asserting on
    // PrefPersistenceService calls pass a `RecordingPrefPersistenceService`.
    prefs: PrefPersistenceService = SharedPrefsPersistenceService(sharedPrefs),
    toastSink: ToastSink = NoopToastSink,
    audioFileFactory: AudioFileFactory = NoopAudioFileFactory,
    audioFileRepository: net.devemperor.dictate.audio.AudioFileRepository = NoopAudioFileRepository,
    continuationLookup: ContinuationLookup = NoopContinuationLookup,
): ModuleServices = ModuleServices(
    recordingHardware = recordingHardware,
    bluetoothSco = bluetoothSco,
    audioFocus = audioFocus,
    recordingTimer = recordingTimer,
    amplitudeStream = amplitudeStream,
    borderGlow = borderGlow,
    pipelineRunner = pipelineRunner,
    sessionRepo = sessionRepo,
    notificationCoordinator = notificationCoordinator,
    inputConnectionProvider = inputConnectionProvider,
    insertionServiceProvider = insertionServiceProvider,
    clipboard = clipboard,
    sharedPrefs = sharedPrefs,
    prefs = prefs,
    toastSink = toastSink,
    audioFileFactory = audioFileFactory,
    audioFileRepository = audioFileRepository,
    continuationLookup = continuationLookup,
    scope = scope,
    emitAction = emitAction,
)

/**
 * Default [net.devemperor.dictate.audio.AudioFileRepository] for tests
 * that don't care about allocation specifics. Returns a deterministic
 * temp-file path; tests that assert on file identity or count call
 * counts substitute a `RecordingAudioFileRepository` or
 * `FixedAudioFileFactory` (the ActionResolversTest's hand-rolled fake).
 *
 * `readForPipeline` returns null and `segments` returns empty so a test
 * that incidentally hits the read path observes a clean "no audio"
 * shape rather than an exception.
 */
object NoopAudioFileRepository : net.devemperor.dictate.audio.AudioFileRepository {
    override fun allocateFirst(sessionId: String): java.io.File =
        java.io.File("/tmp/noop-audio-first-$sessionId.m4a")
    override fun allocateNext(sessionId: String): java.io.File =
        java.io.File("/tmp/noop-audio-next-$sessionId.m4a")
    override fun segments(sessionId: String): List<java.io.File> = emptyList()
    override suspend fun readForPipeline(
        sessionId: String,
    ): net.devemperor.dictate.audio.PipelineAudioResult? = null
    override fun deleteAll(sessionId: String) = Unit
    override fun listOrphanSessionIds(knownSessionIds: Set<String>): Set<String> = emptySet()
    override fun listAllOwnedFiles(): Map<String, List<java.io.File>> = emptyMap()
}

/**
 * Test fake for [PrefPersistenceService] that captures every
 * `persist` call without writing anywhere. Useful for tests asserting
 * an Effect emitted a specific Pref write without also touching the
 * SP-listener mirror.
 */
class RecordingPrefPersistenceService : PrefPersistenceService {
    val writes: MutableList<Pair<net.devemperor.dictate.preferences.Pref<*>, Any?>> = mutableListOf()

    override fun <T> persist(pref: net.devemperor.dictate.preferences.Pref<T>, value: T) {
        writes += pref to value
    }
}

// ──── No-op fakes ────────────────────────────────────────────────────

object NoopRecordingHardware : RecordingHardwareSubsystem {
    override fun allocate(
        target: InsertionTarget,
        useBluetooth: Boolean,
        audioFile: File,
        codecParams: net.devemperor.dictate.audio.CodecParams?,
        sessionId: String?,
    ) = Unit
    override fun start() = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun release() = Unit
}

object NoopBluetoothSco : BluetoothScoSubsystem {
    override fun start() = Unit
    override fun stop() = Unit
}

object NoopAudioFocus : AudioFocusSubsystem {
    override fun request() = Unit
    override fun release() = Unit
}

object NoopRecordingTimer : RecordingTimerSubsystem {
    override fun start() = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun reset() = Unit
}

object NoopAmplitudeStream : AmplitudeStreamSubsystem {
    override fun start() = Unit
    override fun stop() = Unit
}

object NoopBorderGlow : BorderGlowSubsystem {
    override fun start() = Unit
    override fun stop() = Unit
}

object NoopPipelineRunner : PipelineRunnerSubsystem {
    override fun submit(sessionId: String, audioFile: File) = Unit
    override fun submitReprocess(
        sessionId: String,
        audioFile: File?,
        queue: List<Int>,
        language: String?,
    ) = Unit

    override fun cancel(sessionId: String) = Unit
    override fun isRunning(sessionId: String): Boolean = false
    override fun activeJobCount(): Int = 0
}

object NoopSessionRepo : PipelineSessionRepoSubsystem {
    override suspend fun loadPending(): List<PendingSession> = emptyList()
    override suspend fun markInserted(sessionId: String, at: Long) = Unit
    override suspend fun markFailed(sessionId: String, reason: String) = Unit
    override fun pendingFlow(): kotlinx.coroutines.flow.Flow<List<PendingSession>> =
        kotlinx.coroutines.flow.emptyFlow()
    override suspend fun syncAudioFilePaths(sessionId: String): Int = 0
}

object NoopNotificationCoordinator : PipelineNotificationCoordinatorSubsystem {
    override fun show(status: NotificationStatus) = Unit
    override fun dismiss() = Unit
}

object NoopToastSink : ToastSink {
    override fun show(message: CharSequence) = Unit
    override fun showError(message: CharSequence) = Unit
}

object NoopAudioFileFactory : AudioFileFactory {
    override fun allocate(): File = File("/tmp/test-noop.m4a")
}
