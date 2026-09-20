package app.journal.export

import app.journal.model.Dose
import app.journal.model.Note
import app.journal.model.Person
import app.journal.model.Session
import app.journal.model.Substance
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Canonical trip report export: the dose.wiki TR-1 intake payload.
 *
 * Field behavior follows https://josiekins.xyz/html-craft/dosewiki-trip-report-format.html:
 * four hard requirements (title, one named substance, publish_consent,
 * age_confirmed), phase membership by array position, plain text everywhere
 * (no Markdown), tags as a real array, and the honeypot left empty.
 * Unknown keys are never sent; profile_key, avatar_url and pdf_url are omitted.
 */
@Serializable
data class TripReportSubject(
    val name: String? = null,
    val trip_date: String? = null,
    val age: String? = null,
    val gender: String? = null,
    val height: String? = null,
    val weight: String? = null,
    val medications: String? = null,
    val setting: String? = null
)

@Serializable
data class TripReportSubstance(
    val name: String,
    val dose: String? = null,
    val roa: String? = null
)

@Serializable
data class TripReportEntry(
    val time: String? = null,
    val description: String
)

@Serializable
data class TripReportBody(
    val title: String,
    val subject: TripReportSubject = TripReportSubject(),
    val substances: List<TripReportSubstance> = emptyList(),
    val introduction: String? = null,
    val onset: List<TripReportEntry> = emptyList(),
    val peak: List<TripReportEntry> = emptyList(),
    val offset: List<TripReportEntry> = emptyList(),
    val conclusion: String? = null,
    val tags: List<String> = emptyList()
)

@Serializable
data class DoseWikiTripReport(
    val report: TripReportBody,
    val contact_email: String? = null,
    val may_contact: Boolean = false,
    val publish_consent: Boolean = false,
    val age_confirmed: Boolean = false,
    /** Honeypot. Always empty: any content routes the report to the spam queue. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val website: String = ""
) {
    companion object {
        /** Exact rejection strings returned by the intake server. */
        const val ERR_TITLE = "Title is required."
        const val ERR_SUBSTANCE = "At least one substance is required."
        const val ERR_CONSENT = "Publish consent is required."
        const val ERR_AGE = "Age confirmation is required."

        /** Narrative floor: intro + phase descriptions + conclusion, joined. */
        const val MIN_NARRATIVE_CHARS = 80

        /** Max intake body: 131072 bytes of UTF-8. */
        const val MAX_BODY_BYTES = 131072

        /** Clause 9 consent wording. Show verbatim before setting publish_consent. */
        const val CONSENT_TEXT = "I consent to this report being reviewed and, if published, " +
            "dedicated to the public domain (CC0), free for anyone to use, with no attribution " +
            "required. This dedication cannot be revoked once the report is public. Anyone who " +
            "has already copied it may keep using it. If I later ask dose.wiki to remove the " +
            "report, that removal applies to dose.wiki only, not to copies made elsewhere."

        /** Age gate wording shown beside the age checkbox. */
        const val AGE_TEXT = "I confirm I am at least 18 years old and can submit this report."

        const val CONTACT_TEXT = "Editors may contact me about this report."

        val json = Json {
            prettyPrint = true
            encodeDefaults = false
            explicitNulls = false
        }
    }
}

/** Pre-export check result. Errors match the server rejections exactly. */
data class TripReportCheck(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val ok: Boolean get() = errors.isEmpty()
}

private val ONSET_TYPES = setOf(TimelineEventType.ONSET, TimelineEventType.COMEUP)
private val PEAK_TYPES = setOf(TimelineEventType.PEAK, TimelineEventType.PLATEAU)
private val OFFSET_TYPES =
    setOf(TimelineEventType.OFFSET, TimelineEventType.AFTERGLOW, TimelineEventType.END)

private fun formatAmount(amount: Double): String =
    if (amount == kotlin.math.floor(amount) && !amount.isInfinite()) amount.toLong().toString()
    else amount.toString()

/** Elapsed label in the recommended T+H:MM shape, clamped at zero. */
fun elapsedLabel(startTime: Long, timestamp: Long): String {
    val minutes = maxOf(0L, (timestamp - startTime) / 60_000L)
    val hours = minutes / 60
    val mins = (minutes % 60).toString().padStart(2, '0')
    return "T+$hours:$mins"
}

private fun entryOf(event: TimelineEvent, startTime: Long): TripReportEntry {
    val description = if (event.body.isNullOrBlank()) event.label
    else "${event.label}: ${event.body.trim()}"
    return TripReportEntry(time = elapsedLabel(startTime, event.timestamp), description = description)
}

/**
 * Build the canonical TR-1 payload for a session.
 *
 * Demographics come from the assigned individual [person]; the session legacy profile
 * ([Session.profile]) and [Session.consumerName] are fallbacks only.
 * Timeline events typed onset/comeup, peak/plateau, offset/afterglow/end fill
 * the phase arrays (Path A). All other event types fold into the introduction
 * as T+H:MM paragraphs, which keeps the flat-block reshape rules satisfiable.
 */
fun buildDoseWikiReport(
    session: Session,
    person: Person?,
    doses: List<Dose>,
    substancesById: Map<String, Substance>,
    notes: List<Note> = emptyList(),
    events: List<TimelineEvent> = emptyList(),
    publishConsent: Boolean = false,
    ageConfirmed: Boolean = false
): DoseWikiTripReport {
    val tripDate = Instant.fromEpochMilliseconds(session.startTime)
        .toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

    val profile = session.profile
    val subject = TripReportSubject(
        name = person?.displayName?.ifBlank { null }
            ?: session.consumerName?.ifBlank { null },
        trip_date = tripDate,
        age = person?.age?.toString() ?: profile?.age?.toString(),
        gender = person?.gender?.ifBlank { null } ?: profile?.gender?.ifBlank { null },
        height = person?.height?.ifBlank { null }
            ?: profile?.heightCm?.let { "$it cm" },
        weight = person?.weight?.ifBlank { null }
            ?: profile?.weightKg?.let { "$it kg" },
        medications = person?.medications?.ifBlank { null },
        setting = session.setting?.ifBlank { null }
    )

    val substances = doses.sortedBy { it.timestamp }.mapNotNull { dose ->
        val name = substancesById[dose.substanceId]?.name?.trim().orEmpty()
        if (name.isEmpty()) null
        else TripReportSubstance(
            name = name,
            dose = "${formatAmount(dose.amount)} ${dose.unit}".trim().ifEmpty { null },
            roa = dose.routeOfAdministration.trim().lowercase().ifEmpty { null }
        )
    }

    val sortedEvents = events.sortedBy { it.timestamp }
    val introParagraphs = mutableListOf<String>()
    session.intention?.trim()?.takeIf { it.isNotEmpty() }?.let { introParagraphs.add(it) }
    session.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { introParagraphs.add(it) }
    notes.sortedBy { it.updatedAt }
        .map { it.body.trim() }
        .filter { it.isNotEmpty() }
        .forEach { introParagraphs.add(it) }
    sortedEvents
        .filter { it.eventType !in ONSET_TYPES && it.eventType !in PEAK_TYPES && it.eventType !in OFFSET_TYPES }
        .forEach { event ->
            val text = if (event.body.isNullOrBlank()) event.label.trim()
            else "${event.label.trim()}: ${event.body.trim()}"
            if (text.isNotEmpty()) {
                introParagraphs.add("${elapsedLabel(session.startTime, event.timestamp)}: $text")
            }
        }

    fun phase(types: Set<TimelineEventType>): List<TripReportEntry> =
        sortedEvents.filter { it.eventType in types }
            .map { entryOf(it, session.startTime) }
            .filter { it.description.trim().isNotEmpty() }

    return DoseWikiTripReport(
        report = TripReportBody(
            title = session.title,
            subject = subject,
            substances = substances,
            introduction = introParagraphs.joinToString("\n\n").ifEmpty { null },
            onset = phase(ONSET_TYPES),
            peak = phase(PEAK_TYPES),
            offset = phase(OFFSET_TYPES),
            conclusion = session.outcome?.trim()?.takeIf { it.isNotEmpty() },
            tags = session.tags
        ),
        contact_email = person?.contactEmail?.trim()?.takeIf { it.isNotEmpty() },
        may_contact = person?.mayContact == true,
        publish_consent = publishConsent,
        age_confirmed = ageConfirmed
    )
}

/** Narrative length as the server computes it: intro, phase descriptions, conclusion. */
fun TripReportBody.narrativeLength(): Int {
    val parts = mutableListOf<String>()
    introduction?.let { parts.add(it) }
    (onset + peak + offset).forEach { parts.add(it.description) }
    conclusion?.let { parts.add(it) }
    return parts.joinToString(" ").trim().length
}

/** Mirror the four server rejections plus the known advisory warnings. */
fun DoseWikiTripReport.check(): TripReportCheck {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    if (report.title.trim().isEmpty()) errors.add(DoseWikiTripReport.ERR_TITLE)
    if (report.substances.none { it.name.trim().isNotEmpty() }) {
        errors.add(DoseWikiTripReport.ERR_SUBSTANCE)
    }
    if (!publish_consent) errors.add(DoseWikiTripReport.ERR_CONSENT)
    if (!age_confirmed) errors.add(DoseWikiTripReport.ERR_AGE)
    if (report.narrativeLength() < DoseWikiTripReport.MIN_NARRATIVE_CHARS) {
        warnings.add(
            "Narrative is ${report.narrativeLength()} characters, " +
                "below the ${DoseWikiTripReport.MIN_NARRATIVE_CHARS} an editor expects."
        )
    }
    report.substances.forEach {
        if (it.dose.isNullOrBlank()) warnings.add("Substance ${it.name} has no dose.")
    }
    val bytes = DoseWikiTripReport.json.encodeToString(this).toByteArray(Charsets.UTF_8).size
    if (bytes > DoseWikiTripReport.MAX_BODY_BYTES) {
        errors.add("Payload is $bytes bytes, over the ${DoseWikiTripReport.MAX_BODY_BYTES} limit.")
    }
    return TripReportCheck(errors, warnings)
}

fun DoseWikiTripReport.toJsonString(): String = DoseWikiTripReport.json.encodeToString(this)
