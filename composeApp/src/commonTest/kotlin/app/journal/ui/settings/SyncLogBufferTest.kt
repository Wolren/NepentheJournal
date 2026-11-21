package app.journal.ui.settings

import app.journal.ui.components.SyncLogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sync log is fed by a session-long flow. An uncapped prepend list grows
 * with every event, so the buffer keeps the newest [SYNC_LOG_CAP] lines only.
 */
class SyncLogBufferTest {

    private fun line(i: Int) = SyncLogEntry.Info("line $i")

    @Test
    fun appendingKeepsTheNewestLinesOnly() {
        var buffer = emptyList<SyncLogEntry>()
        repeat(500) { i -> buffer = appendSyncLog(buffer, line(i), cap = 200) }

        assertEquals(200, buffer.size, "the log must stay capped")
        assertEquals("line 499", buffer.first().text, "newest line first")
        assertEquals("line 300", buffer.last().text, "oldest retained line")
    }

    @Test
    fun smallCapKeepsOneLine() {
        val buffer = appendSyncLog(listOf(line(1), line(2)), line(3), cap = 1)
        assertEquals(listOf("line 3"), buffer.map { it.text })
    }

    @Test
    fun defaultCapMatchesTheFlowReplay() {
        assertTrue(SYNC_LOG_CAP > 0)
        var buffer = emptyList<SyncLogEntry>()
        repeat(SYNC_LOG_CAP * 3) { buffer = appendSyncLog(buffer, line(it)) }
        assertEquals(SYNC_LOG_CAP, buffer.size)
    }
}
