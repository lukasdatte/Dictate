package net.devemperor.dictate.core

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.devemperor.dictate.R
import net.devemperor.dictate.settings.DictateSettingsActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground Service that hosts the Dictate pipeline state container.
 *
 * **Block 2 scope — skeleton only.** The orchestrator + 13 modules
 * (ADR-0001, ADR-0003 §"Required mechanics" item 1) land in Block 1b.
 * This file implements only the **container** that survives keyboard
 * switches:
 *
 *  - FGS lifecycle (`onCreate` / `onStartCommand` / `onDestroy`)
 *  - Notification channel (created in `onCreate`, must exist before the
 *    first `startForeground` call — Spec 1 §11.1.4)
 *  - `startForeground` within the 5-second budget (Spec 1 §11.1.4 +
 *    ADR-0003 §"Required mechanics" item 2)
 *  - [LocalBinder] with the **Single-Dispatch surface** that Block 1b
 *    will hook up to the orchestrator (Spec 1 §5; ADR-0003 §"Required
 *    mechanics" item 3 — `state` + `dispatch`, no typed forwarders)
 *  - `serviceScope` tied to the service lifecycle (cancelled in
 *    `onDestroy`; canonical placement of the dispatchers + supervisor
 *    job, ready for Block 1b to plug the orchestrator's `init` into)
 *
 * The orchestrator field is intentionally absent in Block 2. The
 * [LocalBinder.dispatch] entry point is therefore a no-op stub that
 * returns immediately — Block 1b replaces the stub with a real
 * `orchestrator.dispatch(action)` call **without** changing the binder
 * contract (`state: StateFlow<DictateUiState>` and
 * `dispatch(action: Action): DispatchOutcome` per ADR-0003). Until then,
 * `Any` is the placeholder for the future `Action` type; the IME-side
 * keeps consuming its existing controllers and only the bind/unbind
 * lifecycle is exercised.
 *
 * @see `docs/decisions/0003-service-foreground-pipeline-architecture.md` §"Required mechanics"
 * @see `docs/plans/2026-05-07 - dictate-keyboard-layout-refactor/research/1-pipeline-service/1-pipeline-service.reviewed.md` §7 §11.1 §11.3 §11.5
 */
class DictatePipelineService : Service() {

    /**
     * Service-scoped coroutine context.
     *
     * Block 1b will pass this into `DictateOrchestrator(scope = serviceScope, …)`
     * so module side-effects (`runEffect`) run here and are cancelled together
     * with the service in [onDestroy]. The supervisor job ensures one failing
     * effect does not collapse the rest of the pipeline.
     *
     * `Dispatchers.Main.immediate` matches Spec 1 §4.3 — the orchestrator's
     * single-dispatch path runs on Main.immediate so re-entrant dispatches
     * stay on the same task.
     *
     * @see Spec 1 §4.3 (orchestrator single-dispatch on Main.immediate)
     * @see `docs/decisions/0001-orchestrator-and-modules-architecture.md` §"Required mechanics" item 2
     */
    private val serviceScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Single binder instance per service lifetime. Android dispatches the
     * same `IBinder` to every client that calls `bindService` (Spec 1
     * §11.3.4 Multi-Bind), so we expose one stable singleton.
     */
    private val binder: LocalBinder = LocalBinder()

    /**
     * Counts how often [LocalBinder.dispatch] was invoked. Block 2 has
     * no orchestrator to record dispatches against, so this counter is
     * the only observable side-effect of the no-op stub — letting the
     * binder-contract test exercise the IME-side call path before
     * Block 1b plugs the real orchestrator in.
     *
     * Atomic because same-process binder transactions are not guaranteed
     * to be main-thread-confined; Block 1b adds a second client (Spec 1
     * §11.3.4 Multi-Bind) which makes concurrent dispatch plausible.
     */
    private val stubDispatchCount: AtomicInteger = AtomicInteger(0)

    /**
     * Test-visibility hook: how many times the binder stub absorbed a
     * dispatch. Block 1b replaces the stub with `orchestrator.dispatch`
     * and this counter is removed together with the stub.
     */
    val dispatchInvocationCount: Int
        get() = stubDispatchCount.get()

    /**
     * Tracks whether [ensureNotificationChannel] has run. Exposed via
     * [isNotificationChannelReady] for the channel-order test in
     * `DictatePipelineServiceTest` — Spec 1 §10 Phase-B S-5
     * "NotificationChannel-vor-startForeground" acceptance.
     */
    private var notificationChannelReady: Boolean = false

    /**
     * Test-visibility hook: `true` after [ensureNotificationChannel] has
     * been invoked. Lets the Robolectric service test assert the
     * channel-before-startForeground ordering without reflecting into
     * the framework's `NotificationManager`.
     */
    val isNotificationChannelReady: Boolean
        get() = notificationChannelReady

    override fun onCreate() {
        super.onCreate()
        // ──────────────────────────────────────────────────────────────
        // Channel-order invariant (Spec 1 §11.1.4 + ADR-0003 Failure-Mode):
        // NotificationChannel MUST exist before the first `startForeground`
        // call. On API ≥ 26, NotificationManager throws
        // IllegalArgumentException otherwise — sticky Play-Store ANR class.
        // Synchronous in-memory call, <5 ms — no FGS 5-second-budget risk.
        // ──────────────────────────────────────────────────────────────
        ensureNotificationChannel()

        // Block 1b composition-root lands here:
        //   val store = DictateUiStateStore(DictateUiState.initial())
        //   …construct ModuleServicesFactory, PrefMirror, Recovery…
        //   orchestrator = DictateOrchestrator(serviceScope, store, …)
        //   notifCoordinator = PipelineNotificationCoordinator(this, orchestrator.state, serviceScope)
        //   actionRouter = PipelineActionRouter(orchestrator)
        //
        // Block 2 keeps the slot empty on purpose. See class-KDoc.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ──────────────────────────────────────────────────────────────
        // FGS 5-second-budget invariant (Spec 1 §11.1.4 + ADR-0003
        // Failure-Mode "startForeground not called within 5 s"):
        // startForeground() runs synchronously as step 1 of onStartCommand,
        // before any coroutine launch, before any DB read, before any
        // potentially-blocking call. buildInitialNotification() is a pure
        // in-memory NotificationCompat.Builder chain — measured well below
        // the 1-second p99 target on API-34 reference devices.
        //
        // Defensive: on API ≥ 31 startForeground can throw
        // `ForegroundServiceStartNotAllowedException` (background-start
        // restrictions); on API ≥ 33 it can throw `SecurityException` when
        // POST_NOTIFICATIONS is denied or the FGS-type does not match. The
        // catch keeps the service from crash-looping behind the IME-side
        // `onBindingDied` rebind (DictateInputMethodService Block-2 wiring).
        // Spec 1 §11.5.1 anticipates this via the onboarding runtime
        // prompt; until that lands (delegated to a follow-up — see Issue
        // Index), stopSelf + START_NOT_STICKY is the safe recovery path.
        //
        // Block 1b switches this call to
        // `notifCoordinator.buildInitial()` + `actionRouter.dispatch(intent)`
        // and adds `notifCoordinator.startReactiveUpdates(::stopSelfWhenTerminal)`
        // (Spec 1 §7.3). The 5-second budget remains the binding contract.
        // ──────────────────────────────────────────────────────────────
        try {
            startForegroundCompat(buildInitialNotification())
        } catch (e: SecurityException) {
            // API 33+: POST_NOTIFICATIONS denied, FGS-type mismatch, etc.
            Log.w(TAG, "FGS start denied (security)", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            // API 31+: ForegroundServiceStartNotAllowedException — caught via
            // Exception base to keep the catch usable without an
            // @RequiresApi gate. Re-throw anything else so genuine bugs
            // still surface in logcat.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.w(TAG, "FGS start denied (background-start restriction)", e)
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }

        // Block 1b: `intent?.let { actionRouter.dispatch(it) }` lands here.
        // Block 2 ignores incoming intents — no action-routing yet.

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Block 1b: insert `runBlocking { withTimeout(2000L) { orchestrator.shutdown() } }`
        // HERE — BEFORE serviceScope.cancel(). Otherwise the shutdown's
        // suspending terminate calls run on an already-cancelled scope and
        // abort instantly (Spec 1 §7.3 onDestroy + ADR-0003 §"Required
        // mechanics" items 8+9; Spec 1 §11.5 Finding 6).
        // Block 2 has neither orchestrator nor recording state to release,
        // so only the scope-teardown step from the §7.3 sequence runs.
        serviceScope.cancel()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────
    // Notification channel + initial notification (Block 2 skeleton)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates the FGS notification channel idempotently.
     *
     * Spec 1 §11.1.2 calls this from `onCreate`. The channel is `IMPORTANCE_LOW`
     * (no sound, no heads-up) because the notification is a passive status
     * indicator — Block 1b's `PipelineNotificationCoordinator` will populate
     * the title/text reactively from `DictateUiState`.
     *
     * On API < 26 NotificationChannels do not exist; the method is a no-op
     * and `notificationChannelReady` is flipped so call-order tests can
     * still observe that this method was reached before
     * [startForegroundCompat].
     */
    private fun ensureNotificationChannel() {
        notificationChannelReady = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.dictate_pipeline_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.dictate_pipeline_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        // Defensive: locked-down devices / restricted profiles can reject
        // channel creation with a SecurityException. We swallow so the
        // service still boots; without a channel, startForeground will
        // raise its own error (handled in onStartCommand).
        try {
            mgr.createNotificationChannel(channel)
        } catch (e: SecurityException) {
            Log.w(TAG, "NotificationChannel create denied", e)
        }
    }

    /**
     * Builds the initial FGS notification — a passive status indicator with
     * no action buttons.
     *
     * Block 1b replaces this with `PipelineNotificationCoordinator.buildInitial(state)`,
     * which adds state-dependent action buttons (Send / Pause / Resume /
     * Cancel — Spec 1 §7.6). The skeleton variant here is deliberately
     * action-less because Block 2 has no orchestrator to dispatch into.
     *
     * Pure in-memory work — must stay below ~50 ms to keep the FGS budget
     * uncontested.
     */
    private fun buildInitialNotification(): Notification {
        // Tapping the notification opens the Settings activity. Spec 1 §11.5.3
        // notes the IME cannot make itself visible from a notification tap —
        // surfacing the configuration UI is the closest equivalent.
        val contentIntent: PendingIntent? = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DictateSettingsActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_mic_20)
            .setContentTitle(getString(R.string.dictate_pipeline_notif_title))
            .setContentText(getString(R.string.dictate_pipeline_notif_idle))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
    }

    /**
     * `startForeground` with the API-34+ explicit-type form when available.
     *
     * Spec 1 §11.1.1: `targetSdk = 35` plus `foregroundServiceType="microphone"`
     * in the Manifest forces the explicit-type signature on API ≥ 34 or
     * Android throws `ForegroundServiceTypeNotAllowedException`. On
     * pre-API-34 devices the implicit overload picks up the manifest-declared
     * type automatically.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // LocalBinder — single-dispatch surface (ADR-0003 §"Required mechanics" item 3)
    // ──────────────────────────────────────────────────────────────────

    /**
     * IME-facing bind surface. Returned from [onBind] and consumed by the
     * IME-Service via a [android.content.ServiceConnection].
     *
     * **Stable contract (Block 2 forward — must NOT widen).** The binder
     * exposes exactly two surfaces, mirroring the Spec 1 §5 sealed contract:
     *
     *  - [service] — direct reference to the hosting service instance, so
     *    Block 1b can wire `state: StateFlow<DictateUiState>` from
     *    `service.orchestrator.state` without changing the binder's
     *    sealed signature. The IME never reaches through this in Block 2.
     *  - [dispatch] — single entry point for all state-mutating events
     *    (record-start, pause, resume, view-shown/hidden, …). No typed
     *    forwarder methods (F-8 / ADR-0001 §"Required mechanics" item 1).
     *
     * The `Any` placeholder reflects the Block 2 skeleton: Block 1b
     * replaces it with the real `Action` sealed-class hierarchy and adds
     * a `state: StateFlow<DictateUiState>` getter. The IME-side already
     * holds the binder via a same-process cast; widening the type later
     * is a no-op for the call site.
     */
    inner class LocalBinder : Binder() {

        /**
         * Module-internal: enforces the ADR-0003 `state + dispatch` sealed
         * contract by preventing IME-side callers from reaching into
         * service internals. Block 1b layers `state: StateFlow<DictateUiState>`
         * on top via `service.orchestrator.state` without exposing the
         * orchestrator itself to the IME side. Tests in the same module
         * still see `internal` via Kotlin module visibility.
         */
        internal val service: DictatePipelineService get() = this@DictatePipelineService

        /**
         * Block-2 single-dispatch entry point — a no-op until Block 1b
         * wires the orchestrator. Documented here so the IME-side bind
         * code path can be exercised end-to-end already.
         *
         * Block 1b changes this to
         * `fun dispatch(action: Action): DispatchOutcome = service.orchestrator.dispatch(action)`.
         * The IME-side does not need to be re-touched — same method name,
         * compatible signature widening.
         */
        @Suppress("UNUSED_PARAMETER") // TODO(Block 1b): remove when action is forwarded to orchestrator
        fun dispatch(action: Any) {
            // Block 1b: service.orchestrator.dispatch(action) goes here.
            // Block 2: deliberate no-op — only the invocation is observable
            // (via [dispatchInvocationCount]) so the IME-side bind/dispatch
            // path can be smoke-tested before the real orchestrator lands.
            stubDispatchCount.incrementAndGet()
        }
    }

    companion object {
        /** Service log tag. */
        const val TAG: String = "DictatePipelineSvc"

        /**
         * NotificationChannel id for the persistent FGS notification.
         *
         * Spec 1 §11.1.2 — kept stable across all blocks because changing
         * it would create a second user-visible channel on devices that
         * already created the v1 channel. Block 1b reuses this constant.
         */
        const val CHANNEL_ID: String = "dictate_pipeline"

        /**
         * FGS notification id.
         *
         * Spec 1 §7.4 places the canonical NOTIF_ID on
         * `PipelineNotificationCoordinator` — that companion does not yet
         * exist in Block 2, so the service owns the constant in the
         * meantime. Block 1b moves the constant into the coordinator's
         * companion and the service reads
         * `PipelineNotificationCoordinator.NOTIF_ID` (Spec 1 §11.1.2
         * NOTIF_ID consolidation block). The value `0xD1C7A7E` is reused
         * verbatim so on-device notifications keep the same id across the
         * Block-2 → Block-1b transition.
         */
        const val NOTIF_ID: Int = 0xD1C7A7E
    }
}
