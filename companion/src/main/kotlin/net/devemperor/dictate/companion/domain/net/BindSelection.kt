package net.devemperor.dictate.companion.domain.net

/**
 * What the user chose to listen on — the persisted shape of the bind configuration.
 *
 * Deliberately only two cases, and no persistent "Tailscale mode": Tailscale is a *default at first
 * configuration* whose address is materialised into [Explicit] the moment it is picked, so the
 * stored setting always states what will actually be bound. A dynamic mode that re-resolved "whatever
 * is CGNAT now" on every start would silently bind a different network after a tailnet re-auth — the
 * exact kind of invisible change [AddressCatalog.resolve] is built to turn into a visible one.
 */
sealed interface BindSelection {

    /** Listen on every interface (`0.0.0.0`). The whole LAN can reach the port; the UI warns. */
    data object AllInterfaces : BindSelection

    /** Listen on these literal addresses — one Ktor connector each. Never empty in a valid state. */
    data class Explicit(val addresses: Set<String>) : BindSelection
}
