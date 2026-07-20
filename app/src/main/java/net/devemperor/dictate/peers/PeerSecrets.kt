package net.devemperor.dictate.peers

import net.devemperor.dictate.ai.secrets.SecretRef

/**
 * The SecretStore addressing convention for a peer's pairing secret — our pairing credential FOR a peer
 * we subscribe to (peer-katalog.md §5.1). Parallel to
 * [net.devemperor.dictate.config.ConfigSecrets]/[net.devemperor.dictate.secrets.PairingSecrets]: the one
 * place that names the `"peer"` namespace, so a §9.1 pair-redemption write and the sync client-factory
 * read can never drift on the handle format. Kept identical to the companion's `PeerSecrets` namespace.
 */
object PeerSecrets {
    const val PEER_NAMESPACE = "peer"
    fun peerSecretRef(secretRef: String): SecretRef = SecretRef(PEER_NAMESPACE, secretRef)
}
