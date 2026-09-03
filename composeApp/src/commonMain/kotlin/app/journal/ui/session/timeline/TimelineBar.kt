package app.journal.ui.session.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

private data class TimelineRowData(
    val name: String,
    val color: Color,
    val doses: List<Dose>,
    val matched: List<TimelineEvent>
)

private data class RibbonSegment(
    val label: String,
    val color: Color,
    val startFrac: Float,
    val endFrac: Float
)

private val EVENT_MARKER_COLORS = mapOf(
    TimelineEventType.SAFETY_CHECK to Color(0xFF66BB6A),
    TimelineEventType.OBSERVATION to Color(0xFF42A5F5),
    TimelineEventType.SIDE_EFFECT to Color(0xFFFFA726),
    TimelineEventType.EMERGENCY to Color(0xFFEF5350),
    TimelineEventType.END to Color(0xFFAB47BC),
    TimelineEventType.NOTE to Color(0xFF90A4AE),
)

/** Pick a "nice" tick step for the time axis so labels never duplicate or collide. */
internal fun axisStepMs(rangeMs: Long): Long {
    val hours = rangeMs / 3_600_000.0
    return when {
        hours < 0.5 -> 5 * 60_000L
        hours < 1.5 -> 15 * 60_000L
        hours < 4.0 -> 30 * 60_000L
        hours < 8.0 -> 60 * 60_000L
        hours < 16.0 -> 2 * 3_600_000L
        hours < 36.0 -> 4 * 3_600_000L
        else -> 6 * 3_600_000L
    }
}

/** Compact axis label: 15m, 1h, 1h30m. */
internal fun axisLabel(ms: Long): String {
    val totalMin = ms / 60_000L
    if (totalMin < 60) return "${totalMin}m"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (m == 0L) "${h}h" else "${h}h${m}m"
}

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
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
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
    val nonPhaseEvents = remember(events) {
        events.filter { it.eventType !in phaseColors }.sortedBy { it.timestamp }
    }
    val doseNameMap = remember(doses) {
        doses.associate { dose ->
            dose.id to (repo.getSubstance(dose.substanceId)?.name ?: dose.substanceId)
        }
    }
    val rowH = 20.dp
    val labelW = 76.dp
    val rowGap = 4.dp

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

    // Phase ribbon segments: from phase events when present, else proportional fallback.
    val ribbonSegments = remember(phaseEvents, rangeMs) {
        val fallback = listOf(
            Triple("Onset", TimelineEventType.ONSET, 0f to 0.25f),
            Triple("Comeup", TimelineEventType.COMEUP, 0.25f to 0.50f),
            Triple("Peak", TimelineEventType.PEAK, 0.50f to 0.75f),
            Triple("Offset", TimelineEventType.OFFSET, 0.75f to 1.0f),
        )
        if (phaseEvents.size >= 2) {
            val first = phaseEvents.first().timestamp
            val last = phaseEvents.last().timestamp
            val span = (last - first).coerceAtLeast(1L)
            val segs = mutableListOf<RibbonSegment>()
            for (i in phaseEvents.indices) {
                val ev = phaseEvents[i]
                val nextT = if (i + 1 < phaseEvents.size) phaseEvents[i + 1].timestamp else last
                val startF = ((ev.timestamp - first).toFloat() / span).coerceIn(0f, 1f)
                val endF = ((nextT - first).toFloat() / span).coerceIn(0f, 1f)
                if (endF > startF) {
                    segs.add(
                        RibbonSegment(
                            label = phaseLabelFor(ev.eventType),
                            color = phaseColors[ev.eventType] ?: sessionColor,
                            startFrac = startF,
                            endFrac = endF
                        )
                    )
                }
            }
            if (segs.isEmpty()) {
                fallback.map { (label, type, r) ->
                    RibbonSegment(label, phaseColors[type] ?: sessionColor, r.first, r.second)
                }
            } else segs
        } else {
            fallback.map { (label, type, r) ->
                RibbonSegment(label, phaseColors[type] ?: sessionColor, r.first, r.second)
            }
        }
    }

    // Intensity samples: timeline events with intensity + check-in overall intensity.
    val intensityPoints = remember(events, checkins) {
        val pts = mutableListOf<Pair<Long, Float>>()
        events.filter { it.intensity != null }.forEach { pts.add(it.timestamp to it.intensity!!) }
        checkins.forEach { pts.add(it.timestamp to it.overallIntensity) }
        pts.sortedBy { it.first }
    }

    val labelStyle = TextStyle(color = Color.White, fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val preMeasuredLabels = remember(rows, labelStyle) {
        rows.map { row ->
            textMeasurer.measure(row.name.take(10),
                style = labelStyle.copy(color = foregroundFor(row.color)))
        }
    }

    val ribbonH = 18.dp
    val ribbonLabelH = 13.dp
    val eventLaneH = if (nonPhaseEvents.isNotEmpty()) 18.dp else 0.dp
    val curveH = if (intensityPoints.size >= 2) 64.dp else 0.dp
    val axisH = 16.dp
    val laneBlock = (rowH + rowGap) * rows.size + rowGap
    val canvasH = 6.dp + ribbonH + ribbonLabelH + 8.dp + laneBlock +
        (if (eventLaneH > 0.dp) eventLaneH + 6.dp else 0.dp) +
        (if (curveH > 0.dp) curveH + 6.dp else 0.dp) + axisH + 4.dp

    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var boxWidthPx by remember { mutableFloatStateOf(0f) }
    val primaryColor = MaterialTheme.colorScheme.primary

    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceVariant),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            if (substanceNames.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    substanceNames.forEach { name ->
                        val c = AdaptiveColors.colorFor(name).getComposeColor(isDark)
                        Surface(shape = RoundedCornerShape(6.dp), color = c.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, c.copy(alpha = 0.45f))) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                                Box(Modifier.size(6.dp).background(c, CircleShape))
                                Text(name, style = MaterialTheme.typography.labelSmall,
                                    color = c, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

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
                    val w = size.width
                    val labelPx = labelW.toPx()
                    val barX = labelPx + 6.dp.toPx()
                    val barW = (w - barX).coerceAtLeast(1f)
                    val rowPx = rowH.toPx()
                    val gapPx = rowGap.toPx()
                    val ribbonPx = ribbonH.toPx()
                    val ribbonLabelPx = ribbonLabelH.toPx()

                    // ---- Phase ribbon ----
                    var y = 6.dp.toPx()
                    // recessed track
                    drawRoundRect(
                        color = onSurface.copy(alpha = 0.06f),
                        topLeft = Offset(barX, y),
                        size = Size(barW, ribbonPx),
                        cornerRadius = CornerRadius(9.dp.toPx(), 9.dp.toPx())
                    )
                    // segments
                    ribbonSegments.forEach { seg ->
                        val sx = barX + seg.startFrac * barW
                        val ex = barX + seg.endFrac * barW
                        val sw = (ex - sx).coerceAtLeast(2f)
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(seg.color.copy(alpha = 0.85f), seg.color.copy(alpha = 0.55f)),
                                startX = sx, endX = ex
                            ),
                            topLeft = Offset(sx, y),
                            size = Size(sw, ribbonPx),
                            cornerRadius = CornerRadius(9.dp.toPx(), 9.dp.toPx())
                        )
                    }
                    // segment labels inside the band (skip narrow ones)
                    val labelTextStyle = TextStyle(color = Color(0xFF1C1C1C), fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    ribbonSegments.forEach { seg ->
                        val sx = barX + seg.startFrac * barW
                        val ex = barX + seg.endFrac * barW
                        val sw = ex - sx
                        val cx = (sx + ex) / 2f
                        val m = textMeasurer.measure(seg.label, style = labelTextStyle)
                        if (sw >= m.size.width + 8.dp.toPx() && sw >= 34.dp.toPx()) {
                            drawText(textLayoutResult = m,
                                topLeft = Offset(cx - m.size.width / 2f,
                                    y + (ribbonPx - m.size.height) / 2f))
                        }
                    }
                    y += ribbonPx + ribbonLabelPx + 8.dp.toPx()

                    // ---- Substance lanes ----
                    rows.forEachIndexed { idx, row ->
                        val ly = y + idx * (rowPx + gapPx)
                        // Row shell: tinted rounded background + hairline outline
                        drawRoundRect(row.color.copy(alpha = 0.07f), Offset(0f, ly), Size(w, rowPx),
                            CornerRadius(8.dp.toPx(), 8.dp.toPx()))
                        drawRoundRect(row.color.copy(alpha = 0.18f), Offset(0f, ly), Size(w, rowPx),
                            CornerRadius(8.dp.toPx(), 8.dp.toPx()), style = Stroke(1.dp.toPx()))
                        // Label chip: soft solid pill
                        drawRoundRect(row.color.copy(alpha = 0.15f), Offset(0f, ly), Size(labelPx, rowPx),
                            CornerRadius(8.dp.toPx(), 8.dp.toPx()))
                        // Bar track: recessed well
                        drawRoundRect(row.color.copy(alpha = 0.09f), Offset(barX, ly), Size(barW, rowPx),
                            CornerRadius(8.dp.toPx(), 8.dp.toPx()))

                        // Phase-matched segments across the bar
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
                                val segX = barX + p1 * barW
                                val segWid = ((p2 - p1) * barW).coerceAtLeast(1f)
                                drawRoundRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(sc, sc.copy(alpha = 0.5f)),
                                        startX = segX, endX = segX + segWid
                                    ),
                                    topLeft = Offset(segX, ly + 2.dp.toPx()),
                                    size = Size(segWid, rowPx - 4.dp.toPx()),
                                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                                )
                            }
                        }

                        // Dose markers with halo - one per dose (redoses included)
                        row.doses.sortedBy { it.timestamp }.forEach { dose ->
                            val pct = ((dose.timestamp - startTime).toFloat() / rangeMs).coerceIn(0f, 1f)
                            val mx = barX + pct * barW
                            val isFirst = dose.id == row.doses.minByOrNull { it.timestamp }?.id
                            if (isFirst) {
                                drawCircle(row.color.copy(alpha = 0.25f), radius = 9.dp.toPx(),
                                    center = Offset(mx, ly + rowPx / 2f))
                                drawRoundRect(row.color, Offset(mx - 2.dp.toPx(), ly + 3.dp.toPx()),
                                    Size(4.dp.toPx(), rowPx - 6.dp.toPx()), CornerRadius(2.dp.toPx(), 2.dp.toPx()))
                            } else {
                                // Redose: smaller ring marker
                                drawCircle(row.color.copy(alpha = 0.30f), radius = 5.5.dp.toPx(),
                                    center = Offset(mx, ly + rowPx / 2f), style = Stroke(2.dp.toPx()))
                                drawCircle(row.color, radius = 2.dp.toPx(),
                                    center = Offset(mx, ly + rowPx / 2f))
                            }
                        }

                        // Now line per lane
                        if (endTime == null || now < endTime) {
                            val p = ((now - startTime).toFloat() / rangeMs).coerceIn(0f, 1f)
                            val nx = barX + p * barW
                            drawLine(onSurface.copy(alpha = 0.85f), Offset(nx, ly + 1.dp.toPx()),
                                Offset(nx, ly + rowPx - 1.dp.toPx()), strokeWidth = 2.dp.toPx())
                            val tri = Path().apply {
                                moveTo(nx - 3.5.dp.toPx(), ly + rowPx - 0.5f)
                                lineTo(nx + 3.5.dp.toPx(), ly + rowPx - 0.5f)
                                lineTo(nx, ly + rowPx + 3.5.dp.toPx())
                                close()
                            }
                            drawPath(tri, onSurface.copy(alpha = 0.9f))
                        }
                    }

                    preMeasuredLabels.forEachIndexed { idx, measured ->
                        val ly = y + idx * (rowPx + gapPx)
                        drawText(textLayoutResult = measured,
                            topLeft = Offset(x = (labelPx - measured.size.width) / 2f,
                                y = ly + (rowPx - measured.size.height) / 2f))
                    }

                    // ---- Event marker lane ----
                    if (nonPhaseEvents.isNotEmpty()) {
                        val laneY = y + rows.size * (rowPx + gapPx) + 6.dp.toPx()
                        drawLine(onSurface.copy(alpha = 0.08f), Offset(barX, laneY + 4.dp.toPx()),
                            Offset(barX + barW, laneY + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                        nonPhaseEvents.forEach { ev ->
                            val pct = ((ev.timestamp - startTime).toFloat() / rangeMs).coerceIn(0f, 1f)
                            val mx = barX + pct * barW
                            val mc = EVENT_MARKER_COLORS[ev.eventType] ?: onSurface
                            val cy = laneY + 4.dp.toPx()
                            // glow + glyph
                            drawCircle(mc.copy(alpha = 0.22f), radius = 6.5.dp.toPx(), center = Offset(mx, cy))
                            when (ev.eventType) {
                                TimelineEventType.EMERGENCY -> {
                                    val tri = Path().apply {
                                        moveTo(mx, cy - 3.5.dp.toPx())
                                        lineTo(mx + 3.5.dp.toPx(), cy + 3.dp.toPx())
                                        lineTo(mx - 3.5.dp.toPx(), cy + 3.dp.toPx())
                                        close()
                                    }
                                    drawPath(tri, mc)
                                }
                                TimelineEventType.END -> {
                                    drawLine(mc, Offset(mx - 3.dp.toPx(), cy),
                                        Offset(mx + 3.dp.toPx(), cy), strokeWidth = 2.5.dp.toPx(),
                                        cap = StrokeCap.Round)
                                }
                                else -> {
                                    drawCircle(mc, radius = 3.dp.toPx(), center = Offset(mx, cy))
                                    drawCircle(Color.White.copy(alpha = 0.35f), radius = 1.2.dp.toPx(),
                                        center = Offset(mx, cy))
                                }
                            }
                        }
                    }

                    // ---- Intensity curve ----
                    if (intensityPoints.size >= 2) {
                        val curveTop = y + rows.size * (rowPx + gapPx) +
                            (if (nonPhaseEvents.isNotEmpty()) 18.dp.toPx() + 6.dp.toPx() else 0f) + 6.dp.toPx()
                        val curveHpx = curveH.toPx()
                        val padL = 20.dp.toPx()
                        val curveW = (barW - padL).coerceAtLeast(1f)
                        val curveX = barX + padL
                        val firstT = startTime
                        val lastT = startTime + rangeMs
                        val tSpan = (lastT - firstT).coerceAtLeast(1L)
                        val primary = primaryColor
                        // grid lines
                        val gridColor = onSurface.copy(alpha = 0.06f)
                        for (i in 0..3) {
                            val gy = curveTop + curveHpx * i / 3
                            drawLine(gridColor, Offset(curveX, gy), Offset(curveX + curveW, gy),
                                strokeWidth = 1.dp.toPx())
                        }

                        // smooth bezier through intensity points
                        val pts = intensityPoints.map { (t, inten) ->
                            val x = curveX + ((t - firstT).toFloat() / tSpan * curveW).coerceIn(0f, curveW)
                            val v = (inten / 10f).coerceIn(0f, 1f)
                            Offset(x, curveTop + curveHpx * (1f - v))
                        }
                        val line = Path()
                        val fill = Path()
                        line.moveTo(pts.first().x, pts.first().y)
                        fill.moveTo(pts.first().x, curveTop + curveHpx)
                        fill.lineTo(pts.first().x, pts.first().y)
                        for (i in 0 until pts.size - 1) {
                            val p0 = pts[i]; val p1 = pts[i + 1]
                            val midX = (p0.x + p1.x) / 2f
                            line.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                            fill.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                        }
                        fill.lineTo(pts.last().x, curveTop + curveHpx)
                        fill.close()
                        drawPath(fill, primary.copy(alpha = 0.12f))
                        drawPath(line, primary.copy(alpha = 0.3f), style = Stroke(width = 4.dp.toPx()))
                        drawPath(line, primary, style = Stroke(width = 2.dp.toPx()))
                        // sample dots
                        pts.forEach { p ->
                            drawCircle(Color.White, radius = 2.6.dp.toPx(), center = p)
                            drawCircle(primary, radius = 1.6.dp.toPx(), center = p)
                        }
                    }

                    // ---- Smart time axis ----
                    val axisY = canvasH.toPx() - 4.dp.toPx() - axisH.toPx()
                    val step = axisStepMs(rangeMs)
                    val axisStyle = TextStyle(color = onSurface.copy(alpha = 0.55f), fontSize = 9.sp)
                    var i = 0L
                    var lastLabelX = -1000f
                    while (i <= rangeMs) {
                        val px = barX + (i.toFloat() / rangeMs * barW)
                        drawLine(onSurface.copy(alpha = 0.12f), Offset(px, axisY),
                            Offset(px, axisY + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                        val lbl = axisLabel(i)
                        val m = textMeasurer.measure(lbl, style = axisStyle)
                        if (px - m.size.width / 2f > lastLabelX + 4.dp.toPx()) {
                            drawText(textLayoutResult = m,
                                topLeft = Offset(px - m.size.width / 2f, axisY + 6.dp.toPx()))
                            lastLabelX = px + m.size.width / 2f
                        }
                        i += step
                    }

                    // ---- Drag-scrub: glow line + floating time chip ----
                    if (dragFraction != null) {
                        val scrbX = barX + dragFraction!! * barW
                        drawLine(onSurface.copy(alpha = 0.14f), Offset(scrbX - 3.dp.toPx(), 0f),
                            Offset(scrbX - 3.dp.toPx(), size.height), strokeWidth = 7.dp.toPx())
                        drawLine(onSurface.copy(alpha = 0.8f), Offset(scrbX, 0f),
                            Offset(scrbX, size.height), strokeWidth = 1.5.dp.toPx())
                        val elapsedMs = (dragFraction!! * rangeMs).toLong()
                        val timeLabel = formatTimeOffset(elapsedMs)
                        val measured = textMeasurer.measure(
                            timeLabel,
                            style = TextStyle(color = onSurface, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold)
                        )
                        val chipW = measured.size.width + 16.dp.toPx()
                        val chipH = measured.size.height + 8.dp.toPx()
                        val labelX = (scrbX - chipW / 2f).coerceIn(chipW / 2f, w - chipW / 2f)
                        drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(labelX, 3.dp.toPx() + 1.5f),
                            Size(chipW, chipH), CornerRadius(7.dp.toPx(), 7.dp.toPx()))
                        drawRoundRect(surfaceColor, Offset(labelX, 3.dp.toPx()), Size(chipW, chipH),
                            CornerRadius(7.dp.toPx(), 7.dp.toPx()))
                        drawRoundRect(onSurface.copy(alpha = 0.15f), Offset(labelX, 3.dp.toPx()),
                            Size(chipW, chipH), CornerRadius(7.dp.toPx(), 7.dp.toPx()),
                            style = Stroke(1.dp.toPx()))
                        drawText(textLayoutResult = measured,
                            topLeft = Offset(labelX + (chipW - measured.size.width) / 2f,
                                3.dp.toPx() + (chipH - measured.size.height) / 2f))
                        drawCircle(onSurface.copy(alpha = 0.9f), radius = 3.5.dp.toPx(),
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
                        val markerColor = if (isDark) Color(0xFFFFD54F) else Color(0xFFB28704)
                        val ratingStyle = TextStyle(color = markerColor, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold)
                        val ratingMeasured = textMeasurer.measure(shulginRating, style = ratingStyle)
                        val cy = size.height - 8.dp.toPx()
                        drawCircle(markerColor.copy(alpha = 0.22f), radius = 10.dp.toPx(),
                            center = Offset(markerX, cy))
                        drawCircle(markerColor, radius = 4.dp.toPx(), center = Offset(markerX, cy))
                        drawCircle(markerColor.copy(alpha = 0.55f), radius = 4.dp.toPx(),
                            center = Offset(markerX, cy), style = Stroke(1.5.dp.toPx()))
                        drawText(textLayoutResult = ratingMeasured,
                            topLeft = Offset(markerX - ratingMeasured.size.width / 2f,
                                cy + 6.dp.toPx()))
                    }
                }
            }

            // Legend
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                phases.forEach { (type, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(7.dp).background(
                            (phaseColors[type] ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.85f),
                            CircleShape))
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (nonPhaseEvents.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(7.dp).background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), CircleShape))
                        Text("Events", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (intensityPoints.size >= 2) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(7.dp).background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), CircleShape))
                        Text("Intensity", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun phaseLabelFor(type: TimelineEventType): String = when (type) {
    TimelineEventType.ONSET -> "Onset"
    TimelineEventType.COMEUP -> "Comeup"
    TimelineEventType.PEAK -> "Peak"
    TimelineEventType.PLATEAU -> "Plateau"
    TimelineEventType.OFFSET -> "Offset"
    TimelineEventType.AFTERGLOW -> "Afterglow"
    else -> "Event"
}
