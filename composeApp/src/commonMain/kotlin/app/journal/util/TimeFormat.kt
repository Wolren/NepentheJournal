package app.journal.util

import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Abbreviated month names for the uniform "DD Mon YYYY" date format. */
private val SHORT_MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

/**
 * Compact date, e.g. "31 Jul 2026". This is the single uniform date format
 * across all screens (the "DD Mon YYYY" convention). Never render raw ISO
 * or numeric month/day in UI text; use this instead.
 */
fun formatDateShort(epochMs: Long, tz: TimeZone = TimeZone.currentSystemDefault()): String =
    formatDateShort(Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz).date)

fun formatDateShort(date: LocalDate): String =
    "${date.dayOfMonth} ${SHORT_MONTHS[date.month.ordinal]} ${date.year}"

fun formatDateShort(local: LocalDateTime): String = formatDateShort(local.date)

/** Four time display modes for session timestamps. */
enum class TimeDisplayMode {
    /** Relative to now, e.g. "2h ago" */
    RELATIVE,
    /** Wall-clock time, e.g. "15:30" */
    CLOCK,
    /** Elapsed since session start, e.g. "T+1:23" */
    ELAPSED,
    /** Duration from start to now, e.g. "1h 23m" */
    DURATION
}

/**
 * Human-readable relative timestamp (e.g. "3m ago", "Yesterday", "2w ago").
 * Replaces ad-hoc implementations across multiple screens.
 */
fun formatRelativeTime(epochMs: Long, now: Long = currentTimeMillis()): String {
    val diff = now - epochMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    val weeks = days / 7
    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "Yesterday"
        days < 7 -> "${days}d ago"
        weeks < 4 -> "${weeks}w ago"
        days < 365 -> "${days / 30}mo ago"
        else -> "${days / 365}y ago"
    }
}

/**
 * Wall-clock time, e.g. "15:30".
 */
fun formatClockTime(epochMs: Long, tz: TimeZone = TimeZone.currentSystemDefault()): String {
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
    return "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}

/**
 * Time elapsed since [sessionStartMs] formatted with a T+ prefix,
 * e.g. "T+1:23" or "T+45m" or "T+12s".
 */
fun formatElapsedSinceStart(sessionStartMs: Long, nowMs: Long = currentTimeMillis()): String {
    val diff = nowMs - sessionStartMs
    if (diff < 0) return "T+0:00"
    val totalSeconds = diff / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hours > 0) {
        "T+$hours:${minutes.toString().padStart(2, '0')}"
    } else if (minutes > 0) {
        "T+${minutes}m"
    } else {
        "T+${secs}s"
    }
}

/**
 * Human-readable duration, e.g. "1h 23m", "45m", "12s".
 */
fun formatDuration(startMs: Long, endMs: Long = currentTimeMillis()): String {
    val diff = endMs - startMs
    if (diff < 0) return "0m"
    val totalSeconds = diff / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}
