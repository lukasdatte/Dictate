package net.devemperor.dictate.shared.auth

import net.devemperor.dictate.shared.protocol.Endpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * Tests for the pairing material. [SecureRandom] is injected, so "random" is reproducible here.
 */
class SecretsTest {

    /** Seeded so the test is deterministic — the production call passes a real SecureRandom. */
    private fun seededRandom(seed: Long = 42L) = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }

    @Test
    fun newPairingToken_hasTheExpectedLengthAndAlphabet() {
        val token = Secrets.newPairingToken(seededRandom(), Endpoints.PAIRING_TOKEN_LENGTH)

        assertEquals(Endpoints.PAIRING_TOKEN_LENGTH, token.length)
        assertTrue(token, token.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
    }

    @Test
    fun newPairingToken_neverContainsTheAmbiguousLetters() {
        // I, L, O and U are excluded because the code is typed by hand as often as it is scanned.
        val tokens = (1..200).map { Secrets.newPairingToken(seededRandom(it.toLong())) }

        tokens.forEach { token ->
            assertFalse(token, token.any { it in "ILOU" })
        }
    }

    @Test
    fun newPairingToken_satisfiesTheProtocolValidation() {
        // The generator and the wire schema must agree, or pairing fails at its own first hurdle.
        val token = Secrets.newPairingToken(seededRandom())
        val request = net.devemperor.dictate.shared.protocol.PairRequest(
            pairingToken = token,
            deviceId = "11111111-2222-3333-4444-555555555555",
            deviceName = "Pixel 8",
        )

        assertTrue(token, net.devemperor.dictate.shared.protocol.Validations.pairRequest(request).isValid)
    }

    @Test
    fun newDeviceSecret_is256BitsOfBase64Url() {
        val secret = Secrets.newDeviceSecret(seededRandom())

        assertEquals(32, Base64.getUrlDecoder().decode(secret).size)
        assertFalse(secret, secret.contains('='))
        assertFalse(secret, secret.contains('+'))
        assertFalse(secret, secret.contains('/'))
    }

    @Test
    fun newDeviceSecret_differsBetweenCalls() {
        val random = seededRandom()

        assertNotEquals(Secrets.newDeviceSecret(random), Secrets.newDeviceSecret(random))
    }

    @Test
    fun newDeviceSecret_satisfiesTheProtocolValidation() {
        val response = net.devemperor.dictate.shared.protocol.PairResponse(
            deviceId = "11111111-2222-3333-4444-555555555555",
            deviceSecret = Secrets.newDeviceSecret(seededRandom()),
            serverName = "PC",
        )

        assertTrue(net.devemperor.dictate.shared.protocol.Validations.pairResponse(response).isValid)
    }

    @Test
    fun sha256_matchesTheKnownVector() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Secrets.sha256("hello"),
        )
    }

    @Test
    fun sha256_isStableAndCaseSensitive() {
        assertEquals(Secrets.sha256("secret"), Secrets.sha256("secret"))
        assertNotEquals(Secrets.sha256("secret"), Secrets.sha256("Secret"))
    }

    @Test
    fun constantTimeEquals_behavesLikeEqualsForTheCallersPurposes() {
        assertTrue(Secrets.constantTimeEquals("abc", "abc"))
        assertFalse(Secrets.constantTimeEquals("abc", "abd"))
        assertFalse(Secrets.constantTimeEquals("abc", "abcd"))
        assertFalse(Secrets.constantTimeEquals("", "abc"))
        assertTrue(Secrets.constantTimeEquals("", ""))
    }
}
