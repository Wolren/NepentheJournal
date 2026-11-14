package app.journal.ui.dashboard

import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Substance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The dashboard maps must survive a tab revisit. A fresh instance would compute
 * them again, which is exactly the cost the cache exists to remove.
 */
class DashboardViewModelTest {

    private val timestamp = 1_700_000_000_000L

    private fun makeRepo(): JournalRepository {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:lsd", name = "LSD", substanceClass = emptyList(),
            aliases = emptyList(), routesOfAdministration = emptyList(),
            effects = emptyList(), toxicity = emptyList(),
            createdAt = 0L, updatedAt = 0L, cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "sub:lsd",
            routeOfAdministration = "Oral", amount = 100.0, unit = "ug",
            timestamp = timestamp
        ))
        return repo
    }

    @Test
    fun `dated substances are computed once and match every dose`() = runBlocking {
        val viewModel = DashboardViewModel(makeRepo())
        val first = viewModel.substancesByDate.first { it.isNotEmpty() }
        val second = viewModel.substancesByDate.first { it.isNotEmpty() }
        assertSame(first, second, "a re-subscribe must reuse the date map")

        val expectedDate = Instant.fromEpochMilliseconds(timestamp)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        assertEquals(1, first.size)
        assertEquals(listOf("LSD"), first[expectedDate])
    }

    @Test
    fun `substance session pairs are computed once`() = runBlocking {
        val viewModel = DashboardViewModel(makeRepo())
        val first = viewModel.substanceSessionPairs.first { it.isNotEmpty() }
        val second = viewModel.substanceSessionPairs.first { it.isNotEmpty() }
        assertSame(first, second, "a re-subscribe must reuse the pair list")
        assertEquals(listOf("LSD" to "s:1"), first)
    }
}