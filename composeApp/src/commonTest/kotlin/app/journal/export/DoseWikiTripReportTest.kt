package app.journal.export

import app.journal.model.Dose
import app.journal.model.Person
import app.journal.model.PersonRole
import app.journal.model.Session
import app.journal.model.SessionProfile
import app.journal.model.Substance
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TS = 1_700_000_000_000L

private fun session(
    title: String = "Evening walk",
    personId: String? = "p:1",
    profile: SessionProfile? = null
) = Session(
    id = "s:1", createdAt = TS, updatedAt = TS, deviceOrigin = "test",
    title = title, startTime = TS,
    setting = "Quiet apartment",
    intention = "A calm evening with enough narrative to clear the length floor comfortably.",
    outcome = "Restful night and an easy morning after.",
    personId = personId, profile = profile
)

private fun person() = Person(
    id = "p:1", createdAt = TS, updatedAt = TS, deviceOrigin = "test",
    displayName = "Rook", role = PersonRole.PARTICIPANT,
    age = 28, gender = "not specified", height = "5 ft 8 in", weight = "150 lb",
    medications = "None", contactEmail = "rook@example.com", mayContact = true
)

private fun substances() = mapOf(
    "sub:1" to Substance(
        id = "sub:1", createdAt = TS, updatedAt = TS, deviceOrigin = "test", name = "LSD",
        cachedAt = TS, sourceVersion = "test"
    )
)

private fun doses() = listOf(
    Dose(
        id = "d:1", createdAt = TS, updatedAt = TS, deviceOrigin = "test",
        sessionId = "s:1", substanceId = "sub:1",
        routeOfAdministration = "Oral", amount = 75.0, unit = "ug", timestamp = TS
    )
)

private fun events() = listOf(
    TimelineEvent(
        id = "e:1", createdAt = TS, updatedAt = TS, deviceOrigin = "test",
        sessionId = "s:1", timestamp = TS, eventType = TimelineEventType.ONSET,
        label = "Tab under tongue", body = "Held for fifteen minutes."
    ),
    TimelineEvent(
        id = "e:2", createdAt = TS, updatedAt = TS, deviceOrigin = "test",
        sessionId = "s:1", timestamp = TS + 80 * 60_000, eventType = TimelineEventType.PEAK,
        label = "Colour separated", body = null
    ),
    TimelineEvent(
        id = "e:3", createdAt = TS, updatedAt = TS, deviceOrigin = "test",
        sessionId = "s:1", timestamp = TS + 270 * 60_000, eventType = TimelineEventType.OFFSET,
        label = "Visuals quiet", body = "Thought stayed wide."
    ),
    TimelineEvent(
        id = "e:4", createdAt = TS, updatedAt = TS, deviceOrigin = "test",
        sessionId = "s:1", timestamp = TS + 10 * 60_000, eventType = TimelineEventType.SAFETY_CHECK,
        label = "Sitter check", body = "All good."
    )
)

class DoseWikiTripReportTest {

    @Test
    fun buildsPhaseSplitReportFromPerson() {
        val report = buildDoseWikiReport(
            session(), person(), doses(), substances(), events = events(),
            publishConsent = true, ageConfirmed = true
        )
        assertEquals("Evening walk", report.report.title)
        assertEquals("Rook", report.report.subject.name)
        assertEquals("28", report.report.subject.age)
        assertEquals("5 ft 8 in", report.report.subject.height)
        assertEquals("150 lb", report.report.subject.weight)
        assertEquals("None", report.report.subject.medications)
        assertEquals("Quiet apartment", report.report.subject.setting)
        assertTrue(report.report.subject.trip_date!!.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))

        assertEquals(1, report.report.substances.size)
        assertEquals("LSD", report.report.substances[0].name)
        assertEquals("75 ug", report.report.substances[0].dose)
        assertEquals("oral", report.report.substances[0].roa)

        assertEquals(1, report.report.onset.size)
        assertEquals("T+0:00", report.report.onset[0].time)
        assertEquals("Tab under tongue: Held for fifteen minutes.", report.report.onset[0].description)
        assertEquals(1, report.report.peak.size)
        assertEquals("T+1:20", report.report.peak[0].time)
        assertEquals(1, report.report.offset.size)
        assertEquals("T+4:30", report.report.offset[0].time)

        // Non-phase event folds into the introduction as a T+ paragraph
        assertTrue(report.report.introduction!!.contains("T+0:10: Sitter check: All good."))
        assertEquals("Restful night and an easy morning after.", report.report.conclusion)

        assertEquals("rook@example.com", report.contact_email)
        assertTrue(report.may_contact)
        assertTrue(report.publish_consent)
        assertTrue(report.age_confirmed)
        assertEquals("", report.website)

        assertTrue(report.check().ok)
    }

    @Test
    fun fallsBackToLegacyProfileAndConsumerName() {
        val legacy = session(
            personId = null,
            profile = SessionProfile(age = 30, gender = "female", heightCm = 165, weightKg = 60)
        ).copy(consumerName = "Wren")
        val report = buildDoseWikiReport(
            legacy, person = null, doses(), substances(),
            publishConsent = true, ageConfirmed = true
        )
        assertEquals("Wren", report.report.subject.name)
        assertEquals("30", report.report.subject.age)
        assertEquals("165 cm", report.report.subject.height)
        assertEquals("60 kg", report.report.subject.weight)
        assertNull(report.report.subject.medications)
        assertNull(report.contact_email)
        assertFalse(report.may_contact)
        assertTrue(report.check().ok)
    }

    @Test
    fun reportsExactServerRejections() {
        val empty = buildDoseWikiReport(
            session(title = "  ", personId = null).copy(consumerName = null),
            person = null, doses = emptyList(), substancesById = emptyMap()
        )
        val check = empty.check()
        assertTrue(check.errors.contains("Title is required."))
        assertTrue(check.errors.contains("At least one substance is required."))
        assertTrue(check.errors.contains("Publish consent is required."))
        assertTrue(check.errors.contains("Age confirmation is required."))
        assertFalse(check.ok)
    }

    @Test
    fun warnsOnShortNarrative() {
        val short = buildDoseWikiReport(
            session().copy(intention = null, notes = null, outcome = null),
            person(), doses(), substances(),
            publishConsent = true, ageConfirmed = true
        )
        val check = short.check()
        assertTrue(check.ok)
        assertTrue(check.warnings.any { it.contains("80") })
    }

    @Test
    fun serializesToSnakeCaseWithoutHoneypot() {
        val report = buildDoseWikiReport(
            session(), person(), doses(), substances(), events = events(),
            publishConsent = true, ageConfirmed = true
        )
        val text = report.toJsonString()
        assertTrue(text.contains("\"trip_date\""))
        assertTrue(text.contains("\"contact_email\""))
        assertTrue(text.contains("publish_consent"))
        assertTrue(report.publish_consent)
        assertTrue(text.contains("\"website\": \"\""))
        assertFalse(text.contains("profile_key"))
        assertFalse(text.contains("avatar_url"))
        assertFalse(text.contains("pdf_url"))
    }
}
