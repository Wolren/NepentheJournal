package app.journal.ui.substances.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.journal.data.ToleranceLevel
import app.journal.model.Dose
import app.journal.ui.charts.ToleranceBarsChart
import app.journal.ui.charts.buildToleranceRows
import app.journal.ui.charts.toleranceLevelColor
import app.journal.util.currentTimeMillis

/** Strips wiki markup like [[Target|Display]] or [[Target]] from a string. */
internal fun cleanWikiMarkup(text: String): String {
    return text.replace(Regex("""\[\[([^|\]]+)\|([^\]]+)\]\]""")) { it.groupValues[2] }
        .replace(Regex("""\[\[([^\]]+)\]\]""")) { it.groupValues[1] }
}

/**
 * Tolerance history for one substance over the last 90 days, in the
 * PsychonautWiki Journal style: level-colored bars on a day axis with a line
 * at today. The bars are rebuilt from ingestion history rather than sampled,
 * and a legend is drawn because a single row of colors means nothing on its
 * own.
 */
@Composable
internal fun ToleranceTimelineSection(doses: List<Dose>) {
    val now = currentTimeMillis()
    val days = 90
    if (doses.isEmpty()) return

    val rows = remember(doses, now) { buildToleranceRows(doses, now, emptyMap(), days = days) }

    SectionCard(title = "Tolerance Timeline ($days days)") {
        if (rows.isEmpty()) {
            Text(
                "No active tolerance in the last $days days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ToleranceBarsChart(
                rows = rows,
                hereMs = now,
                days = days,
                showRowLabels = false,
                rowHeight = 30.dp,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToleranceLevel.entries
                    .filter { it != ToleranceLevel.NONE }
                    .forEach { level ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(8.dp)
                                    .background(toleranceLevelColor(level), CircleShape)
                            )
                            Text(
                                level.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
            }
        }
    }
}
