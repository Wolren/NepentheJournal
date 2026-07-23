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
import kotlin.math.roundToInt

private data class DurationPhase(
    val label: String,
    val minMinutes: Double,
    val maxMinutes: Double,
    val display: String
)

private fun parseDurationValue(value: String): Pair<Double, Double>? {
    val clean = value.trim()
    val parts = clean.split("\u2013", "-", "\u2013")
    val numPattern = Regex("""([\d.]+)""")
    val unitPattern = Regex("""(minute|minutes|min|hour|hours|hr|day|days)\b""", RegexOption.IGNORE_CASE)

    val nums = numPattern.findAll(clean).map { it.value.toDoubleOrNull() }.filterNotNull().toList()
    val unitMatch = unitPattern.find(clean)
    val unit = unitMatch?.value?.lowercase() ?: ""

    if (nums.isEmpty()) return null

    val min = nums.getOrElse(0) { 0.0 }
    val max = nums.getOrElse(1) { min }
    val multiplier = when {
        unit.startsWith("day") -> 1440.0
        unit.startsWith("hour") || unit.startsWith("hr") -> 60.0
        else -> 1.0
    }

    return Pair(min * multiplier, max * multiplier)
}

private fun parseDurationProfile(profile: Map<String, String>): List<DurationPhase> {
    val phaseOrder = listOf("onset", "comeup", "peak", "offset", "afterglow")
    val labels = mapOf(
        "onset" to "Onset", "comeup" to "Comeup", "peak" to "Peak",
        "offset" to "Offset", "afterglow" to "Afterglow", "total" to "Total"
    )

    return phaseOrder.mapNotNull { key ->
        val value = profile[key] ?: return@mapNotNull null
        val parsed = parseDurationValue(value) ?: return@mapNotNull null
        DurationPhase(labels[key] ?: key, parsed.first, parsed.second, value)
    }
}

/**
 * Convert a DoseWikiStage to minutes. Returns null if stage is null or has no data.
 */
private fun stageToMinutes(stage: DoseWikiStage?): Pair<Double, Double>? {
    if (stage == null) return null
    val minRaw = stage.min ?: return null
    val maxRaw = stage.max ?: minRaw
    val multiplier = when (stage.unit?.lowercase()) {
        "hours", "hour", "hr" -> 60.0
        "days", "day" -> 1440.0
        else -> 1.0
    }
    return Pair(minRaw * multiplier, maxRaw * multiplier)
}

/**
 * Format a DoseWikiStage as a human-readable display string.
 */
private fun formatStage(stage: DoseWikiStage?): String {
    if (stage == null) return ""
    val min = stage.min ?: return ""
    val max = stage.max ?: return "$min ${stage.unit ?: "min"}"
    val unit = stage.unit ?: "min"
    return if (min == max) "$min $unit" else "$min - $max $unit"
}

/**
 * Stage key to phase label mapping.
 */
private val stageLabelMap = mapOf(
    "onset" to "Onset",
    "come_up" to "Comeup",
    "peak" to "Peak",
    "offset" to "Offset",
    "after_effects" to "Afterglow",
    "total_duration" to "Total",
)

/**
 * Parse phases from DoseWiki structured duration data.
 * Uses the first route's stages. The stage names map directly to phase labels.
 */
private fun parseDoseWikiDuration(duration: DoseWikiDuration): List<DurationPhase> {
    val stages = duration.routes?.firstOrNull()?.stages ?: return emptyList()

    val stageKeys = listOf("onset", "come_up", "peak", "offset", "after_effects")

    val phaseResults = mutableListOf<DurationPhase>()

    // Return a helper to get stage by key
    fun stageForKey(key: String): DoseWikiStage? = when (key) {
        "onset" -> stages.onset
        "come_up" -> stages.come_up
        "peak" -> stages.peak
        "offset" -> stages.offset
        "after_effects" -> stages.after_effects
        else -> null
    }

    for (key in stageKeys) {
        val stage = stageForKey(key)
        val parsed = stageToMinutes(stage) ?: continue
        val label = stageLabelMap[key] ?: key
        phaseResults.add(
            DurationPhase(
                label = label,
                minMinutes = parsed.first,
                maxMinutes = parsed.second,
                display = formatStage(stage)
            )
        )
    }

    return phaseResults
}

/**
 * Get the total duration stage from DoseWiki data.
 */
private fun getDoseWikiTotal(duration: DoseWikiDuration): Triple<Double?, Double?, String>? {
    val totalStage = duration.routes?.firstOrNull()?.stages?.total_duration ?: return null
    val parsed = stageToMinutes(totalStage) ?: return null
    return Triple(parsed.first, parsed.second, formatStage(totalStage))
}

@Composable
internal fun DurationTimelineSection(
    profile: Map<String, String>,
    doseWikiDuration: DoseWikiDuration? = null
) {
    val doseWikiPhases = remember(doseWikiDuration) {
        doseWikiDuration?.let { parseDoseWikiDuration(it) }
    }
    val phases = remember(doseWikiDuration, profile) {
        doseWikiPhases ?: parseDurationProfile(profile)
    }

    val totalMax: Double
    val totalRaw: String?
    val totalMinStr: String
    val totalMaxStr: String

    if (doseWikiDuration != null) {
        val total = getDoseWikiTotal(doseWikiDuration)
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

    val phaseColors = mapOf(
        "Onset" to Color(0xFF66BB6A),
        "Comeup" to Color(0xFF42A5F5),
        "Peak" to Color(0xFFEF5350),
        "Offset" to Color(0xFFFFA726),
        "Afterglow" to Color(0xFFAB47BC)
    )

    val timelineSpan = maxOf(totalMax, barPhases.maxOfOrNull { it.maxMinutes } ?: 1.0)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Duration", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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

            // Total — visual bar
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
