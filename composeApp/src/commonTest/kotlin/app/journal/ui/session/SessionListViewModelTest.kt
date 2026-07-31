package app.journal.ui.session

import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.Substance
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SessionListViewModelTest {

    private fun makeRepo(): JournalRepository {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:lsd", name = "LSD", substanceClass = emptyList(),
            aliases = emptyList(), routesOfAdministration = emptyList(),
            effects = emptyList(), toxicity = emptyList(),
            createdAt = 0L, updatedAt = 0L, cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSubstance(Substance(
            id = "sub:mdma", name = "MDMA", substanceClass = emptyList(),
            aliases = emptyList(), routesOfAdministration = emptyList(),
            effects = emptyList(), toxicity = emptyList(),
            createdAt = 0L, updatedAt = 0L, cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSubstance(Substance(
            id = "sub:weed", name = "Cannabis", substanceClass = emptyList(),
            aliases = emptyList(), routesOfAdministration = emptyList(),
            effects = emptyList(), toxicity = emptyList(),
            createdAt = 0L, updatedAt = 0L, cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Deep Meditation", startTime = 5000L,
            isFavorite = true
        ))
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "sub:lsd",
            routeOfAdministration = "Oral", amount = 100.0, unit = "ug",
            timestamp = 5000L
        ))
        repo.upsertSession(Session(
            id = "s:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "LSD Trip", startTime = 4000L,
            intention = "Introspection"
        ))
        repo.upsertDose(Dose(
            id = "d:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:2", substanceId = "sub:lsd",
            routeOfAdministration = "Oral", amount = 150.0, unit = "ug",
            timestamp = 4000L
        ))
        repo.upsertSession(Session(
            id = "s:3", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "MDMA Session", startTime = 3000L,
            consumerName = "Alice"
        ))
        repo.upsertDose(Dose(
            id = "d:3", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:3", substanceId = "sub:mdma",
            routeOfAdministration = "Oral", amount = 120.0, unit = "mg",
            timestamp = 3000L
        ))
        repo.upsertSession(Session(
            id = "s:4", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Archived Session", startTime = 2000L,
            isArchived = true
        ))
        // Cannabis substance must have a dose: allSessionSubstances derives
        // from doses joined against substances, so a substance with no dose
        // never appears (and a predicate waiting for it would hang forever).
        repo.upsertDose(Dose(
            id = "d:4", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "sub:weed",
            routeOfAdministration = "Oral", amount = 0.3, unit = "g",
            timestamp = 5000L
        ))
        return repo
    }

    @Test
    fun `all sessions are returned initially`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showArchived.value = true  // include archived by default
        val filtered = vm.filteredSessions.first()
        assertEquals(4, filtered.size)
    }

    @Test
    fun `allSessionSubstances extracts distinct substances`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showArchived.value = true
        // combine may need a dispatch; wait for substance count
        val items = vm.allSessionSubstances.first { it.size == 3 }
        assertEquals(3, items.size)
        assertTrue(items.any { it.name == "LSD" })
        assertTrue(items.any { it.name == "MDMA" })
        assertTrue(items.any { it.name == "Cannabis" })
    }

    @Test
    fun `allConsumers extracts distinct consumer names`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        val consumers = vm.allConsumers.first()
        assertEquals(listOf("Alice"), consumers)
    }

    @Test
    fun `filter by substance narrows results`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.filterSubstanceIds.value = setOf("sub:mdma")
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
        assertEquals("MDMA Session", filtered.first().title)
    }

    @Test
    fun `filter by substance returns union across sessions`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.filterSubstanceIds.value = setOf("sub:lsd")
        val filtered = vm.filteredSessions.first()
        assertEquals(2, filtered.size)
    }

    @Test
    fun `show favorites only`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showFavoritesOnly.value = true
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
        assertEquals("Deep Meditation", filtered.first().title)
    }

    @Test
    fun `search filters by title`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showArchived.value = true
        vm.searchQuery.value = "LSD"
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
        assertEquals("LSD Trip", filtered.first().title)
    }

    @Test
    fun `search filters by intention`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showArchived.value = true
        vm.searchQuery.value = "introspection"
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
        assertEquals("LSD Trip", filtered.first().title)
    }

    @Test
    fun `clearFilters resets all filters`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showArchived.value = true
        vm.filterSubstanceIds.value = setOf("sub:mdma")
        vm.searchQuery.value = "test"
        vm.showFavoritesOnly.value = true
        vm.clearFilters()
        val filtered = vm.filteredSessions.first()
        // After clear: archived off, favorites off, no substance filter, no search
        assertEquals(3, filtered.size) // s:4 is archived, hidden
    }
}
