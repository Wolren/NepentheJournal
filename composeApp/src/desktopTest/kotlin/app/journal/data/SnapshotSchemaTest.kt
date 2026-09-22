package app.journal.data

import app.journal.model.*
import app.journal.serde.AppJson
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.*

/**
 * Golden-file and contract tests for the shared snapshot layer:
 * schemas/journal-snapshot-v7.json (schema version + field coverage),
 * recoverSnapshot field preservation, and the AppJson.apply version dispatch.
 */
class SnapshotSchemaTest {

    private fun findSchema(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "schemas/journal-snapshot-v7.json")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        fail("schemas/journal-snapshot-v7.json not found above ${System.getProperty("user.dir")}")
    }

    private fun schemaJson(): kotlinx.serialization.json.JsonElement =
        Json.parseToJsonElement(findSchema().readText())

    private fun schemaDefProperties(name: String): Set<String> {
        val defs = schemaJson().jsonObject["\$defs"]?.jsonObject
            ?: fail("schema has no \$defs")
        val def = defs[name] ?: fail("schema has no \$defs/$name")
        return def.jsonObject["properties"]!!.jsonObject.keys
    }

    private fun descriptorFieldNames(strategy: SerializationStrategy<*>): Set<String> {
        val descriptor = strategy.descriptor
        return (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
    }

    @Test
    fun schemaDeclaredVersionMatchesCurrentVersion() {
        val properties = schemaJson().jsonObject["properties"]!!.jsonObject
        val declared = properties["version"]!!.jsonObject["const"]!!.jsonPrimitive.content.toInt()
        assertEquals(
            JournalSnapshot.CURRENT_VERSION, declared,
            "schemas/journal-snapshot-v7.json must declare the same version the code writes"
        )
    }

    @Test
    fun schemaTopLevelPropertiesMatchSnapshotSerializer() {
        val properties = schemaJson().jsonObject["properties"]!!.jsonObject.keys
        val code = descriptorFieldNames(JournalSnapshot.serializer())
        assertEquals(code, properties, "schema top-level properties must mirror JournalSnapshot fields exactly")
    }

    @Test
    fun schemaEntityDefsMatchSerializers() {
        val checked: List<Pair<String, SerializationStrategy<*>>> = listOf(
            "Session" to Session.serializer(),
            "CheckIn" to CheckIn.serializer(),
            "SessionProfile" to SessionProfile.serializer(),
            "Dose" to Dose.serializer(),
            "Note" to Note.serializer(),
            "ConflictSibling" to ConflictSibling.serializer(),
            "TimelineEvent" to TimelineEvent.serializer(),
            "Interaction" to Interaction.serializer(),
            "Effect" to Effect.serializer(),
            "CustomUnit" to CustomUnit.serializer(),
            "Person" to Person.serializer(),
            "Substance" to Substance.serializer(),
            "CuratedSection" to CuratedSection.serializer(),
            "ChemicalProperties" to ChemicalProperties.serializer(),
            "ChemblData" to ChemblData.serializer(),
            "Bioactivity" to Bioactivity.serializer(),
            "IupharData" to IupharData.serializer(),
            "IupharInteraction" to IupharInteraction.serializer(),
            "PdspData" to PdspData.serializer(),
            "PdspKiRecord" to PdspKiRecord.serializer(),
            "BindingdbData" to BindingdbData.serializer(),
            "BindingdbRecord" to BindingdbRecord.serializer(),
            "WikipediaData" to WikipediaData.serializer(),
            "WikipediaRecord" to WikipediaRecord.serializer(),
        )
        for ((name, strategy) in checked) {
            assertEquals(
                descriptorFieldNames(strategy), schemaDefProperties(name),
                "\$defs/$name must mirror the data class fields exactly"
            )
        }
    }

    private fun sampleSubstance(id: String) = Substance(
        id = id, name = "Sub $id", createdAt = 1L, updatedAt = 2L, deviceOrigin = "test",
        cachedAt = 3L, sourceVersion = "test"
    )

    /** Full snapshot with every non-default field set, then corrupted so the
     *  whole-file parse fails and recovery takes over. */
    private fun sourceSnapshotAndCorruptedText(): Pair<JournalSnapshot, String> {
        val source = JournalSnapshot(
            savedAt = 111L,
            sessions = listOf(Session(
                id = "s:1", createdAt = 1L, updatedAt = 2L, deviceOrigin = "test",
                title = "Kept Session", startTime = 10L
            )),
            substances = listOf(sampleSubstance("sub:1")),
            doses = listOf(Dose(
                id = "d:1", createdAt = 1L, updatedAt = 2L, deviceOrigin = "test",
                sessionId = "s:1", substanceId = "sub:1", routeOfAdministration = "Oral",
                amount = 1.0, unit = "mg", timestamp = 10L
            )),
            notes = listOf(Note(
                id = "n:1", createdAt = 1L, updatedAt = 2L, deviceOrigin = "test",
                body = "note body", title = "note title"
            )),
            timelineEvents = listOf(TimelineEvent(
                id = "e:1", createdAt = 1L, updatedAt = 2L, deviceOrigin = "test",
                sessionId = "s:1", timestamp = 10L, eventType = TimelineEventType.PEAK,
                label = "peak"
            )),
            interactions = listOf(Interaction(
                id = "i:1", createdAt = 1L, updatedAt = 2L, deviceOrigin = "test",
                substanceAId = "sub:1", substanceBId = "sub:2", riskLevel = InteractionRisk.LOW
            )),
            effects = listOf(Effect(
                id = "f:1", createdAt = 1L, updatedAt = 2L, deviceOrigin = "test",
                name = "Visuals"
            )),
            customUnits = listOf(CustomUnit(
                id = "u:1", createdAt = 1L, updatedAt = 2L, deviceOrigin = "test",
                substanceId = "sub:1", name = "puff", isEstimate = true
            )),
            persons = listOf(Person(
                id = "p:1", createdAt = 1L, updatedAt = 2L, deviceOrigin = "test",
                displayName = "Alex", age = 30, isSelf = true
            )),
            tombstones = mapOf("session:gone" to 42L),
            ratingScaleMode = RatingScaleMode.NUMERIC,
            useShulginRating = true,
            useSubstanceColors = false,
            welcomeCompleted = true,
            seedFingerprint = "seed-fp",
            obsidianVaultPath = "/vault",
            obsidianAutoExport = true,
            obsidianSubfolder = "Trips",
            obsidianFileOrganization = "nested",
            showSessionsTrendChart = true
        )
        val text = AppJson.json.encodeToString(source)
        // One broken element forces the whole-file parse to fail, so recovery
        // runs; the broken element itself is dropped (sessions asserted below).
        val corrupted = text.replaceFirst("\"sessions\":[", "\"sessions\":[{\"__broken\":1},")
        assertNotEquals(text, corrupted, "corruption injection must actually change the text")
        return source to corrupted
    }

    @Test
    fun recoverSnapshotPreservesEveryFieldExceptVersionAndSavedAt() {
        val (source, corrupted) = sourceSnapshotAndCorruptedText()

        // Sanity: the corruption really does break the strict whole-file parse.
        assertFails { AppJson.json.decodeFromString<JournalSnapshot>(corrupted) }

        val recovered = recoverSnapshot(corrupted)

        // The broken element was dropped, the valid session survived.
        assertEquals(1, recovered.sessions.size)
        assertEquals("Kept Session", recovered.sessions.first().title)

        // Descriptor-driven: every JournalSnapshot field except version
        // (recovery stamps CURRENT_VERSION by design) and savedAt (wall clock
        // at recovery time) must survive byte-for-byte. This is the guard the
        // audit asked for: a new field forgotten in recoverSnapshot fails here.
        val descriptor = JournalSnapshot.serializer().descriptor
        val sourceJson = AppJson.pretty.parseToJsonElement(AppJson.json.encodeToString(source)).jsonObject
        val recoveredJson = AppJson.pretty.parseToJsonElement(AppJson.json.encodeToString(recovered)).jsonObject
        for (i in 0 until descriptor.elementsCount) {
            val field = descriptor.getElementName(i)
            if (field == "version" || field == "savedAt" || field == "sessions") continue
            assertEquals(
                sourceJson[field], recoveredJson[field],
                "recoverSnapshot must preserve field '$field'"
            )
        }
        assertEquals(JournalSnapshot.CURRENT_VERSION, recovered.version,
            "recovery stamps the current version")
    }

    @Test
    fun decodeSnapshotWithRecoveryReportsCleanParseAsNoError() {
        val text = AppJson.json.encodeToString(JournalSnapshot(savedAt = 1L))
        val decoded = decodeSnapshotWithRecovery(text)
        assertNull(decoded.parseError)
        assertEquals(1L, decoded.snapshot.savedAt)
    }

    @Test
    fun decodeSnapshotWithRecoverySalvagesGarbage() {
        val decoded = decodeSnapshotWithRecovery("{not json at all")
        assertNotNull(decoded.parseError, "garbage input must report the parse error")
        assertTrue(decoded.snapshot.sessions.isEmpty())
    }

    @Test
    fun strictDecodeSnapshotFailsWithoutRecovery() {
        assertTrue(decodeSnapshot("{not json at all").isFailure)
        assertTrue(decodeSnapshot(AppJson.json.encodeToString(JournalSnapshot(savedAt = 5L))).isSuccess)
    }

    @Test
    fun olderSnapshotVersionAppliesAsIdentityMigration() {
        val repo = JournalRepository()
        val snap = JournalSnapshot(
            savedAt = 1L,
            version = 5,
            sessions = listOf(Session(
                id = "s:old", createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
                title = "Old", startTime = 1L
            ))
        )
        AppJson.apply(repo, snap)
        assertEquals(1, repo.sessions.value.size, "identity arm must still apply the data")
    }

    @Test
    fun unknownSnapshotVersionAppliesBestEffort() {
        val repo = JournalRepository()
        val snap = JournalSnapshot(
            savedAt = 1L,
            version = 999,
            sessions = listOf(Session(
                id = "s:new", createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
                title = "Newer", startTime = 1L
            ))
        )
        AppJson.apply(repo, snap)
        assertEquals(1, repo.sessions.value.size, "unknown version warns but must not drop data")
    }
}
