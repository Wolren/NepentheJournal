package app.journal.model.rules

import app.journal.model.*
import kotlin.test.*

class ActiveInteractionCheckTest {

    private val now = 1_700_000_000_000L

    private fun substance(
        id: String,
        name: String,
        classes: List<String> = emptyList(),
        halfLifeMinutes: Double? = null,
    ) = Substance(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L,
        deviceOrigin = "test",
        substanceClass = listOf("Test"),
        interactionClasses = classes,
        halfLifeMinutes = halfLifeMinutes,
        cachedAt = 0L,
        sourceVersion = "test",
    )

    private fun dose(
        id: String,
        substanceId: String,
        at: Long,
        sessionId: String = "s:1",
    ) = Dose(
        id = id,
        createdAt = at,
        updatedAt = at,
        deviceOrigin = "test",
        sessionId = sessionId,
        substanceId = substanceId,
        routeOfAdministration = "Oral",
        amount = 100.0,
        unit = "mg",
        timestamp = at,
    )

    private fun medication(
        name: String,
        preset: MedicationPreset,
        startedAt: Long = now - 86_400_000L,
        endedAt: Long? = null,
    ) = Medication(
        id = "medication:$name",
        createdAt = startedAt,
        updatedAt = startedAt,
        deviceOrigin = "test",
        name = name,
        presetId = preset.id,
        startedAt = startedAt,
        endedAt = endedAt,
    )

    private fun run(
        sessionDoses: List<Dose> = emptyList(),
        medications: List<Medication> = emptyList(),
        allDoses: List<Dose> = sessionDoses,
        ownDoseIds: Set<String> = sessionDoses.map { it.id }.toSet(),
        substances: List<Substance>,
        interactions: List<Interaction> = emptyList(),
    ) = ActiveInteractionCheck.run(
        sessionDoses = sessionDoses,
        medications = medications,
        allDoses = allDoses,
        ownDoseIds = ownDoseIds,
        substancesById = substances.associateBy { it.id },
        interactions = interactions,
        now = now,
    )

    @Test
    fun standingSsriWithMdmaIsCaution() {
        val mdma = substance("cid:mdma", "MDMA", listOf("serotonin_releaser", "stimulant"))
        val result = run(
            sessionDoses = listOf(dose("d:1", mdma.id, now - 60_000)),
            medications = listOf(medication("Sertraline", MedicationPreset.SSRI)),
            substances = listOf(mdma),
        )

        assertEquals(1, result.classWarnings.size)
        assertEquals(InteractionWarningLevel.CAUTION, result.classWarnings.first().level)
        assertEquals(listOf("Sertraline"), result.medicationNames)
    }

    @Test
    fun standingMaoiWithMdmaIsDanger() {
        val mdma = substance("cid:mdma", "MDMA", listOf("serotonin_releaser"))
        val result = run(
            sessionDoses = listOf(dose("d:1", mdma.id, now - 60_000)),
            medications = listOf(medication("Phenelzine", MedicationPreset.MAOI)),
            substances = listOf(mdma),
        )

        assertEquals(InteractionWarningLevel.DANGER, result.classWarnings.first().level)
    }

    @Test
    fun standingLithiumWithPsychedelicIsDanger() {
        val lsd = substance("cid:lsd", "LSD", listOf("psychedelic"))
        val result = run(
            sessionDoses = listOf(dose("d:1", lsd.id, now - 60_000)),
            medications = listOf(medication("Lithium", MedicationPreset.LITHIUM)),
            substances = listOf(lsd),
        )

        assertEquals(InteractionWarningLevel.DANGER, result.classWarnings.first().level)
    }

    @Test
    fun standingBenzodiazepineWithOpioidIsDanger() {
        val oxy = substance("cid:oxy", "Oxycodone", listOf("opioid"))
        val result = run(
            sessionDoses = listOf(dose("d:1", oxy.id, now - 60_000)),
            medications = listOf(medication("Diazepam", MedicationPreset.BENZODIAZEPINE)),
            substances = listOf(oxy),
        )

        assertEquals(InteractionWarningLevel.DANGER, result.classWarnings.first().level)
    }

    @Test
    fun stoppedMedicationIsIgnored() {
        val mdma = substance("cid:mdma", "MDMA", listOf("serotonin_releaser"))
        val result = run(
            sessionDoses = listOf(dose("d:1", mdma.id, now - 60_000)),
            medications = listOf(
                medication("Sertraline", MedicationPreset.SSRI, endedAt = now - 1_000)
            ),
            substances = listOf(mdma),
        )

        assertTrue(result.isEmpty)
        assertTrue(result.medicationNames.isEmpty())
    }

    @Test
    fun medicationThatStartsLaterIsIgnored() {
        val mdma = substance("cid:mdma", "MDMA", listOf("serotonin_releaser"))
        val result = run(
            sessionDoses = listOf(dose("d:1", mdma.id, now - 60_000)),
            medications = listOf(
                medication("Sertraline", MedicationPreset.SSRI, startedAt = now + 60_000)
            ),
            substances = listOf(mdma),
        )

        assertTrue(result.isEmpty)
    }

    @Test
    fun recentDoseInsideHalfLifeWindowIsIncluded() {
        val mdma = substance("cid:mdma", "MDMA", listOf("serotonin_releaser"), halfLifeMinutes = 120.0)
        val historic = dose("d:hist", mdma.id, now - 2 * 60 * 60 * 1000L, sessionId = "s:old")

        val result = run(
            medications = listOf(medication("Sertraline", MedicationPreset.SSRI)),
            allDoses = listOf(historic),
            ownDoseIds = emptySet(),
            substances = listOf(mdma),
        )

        assertEquals(listOf("MDMA"), result.recentSubstanceNames)
        assertEquals(InteractionWarningLevel.CAUTION, result.classWarnings.first().level)
    }

    @Test
    fun recentDoseOutsideHalfLifeWindowIsIgnored() {
        val mdma = substance("cid:mdma", "MDMA", listOf("serotonin_releaser"), halfLifeMinutes = 120.0)
        val historic = dose("d:hist", mdma.id, now - 12 * 60 * 60 * 1000L, sessionId = "s:old")

        val result = run(
            medications = listOf(medication("Sertraline", MedicationPreset.SSRI)),
            allDoses = listOf(historic),
            ownDoseIds = emptySet(),
            substances = listOf(mdma),
        )

        assertTrue(result.isEmpty)
        assertTrue(result.recentSubstanceNames.isEmpty())
    }

    @Test
    fun substanceWithoutHalfLifeIsNeverRecent() {
        val unknown = substance("cid:x", "Unknown", listOf("serotonin_releaser"), halfLifeMinutes = null)
        val historic = dose("d:hist", unknown.id, now - 60_000, sessionId = "s:old")

        val result = run(
            medications = listOf(medication("Sertraline", MedicationPreset.SSRI)),
            allDoses = listOf(historic),
            ownDoseIds = emptySet(),
            substances = listOf(unknown),
        )

        assertTrue(result.isEmpty)
    }

    @Test
    fun ownDoseIsNotCountedAsRecent() {
        val mdma = substance("cid:mdma", "MDMA", listOf("serotonin_releaser"), halfLifeMinutes = 120.0)
        val own = dose("d:own", mdma.id, now - 60_000)

        val result = run(
            sessionDoses = listOf(own),
            medications = listOf(medication("Sertraline", MedicationPreset.SSRI)),
            allDoses = listOf(own),
            substances = listOf(mdma),
        )

        assertTrue(result.recentSubstanceNames.isEmpty())
        assertEquals(1, result.classWarnings.size)
    }

    @Test
    fun loggedSubstanceAndStandingMedicationMergeWithoutSelfPair() {
        val sertraline = substance("cid:sert", "Sertraline", listOf("ssri", "serotonergic"))
        val result = run(
            sessionDoses = listOf(dose("d:1", sertraline.id, now - 60_000)),
            medications = listOf(medication("Sertraline", MedicationPreset.SSRI)),
            substances = listOf(sertraline),
        )

        assertTrue(result.classWarnings.isEmpty())
        assertEquals(listOf("Sertraline"), result.medicationNames)
    }

    @Test
    fun pairTableRiskIsStillReported() {
        val ghb = substance("cid:ghb", "GHB", listOf("depressant"))
        val alcohol = substance("cid:etoh", "Alcohol", listOf("depressant"))
        val interaction = Interaction(
            id = "i:1",
            createdAt = 0L,
            updatedAt = 0L,
            substanceAId = ghb.id,
            substanceBId = alcohol.id,
            riskLevel = InteractionRisk.DANGEROUS,
        )
        val result = run(
            sessionDoses = listOf(
                dose("d:1", ghb.id, now - 60_000),
                dose("d:2", alcohol.id, now - 30_000),
            ),
            substances = listOf(ghb, alcohol),
            interactions = listOf(interaction),
        )

        assertEquals(1, result.pairs.dangerous.size)
        assertFalse(result.isEmpty)
    }

    @Test
    fun otherPresetContributesNoClasses() {
        val mdma = substance("cid:mdma", "MDMA", listOf("serotonin_releaser"))
        val result = run(
            sessionDoses = listOf(dose("d:1", mdma.id, now - 60_000)),
            medications = listOf(medication("Vitamin D", MedicationPreset.OTHER)),
            substances = listOf(mdma),
        )

        assertTrue(result.classWarnings.isEmpty())
        assertEquals(listOf("Vitamin D"), result.medicationNames)
    }
}
