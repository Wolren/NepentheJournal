package app.journal.export.obsidian

import app.journal.data.AppJson
import app.journal.model.*
import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Result of rendering a session into an Obsidian-compatible Markdown note.
 */
data class MarkdownNote(
    val fileName: String,
    val content: String,
    val sessionId: String
)

/**
 * Canonical data block embedded in every Obsidian note.
 * Enables lossless round-trip import: the JSON is parsed back, not the markdown.
 */
@Serializable
data class ObsidianCanonicalBlock(
    val version: Int = 1,
    val exportedAt: Long,
    val session: Session,
    val doses: List<Dose> = emptyList(),
    val notes: List<Note> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList()
)

/** Fence markers for the canonical data block. */
internal const val BLOCK_OPEN = "```nepenthe"
internal const val BLOCK_CLOSE = "```"

private val canonicalJson = AppJson.pretty

/**
 * Pure function that renders a session + its children into an Obsidian markdown note.
 * No I/O, no side effects — fully testable.
 *
 * @param session the session to render
 * @param doses all doses belonging to this session
 * @param notes all notes belonging to this session
 * @param timelineEvents all timeline events belonging to this session
 * @param substanceNameResolver function from substanceId to display name (e.g., "cid:5761" -> "LSD")
 * @return a MarkdownNote ready to write to disk
 */
fun renderSessionToObsidianNote(
    session: Session,
    doses: List<Dose>,
    notes: List<Note>,
    timelineEvents: List<TimelineEvent>,
    substanceNameResolver: (String) -> String?
): MarkdownNote {
    val dateTime = Instant.fromEpochMilliseconds(session.startTime)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val dateStr = "${dateTime.year}-${pad2(dateTime.monthNumber)}-${pad2(dateTime.dayOfMonth)}"
    val timeStr = "${pad2(dateTime.hour)}:${pad2(dateTime.minute)}"
    val dateTimeStr = "$dateStr $timeStr"

    val titleText = session.title.ifBlank { "Untitled" }
    val slug = slugify(titleText)
    val fileName = buildString {
        append(dateStr)
        append('-')
        append(timeStr.replace(':', '-'))
        if (slug.isNotEmpty()) append("-${slug}")
        append('-')
        append(sanitizeIdForFilename(session.id))
        append(".md")
    }

    val substances: Set<String> = doses.mapNotNull { dose ->
        substanceNameResolver(dose.substanceId)
    }.toSortedSet()

    val durationStr = formatDuration(session.startTime, session.endTime)

    val content = buildString {
        // ---- YAML frontmatter ----
        appendLine("---")
        appendLine("id: \"${yamlEscape(session.id)}\"")
        appendLine("title: \"${yamlEscape(titleText)}\"")
        appendLine("date: \"$dateStr\"")
        appendLine("time: \"$timeStr\"")
        if (session.endTime != null) {
            val hours = formatDurationHours(session.startTime, session.endTime)
            appendLine("duration_hours: $hours")
        }
        if (session.rating != null) appendLine("rating: ${session.rating}")
        if (session.shulginRating != null) appendLine("shulgin_rating: \"${session.shulginRating}\"")
        if (substances.isNotEmpty()) {
            appendLine("substances:")
            substances.forEach { name -> appendLine("  - \"${yamlEscape(name)}\"") }
        }
        if (session.tags.isNotEmpty() || session.isFavorite || session.isArchived) {
            val allTags = buildList {
                addAll(session.tags)
                if (session.isFavorite) add("favorite")
                if (session.isArchived) add("archived")
            }
            appendLine("tags: [${allTags.joinToString(", ") { "\"${yamlEscape(it)}\"" }}]")
        }
        if (session.consumerName != null) appendLine("consumer: \"${yamlEscape(session.consumerName)}\"")
        if (session.intention != null) appendLine("intention: \"${yamlEscape(session.intention)}\"")
        if (session.set != null) appendLine("set: \"${yamlEscape(session.set)}\"")
        if (session.setting != null) appendLine("setting: \"${yamlEscape(session.setting)}\"")
        if (session.outcome != null) appendLine("outcome: \"${yamlEscape(session.outcome)}\"")
        appendLine("source: \"Nepenthe Journal\"")
        appendLine("---")
        appendLine()

        // ---- Title ----
        append("# $titleText — $dateTimeStr")
        appendLine()
        appendLine()

        // ---- Metadata line ----
        append("*Started $dateTimeStr*")
        if (session.endTime != null) {
            val endDt = Instant.fromEpochMilliseconds(session.endTime)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val endStr = "${pad2(endDt.hour)}:${pad2(endDt.minute)}"
            append(" · *Ended $endStr*")
        }
        if (durationStr != null) append(" · *Duration $durationStr*")
        val ratingDisplay = if (session.shulginRating != null) {
            "Shulgin ${session.shulginRating}"
        } else session.rating?.let { "$it/10" }
        if (ratingDisplay != null) append(" · *Rating $ratingDisplay*")
        if (session.consumerName != null) append(" · *${session.consumerName}*")
        appendLine()
        appendLine()

        // ---- Set & Setting ----
        if (session.set != null || session.setting != null || session.intention != null) {
            appendLine("## Set & Setting")
            appendLine()
            appendLine("| Set | Setting | Intention |")
            appendLine("| --- | ------- | --------- |")
            appendLine("| ${mdCell(session.set)} | ${mdCell(session.setting)} | ${mdCell(session.intention)} |")
            appendLine()
        }

        // ---- Doses ----
        if (doses.isNotEmpty()) {
            appendLine("## Doses")
            appendLine()
            appendLine("| Time | Substance | Route | Amount | Stomach | Notes |")
            appendLine("| ---- | --------- | ----- | ------ | ------- | ----- |")
            for (dose in doses.sortedBy { it.timestamp }) {
                val doseTime = formatTime(dose.timestamp)
                val subName = substanceNameResolver(dose.substanceId) ?: dose.substanceId
                val subLink = "[[${subName.replace("[", "").replace("]", "")}]]"
                val amt = formatAmount(dose.amount, dose.unit)
                val stomach = dose.stomachFullness?.label ?: "\u2014"
                val note = dose.notes ?: ""
                val route = if (dose.routeOfAdministration.isBlank()) "\u2014" else dose.routeOfAdministration
                appendLine("| $doseTime | $subLink | ${mdCell(route)} | $amt | $stomach | ${mdCell(note)} |")
            }
            appendLine()
        }

        // ---- Timeline ----
        val sortedEvents = timelineEvents.sortedBy { it.timestamp }
        if (sortedEvents.isNotEmpty()) {
            appendLine("## Timeline")
            appendLine()
            appendLine("| Time | Event | Body | Intensity |")
            appendLine("| ---- | ----- | ---- | --------- |")
            for (event in sortedEvents) {
                val eventTime = formatTime(event.timestamp)
                val eventLabel = if (event.eventType == TimelineEventType.NOTE) event.label
                    else "**${event.label}**"
                val body = event.body ?: ""
                val intensity = event.intensity?.let { "${it}/10" } ?: ""
                appendLine("| $eventTime | $eventLabel | ${mdCell(body)} | $intensity |")
            }
            appendLine()
        }

        // ---- Checkins ----
        if (session.checkins.isNotEmpty()) {
            appendLine("## Checkins")
            appendLine()
            appendLine("| Time | Overall | Mood |")
            appendLine("| ---- | ------- | ---- |")
            for (ci in session.checkins.sortedBy { it.timestamp }) {
                val ciTime = formatTime(ci.timestamp)
                val mood = ci.mood ?: ""
                appendLine("| $ciTime | ${ci.overallIntensity}/10 | ${mdCell(mood)} |")
            }
            appendLine()
        }

        // ---- Notes ----
        if (notes.isNotEmpty()) {
            appendLine("## Notes")
            appendLine()
            for (note in notes.sortedByDescending { it.createdAt }) {
                val noteTitle = note.title ?: "Note"
                appendLine("### ${mdCell(noteTitle)}")
                if (note.tags.isNotEmpty()) {
                    appendLine("*Tags: ${note.tags.joinToString(", ") { "`$it`" }}*")
                    appendLine()
                }
                if (note.isPinned) {
                    appendLine("> **Pinned**")
                    appendLine()
                }
                append(note.body)
                appendLine()
                appendLine()
            }
        }

        // ---- Outcome ----
        if (!session.outcome.isNullOrBlank()) {
            appendLine("## Outcome")
            appendLine()
            append(session.outcome)
            appendLine()
            appendLine()
        }

        // ---- Session Notes ----
        if (!session.notes.isNullOrBlank()) {
            appendLine("## Session Notes")
            appendLine()
            append(session.notes)
            appendLine()
            appendLine()
        }

        // ---- Canonical data block (lossless round-trip) ----
        val canonical = ObsidianCanonicalBlock(
            version = 1,
            exportedAt = currentTimeMillis(),
            session = session,
            doses = doses,
            notes = notes,
            timelineEvents = timelineEvents
        )
        val json = canonicalJson.encodeToString(canonical)
        appendLine("---")
        appendLine()
        appendLine("> [!INFO] Nepenthe Journal Data")
        appendLine("> Machine-readable block — edit the sections above and re-import to sync.")
        appendLine()
        appendLine(BLOCK_OPEN)
        append(json)
        appendLine()
        appendLine(BLOCK_CLOSE)
        appendLine()
    }

    return MarkdownNote(
        fileName = fileName,
        content = content,
        sessionId = session.id
    )
}

// ---- Helpers ----

/** Slugify a string for use in filenames. */
internal fun slugify(s: String): String {
    val out = StringBuilder()
    var prevDash = false
    for (ch in s) {
        if (ch.isLetterOrDigit()) {
            out.append(ch.lowercaseChar())
            prevDash = false
        } else if (!prevDash && out.isNotEmpty()) {
            out.append('-')
            prevDash = true
        }
    }
    while (out.endsWith('-')) out.deleteAt(out.length - 1)
    return if (out.isEmpty()) "untitled" else out.toString()
}

/** Strip non-filename-safe chars from a session ID for use in filenames. */
internal fun sanitizeIdForFilename(id: String): String {
    return id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(16)
        .ifEmpty { "session" }
}

/** Pad a positive integer to 2 digits. */
internal fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

/** Escape a string for use inside a double-quoted YAML scalar value (outer quotes excluded). */
internal fun yamlEscape(s: String?): String {
    val str = s ?: ""
    return str.replace("\\", "\\\\").replace("\"", "\\\"")
}

/** Escape a markdown table cell value. */
internal fun mdCell(s: String?): String {
    return (s ?: "").replace("|", "\\|").replace('\n', ' ')
}

/** Format a timestamp as "HH:MM". */
internal fun formatTime(timestamp: Long): String {
    val dt = Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return "${pad2(dt.hour)}:${pad2(dt.minute)}"
}

/** Format duration between two epoch-millis timestamps as human-readable string. */
internal fun formatDuration(start: Long, end: Long?): String? {
    if (end == null) return null
    val diffMs = end - start
    if (diffMs < 0) return null
    val totalMinutes = diffMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

/** Format duration as decimal hours. */
internal fun formatDurationHours(start: Long, end: Long): Double {
    val diffMs = end - start
    return if (diffMs < 0) 0.0
    else (diffMs.toDouble() / 3_600_000.0).let { kotlin.math.round(it * 100) / 100 }
}

/** Format a dose amount + unit. */
internal fun formatAmount(amount: Double, unit: String): String {
    val amtStr = if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()
    return "$amtStr $unit"
}
