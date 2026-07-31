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

data class PhaseRange(
    val label: String,
    val eventType: TimelineEventType,
    val startTime: Long,
    val endTime: Long,
    val description: String? = null
)

internal fun computePhaseRanges(
    events: List<TimelineEvent>,
    startTime: Long,
    totalDuration: Long
): List<PhaseRange> {
    val relevant = events.filter { it.eventType in phaseColors }.sortedBy { it.timestamp }
    if (relevant.isEmpty()) return emptyList()

    val result = mutableListOf<PhaseRange>()
    var currentPhase: String? = null
    var phaseStart = startTime
    var phaseEnd = startTime
    var phaseType: TimelineEventType? = null

    for (event in relevant) {
        val elapsed = event.timestamp - startTime
        val phaseName = phaseLabel(elapsed, totalDuration) ?: continue
        if (phaseName != currentPhase) {
            if (currentPhase != null && phaseType != null) {
                result.add(PhaseRange(currentPhase, phaseType, phaseStart, phaseEnd))
            }
            currentPhase = phaseName
            phaseStart = event.timestamp
            phaseEnd = event.timestamp
            phaseType = event.eventType
        } else {
            phaseEnd = event.timestamp
        }
    }
    if (currentPhase != null && phaseType != null) {
        result.add(PhaseRange(currentPhase, phaseType, phaseStart, phaseEnd))
    }
    return result
}

internal fun formatTimeOffset(millis: Long): String {
    val totalSec = millis / 1000
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return if (hours > 0) "T+${hours}:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    else "T+${mins}:${secs.toString().padStart(2, '0')}"
}
