package app.journal.ui.session

import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.model.Session
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SessionListViewModelTest {

    private fun makeRepo(): JournalRepository {
        val repo = JournalRepository()
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Deep Meditation", startTime = 5000L, tags = listOf("meditation", "focus"),
            isFavorite = true
        ))
        repo.upsertSession(Session(
            id = "s:2", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "LSD Trip", startTime = 4000L, tags = listOf("psychedelic", "deep"),
            intention = "Introspection"
        ))
        repo.upsertSession(Session(
            id = "s:3", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "MDMA Session", startTime = 3000L, tags = listOf("empathogen"),
            consumerName = "Alice"
        ))
        repo.upsertSession(Session(
            id = "s:4", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Archived Session", startTime = 2000L, tags = emptyList(),
            isArchived = true
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
    fun `allTags extracts distinct sorted tags`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showArchived.value = true
        val tags = vm.allTags.first()
        assertEquals(listOf("deep", "empathogen", "focus", "meditation", "psychedelic"), tags)
    }

    @Test
    fun `allConsumers extracts distinct consumer names`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        val consumers = vm.allConsumers.first()
        assertEquals(listOf("Alice"), consumers)
    }

    @Test
    fun `filter by tag narrows results`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.filterTags.value = setOf("psychedelic")
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
        assertEquals("LSD Trip", filtered.first().title)
    }

    @Test
    fun `filter by multiple tags returns union`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.filterTags.value = setOf("psychedelic", "empathogen")
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
    fun `hide archived`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showArchived.value = false
        val filtered = vm.filteredSessions.first()
        assertEquals(3, filtered.size)
        assertTrue(filtered.none { it.isArchived })
    }

    @Test
    fun `show archived includes archived`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showArchived.value = true
        val filtered = vm.filteredSessions.first()
        assertEquals(4, filtered.size)
    }

    @Test
    fun `search by title`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.searchQuery.value = "lsd"
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
    }

    @Test
    fun `search by tag`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.searchQuery.value = "empathogen"
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
    }

    @Test
    fun `search by intention`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.searchQuery.value = "introspection"
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
    }

    @Test
    fun `search query is case insensitive`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.searchQuery.value = "LSD"
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
    }

    @Test
    fun `empty search returns all`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.searchQuery.value = ""
        vm.showArchived.value = true
        val filtered = vm.filteredSessions.first()
        assertEquals(4, filtered.size)
    }

    @Test
    fun `sort by recency descending`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.showArchived.value = true
        val filtered = vm.filteredSessions.first()
        assertEquals(listOf("s:1", "s:2", "s:3", "s:4"), filtered.map { it.id })
    }

    @Test
    fun `toggle tag adds and removes`() {
        val vm = SessionListViewModel(makeRepo())
        vm.toggleTag("deep")
        assertTrue("deep" in vm.filterTags.value)
        vm.toggleTag("deep")
        assertFalse("deep" in vm.filterTags.value)
    }

    @Test
    fun `clearFilters resets all filters`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.searchQuery.value = "lsd"
        vm.filterTags.value = setOf("deep")
        vm.showFavoritesOnly.value = true
        vm.consumerFilter.value = "Alice"

        vm.clearFilters()

        assertEquals(emptySet<String>(), vm.filterTags.value)
        assertEquals(null, vm.consumerFilter.value)
        assertEquals(false, vm.showFavoritesOnly.value)
        assertEquals(false, vm.showArchived.value)
        assertEquals("", vm.searchQuery.value)
    }

    @Test
    fun `consumer filter narrows results`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.consumerFilter.value = "Alice"
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
        assertEquals("MDMA Session", filtered.first().title)
    }

    @Test
    fun `combined filters work together`() = runBlocking {
        val vm = SessionListViewModel(makeRepo())
        vm.filterTags.value = setOf("focus")
        vm.searchQuery.value = "meditation"
        vm.showArchived.value = true
        val filtered = vm.filteredSessions.first()
        assertEquals(1, filtered.size)
        assertEquals("Deep Meditation", filtered.first().title)
    }
}
