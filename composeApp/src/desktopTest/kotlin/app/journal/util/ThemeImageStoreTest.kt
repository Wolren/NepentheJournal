package app.journal.util

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * The backdrop must outlive the picker: Android hands back a content:// URI whose
 * grant dies with the activity, so the theme stores a copy under the data dir.
 */
class ThemeImageStoreTest {

    private val stored = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        stored.forEach { File(it).delete() }
    }

    private fun backdropDir(): String = "${PlatformFile.dataDir()}/backdrop"

    @Test
    fun adoptCopiesThePickUnderTheDataDir() = runBlocking {
        val source = File.createTempFile("pick", ".png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            val path = assertNotNull(ThemeImageStore.adopt(source.absolutePath), "adopt must return a stored path")
            stored += path
            assertTrue(path.startsWith("${backdropDir()}/"), "the copy must live in the backdrop directory: $path")
            assertTrue(path.endsWith(".png"), "the extension must survive: $path")
            assertTrue(
                File(path).readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)),
                "the copied bytes must match the pick"
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun adoptLeavesRemoteAndDataUrlsAlone() = runBlocking {
        val remote = "https://example.com/wallpaper.jpg"
        assertEquals(remote, ThemeImageStore.adopt(remote), "a URL is already durable")
        val data = "data:image/png;base64,AAAA"
        assertEquals(data, ThemeImageStore.adopt(data), "an inline data URL is already durable")
    }

    @Test
    fun adoptDoesNotCopyAStoredBackdropTwice() = runBlocking {
        val source = File.createTempFile("pick", ".jpg").apply { writeBytes(byteArrayOf(5)) }
        try {
            val first = assertNotNull(ThemeImageStore.adopt(source.absolutePath))
            stored += first
            assertEquals(first, ThemeImageStore.adopt(first), "a stored backdrop must pass through untouched")
        } finally {
            source.delete()
        }
    }

    @Test
    fun discardLeavesUserFilesAndUrlsAlone() = runBlocking {
        val outside = File.createTempFile("outside", ".png").apply { writeBytes(byteArrayOf(7)) }
        try {
            ThemeImageStore.discard(outside.absolutePath)
            assertTrue(outside.exists(), "a file outside the backdrop directory must survive")
            ThemeImageStore.discard("https://example.com/wallpaper.jpg")
            assertTrue(outside.exists(), "a URL is not a file to delete")
        } finally {
            outside.delete()
        }
    }

    @Test
    fun discardRemovesAStoredBackdrop() = runBlocking {
        val source = File.createTempFile("pick", ".png").apply { writeBytes(byteArrayOf(8)) }
        val path = assertNotNull(ThemeImageStore.adopt(source.absolutePath))
        source.delete()
        assertTrue(File(path).exists(), "the copy must exist before the discard")
        ThemeImageStore.discard(path)
        assertTrue(!File(path).exists(), "the discard must remove the stored copy")
    }
}
