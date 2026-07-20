package app.journal.ui.session.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.model.Substance
import app.journal.model.TimelineEvent
import app.journal.util.currentTimeMillis
import app.journal.ui.components.*
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager

@Composable
internal fun RatingBadge(rating: Int?, shulginRating: String?) {
    if (rating != null || shulginRating != null) {
        val displayText = shulginRating ?: "${rating}/10"
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(displayText, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

@Composable
internal fun DoseTimelineCard(dose: Dose, substance: Substance?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp)) {
            Surface(modifier = Modifier.width(4.dp).height(48.dp), shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.primary) {}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(substance?.name ?: dose.substanceId,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(buildString { append("${dose.amount}"); if (dose.isDoseEstimate) append("\u00B1${dose.estimatedDoseStandardDeviation}"); append(" ${dose.unit} - ${dose.routeOfAdministration}"); if (dose.redosing) append(" (redose)") },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dose.stomachFullness != null) Text(dose.stomachFullness.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    if (dose.isDoseEstimate) Text("estimated", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun DosageSummaryTable(doses: List<Dose>, repo: app.journal.data.JournalRepository, sessionStart: Long) {
    val isDark = ThemeManager.instance.isDarkTheme()
    val grouped = doses.groupBy { it.substanceId }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            grouped.entries.forEachIndexed { idx, (substanceId, substanceDoses) ->
                val substance = repo.getSubstance(substanceId)
                val color = AdaptiveColors.colorFor(substance?.name ?: substanceId).getComposeColor(isDark)
                if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.size(4.dp, 40.dp), shape = RoundedCornerShape(2.dp), color = color) {}
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(substance?.name ?: substanceId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        substanceDoses.forEach { dose ->
                            val offsetMin = ((dose.timestamp - sessionStart) / 60000).toInt()
                            Text("${dose.amount} ${dose.unit} ${dose.routeOfAdministration} @ +${offsetMin}m",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EffectTagCloud(session: app.journal.model.Session, repo: app.journal.data.JournalRepository) {
    val allScores = session.checkins
        .flatMap { c -> c.effectScores.entries.map { it.key to it.value } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, scores) -> scores.average().toFloat() }
        .entries.sortedByDescending { it.value }
    if (allScores.isEmpty()) return

    val isDark = ThemeManager.instance.isDarkTheme()
    Column {
        Spacer(Modifier.height(8.dp))
        Text("Effects Experienced", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            allScores.forEach { (effect, avgScore) ->
                val label = effect.replace("_", " ").replaceFirstChar { it.uppercase() }
                val chipColor = when { avgScore >= 7f -> MaterialTheme.colorScheme.tertiary; avgScore >= 4f -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.secondary }
                Surface(shape = RoundedCornerShape(8.dp), color = chipColor.copy(alpha = 0.12f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = chipColor, fontWeight = FontWeight.Medium)
                        Text("${avgScore.toInt()}/10", style = MaterialTheme.typography.labelSmall, color = chipColor.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun IntensityCurveOverlay(events: List<TimelineEvent>, startTime: Long) {
    val now = currentTimeMillis()
    val rangeMs = now - startTime
    val intensityEvents = events.filter { it.intensity != null }.sortedBy { it.timestamp }
    if (intensityEvents.size < 2) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Intensity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Intensity \u2191", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height; val padL = 24.dp.toPx(); val padB = 16.dp.toPx()
                    val drawW = w - padL; val drawH = h - padB
                    val gridColor = Color.Gray.copy(alpha = 0.15f)
                    for (i in 0..4) { val y = drawH * i / 4; drawLine(gridColor, Offset(padL, y), Offset(w, y), strokeWidth = 0.5.dp.toPx()) }
                    if (intensityEvents.size >= 2) {
                        val path = Path()
                        val firstT = intensityEvents.first().timestamp; val lastT = intensityEvents.last().timestamp
                        val eventRange = (lastT - firstT).coerceAtLeast(1L)
                        path.moveTo(padL, drawH)
                        for (event in intensityEvents) {
                            val x = padL + ((event.timestamp - firstT).toFloat() / eventRange * drawW).coerceIn(0f, drawW)
                            val y = drawH - (event.intensity!! / 10f * drawH).coerceIn(0f, drawH)
                            path.lineTo(x, y)
                        }
                        path.lineTo(w, drawH); path.close()
                        drawPath(path, primaryColor.copy(alpha = 0.15f))
                        var first = true
                        for (event in intensityEvents) {
                            val x = padL + ((event.timestamp - firstT).toFloat() / eventRange * drawW).coerceIn(0f, drawW)
                            val y = drawH - (event.intensity!! / 10f * drawH).coerceIn(0f, drawH)
                            if (first) { path.rewind(); path.moveTo(x, y); first = false } else { path.lineTo(x, y) }
                        }
                        drawPath(path, primaryColor, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val durHours = rangeMs / 3600000f
                for (i in 0..4) { Text("${(durHours * i / 4).toInt()}h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
            }
        }
    }
}
