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

private sealed class TimelineItem {
    data class Event(val event: TimelineEvent) : TimelineItem()
    data class PhaseHeader(val label: String) : TimelineItem()
}

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

    // Pre-compute sorted events and phase-grouped items (outside LazyColumn scope)
    val sortedEvents = remember(events) { events.sortedBy { it.timestamp } }
    val sessionDuration = remember(session) { (session?.endTime ?: currentTimeMillis()) - (session?.startTime ?: 0L) }
    val combinedItems = remember(sortedEvents, sessionDuration, session) {
        val items = mutableListOf<TimelineItem>()
        var currentPhase: String? = null
        val startTime = session?.startTime ?: 0L
        for (event in sortedEvents) {
            val elapsedMs = event.timestamp - startTime
            val phase = phaseLabel(elapsedMs, sessionDuration)
            if (phase != null && phase != currentPhase) {
                currentPhase = phase
                items.add(TimelineItem.PhaseHeader(phase))
            }
            items.add(TimelineItem.Event(event))
        }
        items
    }

    var showAddEventDialog by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<TimelineEvent?>(null) }

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
                                                "${local.day.toString().padStart(2,'0')} ${local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${local.year}",
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

        // Timeline events list with phase headers
        if (combinedItems.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Timeline Events", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    FilledTonalIconButton(onClick = { showAddEventDialog = true }) {
                        Icon(Icons.Default.Add, "Add event", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            items(combinedItems, key = {
                when (it) {
                    is TimelineItem.Event -> it.event.id
                    is TimelineItem.PhaseHeader -> "phase_${it.label}"
                }
            }) { item ->
                when (item) {
                    is TimelineItem.Event -> EventCard(item.event, session.startTime, repo = repo,
                        onDelete = { deletingEvent = it })
                    is TimelineItem.PhaseHeader -> {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(item.label, style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
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

        // Intensity curve
        item {
            IntensityCurveOverlay(
                events = sortedEvents,
                startTime = session.startTime
            )
        }

        // Substances / Dosage Table
        if (doses.isNotEmpty()) {
            item {
                Text("Substances", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            item {
                DosageSummaryTable(doses = doses, repo = repo, sessionStart = session.startTime)
            }
        }

        // Check-in effect tags
        if (session.checkins.any { it.effectScores.isNotEmpty() }) {
            item {
                EffectTagCloud(session = session, repo = repo)
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

    if (showAddEventDialog) {
        AddEventDialog(
            session = session,
            repo = repo,
            onDismiss = { showAddEventDialog = false }
        )
    }
    if (deletingEvent != null) {
        AlertDialog(
            onDismissRequest = { deletingEvent = null },
            title = { Text("Delete event?") },
            text = { Text("Delete \"${deletingEvent?.label}\"? This cannot be undone.") },
            confirmButton = {
                AppTextButton(onClick = {
                    deletingEvent?.let { repo.deleteTimelineEvent(it.id) }
                    deletingEvent = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                AppTextButton(onClick = { deletingEvent = null }) { Text("Cancel") }
            }
        )
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
private fun EventCard(
    event: TimelineEvent,
    sessionStart: Long,
    repo: JournalRepository,
    onDelete: ((TimelineEvent) -> Unit)? = null
) {
    var editing by remember { mutableStateOf(false) }
    var editLabel by remember { mutableStateOf(event.label) }
    var editBody by remember { mutableStateOf(event.body ?: "") }
    var editIntensity by remember { mutableFloatStateOf(event.intensity ?: 5f) }
    var editType by remember { mutableStateOf(event.eventType) }
    var useIntensity by remember { mutableStateOf(event.intensity != null) }

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

    if (editing) {
        // --- INLINE EDIT MODE ---
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Event type chips
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    val quickTypes = listOf(
                        TimelineEventType.OBSERVATION to "Obs",
                        TimelineEventType.NOTE to "Note",
                        TimelineEventType.ONSET to "On",
                        TimelineEventType.COMEUP to "Up",
                        TimelineEventType.PEAK to "Peak",
                        TimelineEventType.OFFSET to "Off",
                        TimelineEventType.AFTERGLOW to "Glow",
                        TimelineEventType.SIDE_EFFECT to "SE",
                        TimelineEventType.EMERGENCY to "!"
                    )
                    quickTypes.forEach { (type, lbl) ->
                        FilterChip(
                            selected = editType == type,
                            onClick = { editType = type },
                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }
                OutlinedTextField(value = editLabel, onValueChange = { editLabel = it },
                    label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = editBody, onValueChange = { editBody = it },
                    label = { Text("Notes") }, minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = useIntensity, onClick = { useIntensity = !useIntensity },
                        label = { Text("Intensity", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(26.dp))
                    if (useIntensity) {
                        Text("${editIntensity.toInt()}/10", style = MaterialTheme.typography.labelSmall)
                        Slider(value = editIntensity, onValueChange = { editIntensity = it },
                            valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextButton(onClick = { editing = false }) { Text("Cancel") }
                    AppTextButton(onClick = {
                        repo.upsertTimelineEvent(event.copy(
                            eventType = editType,
                            label = editLabel.ifBlank { event.label },
                            body = editBody.ifBlank { null },
                            intensity = if (useIntensity) editIntensity else null,
                            updatedAt = currentTimeMillis()
                        ))
                        editing = false
                    }) { Text("Save") }
                }
            }
        }
    } else {
        // --- VIEW MODE ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.fillMaxWidth()) {
                Surface(modifier = Modifier.fillMaxHeight().width(4.dp), color = accent) {}
                Row(Modifier.padding(12.dp).weight(1f), verticalAlignment = Alignment.Top,
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
                    // Action buttons
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (onDelete != null) {
                            IconButton(onClick = { onDelete(event) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error)
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

@Composable
private fun DosageSummaryTable(doses: List<Dose>, repo: JournalRepository, sessionStart: Long) {
    val isDark = ThemeManager.instance.isDarkTheme()
    val grouped = doses.groupBy { it.substanceId }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            grouped.entries.forEachIndexed { idx, (substanceId, substanceDoses) ->
                val substance = repo.getSubstance(substanceId)
                val color = AdaptiveColors.colorFor(substance?.name ?: substanceId).getComposeColor(isDark)
                if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(4.dp, 40.dp),
                        shape = RoundedCornerShape(2.dp),
                        color = color
                    ) {}
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(substance?.name ?: substanceId,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        substanceDoses.forEach { dose ->
                            val offsetMin = ((dose.timestamp - sessionStart) / 60000).toInt()
                            Text(
                                "${dose.amount} ${dose.unit} ${dose.routeOfAdministration} @ +${offsetMin}m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectTagCloud(session: Session, repo: JournalRepository) {
    val allScores = session.checkins
        .flatMap { c -> c.effectScores.entries.map { it.key to it.value } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, scores) -> scores.average().toFloat() }
        .entries
        .sortedByDescending { it.value }
    if (allScores.isEmpty()) return

    val isDark = ThemeManager.instance.isDarkTheme()
    Column {
        Spacer(Modifier.height(8.dp))
        Text("Effects Experienced", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            allScores.forEach { (effect, avgScore) ->
                val label = effect.replace("_", " ").replaceFirstChar { it.uppercase() }
                val chipColor = when {
                    avgScore >= 7f -> MaterialTheme.colorScheme.tertiary
                    avgScore >= 4f -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.secondary
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = chipColor.copy(alpha = 0.12f)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = chipColor,
                            fontWeight = FontWeight.Medium)
                        Text("${avgScore.toInt()}/10", style = MaterialTheme.typography.labelSmall,
                            color = chipColor.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun IntensityCurveOverlay(
    events: List<TimelineEvent>,
    startTime: Long
) {
    val now = currentTimeMillis()
    val rangeMs = now - startTime
    val intensityEvents = events.filter { it.intensity != null }.sortedBy { it.timestamp }
    if (intensityEvents.size < 2) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Intensity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Intensity \u2191", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val padL = 24.dp.toPx()
                    val padB = 16.dp.toPx()
                    val drawW = w - padL
                    val drawH = h - padB

                    // Grid lines
                    val gridColor = Color.Gray.copy(alpha = 0.15f)
                    for (i in 0..4) {
                        val y = drawH * i / 4
                        drawLine(gridColor,
                            Offset(padL, y), Offset(w, y), strokeWidth = 0.5.dp.toPx())
                    }

                    // Build path
                    if (intensityEvents.size >= 2) {
                        val path = Path()
                        val firstT = intensityEvents.first().timestamp
                        val lastT = intensityEvents.last().timestamp
                        val eventRange = (lastT - firstT).coerceAtLeast(1L)
                        var firstPoint = true
                        path.moveTo(padL, drawH)
                        for (event in intensityEvents) {
                            val x = padL + ((event.timestamp - firstT).toFloat() / eventRange * drawW).coerceIn(0f, drawW)
                            val y = drawH - (event.intensity!! / 10f * drawH).coerceIn(0f, drawH)
                            if (firstPoint) {
                                path.lineTo(x, y)
                                firstPoint = false
                            } else {
                                path.lineTo(x, y)
                            }
                        }
                        path.lineTo(w, drawH)
                        path.close()
                        drawPath(path, primaryColor.copy(alpha = 0.15f))
                        // Stroke the top edge
                        var first = true
                        for (event in intensityEvents) {
                            val x = padL + ((event.timestamp - firstT).toFloat() / eventRange * drawW).coerceIn(0f, drawW)
                            val y = drawH - (event.intensity!! / 10f * drawH).coerceIn(0f, drawH)
                            if (first) {
                                path.rewind(); path.moveTo(x, y); first = false
                            } else {
                                path.lineTo(x, y)
                            }
                        }
                        drawPath(path, primaryColor, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
            // Time axis for curve
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                val durHours = rangeMs / 3600000f
                for (i in 0..4) {
                    Text("${(durHours * i / 4).toInt()}h",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private fun phaseLabel(elapsedMs: Long, totalMs: Long): String? {
    if (totalMs <= 0) return null
    val fraction = elapsedMs.toFloat() / totalMs
    return when {
        fraction < 0.25f -> "Onset"
        fraction < 0.50f -> "Comeup"
        fraction < 0.75f -> "Peak"
        else -> "Offset"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEventDialog(
    session: Session,
    repo: JournalRepository,
    editEvent: TimelineEvent? = null,
    onDismiss: () -> Unit
) {
    val now = currentTimeMillis()
    val eventTypes = listOf(
        TimelineEventType.OBSERVATION to "Observation",
        TimelineEventType.NOTE to "Note",
        TimelineEventType.ONSET to "Onset",
        TimelineEventType.COMEUP to "Comeup",
        TimelineEventType.PEAK to "Peak",
        TimelineEventType.OFFSET to "Offset",
        TimelineEventType.AFTERGLOW to "Afterglow",
        TimelineEventType.SAFETY_CHECK to "Safety check",
        TimelineEventType.SIDE_EFFECT to "Side effect",
        TimelineEventType.EMERGENCY to "Emergency"
    )
    val initialTypeIdx = eventTypes.indexOfFirst { it.first == editEvent?.eventType }.coerceAtLeast(0)
    var selectedIndex by remember { mutableIntStateOf(initialTypeIdx) }
    var label by remember { mutableStateOf(editEvent?.label ?: "") }
    var notes by remember { mutableStateOf(editEvent?.body ?: "") }
    var intensity by remember { mutableFloatStateOf(editEvent?.intensity ?: 5f) }
    var useIntensity by remember { mutableStateOf(editEvent?.intensity != null) }
    var timestamp by remember { mutableStateOf(editEvent?.timestamp ?: (session.startTime + ((now - session.startTime) / 2).coerceAtLeast(60000L))) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editEvent != null) "Edit Event" else "Add Timeline Event", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Event type dropdown
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = eventTypes[selectedIndex].second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Event type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        eventTypes.forEachIndexed { i, (_, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { selectedIndex = i; expanded = false }
                            )
                        }
                    }
                }

                // Label
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("e.g., Strong visuals") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Notes
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                // Intensity toggle + slider
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = useIntensity, onCheckedChange = { useIntensity = it })
                    Column {
                        Text("Intensity", style = MaterialTheme.typography.bodyMedium)
                        if (useIntensity) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Slider(value = intensity, onValueChange = { intensity = it },
                                    valueRange = 1f..10f, steps = 8,
                                    modifier = Modifier.weight(1f))
                                Text("${intensity.toInt()}/10",
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // Time offset
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val offsetMin = ((timestamp - session.startTime) / 60000).toInt()
                    Text("Time: +${offsetMin}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        timestamp = session.startTime + (offsetMin + 15) * 60000L
                    }) { Text("+15m") }
                    OutlinedButton(onClick = {
                        timestamp = session.startTime + ((offsetMin - 15).coerceAtLeast(0)) * 60000L
                    }) { Text("-15m") }
                }
            }
        },
        confirmButton = {
            AppTextButton(onClick = {
                val eventType = eventTypes[selectedIndex].first
                val eventLabel = label.ifBlank { eventTypes[selectedIndex].second }
                val eventId = editEvent?.id ?: "event:manual:${now}_${session.id}"
                val existing = editEvent
                repo.upsertTimelineEvent(TimelineEvent(
                    id = eventId,
                    sessionId = session.id,
                    timestamp = timestamp,
                    eventType = eventType,
                    label = eventLabel,
                    body = notes.ifBlank { null },
                    intensity = if (useIntensity) intensity else null,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    deviceOrigin = existing?.deviceOrigin ?: "desktop",
                ))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
