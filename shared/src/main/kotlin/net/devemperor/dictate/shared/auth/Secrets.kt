package net.devemperor.dictate.shared.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Token and secret material for the pairing handshake (ADR-0017).
 *
 * Pure and platform-free — shared verbatim between the Android app and the desktop
 * companion (ADR-0015). [SecureRandom] is passed in rather than held, so a test can seed it and
 * get a deterministic run.
 */
object Secrets {

    /**
     * Crockford Base32 — no `I`, `L`, `O` or `U`.
     *
     * The pairing code is not only scanned, it is also read off a screen and typed on a phone
     * keyboard; those four characters are the ones a human confuses with `1`, `0` and `V`.
     */
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** The one-time pairing token the companion shows as a QR code and as a typable code. */
    fun newPairingToken(random: SecureRandom, length: Int = 8): String {
        require(length > 0) { "pairing token length must be positive" }
        val chars = CharArray(length) { CROCKFORD[random.nextInt(CROCKFORD.length)] }
        return String(chars)
    }

    /** The long-lived device secret: 256 bits, base64url, unpadded. */
    fun newDeviceSecret(random: SecureRandom): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Lowercase hex SHA-256. The desktop stores this, never the secret itself. */
    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Compares without leaking *where* two values start to differ.
     *
     * A naive `==` returns as soon as it finds a mismatching character, and the time it took to
     * do so tells an attacker how much of a guessed secret was right — that is what makes a
     * secret brute-forceable byte by byte. Used on the server to check the presented secret's
     * hash against the stored one.
     */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
