package net.devemperor.dictate.companion.domain.net

/**
 * A [BindCandidate] — one address the machine actually has, ready to show or bind.
 */
data class BindCandidate(
    val address: String,
    val interfaceName: String,
    val kind: AddressKind,
)

/**
 * The outcome of matching a [BindSelection] against the live catalogue — the single place where
 * "what do I bind" and "what do I advertise" are decided together.
 *
 * [hosts] is never empty (a dead selection resolves to loopback, not to nothing). [advertised] is
 * derived *from* [hosts] by priority, so a QR advertising an address nobody listens on is not a
 * discipline to keep but a state the type cannot hold — the invariant
 * `advertised == null || advertised in hosts || hosts == ["0.0.0.0"]` holds by construction and is
 * pinned by test. [advertised] is `null` only when there is no candidate at all, leaving the caller
 * on its hostname fallback.
 *
 * [healedSelection] is non-null when [AddressCatalog.resolve] re-pointed an [BindSelection.Explicit]
 * onto a moved address (the Tailscale-re-auth case); the caller persists it so the heal is a
 * one-time correction, not a per-start guess.
 */
data class ResolvedBinding(
    val hosts: List<String>,
    val advertised: String?,
    val warnings: List<BindWarning>,
    val healedSelection: BindSelection? = null,
)

/**
 * Things the user must see about their binding. Rendered as banners in the settings screen; a couple
 * are load-bearing for security (ADR-0017 §3: listening on every interface must be visible).
 */
sealed interface BindWarning {

    /** A chosen address is not on any current interface — it is skipped, the rest still bind. */
    data class AddressUnavailable(val address: String) : BindWarning

    /** Nothing in the selection could be bound; the server is on loopback and reaches no phone. */
    data object FellBackToLoopback : BindWarning

    /** `0.0.0.0` is bound — the LAN can reach the port. The warning ADR-0017 §3 demands. */
    data object ListeningOnAllInterfaces : BindWarning

    /** No Tailscale interface exists; the private-mesh default was not available. */
    data object NoTailscaleFound : BindWarning

    /** The chosen address vanished but a single same-kind replacement existed; it was adopted. */
    data class AddressMigrated(val from: Set<String>, val to: String) : BindWarning
}
