package app.journal.data

import app.journal.model.AutosaveDelay
import app.journal.model.Session
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoSaveDelayTest {

    @Test
    fun slowPreferenceDelaysTheAutoSave() = runTest {
        val repo = JournalRepository()
        repo.setAutosaveDelay(AutosaveDelay.SLOW)
        var saves = 0

        repo.autoSave(backgroundScope) { saves++ }
        runCurrent()

        repo.upsertSession(
            Session(
                id = "s:1",
                createdAt = 0L,
                updatedAt = 0L,
                deviceOrigin = "test",
                title = "Session",
                startTime = 1000L
            )
        )
        advanceTimeBy(4_999)
        assertEquals(0, saves, "the slow preference must not save before five seconds")
        advanceTimeBy(2)
        assertEquals(1, saves, "the slow preference must save after five seconds")
    }
}
