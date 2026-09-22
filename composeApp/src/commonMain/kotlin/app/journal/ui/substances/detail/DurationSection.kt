package app.journal.ui.substances.detail

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.model.DoseWikiDuration
import app.journal.model.DoseWikiStage
import app.journal.util.DurationPhase
import app.journal.util.getDoseWikiTotal
import app.journal.util.parseDurationProfile
import app.journal.util.parseDurationValue
import app.journal.util.parseDoseWikiDuration
import app.journal.ui.components.PhaseColors
import kotlin.math.roundToInt

@Composable
internal fun DurationTimelineSection(
    profile: Map<String, String>,
    doseWikiDuration: DoseWikiDuration? = null
) {
    // Route selector when DoseWiki carries stages for several routes.
    var routeIndex by remember(doseWikiDuration) { mutableStateOf(0) }
    val dwRoutes = doseWikiDuration?.routes.orEmpty()
    val validRouteIndex = routeIndex.coerceIn(0, maxOf(dwRoutes.size - 1, 0))
    val doseWikiPhases = remember(doseWikiDuration, validRouteIndex) {
        doseWikiDuration?.let { parseDoseWikiDuration(it, validRouteIndex) }
    }
    val phases = remember(doseWikiDuration, validRouteIndex, profile) {
        val dw = doseWikiPhases?.takeIf { it.isNotEmpty() }
        dw ?: parseDurationProfile(profile)
    }

    val totalMax: Double
    val totalRaw: String?
    val totalMinStr: String
    val totalMaxStr: String

    if (doseWikiDuration != null) {
        val total = getDoseWikiTotal(doseWikiDuration, validRouteIndex)
        if (total != null) {
            totalMax = total.second ?: total.first ?: phases.maxOfOrNull { it.maxMinutes } ?: return
            totalRaw = total.third
            totalMinStr = total.first?.let {
                val h = it / 60.0
                if (h >= 1) "${(h * 10).roundToInt() / 10.0} hr" else "${it.toInt()} min"
            } ?: ""
            totalMaxStr = total.second?.let {
                val h = it / 60.0
                if (h >= 1) "${(h * 10).roundToInt() / 10.0} hr" else "${it.toInt()} min"
            } ?: ""
        } else {
            totalMax = phases.maxOfOrNull { it.maxMinutes } ?: return
            totalRaw = null
            totalMinStr = ""
            totalMaxStr = ""
        }
    } else {
        val totalRawFromProfile = profile["total"]
        val totalParsed = totalRawFromProfile?.let { parseDurationValue(it) }
        totalRaw = totalRawFromProfile
        totalMax = totalParsed?.second ?: phases.maxOfOrNull { it.maxMinutes } ?: return
        totalMinStr = totalParsed?.first?.let {
            val h = it / 60.0
            if (h >= 1) "${(h * 10).roundToInt() / 10.0} hr" else "${it.toInt()} min"
        } ?: ""
        totalMaxStr = totalParsed?.second?.let {
            val h = it / 60.0
            if (h >= 1) "${(h * 10).roundToInt() / 10.0} hr" else "${it.toInt()} min"
        } ?: ""
    }

    if (phases.isEmpty()) return

    val barPhases = phases.filter { it.label != "Afterglow" }
    val afterglow = phases.find { it.label == "Afterglow" }

    // Canonical phase palette; this section used to carry its own value set.
    val phaseColors = PhaseColors.byLabel

    val timelineSpan = maxOf(totalMax, barPhases.maxOfOrNull { it.maxMinutes } ?: 1.0)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Duration", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            // Per-route selector when several DoseWiki routes carry stages.
            if (dwRoutes.size > 1) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    dwRoutes.forEachIndexed { idx, route ->
                        val selected = idx == validRouteIndex
                        FilterChip(
                            selected = selected,
                            onClick = { routeIndex = idx },
                            label = {
                                Text(
                                    route.route?.replaceFirstChar { it.uppercase() }
                                        ?: "Route ${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 2D intensity-over-time curve (ggplot2 style)
            val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
            val axisLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)

            data class CurvePt(val x: Float, val y: Float, val label: String, val timeLabel: String, val color: Color)
            val curvePoints = remember(barPhases, timelineSpan) {
                val pts = mutableListOf<CurvePt>()
                var acc = 0f
                val rawFracs = barPhases.map {
                    (it.maxMinutes / timelineSpan).toFloat().coerceAtLeast(0.01f)
                }
                val scale = 1f / rawFracs.sum()
                barPhases.forEachIndexed { idx, phase ->
                    val frac = rawFracs[idx] * scale
                    val x = acc + frac / 2f
                    val y = when (phase.label) {
                        "Onset" -> 0.25f; "Comeup" -> 0.75f; "Peak" -> 1f; "Offset" -> 0.15f; else -> 0.5f
                    }
                    val time = phase.display.split("\u2013", "-", "\u2014").firstOrNull()?.trim() ?: ""
                    pts.add(CurvePt(x, y, phase.label, time, phaseColors[phase.label] ?: Color.Gray))
                    acc += frac
                }
                if (pts.any { it.label == "Offset" }) {
                    pts.add(CurvePt(acc, 0f, "", "", Color.Transparent))
                }
                pts.toList()
            }

            val curveColor = remember(curvePoints) {
                curvePoints.findLast { it.label.isNotEmpty() }?.color ?: Color.Gray
            }

            Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val lPad = 28.dp.toPx()
                    val rPad = 4.dp.toPx()
                    val tPad = 4.dp.toPx()
                    val bPad = 4.dp.toPx()
                    val plotW = w - lPad - rPad
                    val plotH = h - tPad - bPad

                    for (i in 0..4) {
                        val y = tPad + plotH * (1f - i / 4f)
                        drawLine(gridColor, Offset(lPad, y), Offset(w - rPad, y), strokeWidth = 0.5f)
                    }

                    if (curvePoints.size >= 2) {
                        val path = Path()
                        val firstX = lPad + curvePoints[0].x * plotW
                        val firstY = tPad + plotH * (1f - curvePoints[0].y)
                        path.moveTo(firstX, firstY)

                        for (i in 0 until curvePoints.size - 1) {
                            val p0 = curvePoints[i]; val p1 = curvePoints[i + 1]
                            val x0 = lPad + p0.x * plotW; val y0 = tPad + plotH * (1f - p0.y)
                            val x1 = lPad + p1.x * plotW; val y1 = tPad + plotH * (1f - p1.y)
                            val midX = (x0 + x1) / 2f
                            val cp2y = if ((p0.y - p1.y) > 0.5f) {
                                y0 - (y0 - y1) * 0.4f
                            } else y1
                            path.cubicTo(midX, y0, midX, cp2y, x1, y1)
                        }

                        val fill = Path().apply {
                            addPath(path)
                            val last = curvePoints.last()
                            lineTo(lPad + last.x * plotW, tPad + plotH)
                            lineTo(firstX, tPad + plotH)
                            close()
                        }
                        drawPath(fill, curveColor.copy(alpha = 0.10f))
                        drawPath(path, curveColor.copy(alpha = 0.3f), style = Stroke(width = 4f))
                        drawPath(path, curveColor, style = Stroke(width = 2.5f))
                    }

                    curvePoints.forEach { pt ->
                        if (pt.label.isEmpty()) return@forEach
                        val cx = lPad + pt.x * plotW
                        val cy = tPad + plotH * (1f - pt.y)
                        drawLine(gridColor.copy(alpha = 0.15f), Offset(cx, cy), Offset(cx, tPad + plotH), strokeWidth = 0.5f)
                        drawCircle(Color.White, radius = 5f, center = Offset(cx, cy))
                        drawCircle(pt.color, radius = 3.5f, center = Offset(cx, cy))
                    }

                    drawLine(axisLineColor,
                        Offset(lPad, tPad + plotH), Offset(w - rPad, tPad + plotH), strokeWidth = 1f)
                }

                Column(
                    modifier = Modifier.fillMaxHeight().padding(start = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("100%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("75%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("50%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("25%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("0%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }

                Text("↑ Intensity",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.TopEnd))
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                barPhases.forEach { phase ->
                    val color = phaseColors[phase.label] ?: Color.Gray
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = color.copy(alpha = 0.10f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
                        ) {
                            Text(phase.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = color, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(phase.display,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }

            if (afterglow != null) {
                val agColor = phaseColors["Afterglow"] ?: Color.Gray
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = agColor.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Afterglow", style = MaterialTheme.typography.labelSmall,
                            color = agColor, fontWeight = FontWeight.SemiBold)
                        Text(afterglow.display, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Total - visual bar
            if (totalRaw != null) {
                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Total duration", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Box(modifier = Modifier.weight(1f).height(8.dp)) {
                            val totalColor = MaterialTheme.colorScheme.primary
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = totalColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {}
                            Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                                val totalMinVal = if (doseWikiDuration != null) {
                                    getDoseWikiTotal(doseWikiDuration)?.first
                                } else {
                                    profile["total"]?.let { parseDurationValue(it) }?.first
                                }
                                val totalMaxVal = if (doseWikiDuration != null) {
                                    getDoseWikiTotal(doseWikiDuration)?.second
                                } else {
                                    profile["total"]?.let { parseDurationValue(it) }?.second
                                }
                                val frac = if (totalMinVal != null && totalMaxVal != null && totalMaxVal > 0)
                                    (totalMinVal / totalMaxVal).toFloat().coerceIn(0.1f, 1f)
                                else 0.3f
                                drawRoundRect(
                                    totalColor.copy(alpha = 0.5f),
                                    size = Size(size.width * frac, size.height),
                                    cornerRadius = CornerRadius(4f, 4f)
                                )
                            }
                        }

                        Text(
                            if (totalMinStr == totalMaxStr) totalMaxStr else "$totalMinStr - $totalMaxStr",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
