package app.journal.data

import app.journal.model.Session
import app.journal.serde.AppJson
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * At-rest contract: a saved journal carries the NEPE2 envelope, a plaintext
 * journal from an older build still loads and is re-sealed on the next save,
 * and the .bak copy is sealed as well.
 */
class JournalAtRestTest {

    private val tmpDir: File = File(System.getProperty("java.io.tmpdir"), "nep-atarst-${System.nanoTime()}")
    private val originalHome: String = System.getProperty("user.home")

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalHome)
        tmpDir.deleteRecursively()
    }

    private fun journalFile() = File(tmpDir, ".nepenthe/journal-data.json")

    private fun withHome(block: () -> Unit) {
        tmpDir.mkdirs()
        System.setProperty("user.home", tmpDir.absolutePath)
        block()
    }

    private fun session(title: String) = Session(
        id = "s:$title",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        deviceOrigin = "desktop",
        title = title,
        startTime = 1_700_000_000_000L
    )

    @Test
    fun sealRoundTripsAndCarriesTheMagic() {
        val sealed = JournalAtRest.seal("""{"version":2}""")
        assertTrue(JournalAtRest.looksEncrypted(sealed), "sealed journal must carry the NEPE2 magic")
        assertFalse(JournalAtRest.looksEncrypted("""{"version":2}""".encodeToByteArray()), "plain JSON must not look sealed")
        assertEquals("""{"version":2}""", JournalAtRest.open(sealed))
    }

    @Test
    fun compressedEnvelopeShrinksThePayload() {
        val text = """{"notes":"""" + "x".repeat(10_000) + """"}"""
        val sealed = JournalAtRest.seal(text)
        assertTrue(sealed.size < text.length / 2, "zstd must shrink the repetitive payload")
        assertEquals(text, JournalAtRest.open(sealed))
    }

    @Test
    fun legacyV1EnvelopeStillOpens() {
        val plain = """{"version":7}"""
        val legacy = "NEPE1".encodeToByteArray() + journalSeal(plain.encodeToByteArray())
        assertTrue(JournalAtRest.looksEncrypted(legacy), "a NEPE1 file must still look sealed")
        assertEquals(plain, JournalAtRest.open(legacy))
    }

    @Test
    fun uncompressedV2EnvelopeOpens() {
        val plain = """{"version":8}"""
        val legacy = "NEPE2".encodeToByteArray() + byteArrayOf(0) + journalSeal(plain.encodeToByteArray())
        assertEquals(plain, JournalAtRest.open(legacy))
    }

    @Test
    fun tamperedCiphertextFailsTheTagCheck() {
        val sealed = JournalAtRest.seal("""{"version":2}""")
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<Exception> { JournalAtRest.open(sealed) }
    }

    @Test
    fun plaintextJournalLoadsThenSavesSealed() = withHome {
        // A journal exactly as the pre-encryption build left it.
        val dir = File(tmpDir, ".nepenthe").apply { mkdirs() }
        val seeded = JournalRepository().apply { upsertSession(session("legacy")) }
        File(dir, "journal-data.json").writeText(AppJson.json.encodeToString(seeded.fullSnapshot()))

        val loaded = JournalRepository()
        val store = JournalStore(loaded)
        store.load()
        assertEquals(listOf("legacy"), loaded.sessions.value.map { it.title })

        store.save(fullBackup = false)
        assertTrue(
            JournalAtRest.looksEncrypted(journalFile().readBytes()),
            "the first save after migration must seal the file"
        )
    }

    @Test
    fun encryptedFileReloadsAndTheBackupIsSealedToo() = withHome {
        val repo = JournalRepository()
        val store = JournalStore(repo)
        repo.upsertSession(session("first"))
        store.save(fullBackup = false)

        repo.upsertSession(session("second"))
        store.save(fullBackup = true)

        val onDisk = journalFile().readBytes()
        assertTrue(JournalAtRest.looksEncrypted(onDisk), "main file must be sealed")

        val backup = File(journalFile().parentFile, "journal-data.json.bak")
        assertTrue(backup.exists(), "a full backup must exist")
        assertTrue(
            JournalAtRest.looksEncrypted(backup.readBytes()),
            ".bak must be sealed too, else a plaintext copy survives"
        )

        val reloaded = JournalRepository()
        JournalStore(reloaded).load()
        assertEquals(setOf("first", "second"), reloaded.sessions.value.map { it.title }.toSet())
    }
}
