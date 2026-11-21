package app.journal.ui.session

import app.journal.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The session list re-collects on every repository mutation, with or without a
 * filter. With no filter active the filter pass must return the input list
 * unchanged: copying every session per emission is the "dead filter scan".
 */
class SessionListFilterTest {

    private val sessions = listOf(
        Session(
            id = "s:1", createdAt = 1L, updatedAt = 1L, deviceOrigin = "t",
            title = "LSD evening", startTime = 100L, isFavorite = true,
        ),
        Session(
            id = "s:2", createdAt = 1L, updatedAt = 1L, deviceOrigin = "t",
            title = "Mushroom walk", startTime = 200L, consumerName = "A",
        ),
        Session(
            id = "s:3", createdAt = 1L, updatedAt = 1L, deviceOrigin = "t",
            title = "Coffee", startTime = 300L, intention = "Focus",
        ),
    )

    @Test
    fun noFilterReturnsTheSameListInstance() {
        val result = filterSessions(sessions, matchingIds = null, favsOnly = false, consumer = null, q = null)
        assertSame(sessions, result, "an inactive filter must not copy the session list")
    }

    @Test
    fun favoritesOnlyKeepsFavorites() {
        val result = filterSessions(sessions, null, favsOnly = true, consumer = null, q = null)
        assertEquals(listOf("s:1"), result.map { it.id })
    }

    @Test
    fun substanceIdsKeepTheirSessions() {
        val result = filterSessions(sessions, setOf("s:2"), favsOnly = false, consumer = null, q = null)
        assertEquals(listOf("s:2"), result.map { it.id })
    }

    @Test
    fun consumerKeepsTheirSessions() {
        val result = filterSessions(sessions, null, favsOnly = false, consumer = "A", q = null)
        assertEquals(listOf("s:2"), result.map { it.id })
    }

    @Test
    fun queryMatchesTitleAndIntentionCaseInsensitively() {
        assertEquals(listOf("s:3"), filterSessions(sessions, null, false, null, "focus").map { it.id })
        assertEquals(listOf("s:3"), filterSessions(sessions, null, false, null, "COFFEE").map { it.id })
        assertTrue(filterSessions(sessions, null, false, null, "zzz").isEmpty())
    }
}
