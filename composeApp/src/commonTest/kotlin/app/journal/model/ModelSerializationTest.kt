package app.journal.model

import app.journal.data.AppJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.*

class ModelSerializationTest {

    @Test
    fun sessionRoundtrip() {
        val original = Session(
            id = "session:test:123", title = "Test Session",
            startTime = 1000L, endTime = 5000L,
            rating = 8, shulginRating = "+++",
            set = "Calm", setting = "Home", intention = "Exploration",
            outcome = "Positive experience",
            createdAt = 500L, updatedAt = 5000L, deviceOrigin = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<Session>(json)
        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.startTime, restored.startTime)
        assertEquals(original.endTime, restored.endTime)
        assertEquals(original.rating, restored.rating)
        assertEquals(original.shulginRating, restored.shulginRating)
        assertEquals(original.set, restored.set)
        assertEquals(original.setting, restored.setting)
    }

    @Test
    fun sessionWithMinimalFields() {
        val original = Session(
            id = "session:min:1", title = "Minimal",
            startTime = 1000L, createdAt = 500L, updatedAt = 500L,
            deviceOrigin = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<Session>(json)
        assertEquals("Minimal", restored.title)
        assertNull(restored.rating)
        assertNull(restored.shulginRating)
        assertNull(restored.endTime)
    }

    @Test
    fun doseRoundtrip() {
        val original = Dose(
            id = "dose:1", sessionId = "session:1", substanceId = "sub:1",
            routeOfAdministration = "Oral", amount = 150.0, unit = "mg",
            timestamp = 2000L, redosing = true, stomachFullness = StomachFullness.EMPTY,
            isDoseEstimate = false,
            createdAt = 1000L, updatedAt = 2000L, deviceOrigin = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<Dose>(json)
        assertEquals(original.id, restored.id)
        assertEquals(original.amount, restored.amount, 0.001)
        assertEquals(original.routeOfAdministration, restored.routeOfAdministration)
        assertEquals(original.stomachFullness, restored.stomachFullness)
        assertEquals(original.redosing, restored.redosing)
    }

    @Test
    fun doseWithEstimateFields() {
        val original = Dose(
            id = "dose:2", sessionId = "session:1", substanceId = "sub:1",
            routeOfAdministration = "Oral", amount = 100.0, unit = "mg",
            timestamp = 2000L, isDoseEstimate = true,
            estimatedDoseStandardDeviation = 10.0, estimatedDoseNotes = "Half a tab",
            createdAt = 1000L, updatedAt = 2000L, deviceOrigin = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<Dose>(json)
        assertTrue(restored.isDoseEstimate)
        assertEquals(10.0, restored.estimatedDoseStandardDeviation, 0.001)
        assertEquals("Half a tab", restored.estimatedDoseNotes)
    }

    @Test
    fun substanceRoundtrip() {
        val original = Substance(
            id = "cid:5761", name = "LSD",
            aliases = listOf("LSD-25", "Acid", "Lucy"),
            substanceClass = listOf("Classical Psychedelic"),
            summary = "A powerful psychedelic.",
            routesOfAdministration = listOf("Oral", "Sublingual"),
            dosageBands = mapOf("light" to "50-100 ug", "common" to "100-200 ug"),
            durationProfile = mapOf("onset" to "30-90 minutes", "peak" to "3-5 hours"),
            effects = listOf("Visual distortions", "Euphoria"),
            toxicity = listOf("Low physiological toxicity"),
            addictionPotential = "Low",
            crossTolerances = listOf("Full tolerance: 7-14 days"),
            sourceVersion = "pwiki-2024",
            cachedAt = 1000L,
            createdAt = 500L, updatedAt = 1000L, deviceOrigin = "system"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<Substance>(json)
        assertEquals(original.name, restored.name)
        assertEquals(original.aliases.size, restored.aliases.size)
        assertEquals(original.substanceClass, restored.substanceClass)
        assertEquals(original.dosageBands["light"], restored.dosageBands["light"])
        assertEquals(original.durationProfile["onset"], restored.durationProfile["onset"])
    }

    @Test
    fun substanceWithMinimalFields() {
        val original = Substance(
            id = "sub:test:1", name = "Test Substance",
            createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            cachedAt = 0L, sourceVersion = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<Substance>(json)
        assertEquals("Test Substance", restored.name)
        assertTrue(restored.aliases.isEmpty())
        assertTrue(restored.substanceClass.isEmpty())
        assertTrue(restored.routesOfAdministration.isEmpty())
    }

    @Test
    fun interactionRoundtrip() {
        val original = Interaction(
            id = "int:1", substanceAId = "cid:5761", substanceBId = "cid:6013",
            riskLevel = InteractionRisk.DANGEROUS,
            description = "Serotonin syndrome risk",
            createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<Interaction>(json)
        assertEquals(original.substanceAId, restored.substanceAId)
        assertEquals(original.riskLevel, restored.riskLevel)
    }

    @Test
    fun effectRoundtrip() {
        val original = Effect(
            id = "eff:1", name = "Euphoria", category = "cognitive",
            substanceIds = listOf("cid:5761", "cid:6013"),
            description = "Intense happiness",
            createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<Effect>(json)
        assertEquals("Euphoria", restored.name)
        assertEquals("cognitive", restored.category)
        assertEquals(2, restored.substanceIds.size)
    }

    @Test
    fun noteRoundtrip() {
        val original = Note(
            id = "note:1", sessionId = "session:1",
            title = "Observation", body = "Noticed visual patterns",
            createdAt = 1000L, updatedAt = 2000L, deviceOrigin = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<Note>(json)
        assertEquals(original.title, restored.title)
        assertEquals(original.body, restored.body)
    }

    @Test
    fun timelineEventRoundtrip() {
        val original = TimelineEvent(
            id = "evt:1", sessionId = "session:1", timestamp = 3000L,
            eventType = TimelineEventType.PEAK, label = "Peak experience",
            body = "Strong visuals", intensity = 8f,
            createdAt = 2000L, updatedAt = 3000L, deviceOrigin = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<TimelineEvent>(json)
        assertEquals(original.eventType, restored.eventType)
        assertEquals(original.label, restored.label)
        assertEquals(original.intensity, restored.intensity, 0.01f)
    }

    @Test
    fun customUnitRoundtrip() {
        val original = CustomUnit(
            id = "unit:1", substanceId = "sub:1",
            name = "Tablet", namePlural = "Tablets",
            abbreviation = "tab", unitCount = 1.0,
            createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test"
        )
        val json = AppJson.json.encodeToString(original)
        val restored = AppJson.json.decodeFromString<CustomUnit>(json)
        assertEquals("Tablet", restored.name)
        assertEquals(1.0, restored.unitCount, 0.001)
    }
}
