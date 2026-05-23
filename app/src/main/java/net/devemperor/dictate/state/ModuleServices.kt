package net.devemperor.dictate.state

import android.content.ClipboardManager
import android.content.SharedPreferences
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import net.devemperor.dictate.preferences.Pref
import net.devemperor.dictate.preferences.put

/**
 * Dependency-injection container for the per-module side-effect handlers.
 *
 * The orchestrator hands an instance of this class to every
 * [DictateModule.runEffect] call. Modules access subsystem dependencies
 * **by name** (e.g. `services.recordingHardware.allocate(...)`) — the
 * field shape is the DI contract.
 *
 * **Why a `class` (not an interface)?** Spec 1 §4.7 prescribes a
 * concrete class with `val`-fields. Modules access fields directly (not
 * via method calls), so the class form is the natural DI container.
 *
 * **Lifecycle:** one instance per `DictatePipelineService` bind. The
 * service builds the instance in `onCreate` (Block 3 wiring) and hands
 * it to the orchestrator; the orchestrator just stores the reference.
 * On `onDestroy` the service drops the reference and lets each
 * subsystem clean up via [DictateModule.terminate].
 *
 * **Threading contract:** all field accesses run on
 * `serviceScope.dispatcher` (Main.immediate). Background effects launch
 * into [scope] via `services.scope.launch { … }`.
 *
 * **Concrete-subsystem status (Chunk C4):**
 *
 * - [scope] and [emitAction] — concrete, wired in this chunk via the
 *   constructor.
 * - All other fields — typed by **interface stubs** (`*Subsystem`,
 *   `*Sink`, `*Factory`) declared in this file. The interfaces define
 *   the contract that B3 implements with the real Android-backed
 *   subsystem classes. Modules implemented in C5/C6 sign on these
 *   interfaces, so the production wire-up in B3 is a swap-in.
 * - [sharedPrefs], [clipboard], [inputConnectionProvider] — Android
 *   types (already real), populated by the service from
 *   `getSystemService(...)` calls in B3.
 *
 * @property recordingHardware MediaRecorder lifecycle adapter
 *   (`allocate` → `prepare` → `start` → `stop` → `release`).
 * @property bluetoothSco Bluetooth-SCO mic-route subsystem.
 * @property audioFocus AudioFocus subsystem (request / release).
 * @property recordingTimer monotonic recording-duration counter; emits
 *   `RecordingTimer.Tick` actions or feeds a flow into the
 *   amplitude/timer UI.
 * @property amplitudeStream microphone-amplitude sampler for the
 *   live recording UI.
 * @property borderGlow keyboard-border glow animation driver.
 * @property pipelineRunner pipeline-job submission + cancellation
 *   (Spec 1 §4.9 `PipelineRunner`).
 * @property sessionRepo persistence for `PendingSession` entities
 *   (Spec 1 §4.9 `PipelineSessionRepo`).
 * @property notificationCoordinator the persistent FGS notification
 *   updater (no-op outside the service).
 * @property inputConnectionProvider lazy supplier of the active
 *   `InputConnection`. Effect handlers call
 *   `inputConnectionProvider()?.commitText(…)` — `null` is a no-op
 *   when the IME-View is detached.
 * @property clipboard system clipboard (nullable per Android docs;
 *   effect handlers treat `null` as no-op).
 * @property sharedPrefs the app's default `SharedPreferences`; modules
 *   may read but the canonical Pref-mirror lives in
 *   `PipelinePrefMirror` (C7). For the **State→SP write direction** use
 *   [prefs] instead — it is the single, typed write seam every module
 *   should route through.
 * @property prefs typed `SharedPreferences` write seam (indirection-cleanup
 *   2026-05-21, Chunk 3.0). Modules that own a `Pref`-mirrored axis emit
 *   an `Effect.PersistPref<T>` (or module-local equivalent) and their
 *   `runEffect` calls `services.prefs.persist(pref, value)`. The
 *   production implementation [SharedPrefsPersistenceService] forwards to
 *   the same [sharedPrefs] this field is built from; the indirection
 *   exists to a) keep the write seam testable without an Android
 *   `SharedPreferences.Editor` mock, and b) document the canonical
 *   "State → SP" direction (the SP → State direction stays with
 *   [net.devemperor.dictate.state.PipelinePrefMirror]).
 * @property toastSink Android `Toast` indirection (nullable backend
 *   for test environments).
 * @property audioFileFactory Pre-Dispatch-Allocator for audio cache
 *   files (Spec 1 §4.11). The caller of `dispatch(StartRecording(...))`
 *   allocates the `audioFile` first via this factory so the reducer
 *   stays pure.
 * @property scope FGS-scoped coroutine context (Spec 1 §4.7). All
 *   async work inside `runEffect` launches into this scope; cancellation
 *   on `Service.onDestroy` cancels every in-flight effect.
 * @property emitAction posts a new action to the orchestrator for
 *   **async** main-thread dispatch. Effect handlers that need to
 *   trigger follow-up actions MUST use this (not call
 *   `orchestrator.dispatch` directly) — see ADR-0001
 *   §"Main-Thread Confined Dispatch" and forbidden pattern (h).
 *
 * @see net.devemperor.dictate.state.DictateModule.runEffect
 * @see docs/architecture/state-architecture/effects-and-failures.md §3
 * @see docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md §4.7
 */
class ModuleServices(
    val recordingHardware: RecordingHardwareSubsystem,
    val bluetoothSco: BluetoothScoSubsystem,
    val audioFocus: AudioFocusSubsystem,
    val recordingTimer: RecordingTimerSubsystem,
    val amplitudeStream: AmplitudeStreamSubsystem,
    val borderGlow: BorderGlowSubsystem,
    val pipelineRunner: PipelineRunnerSubsystem,
    val sessionRepo: PipelineSessionRepoSubsystem,
    val notificationCoordinator: PipelineNotificationCoordinatorSubsystem,
    val inputConnectionProvider: () -> InputConnection?,
    val clipboard: ClipboardManager?,
    val sharedPrefs: SharedPreferences,
    val prefs: PrefPersistenceService,
    val toastSink: ToastSink,
    val audioFileFactory: AudioFileFactory,
    /**
     * Recording audio-file repository (recording-stack-completion
     * Block A1 + Initial-File cutover). The pre-dispatch Record-Action
     * resolver calls [net.devemperor.dictate.audio.AudioFileRepository.allocateFirst]
     * to mint `sess_{sid}_seg1.m4a` BEFORE dispatching `StartRecording`,
     * so the rolling-segments path's `allocateNext` produces `_seg2`,
     * `_seg3`, … under the **same** naming convention. Without this,
     * `segments(sid)` would miss the initial file (the old
     * [audioFileFactory.allocate] produced `rec_{ts}_{uuid8}.m4a`,
     * which doesn't match the `sess_*_seg*` prefix scan).
     */
    val audioFileRepository: net.devemperor.dictate.audio.AudioFileRepository,
    val continuationLookup: ContinuationLookup,
    val scope: CoroutineScope,
    val emitAction: (Action) -> Unit,
)

/**
 * Typed `SharedPreferences` **write seam** for module Effects
 * (indirection-cleanup 2026-05-21, Chunk 3.0 — generic Pref-persistence
 * foundation, per plan §4 Block 3 Chunk 3.0).
 *
 * **Why this exists.** Before Chunk 3.0 the State → SP direction lived
 * in two places: a) module effects writing directly through
 * `services.sharedPrefs.edit().put(Pref.X, ...).apply()` (e.g.
 * [LayoutModule], [OverlayModule]), and b) click-handler imperative SP
 * writes inside `DictateInputMethodService.java` (the original
 * "7-stage SP-roundtrip" anti-pattern). Routing all module-side writes
 * through this interface gives the architecture a single canonical
 * write seam — symmetric to [PipelinePrefMirror] on the SP → State
 * direction. Click-handlers no longer write SP at all (they
 * `dispatch(Action.X)` and the reducer emits an Effect that lands here).
 *
 * **Threading.** Modules call [persist] from `runEffect`, which the
 * orchestrator runs on `services.scope` (Main-immediate). The
 * underlying `SharedPreferences.Editor.apply()` is async-write to disk
 * but the in-memory pref value is visible to the next reader on the
 * same thread immediately. The `PipelinePrefMirror`
 * `OnSharedPreferenceChangeListener` re-emits the just-written value
 * back into the store; the resulting `current.copy(...)` is structurally
 * identical and the `MutableStateFlow` distinct-emission contract
 * absorbs the no-op — no feedback loop.
 *
 * **Not a coalescer.** Every call writes once. If a reducer needs to
 * batch (e.g. write two related prefs atomically) it emits two effects
 * — the call site can build a single `Editor` if needed, but the
 * canonical entry is one effect per pref. Coalescing across effects
 * would risk a race with the [PipelinePrefMirror] listener (see its
 * KDoc).
 *
 * @see SharedPrefsPersistenceService
 * @see net.devemperor.dictate.state.PipelinePrefMirror
 * @see docs/plans/2026-05-21 - dictate-indirection-cleanup/dictate-indirection-cleanup.md §4 Block 3 Chunk 3.0
 */
interface PrefPersistenceService {

    /**
     * Persist `value` under the typed `pref` key. The default
     * implementation forwards to the `DictatePrefsKt`-extension
     * `SharedPreferences.Editor.put(pref, value).apply()`.
     *
     * Idempotent at the persistence level — writing the same value
     * twice produces the same on-disk + in-memory state and the
     * mirror-listener absorbs the no-op via distinct-emission.
     */
    fun <T> persist(pref: Pref<T>, value: T)
}

/**
 * Production [PrefPersistenceService] backed by a real
 * [SharedPreferences] instance. Used in
 * [net.devemperor.dictate.core.DictatePipelineService.onCreate] to
 * wire the orchestrator's [ModuleServices].
 *
 * Tests substitute either a recording fake (for assertions on what
 * the module wrote) or the [SharedPrefsPersistenceService] itself
 * with a [net.devemperor.dictate.testutil.FakeSharedPreferences]
 * underneath — the latter is the cheapest path because it exercises
 * the same `DictatePrefsKt` extension functions production runs.
 */
class SharedPrefsPersistenceService(
    private val sp: SharedPreferences,
) : PrefPersistenceService {
    override fun <T> persist(pref: Pref<T>, value: T) {
        sp.edit().put(pref, value).apply()
    }
}

/**
 * Declarative SharedPreferences ↔ sub-state mirror entry.
 *
 * **Phase 1 (today):** the mirror is hard-coded in `PipelinePrefMirror`
 * (Spec 1 §4.5). Module `prefBindings()` is **dead code** — the default
 * empty list MUST stay empty in Phase 1, otherwise Pref mutations would
 * fire twice (once via hardcoded mirror, once via module bindings) with
 * race-risk.
 *
 * **Phase 2 (backlog):** the hardcoded mirror is removed and
 * `PipelinePrefMirror` iterates `modules.flatMap { it.prefBindings() }`
 * as the single source.
 *
 * @param S the module's sub-state type
 * @param T the binding's value type (read from `SharedPreferences`,
 *   written into the sub-state via [write]).
 * @property prefKey the SharedPreferences key as a string (matches
 *   `Pref.X.key` in `DictatePrefs.kt`).
 * @property read function reading the typed value from a
 *   `SharedPreferences` snapshot.
 * @property write function applying the value to a sub-state instance,
 *   returning the updated sub-state.
 *
 * @see net.devemperor.dictate.state.DictateModule.prefBindings
 */
data class PrefBinding<S, T>(
    val prefKey: String,
    val read: (SharedPreferences) -> T,
    val write: (S, T) -> S,
)

// ════════════════════════════════════════════════════════════════════
// Subsystem interfaces (Chunk C4 — contracts; B3 supplies implementations)
// ════════════════════════════════════════════════════════════════════
//
// Each interface below is the minimum surface that **the module-side
// effect handlers** need. Concrete Android-backed implementations land
// in Block 3 (Subsystem-Adapter-Migration). Splitting the contract from
// the implementation here lets C5/C6 modules sign on the interfaces and
// be unit-tested with hand-rolled fakes — no Robolectric needed.
//
// Why interfaces (not bare class references)? Three reasons:
//
//  1. **K-1** — hand-rolled fakes (no mocking framework). An interface
//     surface is what fakes implement.
//  2. **Adapter substitution** — B3 will likely route legacy classes
//     like `RecordingManager` through thin adapter implementations;
//     interfaces make the substitution mechanical.
//  3. **C4 testability** — `DictateOrchestrator` tests need to build
//     a `ModuleServices` instance to invoke `runEffect`. With
//     interfaces, the test can pass [NoopServices.instance] without
//     pulling in Android infrastructure.

/**
 * Recording-hardware adapter. The minimum surface needed by
 * `RecordingModule.runEffect` (Spec 1 §15.2).
 *
 * **`allocate` signature (Phase-B S-4 fix):** the audio-file path
 * lives in the state (R.2 — `RecordingState.Preparing.audioFile`);
 * `allocate` takes the file as the **third** argument so the
 * subsystem never reads `cacheDir` directly. The pre-dispatch
 * allocator ([AudioFileFactory]) builds the file before
 * `Action.RecordingAction.StartRecording` is dispatched.
 */
interface RecordingHardwareSubsystem {
    /**
     * Allocate the MediaRecorder + storage for a new recording.
     * Returns asynchronously via `Action.RecordingAction.MediaRecorderReady`
     * (emitted by the subsystem's internal callback).
     *
     * @param target the insertion target captured at start-time.
     * @param useBluetooth whether to wire the SCO mic route.
     * @param audioFile pre-allocated output file (R.2).
     * @param codecParams codec params to configure the MediaRecorder
     *   with. `null` selects [net.devemperor.dictate.audio.CodecParams.DEFAULT_AAC_M4A]
     *   (the historic defaults — used for fresh sessions). Cold-Resume
     *   passes the params read from the previous segment so the eventual
     *   MediaMuxer concat in `AudioFileRepository.readForPipeline` does
     *   not reject heterogeneous formats (ADR-0007 §"Failure-Modes §1",
     *   B1.2 mitigation).
     * @param sessionId the active session-id. Required for Rolling-
     *   Segments (B1.3) — the adapter stores it and uses it to call
     *   `audioFileRepository.allocateNext(sessionId)` on each rolling
     *   tick. `null` disables rolling for this allocate (used by tests
     *   and any caller that does not own a session-id).
     */
    fun allocate(
        target: InsertionTarget,
        useBluetooth: Boolean,
        audioFile: java.io.File,
        codecParams: net.devemperor.dictate.audio.CodecParams? = null,
        sessionId: String? = null,
    )

    /** Begin recording — must follow a successful `allocate`. */
    fun start()

    /** Pause the active recording (resumable). */
    fun pause()

    /** Resume a paused recording. */
    fun resume()

    /** Stop the recording and flush to disk. */
    fun stop()

    /** Release hardware (cancel + cleanup). Safe to call from `terminate()`. */
    fun release()
}

/**
 * Bluetooth-SCO subsystem (mic-route to Bluetooth headset).
 *
 * Emits Action.AudioAction.OnBluetoothScoStateChanged via the
 * [ModuleServices.emitAction] indirection when the OS reports
 * connection state changes.
 */
interface BluetoothScoSubsystem {
    fun start()
    fun stop()
}

/**
 * AudioFocus subsystem (system-audio focus request / release).
 */
interface AudioFocusSubsystem {
    fun request()
    fun release()
}

/**
 * Monotonic recording-duration timer.
 *
 * Lives behind an interface for two reasons:
 *  1. Tests don't want a real clock thread.
 *  2. The Phase-2 telemetry hook (anomalous-duration detection) will
 *     intercept here.
 */
interface RecordingTimerSubsystem {
    fun start()
    fun pause()
    fun resume()
    fun reset()
}

/**
 * Live amplitude sampler for the recording UI animation
 * (`Recording.Active` → bar pulse).
 */
interface AmplitudeStreamSubsystem {
    fun start()
    fun stop()
}

/**
 * Keyboard-border glow animation driver. Triggered during
 * Recording.Active to give the user a visual indicator that recording
 * is on.
 */
interface BorderGlowSubsystem {
    fun start()
    fun stop()
}

/**
 * Pipeline-job submission + cancellation (Spec 1 §4.9).
 *
 * Modules call `submit(sessionId, audioFile)` from
 * `PipelineModule.runEffect`; failures are routed back via
 * `Action.PipelineAction.PipelineFailed`.
 */
interface PipelineRunnerSubsystem {
    fun submit(sessionId: String, audioFile: java.io.File)

    /**
     * Re-run a pipeline against a session's already-recorded audio.
     *
     * **F-19 (2026-05-15) — `audioFile` is nullable.** When `null`, the
     * runner resolves the path from the DB session record (the
     * staging-FSM is pure-state-only and doesn't carry the file). When
     * non-null, the runner uses the supplied file directly.
     */
    fun submitReprocess(
        sessionId: String,
        audioFile: java.io.File?,
        queue: List<Int>,
        language: String?,
    )

    fun cancel(sessionId: String)
    fun isRunning(sessionId: String): Boolean
    fun activeJobCount(): Int
}

/**
 * Persistence for the `PendingSession`-list (Spec 1 §4.9
 * `PipelineSessionRepo`). PendingSessionsModule subscribes to
 * [pendingFlow] and emits `PendingSessionsAction.Refresh` whenever the
 * DB changes.
 */
interface PipelineSessionRepoSubsystem {
    suspend fun loadPending(): List<PendingSession>
    suspend fun markInserted(sessionId: String, at: Long)
    suspend fun markFailed(sessionId: String, reason: String)
    fun pendingFlow(): kotlinx.coroutines.flow.Flow<List<PendingSession>>

    /**
     * Synchronise the session's `audio_file_paths` column with the live
     * segment list on disk (recording-stack-completion Block A1).
     *
     * Reads the segments from [net.devemperor.dictate.audio.AudioFileRepository.segments]
     * and writes their absolute paths into the DB row. Called from
     * [RecordingModule]'s effect handler on three boundaries:
     *
     *  1. **`MediaRecorderReady`** (Preparing → Active) — first segment
     *     is allocated; persists the single-element list so the row leaves
     *     the empty-list state.
     *  2. **`SegmentRolled`** (Rolling-Segments handover in Active state) —
     *     a new segment file went live; appends it to the list so a crash
     *     after this point leaves a recoverable trail.
     *  3. **`StartRecordingContinuation`** (Cold-Resume) — the
     *     ContinuationLookup minted a new segment via `allocateNext` but
     *     never wrote to the DB; this is the first sync that picks it up.
     *
     * @return the count of segments that ended up in the column (for
     *   telemetry / test assertions). Returns 0 on DAO/IO failure
     *   (logged but swallowed — recording must never crash because the
     *   path-sync failed).
     */
    suspend fun syncAudioFilePaths(sessionId: String): Int

    /**
     * Insert the session row for a freshly started recording with
     * `status = RECORDING` — the **first link of the recovery chain**
     * (2026-05-22).
     *
     * Called from [net.devemperor.dictate.state.RecordingModule]'s
     * effect handler on the `StartRecording` `Idle → Preparing`
     * boundary. The row is the anchor
     * [net.devemperor.dictate.state.PipelineRecovery] needs: a process
     * death mid-recording (the FGS is torn down when the user switches
     * keyboards) leaves a `RECORDING` row that the next service start
     * promotes to `RECORDING_INTERRUPTED`, making the audio recoverable
     * via the continuation-lookup. Without the row the recovery chain
     * has no first link and the recording is silently lost.
     *
     * Implementations are **fail-soft** — they swallow DAO failures
     * (recording must never crash because the row-create failed). The
     * default body is a no-op so test fakes / stub subsystems that do
     * not model persistence stay valid.
     *
     * @param audioFilePath the first segment's absolute path; written
     *   into both `audio_file_path` and `audio_file_paths` so the row
     *   leaves the empty-list state immediately. The `SyncAudioSegments`
     *   effects keep `audio_file_paths` fresh as segments roll.
     */
    suspend fun createRecordingSession(sessionId: String, audioFilePath: String): Unit = Unit

    /**
     * Re-arm an existing crash-interrupted session row to
     * `status = RECORDING` — called on the `StartRecordingContinuation`
     * boundary so a *second* interruption mid-continuation is caught by
     * [net.devemperor.dictate.state.PipelineRecovery] exactly like the
     * first. Fail-soft; default no-op.
     */
    suspend fun transitionToRecording(sessionId: String): Unit = Unit
}

/**
 * FGS persistent-notification updater. PipelineModule's effect handler
 * delegates here to update the "Running step X of Y" text in the
 * notification.
 */
interface PipelineNotificationCoordinatorSubsystem {
    fun show(status: NotificationStatus)
    fun dismiss()
}

/**
 * Sealed notification-status hierarchy (Spec 1 §11.x). Phase-1 placeholder
 * — the concrete variants are defined in B3 when the coordinator is
 * implemented. Kept minimal here to break the dependency chain.
 */
sealed interface NotificationStatus {
    data object Idle : NotificationStatus
    data class Recording(val sessionId: String) : NotificationStatus

    /**
     * Recording is paused (Spec 1 §7.6 "Recording-Paused" row —
     * `[Resume][Stopp][Senden]`). Emitted by [RecordingModule] on the
     * `Active → Paused` FSM edge once C5 routes the IME recording-trigger
     * through `dispatch(...)`.
     *
     * **Why a distinct variant (not reuse [Recording]):** §7.6 maps
     * Recording-Active to `[Pause]…` and Recording-Paused to `[Resume]…`
     * — the action-button set differs, so the coordinator's `when`
     * must distinguish them. Mirrors the [Recording] shape (carries the
     * FSM `sessionId`) so the back-channel `[Stopp]`/`[Senden]` buttons
     * resolve identically (the reducer reads the id off `state.recording`
     * per F-10, the buttons stay payload-less).
     *
     * **C4-IMPL-1 (B2 block-report):** added in C5. C4's coordinator
     * already renders `Recording`; this completes §7.6 by adding the
     * paused row + its emitter (the `Active → Paused` reducer arm).
     */
    data class Paused(val sessionId: String) : NotificationStatus
    data class Pipeline(val sessionId: String, val step: String) : NotificationStatus

    /**
     * Runtime overlay-permission-revoke fallback (Spec 3 §9, O7).
     * Emitted via [OverlayModule.Effect.NotifyOverlayPermissionRequired]
     * when `SYSTEM_ALERT_WINDOW` is revoked while WIDGET/HOVER is
     * active — the overlay falls back to in-IME rendering and this is
     * the only surface that can tell a user (potentially in another
     * app) why the floating widget disappeared.
     */
    data object OverlayPermissionRequired : NotificationStatus
}

/**
 * Toast indirection. Effect handlers call `services.toastSink.show(msg)`;
 * the real implementation in B3 posts to the IME's Looper.
 *
 * # `@StringRes` overload (B4-VAL F-4)
 *
 * [show] takes a `@StringRes Int` overload so resolvers without an Android
 * Context can dispatch toasts by resource id — the production
 * [net.devemperor.dictate.state.realToastSink] resolves it via
 * `applicationContext.getString(...)`. Test fakes default to no-op via
 * the interface default (override to capture if needed).
 */
interface ToastSink {
    fun show(message: CharSequence)
    fun showError(message: CharSequence)

    /**
     * Show a toast for a string-resource id. The default body is a no-op
     * — real implementations bound to an Android Context resolve the id
     * via `Context.getString(resId)` and forward to [show]. Test fakes
     * may override to capture the resource id for assertions.
     */
    fun show(@androidx.annotation.StringRes resId: Int) = Unit
}

/**
 * Pre-Dispatch-Allocator for audio cache files (Spec 1 §4.11).
 *
 * The reducer is pure, so `Action.RecordingAction.StartRecording` must
 * already carry an allocated [java.io.File]. Callers (the IME's
 * record-button click handler) ask the factory for a fresh file before
 * dispatching the action.
 *
 * Lives on `ModuleServices` so test code can inject a deterministic
 * factory (e.g. `tmp-1.m4a`, `tmp-2.m4a`).
 *
 * **Two responsibilities, two threads (Spec 1 §4.11.5.2):**
 *
 *  - [allocate] — runs on the **Main thread** from the View-layer
 *    pre-dispatch resolver. O(1) (`mkdirs()` + UUID name); no `listFiles`
 *    or `delete` operations allowed here.
 *  - [cleanupOrphans] — runs on **`Dispatchers.IO`** once per service
 *    boot. `listFiles` + per-file `delete` loop; bounded by the
 *    `referencedPaths` set from `SessionDao.findAllAudioFilePaths()`
 *    and a 60 s freshness cut-off (KG-AFF-4) that guards the
 *    allocate → MediaRecorder.prepare race.
 *
 * @see net.devemperor.dictate.core.CacheDirAudioFileFactory
 */
interface AudioFileFactory {
    /**
     * Allocate a new cache-file path. The file is **not** created on
     * disk by this call; `RecordingHardwareSubsystem.allocate` writes
     * to it when `MediaRecorder.start()` runs.
     *
     * `@Throws` is mandatory so Java callers (the IME's
     * `startRecording` resolver) can `catch (IOException)` without a
     * "never thrown" compile error.
     *
     * @throws java.io.IOException when the audio cache directory cannot
     *   be created (storage full, FS permission). Resolvers MUST catch
     *   and translate to a user-visible toast — the reducer never sees
     *   the failure (R.2 Pure-Reducer invariant).
     */
    @Throws(java.io.IOException::class)
    fun allocate(): java.io.File

    /**
     * Best-effort cleanup: deletes every file inside the audio cache
     * sub-directory that matches the factory's naming scheme AND is
     * neither referenced in [referencedPaths] NOR within the freshness
     * cut-off (Spec 1 §4.11 KG-AFF-4).
     *
     * Called once per `DictatePipelineService.onCreate` on
     * `Dispatchers.IO` so the FGS-5-second start budget is preserved
     * (Spec 1 §4.11.5.1 step 8). Default implementation is a no-op so
     * test doubles / minimal fakes do not need to implement it.
     *
     * @param referencedPaths absolute paths that MUST NOT be deleted.
     *   Source: `SessionDao.findAllAudioFilePaths()` filtered to
     *   non-null entries (the database is the source of truth).
     */
    fun cleanupOrphans(referencedPaths: Set<String>) = Unit
}
