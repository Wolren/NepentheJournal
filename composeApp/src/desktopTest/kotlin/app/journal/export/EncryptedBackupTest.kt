package app.journal.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sealed backup contract: the file carries the NEPB1 magic, zstd shrinks the
 * payload before encryption, and a wrong passphrase fails the tag check
 * instead of returning garbage.
 */
class EncryptedBackupTest {

    private val json = buildString {
        append("""{"version":7,"sessions":[""")
        repeat(500) { append("""{"id":"s:$it","title":"Session $it","notes":"repeated note text"},""") }
        append("]}").also { setLength(length - 1) }
        append("}")
    }

    private val passphrase = "correct horse battery".toCharArray()

    @Test
    fun sealRoundTripsAndCarriesTheMagic() {
        val sealed = EncryptedBackup.seal(json, passphrase)
        assertTrue(EncryptedBackup.looksSealed(sealed), "sealed backup must carry the NEPB1 magic")
        assertFalse(EncryptedBackup.looksSealed(json.encodeToByteArray()), "plain JSON must not look sealed")
        assertEquals(json, EncryptedBackup.open(sealed, passphrase))
    }

    @Test
    fun zstdShrinksThePayloadBeforeEncryption() {
        val sealed = EncryptedBackup.seal(json, passphrase)
        val plainSize = json.encodeToByteArray().size
        assertTrue(sealed.size < plainSize, "sealed backup (${sealed.size} B) must be smaller than the JSON ($plainSize B)")
    }

    @Test
    fun wrongPassphraseFailsInsteadOfReturningGarbage() {
        val sealed = EncryptedBackup.seal(json, passphrase)
        assertFailsWith<Exception> { EncryptedBackup.open(sealed, "wrong passphrase".toCharArray()) }
    }

    @Test
    fun everySealUsesFreshSaltAndIv() {
        val first = EncryptedBackup.seal(json, passphrase)
        val second = EncryptedBackup.seal(json, passphrase)
        assertFalse(first.contentEquals(second), "two seals of one journal must differ")
        assertEquals(json, EncryptedBackup.open(second, passphrase))
    }

    @Test
    fun plainJsonIsNotAcceptedAsASealedBackup() {
        assertFailsWith<IllegalArgumentException> {
            EncryptedBackup.open(json.encodeToByteArray(), passphrase)
        }
    }
}
