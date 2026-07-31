package app.journal.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

/**
 * Random 32-byte key stored in its own file with user-only permissions.
 *
 * Provides the entropy for at-rest encryption that a hardcoded or
 * machine-derived password cannot: the previous scheme derived keys from
 * public constants ("nepenthe-truststore-v2") or guessable properties
 * (user.home + os.name), so anyone with file read access could derive the
 * key in milliseconds (audit H2). This key file is the secret; it is never
 * stored in the data it protects.
 *
 * Permissions: POSIX 0600 on macOS/Linux, icacls user-only on Windows
 * (best effort — the fallback still separates the key from the ciphertext).
 *
 * If the key file is missing (legacy install), callers fall back to the
 * legacy derivation for migration and re-encrypt on the next save.
 */
class AtRestKey(private val dataDir: String) {

    private val keyFile: File get() = File(dataDir, "at-rest.key")

    /** The key, or null when the file does not exist (legacy store). */
    fun loadOrNull(): ByteArray? {
        if (!keyFile.exists()) return null
        val bytes = keyFile.readBytes()
        return if (bytes.size == 32) bytes else null
    }

    /** Generate and persist a new key with user-only permissions. */
    fun create(): ByteArray {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        keyFile.parentFile.mkdirs()
        keyFile.writeBytes(bytes)
        restrictPermissions()
        return bytes
    }

    /** Hex string of the key, usable as a keystore password. */
    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun restrictPermissions() {
        try {
            Files.setPosixFilePermissions(
                keyFile.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX (Windows): drop inheritance, grant only the current user.
            try {
                val user = System.getProperty("user.name", "user")
                Runtime.getRuntime().exec(
                    arrayOf("icacls", keyFile.absolutePath, "/inheritance:r", "/grant:r", "$user:F")
                )
            } catch (_: Exception) {
                // Best effort: the key file is still separate from the ciphertext.
            }
        }
    }
}
