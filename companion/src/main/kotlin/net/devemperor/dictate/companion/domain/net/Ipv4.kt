package net.devemperor.dictate.companion.domain.net

/**
 * Dotted-quad IPv4 arithmetic — no `java.net`, so it stays in the domain and needs no real NIC to
 * test.
 *
 * [InetAddress.getByName] would do for parsing, but it drags DNS-resolution semantics and `equals`
 * behaviour along for what is really just "are these four octets in a range". Four `toIntOrNull`s
 * are clearer, total, and cannot touch the network.
 */
object Ipv4 {

    /** The four octets of a dotted-quad literal, or `null` when [literal] is not one. */
    fun octets(literal: String): IntArray? {
        val parts = literal.split('.')
        if (parts.size != 4) return null
        val result = IntArray(4)
        for (i in 0..3) {
            val octet = parts[i].toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result[i] = octet
        }
        return result
    }

    fun isValid(literal: String): Boolean = octets(literal) != null

    /** 100.64.0.0/10 — the range Tailscale assigns from. */
    fun isCgnat(literal: String): Boolean {
        val octets = octets(literal) ?: return false
        return octets[0] == 100 && (octets[1] and 0xC0) == 64
    }

    /** 127.0.0.0/8 — anything the loopback interface answers to. */
    fun isLoopback(literal: String): Boolean = octets(literal)?.get(0) == 127
}
