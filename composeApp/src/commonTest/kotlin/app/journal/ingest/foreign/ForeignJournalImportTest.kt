package app.journal.ingest.foreign

import app.journal.data.JournalRepository
import app.journal.export.CsvExporter
import app.journal.export.ExportImport
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.Substance
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForeignJournalImportTest {

    private fun repoWith(vararg names: String): JournalRepository {
        val repo = JournalRepository()
        names.forEachIndexed { index, name ->
            repo.upsertSubstance(
                Substance(
                    id = "sub:$index",
                    name = name,
                    createdAt = 0L,
                    updatedAt = 0L,
                    deviceOrigin = "test",
                    cachedAt = 0L,
                    sourceVersion = "test",
                )
            )
        }
        return repo
    }

    private fun import(content: String, repo: JournalRepository = JournalRepository()): Pair<ForeignImportResult, JournalRepository> {
        val result = ForeignJournalImporter.import(repo, content, now = NOW, timeZone = TimeZone.UTC)
        return result to repo
    }

    @Test
    fun psychonautWikiImportsExperiences() {
        val json = """
            {
              "experiences": [
                {
                  "title": "02 November 2021", "text": "first time",
                  "creationDate": 1668174995000, "sortDate": 1635861312000,
                  "ingestions": [
                    {"substanceName": "Cocaine", "time": 1635861312000, "administrationRoute": "INSUFFLATED",
                     "dose": 10.0, "isDoseAnEstimate": false, "units": "mg", "notes": "", "stomachFullness": "EMPTY"},
                    {"substanceName": "Cocaine", "time": 1635868578000, "administrationRoute": "INSUFFLATED",
                     "dose": 10.0, "isDoseAnEstimate": true, "units": "mg", "notes": "redose", "stomachFullness": null}
                  ],
                  "location": null, "ratings": []
                },
                {
                  "title": "", "text": "",
                  "creationDate": 1668176199000, "sortDate": 1636640197000,
                  "ingestions": [
                    {"substanceName": "Cannabis", "time": 1636640197000, "administrationRoute": "SMOKED",
                     "dose": 3.0, "isDoseAnEstimate": false, "units": "mg", "notes": ""}
                  ]
                }
              ],
              "substanceCompanions": [],
              "customUnits": []
            }
        """.trimIndent()
        val (result, repo) = import(json, repoWith("Cocaine"))

        assertEquals(ForeignFormat.PSYCHONAUTWIKI, result.format)
        assertEquals(2, result.sessions)
        assertEquals(3, result.doses)
        assertEquals(1, result.substancesCreated)
        assertEquals("02 November 2021", repo.sessions.value.first { it.title == "02 November 2021" }.title)
        assertEquals("first time", repo.sessions.value.first { it.title == "02 November 2021" }.notes)
        val cocaineDoses = repo.doses.value.filter { it.substanceId == "sub:0" }.sortedBy { it.timestamp }
        assertEquals(2, cocaineDoses.size)
        assertEquals("insufflated", cocaineDoses.first().routeOfAdministration)
        assertEquals("mg", cocaineDoses.first().unit)
        assertTrue(cocaineDoses[1].isDoseEstimate)
        assertEquals("redose", cocaineDoses[1].notes)
        assertNotNull(cocaineDoses.first().stomachFullness)
        assertTrue(repo.substances.value.any { it.name == "Cannabis" && it.id.startsWith("import:") })
    }

    @Test
    fun psychonautWikiLegacyArrayShapeImports() {
        val json = """
            [
              {"title": "One", "text": "", "sortDate": 1635861312000,
               "ingestions": [
                 {"substanceName": "Ketamine", "time": 1635861312000,
                  "administrationRoute": "ORAL", "dose": 20.0, "units": "mg", "notes": ""}
               ]}
            ]
        """.trimIndent()
        val (result, repo) = import(json, repoWith("Ketamine"))

        assertEquals(1, result.sessions)
        assertEquals(1, result.doses)
        assertEquals("oral", repo.doses.value.single().routeOfAdministration)
    }

    @Test
    fun psychonautWikiCustomUnitNamesItsSubstance() {
        val json = """
            {
              "experiences": [
                {"title": "Coffee", "text": "",
                 "ingestions": [
                   {"customUnitId": 7, "time": 1635861312000, "administrationRoute": "ORAL",
                    "dose": 1.0, "units": "cup", "notes": ""}
                 ]}
              ],
              "customUnits": [{"id": 7, "name": "Coffee cup", "substanceName": "Caffeine"}]
            }
        """.trimIndent()
        val (result, repo) = import(json)

        assertEquals(1, result.doses)
        assertTrue(repo.substances.value.any { it.name == "Caffeine" })
    }

    @Test
    fun drugsProJoinsTheUsageLedger() {
        val json = """
            {
              "v": 3,
              "t": 1700000000,
              "e": [
                {
                  "date": "2026-11-10", "title": "Ketamine night", "description": "low dose",
                  "substance": "Ketamine + Cannabis",
                  "substances": [
                    {"name": "Ketamine", "doseMult": 25.0, "minutesAhead": 0, "timeHHMM": "21:00"},
                    {"name": "Cannabis", "doseMult": 0.1, "minutesAhead": 30, "timeHHMM": "21:30"}
                  ]
                }
              ],
              "u": [
                {"dateISO": "2026-11-10", "name": "Ketamine", "dose": "25 mg", "roa": "Insufflated"},
                {"dateISO": "2026-11-10", "name": "Cannabis", "dose": "0.1 g", "roa": "Smoked"}
              ]
            }
        """.trimIndent()
        val (result, repo) = import(json)

        assertEquals(ForeignFormat.DRUGSPRO, result.format)
        assertEquals(1, result.sessions)
        assertEquals(2, result.doses)
        val doses = repo.doses.value.sortedBy { it.timestamp }
        assertEquals("mg", doses[0].unit)
        assertEquals("insufflated", doses[0].routeOfAdministration)
        assertEquals("g", doses[1].unit)
        assertEquals("smoked", doses[1].routeOfAdministration)
        assertEquals(30 * 60 * 1000L, doses[1].timestamp - doses[0].timestamp)
        val session = repo.sessions.value.single()
        assertEquals("Ketamine night", session.title)
        assertEquals("low dose", session.notes)
        assertEquals(doses[0].timestamp, session.startTime)
    }

    @Test
    fun spreadsheetRoundTripsTheOwnDoseCsv() {
        val source = repoWith("LSD")
        source.upsertSession(
            Session(
                id = "s:1", createdAt = NOW, updatedAt = NOW, deviceOrigin = "test",
                title = "Trip", startTime = 1_600_000_000_000L,
            )
        )
        source.upsertDose(
            Dose(
                id = "d:1", createdAt = NOW, updatedAt = NOW, deviceOrigin = "test",
                sessionId = "s:1", substanceId = "sub:0", routeOfAdministration = "oral",
                amount = 100.0, unit = "µg", timestamp = 1_600_000_000_000L, notes = "tab",
            )
        )
        val csv = CsvExporter.exportDosesCsv(source)
        val (result, target) = import(csv, repoWith("LSD"))

        assertEquals(ForeignFormat.SPREADSHEET, result.format)
        assertEquals(1, result.sessions)
        assertEquals(1, result.doses)
        assertEquals("LSD", target.sessions.value.single().title)
        val dose = target.doses.value.single()
        assertEquals("sub:0", dose.substanceId)
        assertEquals(100.0, dose.amount)
        assertEquals(1_600_000_000_000L, dose.timestamp)
        assertEquals("tab", dose.notes)
    }

    @Test
    fun tsvWithAmountAndUnitInOneColumnImports() {
        val tsv = "substance\tdate\tdose\troute\tnotes\n" +
            "MDMA\t2026-11-12 22:30\t120 mg\toral\tfirst roll\n" +
            "MDMA\t2026-11-12 23:30\t60 mg\toral\tredose\n"
        val (result, repo) = import(tsv, repoWith("MDMA"))

        assertEquals(1, result.sessions)
        assertEquals(2, result.doses)
        assertEquals("mg", repo.doses.value.first().unit)
        assertEquals(120.0, repo.doses.value.first().amount)
        assertEquals("first roll", repo.doses.value.first().notes)
    }

    @Test
    fun reimportIsIdempotent() {
        val json = """
            {
              "experiences": [
                {"title": "Night", "text": "",
                 "ingestions": [
                   {"substanceName": "Ketamine", "time": 1635861312000,
                    "administrationRoute": "INSUFFLATED", "dose": 20.0, "units": "mg", "notes": ""}
                 ]}
              ]
            }
        """.trimIndent()
        val repo = repoWith("Ketamine")
        val first = ForeignJournalImporter.import(repo, json, now = NOW, timeZone = TimeZone.UTC)
        val second = ForeignJournalImporter.import(repo, json, now = NOW, timeZone = TimeZone.UTC)

        assertEquals(1, first.doses)
        assertEquals(0, second.doses)
        assertEquals(1, second.duplicatesSkipped)
        assertEquals(1, repo.sessions.value.size)
        assertEquals(1, repo.doses.value.size)
    }

    @Test
    fun nepentheBackupIsRejectedWithAPointer() {
        val repo = repoWith("LSD")
        val backup = ExportImport.exportFullJournal(repo)
        val result = ForeignJournalImporter.import(JournalRepository(), backup, now = NOW)

        assertNotNull(result.error)
        assertTrue(result.error.contains("Import backup"))
    }

    @Test
    fun unknownFileReportsAnError() {
        val result = ForeignJournalImporter.import(JournalRepository(), "hello there", now = NOW)

        assertNotNull(result.error)
        assertEquals(0, result.doses)
    }

    @Test
    fun routeNamesCoverEverySpelling() {
        assertEquals("insufflated", RouteNames.normalize("INSUFFLATED"))
        assertEquals("insufflated", RouteNames.normalize("intranasal"))
        assertEquals("intravenous", RouteNames.normalize("IV"))
        assertEquals("vaporized", RouteNames.normalize("vaporised"))
        assertEquals("oral", RouteNames.normalize(null))
        assertEquals("µg", RouteNames.unit("ug"))
        assertEquals("mg", RouteNames.unit(""))
    }

    @Test
    fun twoExperiencesWithTheSameStartAndTitleAreDistinctSessions() {
        val json = """
            {
              "experiences": [
                {"title": "Same", "creationDate": 1, "sortDate": $NOW, "ingestions": [
                  {"substanceName": "Ketamine", "time": $NOW, "administrationRoute": "INSUFFLATED", "dose": 10.0, "units": "mg"}
                ]},
                {"title": "Same", "creationDate": 2, "sortDate": $NOW, "ingestions": [
                  {"substanceName": "Ketamine", "time": $NOW, "administrationRoute": "INSUFFLATED", "dose": 20.0, "units": "mg"}
                ]}
              ]
            }
        """.trimIndent()
        val (result, repo) = import(json, repoWith("Ketamine"))

        assertEquals(2, result.sessions)
        assertEquals(2, repo.sessions.value.size)
        assertEquals(2, repo.doses.value.map { it.sessionId }.distinct().size)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
