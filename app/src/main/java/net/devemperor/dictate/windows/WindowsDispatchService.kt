package net.devemperor.dictate.windows

import net.devemperor.dictate.preferences.WindowsTarget
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.DispatchResponse
import net.devemperor.dictate.shared.protocol.InputCommandResponse
import net.devemperor.dictate.shared.protocol.InputCommandWire
import net.devemperor.dictate.shared.sync.SyncClient
import net.devemperor.dictate.shared.sync.SyncOutcome

/**
 * Sends a session's final text to the paired Windows companion (ADR-0019). Pure, blocking,
 * Android-free — the caller resolves all platform state (prefs → [WindowsTarget], text →
 * `getFinalOutput`) up front. Never throws: the shared [DispatchClient] returns a classified
 * [DispatchResult] instead.
 *
 * The clients are built lazily per call through the injected factories, so a re-pairing takes
 * effect without rebuilding the service (the factories read the current [WindowsTarget]).
 *
 * @property clientFactory builds a [DispatchClient] bound to a target.
 * @property syncClientFactory builds a [SyncClient] bound to a target (its own [DispatchClient] +
 *   the Room-backed sync source).
 * @property logger a log sink — on Android a `Log.i` wrapper; a no-op in tests.
 */
class WindowsDispatchService(
    private val clientFactory: (WindowsTarget) -> DispatchClient,
    private val syncClientFactory: (WindowsTarget) -> SyncClient,
    private val logger: (String) -> Unit = {},
) {

    /**
     * The blocking send. Returns the shared [DispatchResult] verbatim — a
     * [DispatchResult.Success] means a parsed 200 with `delivered = true` (the client already
     * downgrades a `delivered = false` 200 to a failure), so the coordinator can treat Success as
     * "the text is on the PC".
     */
    fun send(target: WindowsTarget, request: DispatchRequest): DispatchResult<DispatchResponse> =
        clientFactory(target).dispatch(request)

    /**
     * The blocking keyboard-action send (`/v1/input`, §5.3). Like [send] it returns the shared
     * [DispatchResult] verbatim — a 404 already arrives as `DispatchError.EndpointMissing` so the
     * caller can tell "companion too old" apart from "PC unreachable".
     */
    fun input(target: WindowsTarget, commands: List<InputCommandWire>): DispatchResult<InputCommandResponse> =
        clientFactory(target).input(commands)

    /**
     * Fire-and-forget cursor sync (ADR-0020). The ONE sync entry point, shared by both triggers:
     * after a successful dispatch (the coordinator's Delivered branch) and at app start
     * ([net.devemperor.dictate.core.DictatePipelineService.onCreate]). A sync failure must NEVER
     * matter to the caller — after a dispatch the text is already on the PC, and an app-start sync
     * is not a user event — so this only logs a non-trivial outcome and never throws.
     */
    fun sync(target: WindowsTarget) {
        val outcome = syncClientFactory(target).sync()
        if (outcome !is SyncOutcome.UpToDate) {
            logger("windows-sync ended as ${outcome::class.simpleName}")
        }
    }
}
