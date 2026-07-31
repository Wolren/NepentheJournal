package app.journal.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.ui.theme.ThemeManager
import kotlinx.datetime.*

@Composable
fun ActivityHeatmap(
    sessionDates: List<Long>,
    nowMillis: Long,
    onCellClick: (LocalDate, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val cellSize = 13.dp
    val gap = 2.dp
    val rows = 7

    val tz = TimeZone.currentSystemDefault()
    val now = Instant.fromEpochMilliseconds(nowMillis)
    val today = now.toLocalDateTime(tz).date

    val dayCounts = remember(sessionDates) {
        val map = mutableMapOf<LocalDate, Int>()
        sessionDates.forEach { epoch ->
            val date = Instant.fromEpochMilliseconds(epoch)
                .toLocalDateTime(TimeZone.currentSystemDefault()).date
            map[date] = (map[date] ?: 0) + 1
        }
        map
    }

    // Theme-aware palette: dark mode uses dark cells on dark cards; light mode
    // uses a subtle gray + lighter greens so the board reads as a light grid
    // instead of a black slab (light-theme audit 2026-07-31).
    val isDark = ThemeManager.instance.isDarkTheme()
    val inactive = if (isDark) Color(0xFF3A3A3A) else Color(0xFFE4E4E4)
    val activeColors = if (isDark) listOf(
        Color(0xFF1B4A1B),
        Color(0xFF2D6A2D),
        Color(0xFF3D8A3D),
        Color(0xFF4CAF50)
    ) else listOf(
        Color(0xFFB9DFB9),
        Color(0xFF8FCF8F),
        Color(0xFF66BB6A),
        Color(0xFF43A047)
    )

    fun colorFor(count: Int): Color = when {
        count <= 0 -> inactive
        count == 1 -> activeColors[0]
        count <= 3 -> activeColors[1]
        count <= 6 -> activeColors[2]
        else -> activeColors[3]
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Activity",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(10.dp))

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth()
        ) {
            val availWidth = maxWidth
            val step = cellSize + gap
            val cols = ((availWidth - 4.dp) / step).toInt().coerceIn(5, 100)
            val firstVisibleDate = today.minus(cols * 7 - 1, DateTimeUnit.DAY)
            
            val cellSizePx = with(LocalDensity.current) { cellSize.toPx() }
            val gapPx = with(LocalDensity.current) { gap.toPx() }
            val stepPx = cellSizePx + gapPx
            val availWidthPx = with(LocalDensity.current) { availWidth.toPx() }
            val gridWidthPx = stepPx * cols
            val leftoverPx = (availWidthPx - gridWidthPx).coerceAtLeast(0f)
            
            val cells = remember(firstVisibleDate, cols, dayCounts, today) {
                buildList {
                    for (col in 0 until cols) {
                        for (row in 0 until 7) {
                            val date = firstVisibleDate.plus(col * 7 + row, DateTimeUnit.DAY)
                            val isFuture = date > today
                            val count = if (isFuture) 0 else (dayCounts[date] ?: 0)
                            add(Triple(date, count, isFuture))
                        }
                    }
                }
            }
            
            val heightDp = cellSize * 7 + gap * 6
            
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heightDp)
                        .padding(start = if (leftoverPx > 0f) with(LocalDensity.current) { (leftoverPx / 2f).toDp() } else 0.dp)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(cells) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val leftPad = if (leftoverPx > 0f) leftoverPx / 2f else 0f
                                    val x = down.position.x - leftPad
                                    val y = down.position.y
                                    val col = (x / stepPx).toInt()
                                    val row = (y / stepPx).toInt()
                                    if (col in 0 until cols && row in 0..6) {
                                        val idx = col * 7 + row
                                        if (idx < cells.size) {
                                            val (date, count, isFuture) = cells[idx]
                                            if (!isFuture) onCellClick(date, count)
                                        }
                                    }
                                }
                            }
                    ) {
                        val leftPad = if (leftoverPx > 0f) leftoverPx / 2f else 0f
                        var idx = 0
                        for (col in 0 until cols) {
                            for (row in 0 until 7) {
                                val (date, count, isFuture) = cells[idx]
                                val x = leftPad + col * stepPx
                                val y = row * stepPx
                                if (!isFuture) {
                                    val bg = colorFor(count)
                                    drawRoundRect(bg, Offset(x, y), Size(cellSizePx, cellSizePx), CornerRadius(2f, 2f))
                                    if (date == today) {
                                        drawRoundRect(
                                            if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF2E7D32).copy(alpha = 0.55f),
                                            Offset(x, y), Size(cellSizePx, cellSizePx),
                                            CornerRadius(2f, 2f),
                                            style = Stroke(width = 1.5f)
                                        )
                                    }
                                }
                                idx++
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${firstVisibleDate.month.name.lowercase().take(3)} ${firstVisibleDate.day}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        "${today.month.name.lowercase().take(3)} ${today.day}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
