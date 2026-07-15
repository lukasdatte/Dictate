package net.devemperor.dictate.companion.domain.net

/**
 * How an interface address relates to the phone that wants to reach the companion.
 *
 * The distinction is load-bearing, not cosmetic: [TAILSCALE] is the one address both devices share
 * privately (ADR-0017), so it is the default to bind and the first choice to advertise, while
 * [LAN] means "reachable by every other machine on this network" and [LOOPBACK] means "this PC
 * only". The UI shows the kind so the user chooses with the exposure in view, and priority ordering
 * (Tailscale > LAN > loopback) falls out of it.
 *
 * IPv4-only for now (see ADR-0023); [OTHER] is the reserved catch-all that keeps the
 * classifier total when an address is neither loopback, CGNAT, nor a valid dotted quad — and the
 * seam an `IPV6` constant slots into later without touching the call sites.
 */
enum class AddressKind {
    TAILSCALE,
    LAN,
    LOOPBACK,
    OTHER,
}
