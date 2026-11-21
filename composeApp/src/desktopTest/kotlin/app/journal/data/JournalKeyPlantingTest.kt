package app.journal.data

import app.journal.model.Session
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A key file must never be adopted or rotated by a read.
 *
 * On Windows a raw 32-byte file is not a key: the stored form is a DPAPI blob.
 * Trusting raw bytes let anyone who can drop a file next to the journal choose
 * the key, so a later copy of the sealed journal was readable by them.
 *
 * A load that meets an undecodable key must fail in place: replacing the key
 * destroys the evidence and orphans every sealed copy. Each case opens in a
 * SECOND home directory so the in-process key cache of the producing home
 * cannot mask the file-level behavior, which is what a restart sees.
 */
class JournalKeyPlantingTest {

    private val base = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-plant-${System.nanoTime()}")
    private var previousHome: String? = null

    @AfterTest
    fun restoreHome() {
        previousHome?.let { System.setProperty("user.home", it) }
        base.deleteRecursively()
    }

    private fun windows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Point the app's data directory at [dir] without dropping the cached key of the previous home. */
    private fun useHome(dir: File): File {
        if (previousHome == null) previousHome = System.getProperty("user.home")
        dir.mkdirs()
        System.setProperty("user.home", dir.absolutePath)
        return File(dir, ".nepenthe").apply { mkdirs() }
    }

    private fun session(title: String) = Session(
        id = "s:$title",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        deviceOrigin = "plant-test",
        title = title,
        startTime = 1_700_000_000_000L,
    )

    @Test
    fun aPlantedRawKeyIsNotAdoptedForNewData() {
        if (!windows()) return
        val keyDir = useHome(File(base, "planted"))
        val planted = ByteArray(32) { 0x42 }
        File(keyDir, "journal.key").writeBytes(planted)

        val sealed = journalSeal("planted-key-probe".encodeToByteArray())

        val outsider = runCatching { aesGcmOpen(sealed, planted) }
        assertTrue(
            outsider.isFailure,
            "a planted raw key must not become the key that seals this device's data",
        )
        assertNotEquals(32L, File(keyDir, "journal.key").length(),
            "the stored key must be a DPAPI blob, not the planted raw bytes")
    }

    @Test
    fun anUnreadableKeyIsNotReplacedDuringLoad() {
        val producedIn = useHome(File(base, "producer"))
        val repo = JournalRepository()
        repo.upsertSession(session("sealed"))
        JournalStore(repo).save(fullBackup = false)
        val sealedJournal = File(producedIn, "journal-data.json").readBytes()
        assertTrue(JournalAtRest.looksEncrypted(sealedJournal), "the producer must seal the journal")

        val openedIn = useHome(File(base, "opened"))
        File(openedIn, "journal-data.json").writeBytes(sealedJournal)
        val key = File(openedIn, "journal.key")
        val unreadable = byteArrayOf(1, 2, 3, 4, 5)
        key.writeBytes(unreadable)

        runCatching { JournalStore(JournalRepository()).load() }

        assertContentEquals(
            unreadable,
            key.readBytes(),
            "a load must leave an undecodable key exactly as it found it",
        )
        assertTrue(
            openedIn.listFiles().orEmpty().none { it.name.startsWith("journal.key.corrupt-") },
            "a load must not quarantine (replace) the key; that is a write, not a read",
        )
    }

    @Test
    fun aSealedJournalWithNoKeyFailsInPlace() {
        val producedIn = useHome(File(base, "sealed-producer"))
        val sealed = journalSeal("{}".encodeToByteArray())
        assertTrue(File(producedIn, "journal.key").isFile, "the producer must create a key")

        val openedIn = useHome(File(base, "sealed-opener"))
        assertTrue(!File(openedIn, "journal.key").exists(), "the opener starts with no key")

        assertFailsWith<IllegalStateException> { journalOpen(sealed) }
        assertTrue(!File(openedIn, "journal.key").exists(),
            "a read must not mint a key for a sealed journal it cannot open")
    }
}
