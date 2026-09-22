package app.journal.ui.components

import androidx.compose.ui.graphics.Color
import app.journal.model.TimelineEventType

/**
 * Single source of truth for phase colors.
 *
 * The timeline header, the session chart ribbon, the substance duration bars
 * and the live phase chips all read from here instead of keeping their own
 * value sets, so a phase keeps one identity across the app.
 */
object PhaseColors {

    /** Canonical pastel palette, keyed by event type. Source of truth. */
    val byType: Map<TimelineEventType, Color> = mapOf(
        TimelineEventType.ONSET to Color(0xFF80CBC4),
        TimelineEventType.COMEUP to Color(0xFFA5D6A7),
        TimelineEventType.PEAK to Color(0xFFFFAB91),
        TimelineEventType.PLATEAU to Color(0xFFCE93D8),
        TimelineEventType.OFFSET to Color(0xFFFFF59D),
        TimelineEventType.AFTERGLOW to Color(0xFF80DEEA),
    )

    private val labelByType: Map<TimelineEventType, String> = mapOf(
        TimelineEventType.ONSET to "Onset",
        TimelineEventType.COMEUP to "Comeup",
        TimelineEventType.PEAK to "Peak",
        TimelineEventType.PLATEAU to "Plateau",
        TimelineEventType.OFFSET to "Offset",
        TimelineEventType.AFTERGLOW to "Afterglow",
    )

    /** The same palette addressed by display label, for label-keyed charts. */
    val byLabel: Map<String, Color> = labelByType.entries
        .mapNotNull { (type, label) -> byType[type]?.let { label to it } }
        .toMap()

    /**
     * Saturated variant, a deliberately second color for contexts where the
     * pastel set is too weak: the session chart ribbon paints labels on top of
     * the fill and needs the stronger values to stay readable on both dark and
     * light themes. Same phases, stronger values, no other difference.
     */
    val saturatedByLabel: Map<String, Color> = mapOf(
        "Onset" to Color(0xFF26A69A),
        "Comeup" to Color(0xFF66BB6A),
        "Peak" to Color(0xFFEF5350),
        "Plateau" to Color(0xFFAB47BC),
        "Offset" to Color(0xFFFFCA28),
        "Afterglow" to Color(0xFF26C6DA),
    )

    /** Canonical color for an event type, null for non-phase types. */
    fun color(type: TimelineEventType): Color? = byType[type]

    /** Canonical color for a display label ("Onset", "Peak", ...), else null. */
    fun color(label: String): Color? = byLabel[label]

    /** Saturated ribbon color for a display label, else null. */
    fun saturated(label: String): Color? = saturatedByLabel[label]
}
