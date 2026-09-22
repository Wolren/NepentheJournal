package app.journal.ui.session.timeline

import androidx.compose.ui.graphics.Color
import app.journal.model.TimelineEventType
import app.journal.model.TimelineEvent
import app.journal.ui.components.PhaseColors
import kotlin.math.pow

sealed class TimelineItem {
    data class Event(val event: TimelineEvent) : TimelineItem()
    data class PhaseHeader(val label: String) : TimelineItem()
}

/**
 * Canonical phase palette, delegated to [PhaseColors] so the timeline, the
 * session chart ribbon, the duration bars and the live phase chips read one
 * set of values instead of carrying their own forks.
 */
val phaseColors: Map<TimelineEventType, Color> = PhaseColors.byType

val phases = listOf(
    TimelineEventType.ONSET to "Onset",
    TimelineEventType.COMEUP to "Comeup",
    TimelineEventType.PEAK to "Peak",
    TimelineEventType.OFFSET to "Offset",
    TimelineEventType.AFTERGLOW to "Afterglow"
)

internal fun phaseLabelForEntry(type: TimelineEventType): String = when (type) {
    TimelineEventType.ONSET -> "Onset"
    TimelineEventType.COMEUP -> "Comeup"
    TimelineEventType.PEAK -> "Peak"
    TimelineEventType.PLATEAU -> "Plateau"
    TimelineEventType.OFFSET -> "Offset"
    TimelineEventType.AFTERGLOW -> "Afterglow"
    else -> "Event"
}

/** Canonical phase color for a display label (e.g. "Offset" always yellow). */
internal fun phaseColorForLabel(label: String): Color? = PhaseColors.color(label)

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
        val typeLabel = phaseLabelForEntry(event.eventType)
        val phaseName = if (typeLabel != "Event") typeLabel
            else phaseLabel(event.timestamp - startTime, totalDuration) ?: continue
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

    // Extend each range's end to the next range's start (or session end),
    // so phase cards show the real span instead of zero-width ranges.
    for (i in result.indices) {
        val nextStart = if (i + 1 < result.size) result[i + 1].startTime else startTime + totalDuration
        if (result[i].endTime < nextStart) {
            result[i] = result[i].copy(endTime = nextStart)
        }
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

/**
 * Display formatting for a logged dose amount, scaled to the magnitude:
 * hundreds and up render whole (195.44 mg becomes 195 mg), tens keep one
 * decimal, singles keep two, sub-unit amounts keep three. Trailing zeros
 * strip, so float dust (151.019999) and spurious precision never reach
 * the UI. Stored values and exports keep full precision; this is display only.
 */
internal fun formatDoseAmount(amount: Double): String {
    val abs = kotlin.math.abs(amount)
    val decimals = when {
        abs >= 100 -> 0
        abs >= 10 -> 1
        abs >= 1 -> 2
        else -> 3
    }
    val factor = 10.0.pow(decimals)
    val rounded = kotlin.math.round(amount * factor) / factor
    if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
    return rounded.toString().trimEnd('0').trimEnd('.')
}

/**
 * Compact T+ offset label for dose rows ("T+45m", "T+2h 15m"). Empty for
 * doses at session start so rows don't carry "@ +0m" noise.
 */
internal fun formatTOffsetLabel(offsetMin: Int): String {
    if (offsetMin <= 0) return ""
    val h = offsetMin / 60
    val m = offsetMin % 60
    return if (h > 0) "T+${h}h ${m}m" else "T+${m}m"
}
