package net.devemperor.dictate.companion.secrets

import net.devemperor.dictate.ai.secrets.SecretRef
import net.devemperor.dictate.ai.secrets.SecretStoreException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * The file-based fallback store, exercised for real on this Linux VM (spec §2 criteria 2 & 3, §10).
 *
 * Covers round-trip / delete→null, the `0600` master-key permission, `available == false` on a
 * read-only directory, and `DecryptionFailed` when the master key is swapped under an existing blob.
 */
class FileAesGcmSecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ref = SecretRef("legacy", "rewording_api_key_openai")

    private fun store() = FileAesGcmSecretStore(tmp.root.toPath())

    @Test
    fun putThenGet_returnsByteIdenticalValue() {
        val store = store()
        val raw = byteArrayOf(0, -1, 13, -128, 127, 42)

        store.put(ref, raw)

        assertArrayEquals(raw, store.get(ref))
    }

    @Test
    fun get_onAbsentRef_isNull() {
        assertNull(store().get(ref))
    }

    @Test
    fun deleteThenGet_isNull() {
        val store = store()
        store.put(ref, "value".toByteArray())

        store.delete(ref)

        assertNull(store.get(ref))
    }

    @Test
    fun put_replacesPriorValue() {
        val store = store()
        store.put(ref, "first".toByteArray())
        store.put(ref, "second".toByteArray())

        assertArrayEquals("second".toByteArray(), store.get(ref))
    }

    @Test
    fun twoPuts_ofSameValue_useDistinctIVs() {
        val store = store()
        store.put(ref, "same".toByteArray())
        val first = Files.readAllBytes(secretFilePath())
        store.put(ref, "same".toByteArray())
        val second = Files.readAllBytes(secretFilePath())

        assertFalse("stored blobs must differ across puts", first.contentEquals(second))
    }

    @Test
    fun masterKeyFile_hasOwnerOnlyPermissions() {
        assumeTrue("POSIX-only assertion", isPosix())
        store().put(ref, "value".toByteArray())

        val masterKey = tmp.root.toPath().resolve("secrets").resolve("master.key")
        val perms = Files.getPosixFilePermissions(masterKey)

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms,
        )
    }

    @Test
    fun available_isFalse_onReadOnlyDirectory() {
        assumeTrue("POSIX-only assertion", isPosix())
        val readOnly = tmp.newFolder("locked").toPath()
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"))

        val store = FileAesGcmSecretStore(readOnly)

        assertFalse(store.available)
        assertThrows(SecretStoreException.Unavailable::class.java) { store.put(ref, "x".toByteArray()) }
    }

    @Test
    fun available_isTrue_onWritableDirectory() {
        assertTrue(store().available)
    }

    @Test
    fun get_afterMasterKeySwap_throwsDecryptionFailed_notEmpty() {
        val store = store()
        store.put(ref, "value".toByteArray())

        // Swap the master key under the existing blob → GCM tag mismatch on the next read.
        val masterKey = tmp.root.toPath().resolve("secrets").resolve("master.key")
        Files.write(masterKey, ByteArray(32) { 7 })

        assertThrows(SecretStoreException.DecryptionFailed::class.java) {
            FileAesGcmSecretStore(tmp.root.toPath()).get(ref)
        }
    }

    @Test
    fun get_onTruncatedBlob_throwsDecryptionFailed() {
        val store = store()
        store.put(ref, "value".toByteArray())
        Files.write(secretFilePath(), byteArrayOf(1, 2, 3)) // shorter than the IV

        assertThrows(SecretStoreException.DecryptionFailed::class.java) {
            FileAesGcmSecretStore(tmp.root.toPath()).get(ref)
        }
    }

    private fun secretFilePath() =
        tmp.root.toPath().resolve("secrets").resolve(secretFileName(ref.handle))

    private fun isPosix(): Boolean =
        Files.getFileAttributeView(tmp.root.toPath(), java.nio.file.attribute.PosixFileAttributeView::class.java) != null
}
