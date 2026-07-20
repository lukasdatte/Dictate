package net.devemperor.dictate.companion.catalog.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [PeerDiscovery] over the Tailscale CLI: `tailscale status --json`, parsed for the peers' MagicDNS
 * names, tailnet IPs and online flag (peer-katalog.md §9.2, Gap 5).
 *
 * **CLI, not LocalAPI — the Gap-5 call.** The LocalAPI would save a subprocess but needs a
 * Unix-socket on Linux/macOS and a named pipe + auth token on Windows — three platform paths for the
 * same JSON the CLI prints uniformly everywhere. Smallest robust variant wins (Gap 5 fallback
 * position); the port boundary keeps a later LocalAPI impl a drop-in.
 *
 * **Availability detection, no hard dependency (AC11).** `tailscale` missing from the PATH, a
 * non-zero exit, a timeout, or unparsable output all collapse into an empty list — the
 * [JvmNetworkInterfaces][net.devemperor.dictate.companion.platform.JvmNetworkInterfaces] pattern of
 * swallowing enumeration failure into the honest "nothing found". A machine without Tailscale still
 * adds peers manually (§9.1).
 *
 * Only **online** peers with a MagicDNS name are returned: an offline machine cannot answer the
 * health probe that must follow anyway, and a peer without a DNS name has no stable address to store.
 *
 * @param runCommand runs the CLI and returns its stdout, or null on any failure — injected so tests
 *   feed fixture JSON without a subprocess (the production default execs the real binary).
 */
class TailscalePeerDiscovery(
    private val runCommand: () -> String? = { execTailscaleStatus() },
) : PeerDiscovery {

    override fun discover(): List<PeerCandidate> {
        val output = runCommand() ?: return emptyList()
        val status = try {
            json.decodeFromString(TailscaleStatus.serializer(), output)
        } catch (_: Exception) {
            return emptyList() // not the JSON we know — an old CLI, a truncated pipe: no candidates
        }
        return status.peers.values
            .filter { it.online && it.dnsName.isNotBlank() }
            .map { peer ->
                PeerCandidate(
                    // The CLI emits a trailing dot (a FQDN); the stored address must not carry it.
                    magicDnsName = peer.dnsName.removeSuffix("."),
                    address = peer.tailscaleIPs.firstOrNull().orEmpty(),
                )
            }
            .sortedBy { it.magicDnsName }
    }

    private companion object {

        /** Lenient on purpose: `tailscale status --json` carries dozens of fields we never model. */
        val json = Json { ignoreUnknownKeys = true }

        const val TIMEOUT_SECONDS = 5L

        /**
         * Exec the real CLI; null on ANY failure (missing binary, non-zero exit, timeout).
         *
         * **The timeout must own the deadline, not `readText()`.** `readText()` blocks until stdout
         * EOF, so reading it inline (as this once did) would outrun the [TIMEOUT_SECONDS] guard: a
         * hung `tailscale` never reaches EOF, the read never returns, and the AC11 "timeout → empty
         * list" promise silently breaks (worse: on `Dispatchers.IO` a hang starves the IO pool). So
         * we drain stdout on a daemon thread, let [Process.waitFor] enforce the deadline, and on
         * expiry [Process.destroyForcibly] the child — otherwise it orphan-leaks. stderr is discarded
         * at the OS level ([ProcessBuilder.Redirect.DISCARD]) so its pipe can never fill and deadlock
         * the child while we drain only stdout.
         */
        fun execTailscaleStatus(): String? = try {
            val process = ProcessBuilder("tailscale", "status", "--json")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = AtomicReference<String?>()
            val drainer = Thread {
                try {
                    output.set(process.inputStream.bufferedReader().use { it.readText() })
                } catch (_: Exception) {
                    // Child torn down mid-read (destroyForcibly below): leave output null → empty list.
                }
            }.apply { isDaemon = true }.also { it.start() }
            when {
                !process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) -> {
                    process.destroyForcibly() // timed out: kill the child so it cannot orphan-leak
                    null
                }
                process.exitValue() != 0 -> null
                else -> {
                    // Process exited → stdout is at EOF, but the drainer may not have run its final
                    // set() yet; join (bounded) so output.get() sees the complete stdout, not null.
                    drainer.join(TIMEOUT_SECONDS * 1000L)
                    output.get()
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

// ── The slice of `tailscale status --json` this feature reads (§9.2) ────────────────────────────
// Field names are the CLI's PascalCase originals (`Peer`, `DNSName`, …), mapped via @SerialName.

@Serializable
internal data class TailscaleStatus(
    /** Keyed by node public key — the key is irrelevant here, only the values are read. */
    @SerialName("Peer") val peers: Map<String, TailscalePeer> = emptyMap(),
)

@Serializable
internal data class TailscalePeer(
    @SerialName("DNSName") val dnsName: String = "",
    @SerialName("TailscaleIPs") val tailscaleIPs: List<String> = emptyList(),
    @SerialName("Online") val online: Boolean = false,
)
