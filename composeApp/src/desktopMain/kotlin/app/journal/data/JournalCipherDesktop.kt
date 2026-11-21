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
    val existing = readKeyFile(file)
    val decoded = existing?.let(::decodeStoredKey)
    if (decoded != null) {
        cachedKey = path to decoded
        return@withLock decoded
    }
    if (existing != null) {
        // Not a key here: keep the bytes as evidence, mint this device's own key.
        if (quarantineKeyFile(file) == null) {
            throw IllegalStateException("Unreadable key file ${file.absolutePath} cannot be moved aside")
        }
    }
    val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
    persistKey(file, fresh)
    cachedKey = path to fresh
    fresh
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/** Stored key bytes, or null when the file is absent or implausibly large. Read-only. */
private fun readKeyFile(file: File): ByteArray? {
    if (!file.isFile) return null
    if (file.length() > MAX_KEY_FILE_BYTES) {
        Log.withTag("JournalAtRest").w { "Refusing oversized key file (${file.length()} bytes)" }
        return null
    }
    return runCatching { file.readBytes() }.getOrNull()
}

/**
 * POSIX: 32 raw bytes. Windows: a DPAPI blob only. A raw 32-byte file on Windows is
 * NOT a key by itself; [journalOpen] may adopt one after it decrypts the journal.
 */
private fun decodeStoredKey(bytes: ByteArray): ByteArray? {
    if (!isWindows()) return bytes.takeIf { it.size == 32 }
    return runCatching {
        Crypt32Util.cryptUnprotectData(bytes)
    }.getOrNull()?.takeIf { it.size == 32 }
}

/**
 * A read must never create, replace or move key material: a sealed journal can only
 * be opened by the key that sealed it, and a load that rotates the key orphans every
 * sealed copy. The one write allowed here is wrapping a PROVEN legacy raw key under
 * DPAPI (a migration, not a replacement).
 */
internal actual fun journalOpen(sealed: ByteArray): ByteArray {
    val file = keyFile()
    val path = file.absolutePath
    cachedKey?.let { (cachedPath, key) -> if (cachedPath == path) return aesGcmOpen(sealed, key) }
    val existing = readKeyFile(file)
        ?: throw IllegalStateException("Sealed journal but no key file at $path; refusing to mint one")
    decodeStoredKey(existing)?.let { key ->
        cachedKey = path to key
        return aesGcmOpen(sealed, key)
    }
    if (isWindows() && existing.size == 32) {
        val opened = runCatching { aesGcmOpen(sealed, existing) }
        if (opened.isSuccess) {
            // Legacy raw key proven by the journal: wrap it so a copied folder stops carrying it.
            runCatching { persistKey(file, existing) }
                .onFailure { Log.withTag("JournalAtRest").w { "Cannot wrap the legacy key: ${it.message}" } }
            cachedKey = path to existing
            return opened.getOrThrow()
        }
    }
    throw IllegalStateException("Journal key at $path cannot open the sealed journal; left in place")
}

/** POSIX: the key directory is owner-only; the file mode alone leaves a race. */
private fun restrictDataDir(dir: File?): Boolean {
    if (dir == null || isWindows()) return true
    return runCatching {
        Files.setPosixFilePermissions(
            dir.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }.onFailure { Log.withTag("JournalAtRest").w { "Cannot restrict data directory: ${it.message}" } }.isSuccess
}

private fun persistKey(file: File, key: ByteArray) {
    file.parentFile?.mkdirs()
    if (!restrictDataDir(file.parentFile)) {
        throw IllegalStateException("Cannot restrict the key directory ${file.parentFile}")
    }
    val stored = if (isWindows()) Crypt32Util.cryptProtectData(key) else key
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

/** Sanity cap so a corrupt key file can never OOM the reader. */
private const val MAX_KEY_FILE_BYTES = 4096L
