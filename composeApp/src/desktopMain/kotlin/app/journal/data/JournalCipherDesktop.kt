package app.journal.data

import app.journal.log.Log
import app.journal.util.PlatformLock
import app.journal.util.replaceDurably
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

private val keyLock = PlatformLock()
private var cachedKey: Pair<String, ByteArray>? = null

private fun keyFile(): File {
    // Same directory JournalStoreDesktop writes the journal to; the key must sit beside it.
    val home = System.getProperty("user.home") ?: "."
    return File("$home${File.separator}.nepenthe${File.separator}journal.key")
}

/**
 * 32-byte key beside the journal. Windows wraps it with DPAPI, so a copied folder does
 * not carry a usable key; POSIX systems keep the raw bytes readable by the owner only.
 */
private fun loadOrCreateKey(): ByteArray = keyLock.withLock {
    val file = keyFile()
    val path = file.absolutePath
    cachedKey?.let { (cachedPath, key) -> if (cachedPath == path) return@withLock key }
    val existing = if (file.exists()) file.readBytes() else null
    val decoded = existing?.let(::decodeKeyBytes)
    if (decoded != null) {
        if (isWindows() && existing.size == 32) {
            // Legacy plain key: re-wrap it now that DPAPI is available.
            persistKey(file, decoded)
        }
        cachedKey = path to decoded
        return@withLock decoded
    }
    if (existing != null) quarantineKeyFile(file)
    val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
    persistKey(file, fresh)
    cachedKey = path to fresh
    fresh
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/** Raw 32 bytes on POSIX, a DPAPI blob on Windows; null when neither decodes to a key. */
private fun decodeKeyBytes(bytes: ByteArray): ByteArray? {
    if (bytes.size == 32) return bytes
    if (!isWindows()) return null
    return runCatching {
        Crypt32Util.cryptUnprotectData(bytes)
    }.getOrNull()?.takeIf { it.size == 32 }
}

private fun persistKey(file: File, key: ByteArray) {
    file.parentFile?.mkdirs()
    val stored = if (isWindows()) {
        runCatching { Crypt32Util.cryptProtectData(key) }.getOrDefault(key)
    } else key
    replaceDurably(file, stored)
    if (!isWindows()) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }.onFailure { Log.withTag("JournalAtRest").w { "Cannot restrict key file: ${it.message}" } }
    }
}

/**
 * Moves a key file that is not 32 bytes aside instead of overwriting it: the
 * bytes are useless for decryption, but they are the only evidence of what the
 * journal was sealed with.
 */
internal fun quarantineKeyFile(file: File): File? {
    val target = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
    val moved = runCatching { file.renameTo(target) }.getOrDefault(false)
    Log.withTag("JournalAtRest").e {
        "Key file had ${file.length()} bytes; kept as ${if (moved) target.name else "unmoved original"}"
    }
    return if (moved) target else null
}

internal actual fun journalSeal(plain: ByteArray): ByteArray = aesGcmSeal(plain, loadOrCreateKey())

internal actual fun journalOpen(sealed: ByteArray): ByteArray = aesGcmOpen(sealed, loadOrCreateKey())
