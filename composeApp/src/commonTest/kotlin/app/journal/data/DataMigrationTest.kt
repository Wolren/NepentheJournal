package app.journal.data

import app.journal.model.*
import kotlin.test.*

class DataMigrationTest {

    @Test
    fun oldPwikiDoseReferencesAreMigrated() {
        val repo = JournalRepository()

        // Substance with old pwiki ID and new cid ID
        repo.upsertSubstance(Substance(
            id = "cid:5761", oldId = "pwiki:lsd", name = "LSD",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))

        // Dose referencing the OLD pwiki ID
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "pwiki:lsd",
            routeOfAdministration = "Oral", amount = 100.0, unit = "µg", timestamp = 1000L
        ))

        // Run migration
        DataInitializer.migrateOldIds(repo)

        // Dose should now reference the cid
        val dose = repo.doses.value.first()
        assertEquals("cid:5761", dose.substanceId)
    }

    @Test
    fun oldPwikiInteractionReferencesAreMigrated() {
        val repo = JournalRepository()

        repo.upsertSubstance(Substance(
            id = "cid:5761", oldId = "pwiki:lsd", name = "LSD",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSubstance(Substance(
            id = "cid:1615", oldId = "pwiki:psilocybin", name = "Psilocybin",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))

        // Interaction referencing old pwiki IDs
        repo.upsertInteraction(Interaction(
            id = "interaction:pwiki:lsd:pwiki:psilocybin",
            substanceAId = "pwiki:lsd", substanceBId = "pwiki:psilocybin",
            riskLevel = InteractionRisk.UNSAFE,
            description = "Cross-tolerance",
            sources = listOf("psychonautwiki"),
            createdAt = 0L, updatedAt = 0L
        ))

        DataInitializer.migrateOldIds(repo)

        val interaction = repo.interactions.value.first()
        // IDs are alphabetically sorted after migration
        assertEquals("cid:1615", interaction.substanceAId)
        assertEquals("cid:5761", interaction.substanceBId)
        // ID preserved as-is (just a unique key, the fields hold the canonical relationship)
    }

    @Test
    fun migrationIsIdempotent() {
        val repo = JournalRepository()

        repo.upsertSubstance(Substance(
            id = "cid:5761", oldId = "pwiki:lsd", name = "LSD",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "pwiki:lsd",
            routeOfAdministration = "Oral", amount = 100.0, unit = "µg", timestamp = 1000L
        ))

        DataInitializer.migrateOldIds(repo)
        assertEquals("cid:5761", repo.doses.value.first().substanceId)

        // Run again
        DataInitializer.migrateOldIds(repo)
        assertEquals("cid:5761", repo.doses.value.first().substanceId)
        assertEquals(1, repo.doses.value.size)
    }

    @Test
    fun noMigrationNeededWhenAllCids() {
        val repo = JournalRepository()

        repo.upsertSubstance(Substance(
            id = "cid:5761", name = "LSD",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "cid:5761",
            routeOfAdministration = "Oral", amount = 100.0, unit = "µg", timestamp = 1000L
        ))

        // No oldId set — migration should be a no-op
        DataInitializer.migrateOldIds(repo)
        assertEquals("cid:5761", repo.doses.value.first().substanceId)
    }
}
