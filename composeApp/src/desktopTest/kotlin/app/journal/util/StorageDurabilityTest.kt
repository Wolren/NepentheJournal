package app.journal.util

import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings and journal writes must never leave a torn file. These tests hammer
 * the write primitive from several threads and kill a writer mid-flight.
 */
class StorageDurabilityTest {

    private val dir = File(
        System.getProperty("java.io.tmpdir") ?: ".",
        "nepenthe-durability-${System.nanoTime()}"
    ).apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun payload(tag: String, pad: Int): String =
        """{"tag":"$tag","pad":"${"x".repeat(pad)}"}"""

    @Test
    fun concurrentWritersNeverExposeAPartialFile() {
        val target = File(dir, "settings.json")
        val payloads = (0 until 4).map { payload("w$it", 120_000) }
        PlatformFile.writeText(target.absolutePath, payloads[0])

        val failures = Collections.synchronizedList(mutableListOf<String>())
        val stop = AtomicBoolean(false)
        val reader = Thread {
            while (!stop.get()) {
                try {
                    val text = PlatformFile.readText(target.absolutePath)
                    if (text !in payloads) failures += "torn read of ${text.length} chars"
                } catch (e: Exception) {
                    failures += "read failed: ${e.message}"
                }
            }
        }
        val writers = payloads.map { text ->
            Thread {
                repeat(40) {
                    try {
                        PlatformFile.writeText(target.absolutePath, text)
                    } catch (e: Exception) {
                        failures += "write failed: ${e.message}"
                    }
                }
            }
        }
        reader.start()
        writers.forEach { it.start() }
        writers.forEach { it.join() }
        stop.set(true)
        reader.join()

        assertTrue(failures.isEmpty(), "every read must be a whole payload, saw: ${failures.take(3)}")
        assertEquals(0, dir.listFiles()?.count { it.name.endsWith(".tmp") },
            "the atomic write must leave no temp file behind")
    }

    @Test
    fun aKilledWriterLeavesTheOldFileWhole() {
        val target = File(dir, "killed.json")
        val seed = payload("seed", 10_000)
        PlatformFile.writeText(target.absolutePath, seed)
        val java = File(System.getProperty("java.home") ?: ".", "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path") ?: ""

        repeat(3) { round ->
            val childLog = File(dir, "child-$round.log")
            val child = ProcessBuilder(
                java, "-cp", classpath,
                "app.journal.util.CrashWriterMainKt", target.absolutePath
            ).redirectErrorStream(true).redirectOutput(childLog).start()

            val deadline = System.currentTimeMillis() + 15_000
            var replaced = false
            while (System.currentTimeMillis() < deadline && !replaced) {
                val text = runCatching { PlatformFile.readText(target.absolutePath) }.getOrNull()
                replaced = text != null && text != seed
                if (!replaced) Thread.sleep(20)
            }
            Thread.sleep(120)
            child.destroyForcibly()
            child.waitFor()

            assertTrue(replaced, "the child wrote nothing; its output was: ${childLog.readText().take(400)}")
            val text = PlatformFile.readText(target.absolutePath)
            assertTrue(text.startsWith("{\"n\":"), "a killed writer left a torn head: ${text.take(24)}")
            assertTrue(text.endsWith("\"}"), "a killed writer left a torn tail: ${text.takeLast(24)}")
        }
    }

    @Test
    fun largePayloadRoundTrips() {
        val target = File(dir, "big.json")
        val text = payload("big", 8 * 1024 * 1024)
        PlatformFile.writeText(target.absolutePath, text)
        assertEquals(text, PlatformFile.readText(target.absolutePath))
        assertEquals(text.toByteArray().size.toLong(), PlatformFile.size(target.absolutePath))
        assertFalse(File("${target.absolutePath}.tmp").exists(), "no temp file may survive a completed write")
    }
}
