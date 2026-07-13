package net.devemperor.dictate.companion.server.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import net.devemperor.dictate.shared.protocol.ProtocolCodec

/**
 * Content negotiation on **[ProtocolCodec.json]** — the very same `Json` instance the phone encodes
 * with, not a second one configured to look similar.
 *
 * The routes do not actually go through this plugin (they read raw text and answer through the
 * codec, so that no payload can skip the Konform validation — see `ProtocolCalls.kt`). It is
 * installed anyway, and on the shared instance, so that the day someone reaches for a plain
 * `call.respond(dto)` the wire format is still the agreed one (`ignoreUnknownKeys`,
 * `encodeDefaults`, `explicitNulls = false`) rather than kotlinx's defaults.
 */
fun Application.installSerialization() = install(ContentNegotiation) {
    json(ProtocolCodec.json)
}
