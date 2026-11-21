package app.journal.data

import kotlin.test.Test
import kotlin.test.assertEquals

class TombstoneDeleteTest {

    @Test
    fun deletingAbsentSubstanceRecordsNoTombstone() {
        val repo = JournalRepository.instance
        val before = repo.exportTombstones()

        repo.deleteSubstance("substance:missing")

        assertEquals(before, repo.exportTombstones())
    }
}
