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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.ui.components.*
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
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
        onBack = onBack,
        actions = {
            // Export to Obsidian
            val vaultPath by repo.obsidianVaultPath.collectAsState()
            val subfolder by repo.obsidianSubfolder.collectAsState()
            if (vaultPath.isNotBlank()) {
                var exportStatus by remember { mutableStateOf<String?>(null) }
                IconButton(onClick = {
                    val config = app.journal.export.obsidian.ObsidianExportConfig(
                        vaultPath = vaultPath,
                        subfolder = subfolder
                    )
                    val path = app.journal.export.obsidian.ObsidianExportManager.exportSession(
                        repo, sessionId, config
                    )
                    exportStatus = if (path != null) "Exported" else "Export failed"
                }) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = "Export to Obsidian",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
    val repo = remember { JournalRepository.instance }
    val isDark = ThemeManager.instance.isDarkTheme()
    val totalDuration = (endTime ?: currentTimeMillis()) - startTime
    val now = currentTimeMillis()
    val rangeMs = totalDuration.coerceAtLeast(1L)
    val textMeasurer = rememberTextMeasurer()

    // Build substance rows from dose data
    val substanceNames = doses.map { d -> repo.getSubstance(d.substanceId)?.name ?: d.substanceId }.distinct()
    val displayNames = if (substanceNames.isNotEmpty()) substanceNames else listOf("Session")
    val phaseEvents = events.filter { it.eventType in phaseColors }.sortedBy { it.timestamp }
    val rowH = 22.dp
    val labelW = 72.dp
    val rowGap = 4.dp
    val topPad = 4.dp

    // Pre-compute row data (composable-safe, outside Canvas)
    val rows = displayNames.map { name ->
        val isFall = name == "Session"
        val col = if (isFall) MaterialTheme.colorScheme.primary
            else AdaptiveColors.colorFor(name).getComposeColor(isDark)
        val d = if (isFall) emptyList() else doses.filter { dose ->
            repo.getSubstance(dose.substanceId)?.name == name || dose.substanceId == name
        }
        val matched = if (isFall) phaseEvents
            else phaseEvents.filter { pe ->
                d.any { dose -> kotlin.math.abs(dose.timestamp - pe.timestamp) < 3600000 }
            }
        TimelineRowData(name, col, d, matched)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            // Substance chips
            if (substanceNames.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    substanceNames.forEach { name ->
                        val c = AdaptiveColors.colorFor(name).getComposeColor(isDark)
                        Surface(shape = RoundedCornerShape(6.dp), color = c.copy(alpha = 0.15f)) {
                            Text(name, style = MaterialTheme.typography.labelSmall,
                                color = c, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Timeline Canvas
            val canvasH = (displayNames.size * 26 + 4).dp
            Box(modifier = Modifier.fillMaxWidth().height(canvasH)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val labelPx = labelW.toPx()
                    val barX = labelPx + 6.dp.toPx()
                    val barW = (w - barX).coerceAtLeast(1f)
                    val rowPx = rowH.toPx()
                    val gapPx = rowGap.toPx()
                    val padPx = topPad.toPx()

                    // Draw each row
                    rows.forEachIndexed { idx, row ->
                        val y = padPx + idx * (rowPx + gapPx)

                        // Label background
                        drawRoundRect(row.color, Offset(0f, y), Size(labelPx, rowPx), CornerRadius(4f, 4f))

                        // Bar background
                        drawRoundRect(row.color.copy(alpha = 0.12f), Offset(barX, y), Size(barW, rowPx), CornerRadius(4f, 4f))

                        // Dose start marker
                        val firstDose = row.doses.minByOrNull { it.timestamp }
                        if (firstDose != null) {
                            val pct = ((firstDose.timestamp - startTime).toFloat() / rangeMs).coerceIn(0f, 1f)
                            drawRoundRect(row.color, Offset(barX + pct * barW, y + 2.dp.toPx()),
                                Size(4.dp.toPx(), rowPx - 4.dp.toPx()), CornerRadius(2f, 2f))
                        }

                        // Phase segments (proportional within their own range)
                        if (row.matched.size >= 2) {
                            val firstT = row.matched.first().timestamp
                            val lastT = row.matched.last().timestamp
                            val segR = (lastT - firstT).coerceAtLeast(1L)
                            for (i in 0 until row.matched.size - 1) {
                                val cur = row.matched[i]
                                val nxt = row.matched[i + 1]
                                val p1 = ((cur.timestamp - firstT).toFloat() / segR).coerceIn(0f, 1f)
                                val p2 = ((nxt.timestamp - firstT).toFloat() / segR).coerceIn(0f, 1f)
                                val sc = phaseColors[cur.eventType] ?: row.color
                                drawRect(sc.copy(alpha = 0.5f), Offset(barX + p1 * barW, y + 2.dp.toPx()),
                                    Size(((p2 - p1) * barW).coerceAtLeast(1f), rowPx - 4.dp.toPx()))
                            }
                        }

                        // Current time marker
                        if (endTime == null || now < endTime) {
                            val p = ((now - startTime).toFloat() / rangeMs).coerceIn(0f, 1f)
                            drawLine(Color.White, Offset(barX + p * barW, y), Offset(barX + p * barW, y + rowPx), strokeWidth = 2.dp.toPx())
                        }
                    }

                    // Draw text labels using Compose TextMeasurer
                    val labelStyle = TextStyle(
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    rows.forEachIndexed { idx, row ->
                        val y = padPx + idx * (rowPx + gapPx)
                        val text = row.name.take(10)
                        val measured = textMeasurer.measure(text, style = labelStyle)
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                x = (labelPx - measured.size.width) / 2f,
                                y = y + (rowPx - measured.size.height) / 2f
                            )
                        )
                    }
                }
            }

            // Time axis
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(start = labelW + 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..5) {
                    val h = rangeMs * i / 5 / 3600000
                    Text("${h}h", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Phase legend
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                phases.forEach { (type, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.size(6.dp).background(
                            phaseColors[type] ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private data class TimelineRowData(
    val name: String,
    val color: Color,
    val doses: List<Dose>,
    val matched: List<TimelineEvent>
)

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
