package net.devemperor.dictate.companion.server.plugins

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import net.devemperor.dictate.companion.domain.AuthService
import net.devemperor.dictate.companion.domain.CompanionException
import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.server.requireSupportedProtocolHeader
import net.devemperor.dictate.shared.auth.AuthHeaders
import net.devemperor.dictate.shared.protocol.Endpoints

private val DeviceKey = AttributeKey<Device>("dictate.device")

/**
 * The authenticated device of the current call.
 *
 * Only reachable inside an [authenticated] block, where the plugin has already put it there — an
 * access outside one is a programming error and fails loudly rather than serving an anonymous call.
 */
val ApplicationCall.device: Device
    get() = attributes[DeviceKey]

class DeviceAuthConfig {
    lateinit var authService: AuthService
}

/**
 * Bearer secret + device id → a resolved [Device], or a single, uniform 401.
 *
 * **One 401 for every failure** — missing header, malformed header, unknown device, wrong secret.
 * Telling them apart would tell an attacker which device ids exist; the timing does not give it
 * away either, because `AuthService` hashes unconditionally.
 */
val DeviceAuthPlugin = createRouteScopedPlugin("DeviceAuth", ::DeviceAuthConfig) {
    val auth = pluginConfig.authService

    onCall { call ->
        // Version before auth: an outdated peer deserves "update me", not "who are you?" (ADR-0016).
        call.requireSupportedProtocolHeader()

        val deviceId = call.request.header(Endpoints.HEADER_DEVICE_ID)
        val secret = AuthHeaders.parseBearer(call.request.header(Endpoints.HEADER_AUTHORIZATION))
        val device = auth.authenticate(deviceId, secret) ?: throw CompanionException.UnauthorizedException()

        call.attributes.put(DeviceKey, device)
    }
}

/**
 * Everything inside needs a paired device. `/v1/pair` is the one route outside — the token in its
 * body *is* the credential (ADR-0017).
 */
fun Route.authenticated(auth: AuthService, build: Route.() -> Unit) {
    // A "/" child adds no path segment, so the routes inside keep their absolute /v1/... paths.
    route("/") {
        install(DeviceAuthPlugin) { authService = auth }
        build()
    }
}
