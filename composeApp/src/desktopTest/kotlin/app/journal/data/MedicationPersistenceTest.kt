package app.journal.data

import app.journal.export.ExportImport
import app.journal.model.Medication
import app.journal.model.MedicationPreset
import app.journal.serde.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.test.*

class MedicationPersistenceTest {

    private fun medication(
        id: String = "medication:1",
        name: String = "Sertraline",
        endedAt: Long? = null,
    ) = Medication(
        id = id,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        deviceOrigin = "test",
        name = name,
        presetId = MedicationPreset.SSRI.id,
        doseLabel = "50 mg daily",
        startedAt = 1_000L,
        endedAt = endedAt,
    )

    private fun withTempHome(test: (String) -> Unit) {
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-med-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        val origHome = System.getProperty("user.home")
        System.setProperty("user.home", tmpDir.absolutePath)
        try {
            test(tmpDir.absolutePath)
        } finally {
            System.setProperty("user.home", origHome)
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun snapshotCarriesMedications() {
        val repo = JournalRepository()
        repo.upsertMedication(medication())

        val snapshot = repo.fullSnapshot()

        assertEquals(JournalSnapshot.CURRENT_VERSION, snapshot.version)
        assertEquals(1, snapshot.medications.size)
        assertEquals("Sertraline", snapshot.medications.first().name)
    }

    @Test
    fun snapshotRoundtripKeepsEveryMedicationField() {
        val repo = JournalRepository()
        val original = medication(endedAt = 5_000L)
        repo.upsertMedication(original)

        val text = AppJson.json.encodeToString(repo.fullSnapshot())
        val restored = JournalRepository()
        AppJson.apply(restored, AppJson.json.decodeFromString(text))

        assertEquals(listOf(original), restored.medications.value)
    }

    @Test
    fun diskRoundtripKeepsMedications() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertMedication(medication())
        val store = JournalStore(repo)
        File(store.dataPath()).parentFile.mkdirs()

        store.save()
        val reloaded = JournalRepository()
        JournalStore(reloaded).load()

        assertEquals(1, reloaded.medications.value.size)
        assertEquals("Sertraline", reloaded.medications.value.first().name)
    }

    @Test
    fun version7FileWithoutMedicationsLoadsEmpty() = withTempHome { _ ->
        val fixture = """
            {"version":7,"savedAt":1700000000000,
             "sessions":[{"id":"s:1","createdAt":1690000000000,"updatedAt":1690000000000,
                          "deviceOrigin":"test","title":"Old Session","startTime":1690000000000}]}
        """.trimIndent()
        val repo = JournalRepository()
        val store = JournalStore(repo)
        File(store.dataPath()).parentFile.mkdirs()
        File(store.dataPath()).writeText(fixture)

        store.load()

        assertEquals(1, repo.sessions.value.size)
        assertTrue(repo.medications.value.isEmpty())
    }

    @Test
    fun recoveryKeepsMedicationsWhenAnotherListIsCorrupt() {
        val encoded = AppJson.json.encodeToString(Medication.serializer(), medication())
        val text = """
            {"version":8,"savedAt":1,"medications":[$encoded],
             "sessions":[{"id":"broken"}]}
        """.trimIndent()

        val recovered = recoverSnapshot(text)

        assertEquals(1, recovered.medications.size)
        assertTrue(recovered.sessions.isEmpty())
    }

    @Test
    fun clearAllDropsMedications() {
        val repo = JournalRepository()
        repo.upsertMedication(medication())

        repo.clearAll()

        assertTrue(repo.medications.value.isEmpty())
    }

    @Test
    fun updateAndDeleteKeepTheStoreConsistent() {
        val repo = JournalRepository()
        repo.upsertMedication(medication())
        repo.upsertMedication(medication().copy(name = "Fluoxetine"))

        assertEquals(1, repo.medications.value.size)
        assertEquals("Fluoxetine", repo.getMedication("medication:1")?.name)

        repo.deleteMedication("medication:1")

        assertTrue(repo.medications.value.isEmpty())
        assertNull(repo.getMedication("medication:1"))
    }

    @Test
    fun fullJournalImportRejectsAnOversizedMedicationList() {
        val repo = JournalRepository()
        val medications = (1..ExportImport.MAX_IMPORT_MEDICATIONS + 1).map { index ->
            medication(id = "medication:$index", name = "Med $index")
        }
        val text = AppJson.json.encodeToString(
            JournalSnapshot(savedAt = 1_700_000_000_000L, medications = medications)
        )

        val result = ExportImport.importFullJournal(repo, text)

        assertEquals(
            "Too many medications in backup (max ${ExportImport.MAX_IMPORT_MEDICATIONS})",
            result.error
        )
        assertTrue(repo.medications.value.isEmpty())
    }

    @Test
    fun fullJournalImportDropsInvalidMedicationRecords() {
        val stamp = 1_700_000_000_000L
        val valid = medication().copy(createdAt = stamp, updatedAt = stamp, startedAt = stamp)
        val badTime = valid.copy(id = "medication:bad-time", startedAt = 0L)
        val badName = valid.copy(
            id = "medication:bad-name",
            name = "x".repeat(Medication.MAX_NAME_LENGTH + 1)
        )
        val text = AppJson.json.encodeToString(
            JournalSnapshot(savedAt = stamp, medications = listOf(valid, badTime, badName))
        )

        val result = ExportImport.importFullJournal(JournalRepository(), text)

        assertNull(result.error)
        assertEquals(1, result.medications)
    }
}
