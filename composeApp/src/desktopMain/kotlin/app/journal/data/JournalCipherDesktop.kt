package app.journal.data

import app.journal.log.Log
import app.journal.util.PlatformLock
import app.journal.util.replaceDurably
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
 * 32-byte key beside the journal, readable only by the owning user (POSIX 0600).
 * Windows has no POSIX mode here: the file inherits the profile ACL, which the
 * platform restricts to the user.
 */
private fun loadOrCreateKey(): ByteArray = keyLock.withLock {
    val file = keyFile()
    val path = file.absolutePath
    cachedKey?.let { (cachedPath, key) -> if (cachedPath == path) return@withLock key }
    val existing = if (file.exists()) file.readBytes() else null
    if (existing != null && existing.size == 32) {
        cachedKey = path to existing
        return@withLock existing
    }
    if (existing != null) quarantineKeyFile(file)
    val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
    file.parentFile?.mkdirs()
    replaceDurably(file, fresh)
    runCatching {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        )
    }.onFailure { Log.withTag("JournalAtRest").w { "Cannot restrict key file: ${it.message}" } }
    cachedKey = path to fresh
    fresh
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
