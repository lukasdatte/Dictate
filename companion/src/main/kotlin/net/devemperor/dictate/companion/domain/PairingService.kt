package net.devemperor.dictate.companion.domain

import net.devemperor.dictate.companion.domain.model.Device
import net.devemperor.dictate.companion.domain.port.ClockPort
import net.devemperor.dictate.companion.domain.port.DeviceRepository
import net.devemperor.dictate.shared.auth.Secrets
import net.devemperor.dictate.shared.protocol.Endpoints
import net.devemperor.dictate.shared.protocol.PairResponse
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * The one-time-token half of pairing (ADR-0017).
 *
 * **The pending token lives in memory only** — an `AtomicReference`, never a database row. That is
 * a decision, not laziness: restarting the companion invalidates an open QR code, which is exactly
 * what a user expects from a code that is displayed on a screen, and it means a token can never
 * outlive the process that showed it. Only the *result* of a redemption (the device with its secret
 * hash) is persisted.
 *
 * Exactly one token is redeemable at a time; issuing a new one burns the old (the "new code" button
 * in the pairing dialog).
 */
class PairingService(
    private val devices: DeviceRepository,
    private val clock: ClockPort,
    private val serverName: String,
    private val random: SecureRandom = SecureRandom(),
    private val ttlMillis: Long = Endpoints.PAIRING_TOKEN_TTL_MILLIS,
) {

    /** [consumed] survives redemption on purpose — that is what turns a *reuse* into a 409 rather than a 401. */
    data class PendingPairing(
        val token: String,
        val issuedAt: Long,
        val expiresAt: Long,
        val consumed: Boolean = false,
    )

    private val pending = AtomicReference<PendingPairing?>(null)

    /** Issues a fresh token and invalidates any previous one. */
    fun issue(): PendingPairing {
        val now = clock.nowMillis()
        val fresh = PendingPairing(
            token = Secrets.newPairingToken(random, Endpoints.PAIRING_TOKEN_LENGTH),
            issuedAt = now,
            expiresAt = now + ttlMillis,
        )
        pending.set(fresh)
        return fresh
    }

    /** The token currently on screen, if any — the UI reads this to draw the QR and the countdown. */
    fun current(): PendingPairing? = pending.get()

    fun cancel() = pending.set(null)

    /** Milliseconds until the current token expires; 0 when there is none or it is done. */
    fun remainingMillis(): Long {
        val open = pending.get() ?: return 0L
        if (open.consumed) return 0L
        return (open.expiresAt - clock.nowMillis()).coerceAtLeast(0L)
    }

    /**
     * Redeems [token] for a long-lived device secret.
     *
     * The checks run in the order a *user* would want them explained: "I never issued that"
     * (401), then "that one is too old" (401), then "that one is already used" (409). The
     * comparison is constant-time — the token is a credential for the length of its TTL.
     *
     * @throws CompanionException.InvalidTokenException
     * @throws CompanionException.TokenExpiredException
     * @throws CompanionException.TokenConsumedException
     */
    fun redeem(token: String, deviceId: String, deviceName: String): PairResponse {
        val open = pending.get() ?: throw CompanionException.InvalidTokenException()
        if (!Secrets.constantTimeEquals(open.token, token)) throw CompanionException.InvalidTokenException()

        val now = clock.nowMillis()
        if (now >= open.expiresAt) {
            // Burn it even though it failed: a token that has been *presented* has been on a
            // network, and re-showing the same one after a clock correction would be careless.
            // compareAndSet, not set: never clobber a token issue() replaced in the meantime.
            pending.compareAndSet(open, open.copy(consumed = true))
            throw CompanionException.TokenExpiredException()
        }
        if (open.consumed) throw CompanionException.TokenConsumedException()

        // Claim the token ATOMICALLY, before the save. Ktor CIO serves calls concurrently, so two
        // redemptions of the same token can both pass the checks above; the compareAndSet is the one
        // point where exactly one wins. The loser lost the race → the token is now spent → Consumed.
        // (The CAS also refuses to overwrite a token issue() replaced concurrently.) Burning before
        // the save means a save failure spends the token — acceptable: pairing is re-triable with a
        // fresh QR, and one-token-one-device is the stronger invariant (ADR-0017).
        if (!pending.compareAndSet(open, open.copy(consumed = true))) {
            throw CompanionException.TokenConsumedException()
        }

        val secret = Secrets.newDeviceSecret(random)
        devices.save(
            Device(
                deviceId = deviceId,
                name = deviceName,
                secretHash = Secrets.sha256(secret),
                pairedAt = now,
                lastSeenAt = now,
            ),
        )

        return PairResponse(deviceId = deviceId, deviceSecret = secret, serverName = serverName)
    }
}
