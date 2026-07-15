package net.devemperor.dictate.companion.cli

import java.io.File
import java.net.InetAddress
import java.util.Properties
import java.util.UUID
import kotlin.system.exitProcess
import net.devemperor.dictate.shared.auth.Credentials
import net.devemperor.dictate.shared.auth.PairingUri
import net.devemperor.dictate.shared.client.DispatchClient
import net.devemperor.dictate.shared.client.DispatchResult
import net.devemperor.dictate.shared.protocol.DispatchRequest
import net.devemperor.dictate.shared.protocol.SessionOriginWire
import net.devemperor.dictate.shared.transport.OkHttpDispatchTransport

/**
 * Headless pairing + smoke-test client for the companion server.
 *
 * Exists so an operator (or a remote agent session without a phone at hand) can exercise the real
 * pairing and dispatch path end-to-end. It deliberately goes through the same shared
 * [DispatchClient] the Android app uses — a green run here proves the server, the protocol and the
 * network path; what remains phone-specific is only the QR scan and Android networking.
 *
 * Usage (via `./gradlew :companion:pairCli --args="…"`):
 *   pair <dictate://pair?…| baseUrl> [--token <t>] [--name <deviceName>] [--store <file>]
 *   health   [--store <file>]
 *   dispatch <text…> [--store <file>]
 *
 * The store file (default `~/.dictate-pair-cli.properties`) keeps the device secret between
 * invocations — treat it like a credential.
 */
object PairCli {

    private val defaultStore = File(System.getProperty("user.home"), ".dictate-pair-cli.properties")

    fun run(args: Array<String>): Int {
        if (args.isEmpty()) return usage()
        val positional = mutableListOf<String>()
        val options = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                if (i + 1 >= args.size) return fail("missing value for $a")
                options[a.removePrefix("--")] = args[i + 1]; i += 2
            } else {
                positional += a; i += 1
            }
        }
        val store = options["store"]?.let(::File) ?: defaultStore

        return when (positional.firstOrNull()) {
            "pair" -> pair(positional.drop(1), options, store)
            "health" -> health(store)
            "dispatch" -> dispatch(positional.drop(1).joinToString(" "), store)
            else -> usage()
        }
    }

    private fun pair(rest: List<String>, options: Map<String, String>, store: File): Int {
        val target = rest.firstOrNull() ?: return fail("pair needs a dictate://pair?… URI or a base URL")
        val parsed = PairingUri.parse(target)
        val baseUrl = parsed?.baseUrl ?: target
        val token = parsed?.token ?: options["token"] ?: return fail("no token: pass a pairing URI or --token")
        val name = options["name"] ?: ("pair-cli@" + hostName())

        val client = client(baseUrl) { null }
        val deviceId = "cli-" + UUID.randomUUID()
        return when (val result = client.pair(token = token, deviceId = deviceId, deviceName = name)) {
            is DispatchResult.Success -> {
                val props = Properties()
                props["baseUrl"] = baseUrl
                props["deviceId"] = result.value.deviceId
                props["deviceSecret"] = result.value.deviceSecret
                props["serverName"] = result.value.serverName
                store.outputStream().use { props.store(it, "dictate pair-cli credentials — treat as secret") }
                println("PAIRED with '${result.value.serverName}' at $baseUrl")
                println("deviceId=${result.value.deviceId}")
                println("stored: ${store.absolutePath}")
                0
            }
            is DispatchResult.Failure -> fail("pair failed: ${result.error}")
        }
    }

    private fun health(store: File): Int {
        val (baseUrl, credentials) = load(store) ?: return fail("no stored pairing — run 'pair' first (${store.absolutePath})")
        return when (val result = client(baseUrl) { credentials }.health()) {
            is DispatchResult.Success -> {
                val h = result.value
                println("OK server='${h.serverName}' version=${h.appVersion} canInsert=${h.canInsert} ($baseUrl)")
                0
            }
            is DispatchResult.Failure -> fail("health failed: ${result.error}")
        }
    }

    private fun dispatch(text: String, store: File): Int {
        if (text.isBlank()) return fail("dispatch needs a text argument")
        val (baseUrl, credentials) = load(store) ?: return fail("no stored pairing — run 'pair' first (${store.absolutePath})")
        val request = DispatchRequest(
            sessionId = "cli-" + UUID.randomUUID(),
            text = text,
            createdAt = System.currentTimeMillis(),
            origin = SessionOriginWire.UNKNOWN,
        )
        return when (val result = client(baseUrl) { credentials }.dispatch(request)) {
            is DispatchResult.Success -> {
                println("DELIVERED sessionId=${result.value.sessionId} ($baseUrl)")
                0
            }
            is DispatchResult.Failure -> fail("dispatch failed: ${result.error}")
        }
    }

    private fun client(baseUrl: String, credentials: () -> Credentials?): DispatchClient =
        DispatchClient(OkHttpDispatchTransport(baseUrl), credentials)

    private fun load(store: File): Pair<String, Credentials>? {
        if (!store.isFile) return null
        val props = Properties().apply { store.inputStream().use(::load) }
        val baseUrl = props.getProperty("baseUrl") ?: return null
        val id = props.getProperty("deviceId") ?: return null
        val secret = props.getProperty("deviceSecret") ?: return null
        return baseUrl to Credentials(deviceId = id, deviceSecret = secret)
    }

    private fun hostName(): String = try {
        InetAddress.getLocalHost().hostName.ifBlank { "unknown-host" }
    } catch (e: Exception) {
        "unknown-host"
    }

    private fun fail(message: String): Int {
        System.err.println("ERROR: $message")
        return 1
    }

    private fun usage(): Int {
        System.err.println(
            """
            usage:
              pair <dictate://pair?…|baseUrl> [--token <t>] [--name <deviceName>] [--store <file>]
              health   [--store <file>]
              dispatch <text…> [--store <file>]
            """.trimIndent(),
        )
        return 2
    }
}

fun main(args: Array<String>) {
    exitProcess(PairCli.run(args))
}
