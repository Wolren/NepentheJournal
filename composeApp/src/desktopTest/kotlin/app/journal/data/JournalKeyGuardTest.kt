package app.journal.data

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A key file that is not 32 bytes cannot decrypt anything. It must be kept for
 * forensics, never overwritten in place, so the failure stays visible.
 */
class JournalKeyGuardTest {

    @Test
    fun aShortKeyFileIsMovedAsideInsteadOfOverwritten() {
        val dir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-key-${System.nanoTime()}"
        ).apply { mkdirs() }
        try {
            val key = File(dir, "journal.key").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val kept = quarantineKeyFile(key)
            assertNotNull(kept, "the unreadable key must be preserved")
            assertTrue(kept.exists(), "the preserved key must be on disk")
            assertEquals(3L, kept.length(), "the preserved bytes must be untouched")
            assertTrue(kept.name.startsWith("journal.key.corrupt-"), "the name must mark it as corrupt")
            assertFalse(key.exists(), "the live key path must be free for the fresh key")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun posixDataDirAndKeyAreOwnerOnly() {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        val home = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-perm-${System.nanoTime()}",
        ).apply { mkdirs() }
        val previous = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            JournalAtRest.seal("{}")
            val dir = File(home, ".nepenthe")
            val key = File(dir, "journal.key")
            assertTrue(key.exists(), "the key file must exist")
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(key.toPath()),
                "the key file must be owner read/write only",
            )
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(dir.toPath()),
                "the data directory must not be traversable by other local users",
            )
        } finally {
            System.setProperty("user.home", previous)
            home.deleteRecursively()
        }
    }
}
