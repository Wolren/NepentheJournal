package app.journal.ui.substances

import app.journal.data.JournalRepository
import app.journal.model.CuratedSection
import app.journal.model.IupharData
import app.journal.model.IupharInteraction
import app.journal.model.Substance
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SubstanceScreenViewModelTest {

    private fun makeRepo(): JournalRepository {
        val repo = JournalRepository()
        // Substance with data: appears in "real" list
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
        // Category-only entry: filtered out by realSubstances
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
    fun `broadOptions groups present broads in taxonomy order with counts`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        val broads = vm.broadOptions.first()
        assertEquals(
            listOf(BroadOption("psychedelics", "Psychedelics", 1), BroadOption("entactogens", "Entactogens", 1)),
            broads,
        )
    }

    @Test
    fun `specificOptions lists raw classes under the active broad`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.selectBroad("psychedelics")
        val specifics = vm.specificOptions.first()
        assertEquals(listOf(SpecificOption("Classical Psychedelic", 1)), specifics)
    }

    @Test
    fun `specificOptions is empty without an active broad`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        assertTrue(vm.specificOptions.first().isEmpty())
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
    fun `broad filter narrows results`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.selectBroad("entactogens")
        val res = vm.results.first()
        assertEquals(1, res.size)
        assertEquals("MDMA", res.first().name)
    }

    @Test
    fun `broad filter with search`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.selectBroad("psychedelics")
        vm.query.value = "lsd"
        val res = vm.results.first()
        assertEquals(1, res.size)
        assertEquals("LSD", res.first().name)
    }

    @Test
    fun `specific refinement narrows within the broad`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.selectBroad("psychedelics")
        vm.toggleSpecific("Nonexistent Class")
        assertTrue(vm.results.first().isEmpty())
        vm.toggleSpecific("Nonexistent Class")
        assertEquals(1, vm.results.first().size)
    }

    @Test
    fun `selecting another broad clears specifics`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.selectBroad("psychedelics")
        vm.toggleSpecific("Classical Psychedelic")
        vm.selectBroad("entactogens")
        assertTrue(vm.activeSpecifics.value.isEmpty())
        assertEquals(1, vm.results.first().size)
    }

    @Test
    fun `clearFilters resets all`() {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.query.value = "test"
        vm.selectBroad("psychedelics")
        vm.toggleSpecific("Classical Psychedelic")
        vm.clearFilters()
        assertEquals("", vm.query.value)
        assertEquals(null, vm.activeBroad.value)
        assertTrue(vm.activeSpecifics.value.isEmpty())
    }

    @Test
    fun `selectBroad toggles off by reselecting from the screen`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.selectBroad("psychedelics")
        assertEquals(1, vm.results.first().size)
        vm.selectBroad(null)
        assertEquals(2, vm.results.first().size)
    }

    @Test
    fun `toggleSpecific adds and removes`() {
        val vm = SubstanceScreenViewModel(makeRepo())
        vm.toggleSpecific("Classical Psychedelic")
        assertTrue("Classical Psychedelic" in vm.activeSpecifics.value)
        vm.toggleSpecific("Classical Psychedelic")
        assertFalse("Classical Psychedelic" in vm.activeSpecifics.value)
    }

    @Test
    fun `substanceDoseStats is empty for empty repo`() {
        val vm = SubstanceScreenViewModel(JournalRepository())
        assertTrue(vm.substanceDoseStats.isEmpty())
    }

    private fun makeKorRepo(): JournalRepository {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "cid:9", name = "Salvinorin A", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("salvinorin"),
            routesOfAdministration = listOf("Smoked"), effects = listOf("Hallucination"),
            dosageBands = emptyMap(), cachedAt = 0L, sourceVersion = "test",
            iupharData = IupharData(
                interactions = listOf(
                    IupharInteraction(targetName = "&kappa; receptor", action = "Full agonist"),
                ),
            ),
        ))
        // The plant carries no assay rows; only the name marks it.
        repo.upsertSubstance(Substance(
            id = "cid:11", name = "Salvia divinorum", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("atypical hallucinogen", "furanolactone", "hallucinogen"),
            routesOfAdministration = listOf("Smoked"), effects = listOf("Hallucination"),
            dosageBands = emptyMap(), cachedAt = 0L, sourceVersion = "test",
        ))
        return repo
    }

    @Test
    fun `kor agonist appears under Dysdelic`() = runBlocking {
        val vm = SubstanceScreenViewModel(makeKorRepo())
        val broads = vm.broadOptions.first()
        assertTrue(
            broads.any { it.id == "dysdelic" && it.label == "Dysdelic" && it.count == 2 },
            "expected Dysdelic broad with 2, got $broads",
        )
        vm.selectBroad("dysdelic")
        val res = vm.results.first()
        assertEquals(listOf("Salvia divinorum", "Salvinorin A"), res.map { it.name }.sorted())
        assertEquals(
            listOf(
                SpecificOption("KOR agonist", 1),
                SpecificOption("KOR full agonist", 1),
                SpecificOption("salvinorin", 1),
            ),
            vm.specificOptions.first().sortedBy { it.label },
        )
    }

    @Test
    fun `curated section labels lead the submenu`() = runBlocking {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "cid:12", name = "Mephedrone", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("cathinone (substituted)"),
            routesOfAdministration = listOf("Oral"), effects = listOf("Stimulation"),
            dosageBands = emptyMap(), cachedAt = 0L, sourceVersion = "test",
            curatedSections = listOf(CuratedSection("stimulant", "cathinone", "Cathinone")),
        ))
        val vm = SubstanceScreenViewModel(repo)
        vm.selectBroad("stimulants")
        assertEquals("Mephedrone", vm.results.first().single().name)
        val labels = vm.specificOptions.first().map { it.label }
        assertEquals("Cathinone", labels.first())
        assertTrue("cathinone (substituted)" in labels)
    }

    @Test
    fun `kor antagonist does not appear under Dysdelic`() = runBlocking {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "cid:10", name = "Buprenorphine", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("opioid"),
            routesOfAdministration = listOf("Sublingual"), effects = listOf("Sedation"),
            dosageBands = emptyMap(), cachedAt = 0L, sourceVersion = "test",
            iupharData = IupharData(
                interactions = listOf(
                    IupharInteraction(targetName = "&kappa; receptor", action = "Antagonist"),
                ),
            ),
        ))
        val vm = SubstanceScreenViewModel(repo)
        assertTrue(vm.broadOptions.first().none { it.id == "dysdelic" })
        vm.selectBroad("opioids")
        assertEquals("Buprenorphine", vm.results.first().single().name)
    }
}
