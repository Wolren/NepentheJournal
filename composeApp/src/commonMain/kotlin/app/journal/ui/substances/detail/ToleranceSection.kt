package app.journal.ui.substances.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.util.currentTimeMillis

/** Strips wiki markup like [[Target|Display]] or [[Target]] from a string. */
internal fun cleanWikiMarkup(text: String): String {
    return text.replace(Regex("""\[\[([^|\]]+)\|([^\]]+)\]\]""")) { it.groupValues[2] }
        .replace(Regex("""\[\[([^\]]+)\]\]""")) { it.groupValues[1] }
}

/** Converts internal source version codes to human-readable labels. */
internal fun formatSource(version: String): String {
    return when {
        version.startsWith("pwiki-") -> "PsychonautWiki"
        version.startsWith("tripsit") -> "TripSit"
        version.startsWith("wikidata") -> "Wikidata"
        version.startsWith("pubchem") || version.startsWith("pubsci") -> "PubChem"
        version.startsWith("chembl") -> "ChEMBL"
        version == "test" -> "Test data"
        else -> version
    }
}

@Composable
internal fun ToleranceTimelineSection(doses: List<Dose>, substanceName: String, isDark: Boolean) {
    val now = currentTimeMillis()
    val dayMs = 86400000L
    val lookbackDays = 90
    val sorted = doses.sortedBy { it.timestamp }
    if (sorted.isEmpty()) return

    SectionCard(title = "Tolerance Timeline (90 days)") {
        val lineColor = AdaptiveColors.colorFor(substanceName).getComposeColor(isDark)
        Canvas(modifier = Modifier.fillMaxWidth().height(60.dp)) {
            val w = size.width
            val h = size.height
            val start = now - lookbackDays * dayMs

            drawLine(Color.Gray.copy(alpha = 0.2f), Offset(0f, h), Offset(w, h), strokeWidth = 2f)

            sorted.forEach { dose ->
                if (dose.timestamp > start) {
                    val x = ((dose.timestamp - start).toFloat() / (lookbackDays * dayMs)) * w
                    val relHeight = (dose.amount / 500.0).coerceIn(0.05, 1.0).toFloat() * h
                    drawLine(
                        lineColor,
                        Offset(x, h), Offset(x, h - relHeight), strokeWidth = 2f
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${lookbackDays}d ago", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Today", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Each vertical line is a dose. Height = relative amount.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
