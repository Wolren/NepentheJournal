package app.journal.ingest.foreign

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Lenient field readers: foreign files carry the same key with different types across versions. */
internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

internal fun JsonObject.str(key: String): String? {
    val prim = this[key] as? JsonPrimitive ?: return null
    if (prim is JsonNull) return null
    return prim.contentOrNull?.trim()?.ifEmpty { null }
}

internal fun JsonObject.num(key: String): Double? {
    val prim = this[key] as? JsonPrimitive ?: return null
    if (prim is JsonNull) return null
    return prim.contentOrNull?.trim()?.replace(',', '.')?.toDoubleOrNull()
}

internal fun JsonObject.boolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.equals("true", ignoreCase = true) == true

/** Epoch millis from an epoch number (seconds or millis) or an ISO-8601 text. */
internal fun parseTimestamp(raw: String?, tz: TimeZone): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return epochMillis(it) }
    text.toDoubleOrNull()?.let { return epochMillis(it.toLong()) }
    val iso = text.replace(' ', 'T')
    runCatching { kotlinx.datetime.Instant.parse(iso).toEpochMilliseconds() }.getOrNull()?.let { return it }
    runCatching { LocalDateTime.parse(iso).toInstant(tz).toEpochMilliseconds() }.getOrNull()?.let { return it }
    europeanTimestamp(text, tz)?.let { return it }
    runCatching { LocalDate.parse(text).atStartOfDayIn(tz).toEpochMilliseconds() }.getOrNull()?.let { return it }
    return null
}

/** Day-first "14.11.2026" and "14/11/2026 15:30", the shape European exports use. */
private fun europeanTimestamp(text: String, tz: TimeZone): Long? {
    val match = EUROPEAN.find(text) ?: return null
    val day = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    if (month !in 1..12 || day !in 1..31) return null
    val hour = match.groupValues[4].toIntOrNull() ?: 0
    val minute = match.groupValues[5].toIntOrNull() ?: 0
    val local = LocalDateTime(match.groupValues[3].toInt(), Month.entries[month - 1], day, hour, minute)
    return runCatching { local.toInstant(tz).toEpochMilliseconds() }.getOrNull()
}

private val EUROPEAN = Regex("^(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?:[ T](\\d{1,2}):(\\d{2}))?$")

/** 10-digit values are epoch seconds, 13-digit values are millis. */
private fun epochMillis(value: Long): Long =
    if (value > 100_000_000_000L) value else value * 1000L

/** Minutes after midnight for "HH:mm", or null for a malformed clock. */
internal fun clockMinutes(raw: String?): Int? {
    val parts = raw?.trim()?.split(':') ?: return null
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
