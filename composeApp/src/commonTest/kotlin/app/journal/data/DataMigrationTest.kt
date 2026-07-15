package app.journal.data

import app.journal.model.*
import kotlin.test.*

class DataMigrationTest {

    @Test
    fun oldPwikiDoseReferencesAreMigrated() {
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
            routeOfAdministration = "Oral", amount = 100.0, unit = "\u00B5g", timestamp = 1000L
        ))

        DataInitializer.migrateOldIds(repo)

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
        assertEquals("cid:1615", interaction.substanceAId)
        assertEquals("cid:5761", interaction.substanceBId)
    }

    @Test
    fun migrationDoesNotTouchEffectsOrNotes() {
        val repo = JournalRepository()

        repo.upsertSubstance(Substance(
            id = "cid:5761", oldId = "pwiki:lsd", name = "LSD",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))

        repo.upsertEffect(Effect(
            id = "ef:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            name = "Euphoria", substanceIds = listOf("pwiki:lsd")
        ))
        repo.upsertNote(Note(
            id = "n:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", body = "Test note"
        ))

        DataInitializer.migrateOldIds(repo)

        val effect = repo.effects.value.first()
        assertEquals("pwiki:lsd", effect.substanceIds.first(),
            "effects should not be migrated (only doses and interactions)")
        assertEquals(1, repo.notes.value.size)
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
            routeOfAdministration = "Oral", amount = 100.0, unit = "\u00B5g", timestamp = 1000L
        ))

        DataInitializer.migrateOldIds(repo)
        assertEquals("cid:5761", repo.doses.value.first().substanceId)

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
            routeOfAdministration = "Oral", amount = 100.0, unit = "\u00B5g", timestamp = 1000L
        ))

        DataInitializer.migrateOldIds(repo)
        assertEquals("cid:5761", repo.doses.value.first().substanceId)
    }

    @Test
    fun migrationSkipsEmptyIdMap() {
        val repo = JournalRepository()
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "cid:5761",
            routeOfAdministration = "Oral", amount = 100.0, unit = "\u00B5g", timestamp = 1000L
        ))
        DataInitializer.migrateOldIds(repo)
        assertEquals("cid:5761", repo.doses.value.first().substanceId)
    }
}
