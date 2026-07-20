package net.devemperor.dictate.companion.secrets

import com.sun.jna.Platform
import net.devemperor.dictate.ai.secrets.SecretRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DPAPI round-trip — `pending: block-B-windows-abnahme`.
 *
 * `Crypt32Util` calls into real Windows crypto, which does not exist on this Linux CI. The
 * assertions are the genuine target behaviour (byte-identical round-trip, delete→null); `assumeTrue`
 * skips them everywhere but Windows, where the F1 acceptance run turns them green. Kept runnable (not
 * a comment/TODO) so the readiness check is one `./gradlew :companion:test` away on the target OS.
 *
 * @see docs/plans/2026-07-19 - desktop-companion-v1/research/secretstore.md §6.3, §10
 */
class DpapiSecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ref = SecretRef("legacy", "transcription_api_key_openai")

    @Test
    fun putThenGet_returnsByteIdenticalValue() {
        assumeTrue("pending: block-B-windows-abnahme — DPAPI is real only on Windows", Platform.isWindows())
        val store = DpapiSecretStore(tmp.root.toPath())
        val secret = "sk-windows-key".toByteArray(Charsets.UTF_8)

        store.put(ref, secret)

        assertArrayEquals(secret, store.get(ref))
    }

    @Test
    fun deleteThenGet_isNull() {
        assumeTrue("pending: block-B-windows-abnahme — DPAPI is real only on Windows", Platform.isWindows())
        val store = DpapiSecretStore(tmp.root.toPath())
        store.put(ref, "value".toByteArray())

        store.delete(ref)

        assertNull(store.get(ref))
    }

    @Test
    fun get_onAbsentRef_isNull() {
        // This assertion needs no DPAPI (no file ⇒ null before any crypto), so it runs everywhere.
        assertNull(DpapiSecretStore(tmp.root.toPath()).get(ref))
    }
}
