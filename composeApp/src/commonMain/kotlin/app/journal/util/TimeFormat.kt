package app.journal.util

import app.journal.util.currentTimeMillis

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
