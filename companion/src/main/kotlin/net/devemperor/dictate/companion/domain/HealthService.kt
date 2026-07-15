package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.port.InputCommandPerformer
import net.devemperor.dictate.companion.domain.port.TextInserter
import net.devemperor.dictate.shared.protocol.HealthResponse

/**
 * What the phone may know about this companion before it trusts it with a dictation.
 *
 * `canInsert = false` is the honest answer of a companion running on Linux (or on a Windows box
 * whose inserter did not initialise): the phone can warn *while pairing* rather than letting the
 * user find out through a text that lands only in the clipboard. `supportsInputCommands` is the same
 * honesty for the keyboard-action channel — the phone reads it to warn before the first `/v1/input`.
 */
class HealthService(
    private val serverName: String,
    private val appVersion: String,
    private val inserter: TextInserter,
    private val inputPerformer: InputCommandPerformer,
) {

    fun health(): HealthResponse = HealthResponse(
        serverName = serverName,
        appVersion = appVersion,
        canInsert = inserter.available,
        supportsInputCommands = inputPerformer.available,
    )
}
