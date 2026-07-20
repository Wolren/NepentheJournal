package app.journal.ui.session.timeline

import androidx.compose.ui.graphics.Color
import app.journal.model.TimelineEventType
import app.journal.model.TimelineEvent

sealed class TimelineItem {
    data class Event(val event: TimelineEvent) : TimelineItem()
    data class PhaseHeader(val label: String) : TimelineItem()
}

val phaseColors = mapOf(
    TimelineEventType.ONSET to Color(0xFF80CBC4),
    TimelineEventType.COMEUP to Color(0xFFA5D6A7),
    TimelineEventType.PEAK to Color(0xFFFFAB91),
    TimelineEventType.PLATEAU to Color(0xFFCE93D8),
    TimelineEventType.OFFSET to Color(0xFFFFF59D),
    TimelineEventType.AFTERGLOW to Color(0xFF80DEEA),
)

val phases = listOf(
    TimelineEventType.ONSET to "Onset",
    TimelineEventType.COMEUP to "Comeup",
    TimelineEventType.PEAK to "Peak",
    TimelineEventType.OFFSET to "Offset",
    TimelineEventType.AFTERGLOW to "Afterglow"
)

internal fun phaseLabel(elapsedMs: Long, totalMs: Long): String? {
    if (totalMs <= 0) return null
    val fraction = elapsedMs.toFloat() / totalMs
    return when {
        fraction < 0.25f -> "Onset"
        fraction < 0.50f -> "Comeup"
        fraction < 0.75f -> "Peak"
        else -> "Offset"
    }
}

internal fun formatDuration(millis: Long): String {
    val totalSec = millis / 1000
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
