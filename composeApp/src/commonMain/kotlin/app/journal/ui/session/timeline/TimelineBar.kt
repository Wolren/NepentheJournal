package app.journal.ui.session.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.model.CheckIn
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.ui.theme.foregroundFor
import app.journal.util.currentTimeMillis
import app.journal.ui.session.timeline.phaseColors

private data class TimelineRowData(
    val name: String,
    val color: Color,
    val doses: List<Dose>,
    val matched: List<TimelineEvent>
)

@Composable
internal fun TimelineBar(
    startTime: Long,
    endTime: Long?,
    events: List<TimelineEvent>,
    checkins: List<CheckIn>,
    doses: List<Dose>,
    shulginRating: String? = null
) {
    val repo = remember { JournalRepository.instance }
    val isDark = ThemeManager.instance.isDarkTheme()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val totalDuration = (endTime ?: currentTimeMillis()) - startTime
    val now = currentTimeMillis()
    val rangeMs = totalDuration.coerceAtLeast(1L)
    val textMeasurer = rememberTextMeasurer()

    val substanceNames = remember(doses) {
        doses.map { d -> repo.getSubstance(d.substanceId)?.name ?: d.substanceId }.distinct()
    }
    val displayNames = if (substanceNames.isNotEmpty()) substanceNames else listOf("Session")
    val phaseEvents = remember(events) {
        events.filter { it.eventType in phaseColors }.sortedBy { it.timestamp }
    }
    val doseNameMap = remember(doses) {
        doses.associate { dose ->
            dose.id to (repo.getSubstance(dose.substanceId)?.name ?: dose.substanceId)
        }
    }
    val rowH = 22.dp
    val labelW = 72.dp
    val rowGap = 4.dp
    val topPad = 4.dp

    val rows: List<TimelineRowData>
    val sessionColor = MaterialTheme.colorScheme.primary
    val nameColors = displayNames.associateWith { name ->
        if (name == "Session") sessionColor
        else AdaptiveColors.colorFor(name).getComposeColor(isDark)
    }
    rows = remember(displayNames, doses, phaseEvents, nameColors) {
        displayNames.map { name ->
            val isFall = name == "Session"
            val col = nameColors[name] ?: sessionColor
            val d = if (isFall) emptyList() else doses.filter { dose ->
                doseNameMap[dose.id] == name || dose.substanceId == name
            }
            val matched = if (isFall) phaseEvents
                else phaseEvents.filter { pe ->
                    d.any { dose -> kotlin.math.abs(dose.timestamp - pe.timestamp) < 3600000 }
                }
            TimelineRowData(name, col, d, matched)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            if (substanceNames.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    substanceNames.forEach { name ->
                        val c = AdaptiveColors.colorFor(name).getComposeColor(isDark)
                        Surface(shape = RoundedCornerShape(6.dp), color = c) {
                            Text(name, style = MaterialTheme.typography.labelSmall,
                                color = foregroundFor(c), fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            val labelStyle = TextStyle(color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            val preMeasuredLabels = remember(rows, labelStyle) {
                rows.map { row ->
                    textMeasurer.measure(row.name.take(10),
                        style = labelStyle.copy(color = foregroundFor(row.color)))
                }
            }
            val canvasH = (displayNames.size * 26 + 4).dp
            var dragFraction by remember { mutableStateOf<Float?>(null) }
            var boxWidthPx by remember { mutableFloatStateOf(0f) }
            Box(modifier = Modifier.fillMaxWidth().height(canvasH)
                .onGloballyPositioned { boxWidthPx = it.size.width.toFloat() }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val barX = labelW.toPx() + 6.dp.toPx()
                            val barW = (boxWidthPx - barX).coerceAtLeast(1f)
                            if (offset.x >= barX) {
                                dragFraction = ((offset.x - barX) / barW).coerceIn(0f, 1f)
                            }
                        },
                        onDragEnd = { dragFraction = null },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { change, _ ->
                            val barX = labelW.toPx() + 6.dp.toPx()
                            val barW = (boxWidthPx - barX).coerceAtLeast(1f)
                            dragFraction = ((change.position.x - barX) / barW).coerceIn(0f, 1f)
                        }
                    )
                }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width; val labelPx = labelW.toPx()
                    val barX = labelPx + 6.dp.toPx(); val barW = (w - barX).coerceAtLeast(1f)
                    val rowPx = rowH.toPx(); val gapPx = rowGap.toPx(); val padPx = topPad.toPx()

                    rows.forEachIndexed { idx, row ->
                        val y = padPx + idx * (rowPx + gapPx)
                        drawRoundRect(row.color, Offset(0f, y), Size(labelPx, rowPx), CornerRadius(4f, 4f))
                        drawRoundRect(row.color.copy(alpha = 0.12f), Offset(barX, y), Size(barW, rowPx), CornerRadius(4f, 4f))

                        val firstDose = row.doses.minByOrNull { it.timestamp }
                        if (firstDose != null) {
                            val pct = ((firstDose.timestamp - startTime).toFloat() / rangeMs).coerceIn(0f, 1f)
                            drawRoundRect(row.color, Offset(barX + pct * barW, y + 2.dp.toPx()),
                                Size(4.dp.toPx(), rowPx - 4.dp.toPx()), CornerRadius(2f, 2f))
                        }

                        if (row.matched.size >= 2) {
                            val firstT = row.matched.first().timestamp
                            val lastT = row.matched.last().timestamp
                            val segR = (lastT - firstT).coerceAtLeast(1L)
                            for (i in 0 until row.matched.size - 1) {
                                val cur = row.matched[i]; val nxt = row.matched[i + 1]
                                val p1 = ((cur.timestamp - firstT).toFloat() / segR).coerceIn(0f, 1f)
                                val p2 = ((nxt.timestamp - firstT).toFloat() / segR).coerceIn(0f, 1f)
                                val sc = phaseColors[cur.eventType] ?: row.color
                                drawRect(sc.copy(alpha = 0.5f), Offset(barX + p1 * barW, y + 2.dp.toPx()),
                                    Size(((p2 - p1) * barW).coerceAtLeast(1f), rowPx - 4.dp.toPx()))
                            }
                        }

                        if (endTime == null || now < endTime) {
                            val p = ((now - startTime).toFloat() / rangeMs).coerceIn(0f, 1f)
                            drawLine(onSurface.copy(alpha = 0.9f),
                                Offset(barX + p * barW, y), Offset(barX + p * barW, y + rowPx), strokeWidth = 2.dp.toPx())
                        }
                    }

                    preMeasuredLabels.forEachIndexed { idx, measured ->
                        val y = padPx + idx * (rowPx + gapPx)
                        drawText(textLayoutResult = measured,
                            topLeft = Offset(x = (labelPx - measured.size.width) / 2f,
                                y = y + (rowPx - measured.size.height) / 2f))
                    }

                    // Drag-scrub indicator line and time label
                    if (dragFraction != null) {
                        val scrbX = barX + dragFraction!! * barW
                        drawLine(onSurface.copy(alpha = 0.8f), Offset(scrbX, 0f),
                            Offset(scrbX, size.height), strokeWidth = 1.5.dp.toPx())
                        val elapsedMs = (dragFraction!! * rangeMs).toLong()
                        val timeLabel = formatTimeOffset(elapsedMs)
                        val measured = textMeasurer.measure(
                            timeLabel,
                            style = TextStyle(color = onSurface, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold)
                        )
                        val labelX = (scrbX - measured.size.width / 2f)
                            .coerceIn(measured.size.width / 2f, w - measured.size.width / 2f)
                        drawText(textLayoutResult = measured,
                            topLeft = Offset(labelX, 4.dp.toPx()))
                        // Small circle at drag position on bar
                        drawCircle(onSurface.copy(alpha = 0.8f), radius = 3.dp.toPx(),
                            center = Offset(scrbX, size.height - 2.dp.toPx()))
                    }

                    // Shulgin rating marker near peak phase
                    if (shulginRating != null && phaseEvents.size >= 2) {
                        val peakEvents = phaseEvents.filter { it.eventType == TimelineEventType.PEAK }
                        val peakTime = if (peakEvents.isNotEmpty()) {
                            (peakEvents.first().timestamp + peakEvents.last().timestamp) / 2
                        } else {
                            val midIdx = phaseEvents.size / 2
                            phaseEvents[midIdx].timestamp
                        }
                        val pct = ((peakTime - startTime).toFloat() / rangeMs).coerceIn(0f, 1f)
                        val markerX = barX + pct * barW
                        // Theme-aware gold: pale gold is invisible on light surfaces
                        val markerColor = if (isDark) Color(0xFFFFD54F) else Color(0xFFB28704)
                        val ratingStyle = TextStyle(color = markerColor, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold)
                        val ratingMeasured = textMeasurer.measure(shulginRating, style = ratingStyle)
                        val diamondSize = 5.dp.toPx()
                        // Draw diamond marker
                        val cy = size.height - diamondSize - 4.dp.toPx()
                        drawCircle(markerColor, radius = diamondSize, center = Offset(markerX, cy))
                        drawCircle(Color.White.copy(alpha = 0.3f), radius = diamondSize,
                            center = Offset(markerX, cy))
                        // Rating label below diamond
                        drawText(textLayoutResult = ratingMeasured,
                            topLeft = Offset(markerX - ratingMeasured.size.width / 2f,
                                cy + diamondSize + 2.dp.toPx()))
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(start = labelW + 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..5) { val h = rangeMs * i / 5 / 3600000; Text("${h}h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                phases.forEach { (type, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.size(6.dp).background(phaseColors[type] ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
