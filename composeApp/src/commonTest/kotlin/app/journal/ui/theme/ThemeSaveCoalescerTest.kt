package app.journal.ui.theme

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A theme slider drag must not write theme.json once per tick. The coalescer
 * saves the settled config after the window, and a flush saves at once.
 */
class ThemeSaveCoalescerTest {

    @Test
    fun aBurstOfSliderTicksWritesOnce() = runTest {
        val saved = mutableListOf<ThemeConfig>()
        val coalescer = ThemeSaveCoalescer({ saved += it }, backgroundScope, windowMs = 300)

        repeat(12) { i ->
            coalescer.request(ThemeDefaults.Dark.copy(backgroundImagePath = "drag-$i"))
        }
        advanceTimeBy(299)
        assertEquals(0, saved.size, "nothing may reach disk before the window closes")

        advanceTimeBy(2)
        assertEquals(1, saved.size, "the whole drag must collapse into one write")
        assertEquals("drag-11", saved.single().backgroundImagePath, "the newest config must win")
    }

    @Test
    fun flushWritesThePendingConfigImmediately() = runTest {
        val saved = mutableListOf<ThemeConfig>()
        val coalescer = ThemeSaveCoalescer({ saved += it }, backgroundScope, windowMs = 300)

        coalescer.request(ThemeDefaults.Dark.copy(backgroundImagePath = "exit"))
        coalescer.flush()
        assertEquals(1, saved.size)
        assertEquals("exit", saved.single().backgroundImagePath)

        advanceTimeBy(1_000)
        assertEquals(1, saved.size, "a flushed write must not be repeated by the timer")
    }

    @Test
    fun aSecondBurstWritesAgain() = runTest {
        val saved = mutableListOf<ThemeConfig>()
        val coalescer = ThemeSaveCoalescer({ saved += it }, backgroundScope, windowMs = 300)

        coalescer.request(ThemeDefaults.Dark.copy(backgroundImagePath = "one"))
        advanceTimeBy(301)
        coalescer.request(ThemeDefaults.Dark.copy(backgroundImagePath = "two"))
        advanceTimeBy(301)

        assertEquals(listOf("one", "two"), saved.map { it.backgroundImagePath })
        assertTrue(saved.size == 2)
    }
}
