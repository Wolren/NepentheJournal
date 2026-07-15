package app.journal.ui.substances

import app.journal.data.JournalRepository
import app.journal.model.Substance
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SubstanceScreenViewModelTest {

    private fun makeRepo(): JournalRepository {
        val repo = JournalRepository()
        // Substance with data — appears in "real" list
        repo.upsertSubstance(Substance(
            id = "cid:1", name = "LSD", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("Classical Psychedelic"),
            routesOfAdministration = listOf("Oral"), effects = listOf("Visual"),
            dosageBands = emptyMap(), cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSubstance(Substance(
            id = "cid:2", name = "MDMA", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("Empathogen"),
            routesOfAdministration = listOf("Oral"), effects = emptyList(),
            dosageBands = emptyMap(), cachedAt = 0L, sourceVersion = "test"
        ))
        // Category-only entry — filtered out by realSubstances
        repo.upsertSubstance(Substance(
            id = "cid:3", name = "Stimulants", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("Stimulant"),
            routesOfAdministration = emptyList(), effects = emptyList(),
            dosageBands = emptyMap(), cachedAt = 0L, sourceVersion = "test"
        ))
        return repo
    }

    @Test
    fun `realSubstances filters out category-only entries`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        val real = vm.realSubstances.first()
        assertEquals(2, real.size)
        assertTrue(real.none { it.name == "Stimulants" })
    }

    @Test
    fun `allCategories extracts distinct sorted classes`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        val cats = vm.allCategories.first()
        assertEquals(listOf("Classical Psychedelic", "Empathogen"), cats)
    }

    @Test
    fun `results returns all real substances initially`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        val res = vm.results.first()
        assertEquals(2, res.size)
    }

    @Test
    fun `search by name`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.query.value = "lsd"
        val res = vm.results.first()
        assertEquals(1, res.size)
        assertEquals("LSD", res.first().name)
    }

    @Test
    fun `search by alias`() = runBlocking {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "cid:1", name = "LSD", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("Classical Psychedelic"),
            routesOfAdministration = listOf("Oral"), effects = listOf("Visual"),
            dosageBands = emptyMap(), cachedAt = 0L, sourceVersion = "test",
            aliases = listOf("Lucy", "Acid")
        ))
        val vm = SubstanceScreenViewModel(repo)
        vm.query.value = "lucy"
        val res = vm.results.first()
        assertEquals(1, res.size)
        assertEquals("LSD", res.first().name)
    }

    @Test
    fun `search by class`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.query.value = "empathogen"
        val res = vm.results.first()
        assertEquals(1, res.size)
        assertEquals("MDMA", res.first().name)
    }

    @Test
    fun `search is case insensitive`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.query.value = "LSD"
        val res = vm.results.first()
        assertEquals(1, res.size)
    }

    @Test
    fun `empty search returns all`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.query.value = ""
        val res = vm.results.first()
        assertEquals(2, res.size)
    }

    @Test
    fun `no match returns empty`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.query.value = "zzznonexistent"
        val res = vm.results.first()
        assertTrue(res.isEmpty())
    }

    @Test
    fun `category filter narrows results`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.activeCategories.value = setOf("Empathogen")
        val res = vm.results.first()
        assertEquals(1, res.size)
        assertEquals("MDMA", res.first().name)
    }

    @Test
    fun `category filter with search`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.activeCategories.value = setOf("Classical Psychedelic")
        vm.query.value = "lsd"
        val res = vm.results.first()
        assertEquals(1, res.size)
        assertEquals("LSD", res.first().name)
    }

    @Test
    fun `clearFilters resets all`() {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.query.value = "test"
        vm.activeCategories.value = setOf("Psychedelic")
        vm.clearFilters()
        assertEquals("", vm.query.value)
        assertTrue(vm.activeCategories.value.isEmpty())
    }

    @Test
    fun `toggleCategory adds and removes`() {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.toggleCategory("Psychedelic")
        assertTrue("Psychedelic" in vm.activeCategories.value)
        vm.toggleCategory("Psychedelic")
        assertFalse("Psychedelic" in vm.activeCategories.value)
    }

    @Test
    fun `substanceDoseStats is empty for empty repo`() {
        val vm = SubstanceScreenViewModel(JournalRepository())
        assertTrue(vm.substanceDoseStats.isEmpty())
    }
}
