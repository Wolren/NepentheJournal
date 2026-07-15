package app.journal.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    val inactive = Color(0xFF3A3A3A)
    val activeColors = listOf(
        Color(0xFF1B4A1B),
        Color(0xFF2D6A2D),
        Color(0xFF3D8A3D),
        Color(0xFF4CAF50)
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
            val gridWidth = step * cols
            val leftover = availWidth - gridWidth

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(start = if (leftover > 0.dp) leftover / 2 else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    for (col in 0 until cols) {
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            for (row in 0 until rows) {
                                val date = firstVisibleDate.plus(col * 7 + row, DateTimeUnit.DAY)
                                val isFuture = date > today
                                val count = if (isFuture) 0 else (dayCounts[date] ?: 0)
                                val bg = if (isFuture) Color.Transparent else colorFor(count)
                                val isToday = date == today

                                val mod = Modifier
                                    .size(cellSize)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(bg)
                                    .then(
                                        if (isToday) Modifier.border(
                                            1.5.dp, Color.White.copy(alpha = 0.7f),
                                            RoundedCornerShape(2.dp)
                                        ) else Modifier
                                    )
                                    .then(
                                        if (!isFuture) Modifier.clickable {
                                            onCellClick(date, count)
                                        } else Modifier
                                    )

                                Box(mod)
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
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        "${today.month.name.lowercase().take(3)} ${today.day}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
