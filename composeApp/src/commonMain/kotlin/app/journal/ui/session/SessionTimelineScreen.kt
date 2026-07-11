package app.journal.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val phaseColors = mapOf(
    TimelineEventType.ONSET to Color(0xFF80CBC4),
    TimelineEventType.COMEUP to Color(0xFFA5D6A7),
    TimelineEventType.PEAK to Color(0xFFFFAB91),
    TimelineEventType.PLATEAU to Color(0xFFCE93D8),
    TimelineEventType.OFFSET to Color(0xFFFFF59D),
    TimelineEventType.AFTERGLOW to Color(0xFF80DEEA),
)

private val phases = listOf(
    TimelineEventType.ONSET to "Onset",
    TimelineEventType.COMEUP to "Comeup",
    TimelineEventType.PEAK to "Peak",
    TimelineEventType.OFFSET to "Offset",
    TimelineEventType.AFTERGLOW to "Afterglow"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTimelineScreen(
    sessionId: String,
    onBack: () -> Unit
) {
    val repo = remember { JournalRepository.instance }
    val session = remember(sessionId) { repo.getSession(sessionId) }
    val events = remember(sessionId) { repo.eventsForSession(sessionId) }
    val doses = remember(sessionId) { repo.dosesForSession(sessionId) }

    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Session not found")
        }
        return
    }

    ScreenScaffold(
        title = session.title,
        onBack = onBack
    ) {
        // Session header with rating
        item {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    val tz = TimeZone.currentSystemDefault()
                    val local = Instant.fromEpochMilliseconds(session.startTime).toLocalDateTime(tz)
                    Text(
                        "${local.year}-${(local.month.ordinal + 1).toString().padStart(2,'0')}-${local.day.toString().padStart(2,'0')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RatingBadge(session.rating, session.shulginRating)
            }
        }

        // Visual timeline bar
        item {
            TimelineBar(
                startTime = session.startTime,
                endTime = session.endTime,
                events = events,
                checkins = session.checkins,
                doses = doses
            )
        }

        // Session intention/outcome
        if (!session.intention.isNullOrBlank() || !session.outcome.isNullOrBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (!session.intention.isNullOrBlank()) {
                            Text("Intention", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(session.intention, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!session.outcome.isNullOrBlank()) {
                            if (!session.intention.isNullOrBlank()) Spacer(Modifier.height(8.dp))
                            Text("Outcome", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(session.outcome, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Set & Setting
        if (!session.set.isNullOrBlank() || !session.setting.isNullOrBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (!session.set.isNullOrBlank()) {
                            Text("Set (Mindset)", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(session.set, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!session.setting.isNullOrBlank()) {
                            if (!session.set.isNullOrBlank()) Spacer(Modifier.height(8.dp))
                            Text("Setting (Environment)", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(session.setting, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Timeline events list
        if (events.isNotEmpty()) {
            item {
                Text("Timeline Events", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            itemsIndexed(events, key = { _, e -> e.id }) { _, event ->
                AnimatedListItem {
                    EventCard(event, session.startTime)
                }
            }
        } else {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp))
                        Text("No timeline events recorded. Add check-ins during a session to build a timeline.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Substances / Doses
        if (doses.isNotEmpty()) {
            item {
                Text("Substances", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            itemsIndexed(doses, key = { _, d -> d.id }) { _, dose ->
                AnimatedListItem {
                    val substance = repo.getSubstance(dose.substanceId)
                    DoseTimelineCard(dose, substance)
                }
            }
        }

        // Tags
        if (session.tags.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    session.tags.forEach { tag ->
                        app.journal.ui.components.TagChip(tag = tag)
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingBadge(rating: Int?, shulginRating: String?) {
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
private fun DoseTimelineCard(dose: Dose, substance: Substance?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp)) {
            Surface(
                modifier = Modifier.width(4.dp).height(48.dp),
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.primary
            ) {}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(substance?.name ?: dose.substanceId,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append("${dose.amount}")
                        if (dose.isDoseEstimate) append("±${dose.estimatedDoseStandardDeviation}")
                        append(" ${dose.unit} - ${dose.routeOfAdministration}")
                        if (dose.redosing) append(" (redose)")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dose.stomachFullness != null) {
                        Text(dose.stomachFullness.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                    if (dose.isDoseEstimate) {
                        Text("estimated",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineBar(
    startTime: Long,
    endTime: Long?,
    events: List<TimelineEvent>,
    checkins: List<CheckIn>,
    doses: List<Dose>
) {
    val totalDuration = (endTime ?: currentTimeMillis()) - startTime
    val now = currentTimeMillis()
    val progress = if (totalDuration > 0) {
        ((now - startTime).toFloat() / totalDuration).coerceIn(0f, 1f)
    } else 1f

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Timeline", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            // Check-in intensity chart
            if (checkins.isNotEmpty()) {
                val intensityColor = MaterialTheme.colorScheme.tertiary
                Box(
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val width = size.width
                        val height = size.height
                        val range = totalDuration.coerceAtLeast(1L)

                        if (checkins.size >= 2) {
                            val sorted = checkins.sortedBy { it.timestamp }
                            val path = Path()
                            sorted.forEachIndexed { i, checkin ->
                                val x = ((checkin.timestamp - startTime).toFloat() / range * width).coerceIn(0f, width)
                                val y = height - (checkin.overallIntensity / 10f * height).coerceIn(0f, height)
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            val lastX = ((sorted.last().timestamp - startTime).toFloat() / range * width).coerceIn(0f, width)
                            path.lineTo(lastX, height)
                            path.lineTo(0f, height)
                            path.close()
                            drawPath(path, color = intensityColor.copy(alpha = 0.2f))

                            val linePath = Path()
                            sorted.forEachIndexed { i, checkin ->
                                val x = ((checkin.timestamp - startTime).toFloat() / range * width).coerceIn(0f, width)
                                val y = height - (checkin.overallIntensity / 10f * height).coerceIn(0f, height)
                                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                            }
                            drawPath(linePath, color = intensityColor, style = Stroke(width = 2f))

                            sorted.forEach { checkin ->
                                val x = ((checkin.timestamp - startTime).toFloat() / range * width).coerceIn(0f, width)
                                val y = height - (checkin.overallIntensity / 10f * height).coerceIn(0f, height)
                                drawCircle(color = intensityColor, radius = 3f, center = Offset(x, y))
                            }
                        } else if (checkins.size == 1) {
                            val c = checkins.first()
                            val x = ((c.timestamp - startTime).toFloat() / range * width).coerceIn(0f, width)
                            val y = height - (c.overallIntensity / 10f * height).coerceIn(0f, height)
                            drawCircle(color = intensityColor, radius = 4f, center = Offset(x, y))
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Intensity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    Text("10", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
            }

            // Track bar
            val primaryColor = MaterialTheme.colorScheme.primary
            Box(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val barY = size.height / 2 - 4
                    val barW = size.width
                    drawRoundRect(
                        color = Color.Gray.copy(alpha = 0.2f),
                        topLeft = Offset(0f, barY),
                        size = Size(barW, 8f),
                        cornerRadius = CornerRadius(4f)
                    )
                    val phaseEvents = events.filter { it.eventType in phaseColors }
                    if (phaseEvents.isNotEmpty()) {
                        val sorted = phaseEvents.sortedBy { it.timestamp }
                        val range = (sorted.last().timestamp - sorted.first().timestamp).coerceAtLeast(1L)
                        sorted.forEach { event ->
                            val color = phaseColors[event.eventType] ?: Color.Gray
                            val x = ((event.timestamp - sorted.first().timestamp).toFloat() / range * barW).coerceIn(0f, barW - 4f)
                            drawCircle(color = color, radius = 6f, center = Offset(x, barY + 4f))
                        }
                    }
                    if (endTime == null || now < endTime) {
                        val px = (barW * progress).coerceIn(0f, barW)
                        drawCircle(color = primaryColor, radius = 8f, center = Offset(px, barY + 4f))
                        drawCircle(color = primaryColor.copy(alpha = 0.3f), radius = 14f, center = Offset(px, barY + 4f))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                phases.forEach { (type, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.size(8.dp).padding(bottom = 2.dp).background(
                            color = phaseColors[type] ?: MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)))
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(now - startTime), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                if (endTime != null) {
                    Text("Total: ${formatDuration(endTime - startTime)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("In progress", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: TimelineEvent, sessionStart: Long) {
    val elapsed = event.timestamp - sessionStart
    val mins = elapsed / 60000

    val icon = when (event.eventType) {
        TimelineEventType.ONSET -> Icons.Default.ArrowForward
        TimelineEventType.COMEUP -> Icons.Default.TrendingUp
        TimelineEventType.PEAK -> Icons.Default.Star
        TimelineEventType.PLATEAU -> Icons.Default.HorizontalRule
        TimelineEventType.OFFSET -> Icons.Default.TrendingDown
        TimelineEventType.AFTERGLOW -> Icons.Default.NightsStay
        TimelineEventType.END -> Icons.Default.Stop
        TimelineEventType.OBSERVATION -> Icons.Default.Visibility
        TimelineEventType.SAFETY_CHECK -> Icons.Default.CheckCircle
        TimelineEventType.SIDE_EFFECT -> Icons.Default.Warning
        TimelineEventType.EMERGENCY -> Icons.Default.Error
        TimelineEventType.NOTE -> Icons.Default.Notes
    }

    val accent = when (event.eventType) {
        TimelineEventType.EMERGENCY, TimelineEventType.SIDE_EFFECT,
        TimelineEventType.NOTE -> MaterialTheme.colorScheme.error
        TimelineEventType.SAFETY_CHECK, TimelineEventType.OBSERVATION -> MaterialTheme.colorScheme.primary
        else -> phaseColors[event.eventType] ?: MaterialTheme.colorScheme.primary
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth()) {
            Surface(modifier = Modifier.fillMaxHeight().width(4.dp), color = accent) {}
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(event.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("+${mins}m", style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                    if (!event.body.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(event.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (event.intensity != null) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Intensity:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text("${event.intensity}/10", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSec = millis / 1000
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
