package app.journal.sync

import app.journal.log.Log
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
 * (checked: creation fails closed when the ACL cannot be applied).
 */
class AtRestKey(private val dataDir: String) {

    private val keyFile: File get() = File(dataDir, "at-rest.key")

    /** The key, or null when the file does not exist (legacy store). */
    fun loadOrNull(): ByteArray? {
        if (!keyFile.exists()) return null
        if (keyFile.length() > MAX_KEY_FILE_BYTES) {
            Log.withTag("AtRestKey").w { "Refusing oversized key file (${keyFile.length()} bytes)" }
            return null
        }
        val bytes = try {
            keyFile.readBytes()
        } catch (e: Exception) {
            Log.withTag("AtRestKey").w { "Cannot read key file: ${e.message}" }
            return null
        }
        return if (bytes.size == 32) bytes else null
    }

    /**
     * Generate and persist a new key with user-only permissions.
     * Fails closed: throws when the file cannot be written or the ACL
     * cannot be applied, so callers never silently fall back to weak keys.
     */
    @Throws(IllegalStateException::class)
    fun create(): ByteArray {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        keyFile.parentFile.mkdirs()
        try {
            keyFile.writeBytes(bytes)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot write at-rest key file: ${e.message}", e)
        }
        if (!restrictPermissions()) {
            try { keyFile.delete() } catch (_: Exception) { }
            throw IllegalStateException("Cannot restrict at-rest key permissions; refusing weak storage")
        }
        return bytes
    }

    /** Hex string of the key, usable as a keystore password. */
    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Apply user-only permissions. Returns true when the ACL was applied,
     * false when it could not be verified. Waits for icacls and checks its
     * exit code on Windows; warns whenever hardening fails.
     */
    private fun restrictPermissions(): Boolean {
        return try {
            Files.setPosixFilePermissions(
                keyFile.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
            true
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX (Windows): drop inheritance, grant only the current user.
            try {
                val user = System.getProperty("user.name", "user")
                val proc = Runtime.getRuntime().exec(
                    arrayOf("icacls", keyFile.absolutePath, "/inheritance:r", "/grant:r", "$user:F")
                )
                val exit = proc.waitFor()
                if (exit == 0) {
                    true
                } else {
                    Log.withTag("AtRestKey").w { "icacls exited with code $exit; key file ACL not hardened" }
                    false
                }
            } catch (e: Exception) {
                Log.withTag("AtRestKey").w { "icacls failed (${e.message}); key file ACL not hardened" }
                false
            }
        } catch (e: Exception) {
            Log.withTag("AtRestKey").w { "Cannot restrict key file permissions (${e.message})" }
            false
        }
    }

    companion object {
        /** Sanity cap so a corrupt key file can never OOM the reader. */
        const val MAX_KEY_FILE_BYTES = 4096L
    }
}
