package app.journal.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.data.JournalRepository
import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import app.journal.ui.components.*
private data class CalendarMonth(val year: Int, val month: Month) {
    fun previous(): CalendarMonth {
        return if (month == Month.JANUARY) CalendarMonth(year - 1, Month.DECEMBER)
        else CalendarMonth(year, Month.entries[month.ordinal - 1])
    }
    fun next(): CalendarMonth {
        return if (month == Month.DECEMBER) CalendarMonth(year + 1, Month.JANUARY)
        else CalendarMonth(year, Month.entries[month.ordinal + 1])
    }
    fun daysInMonth(): Int = when (month) {
        Month.JANUARY -> 31; Month.FEBRUARY -> if (isLeapYear(year)) 29 else 28
        Month.MARCH -> 31; Month.APRIL -> 30; Month.MAY -> 31; Month.JUNE -> 30
        Month.JULY -> 31; Month.AUGUST -> 31; Month.SEPTEMBER -> 30
        Month.OCTOBER -> 31; Month.NOVEMBER -> 30; Month.DECEMBER -> 31
    }
    fun firstDayOfWeek(): Int {
        val m = if (month.ordinal + 1 <= 2) month.ordinal + 1 + 12 else month.ordinal + 1
        val y = if (month.ordinal + 1 <= 2) year - 1 else year
        val k = y % 100; val j = y / 100
        val h = (1 + (13 * (m + 1)) / 5 + k + k / 4 + j / 4 - 2 * j) % 7
        return ((h + 5) % 7) // Zeller to ISO (0=Mon)
    }
    private fun isLeapYear(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)
}

/** Precomputed cell data for the calendar Canvas grid. */
private data class CalendarCellData(
    val day: Int,
    val date: LocalDate,
    val hasSession: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    repo: JournalRepository = JournalRepository.instance,
    onBack: () -> Unit,
    onSessionTap: (String) -> Unit,
) {
    val sessions by repo.sessions.collectAsState()

    val tz = TimeZone.currentSystemDefault()
    val today = Instant.fromEpochMilliseconds(currentTimeMillis()).toLocalDateTime(tz)

    var currentMonth by remember { mutableStateOf(CalendarMonth(today.year, today.month)) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }

    // Build session date map (converts timestamps once, not twice)
    val sessionsByDate = remember(sessions) {
        sessions.groupBy { session ->
            Instant.fromEpochMilliseconds(session.startTime).toLocalDateTime(tz).date
        }
    }
    val sessionDates = remember(sessionsByDate) { sessionsByDate.keys }

    // Sessions for selected date (O(1) lookup)
    val sessionsForDate = remember(sessionsByDate, selectedDate) {
        if (selectedDate == null) emptyList()
        else (sessionsByDate[selectedDate] ?: emptyList()).sortedByDescending { it.startTime }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)
        ) {
            // Month navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    currentMonth = currentMonth.previous()
                    selectedDate = null
                }) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                Text(
                    "${currentMonth.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${currentMonth.year}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = {
                    currentMonth = currentMonth.next()
                    selectedDate = null
                }) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next month")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Day-of-week headers
            Row(modifier = Modifier.fillMaxWidth()) {
                val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                dayNames.forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Calendar grid — Canvas for perf (single composable instead of ~42 Boxes)
            val daysInMonth = currentMonth.daysInMonth()
            val firstDayOfWeek = currentMonth.firstDayOfWeek() // 0=Mon, 6=Sun
            val totalCells = firstDayOfWeek + daysInMonth
            val rowsCount = (totalCells + 6) / 7
            val cellHeight = 44.dp

            val cellData = remember(currentMonth, sessionDates, selectedDate, today.date) {
                val result = mutableListOf<CalendarCellData?>()
                for (row in 0 until rowsCount) {
                    for (col in 0..6) {
                        val cellIndex = row * 7 + col
                        val day = cellIndex - firstDayOfWeek + 1
                        if (day in 1..daysInMonth) {
                            val date = LocalDate(currentMonth.year, currentMonth.month, day)
                            result.add(CalendarCellData(
                                day = day, date = date,
                                hasSession = date in sessionDates,
                                isToday = date == today.date,
                                isSelected = date == selectedDate
                            ))
                        } else {
                            result.add(null)
                        }
                    }
                }
                result
            }

            val textMeasurer = rememberTextMeasurer()

            val primary = MaterialTheme.colorScheme.primary
            val onPrimary = MaterialTheme.colorScheme.onPrimary
            val onSurface = MaterialTheme.colorScheme.onSurface

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cellWPx = with(LocalDensity.current) { (maxWidth / 7f).toPx() }
                val cellHPx = with(LocalDensity.current) { cellHeight.toPx() }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cellHeight * rowsCount)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentMonth) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val col = (down.position.x / cellWPx).toInt().coerceIn(0, 6)
                                    val row = (down.position.y / cellHPx).toInt().coerceIn(0, rowsCount - 1)
                                    val idx = row * 7 + col
                                    if (idx < cellData.size) {
                                        cellData[idx]?.let { data ->
                                            selectedDate = data.date
                                        }
                                    }
                                }
                            }
                    ) {
                        val circleRadius = cellHPx * 0.43f
                        val dayStyle = TextStyle(
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )

                        cellData.forEachIndexed { idx, data ->
                            if (data != null) {
                                val col = idx % 7
                                val row = idx / 7
                                val cx = col * cellWPx + cellWPx / 2f
                                val cy = row * cellHPx + cellHPx / 2f

                                if (data.isSelected) {
                                    drawCircle(primary, radius = circleRadius, center = Offset(cx, cy))
                                } else if (data.isToday) {
                                    drawCircle(primary, radius = circleRadius, center = Offset(cx, cy), style = Stroke(width = 1.5f))
                                }

                                val textColor = if (data.isSelected) onPrimary else onSurface
                                val measured = textMeasurer.measure(
                                    data.day.toString(),
                                    style = dayStyle.copy(
                                        color = textColor,
                                        fontWeight = if (data.isToday || data.isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                                val textY = if (data.hasSession) cy - measured.size.height / 2f - 3f
                                    else cy - measured.size.height / 2f
                                drawText(
                                    textLayoutResult = measured,
                                    topLeft = Offset(cx - measured.size.width / 2f, textY)
                                )

                                if (data.hasSession) {
                                    val dotColor = if (data.isSelected) onPrimary else primary
                                    drawCircle(dotColor, radius = 2f, center = Offset(cx, cy + cellHPx * 0.28f))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Sessions for selected date
            if (selectedDate != null) {
                Text(
                    "${sessionsForDate.size} session${if (sessionsForDate.size != 1) "s" else ""} on ${selectedDate}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))

                if (sessionsForDate.isEmpty()) {
                    Text("No sessions on this date",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    sessionsForDate.forEach { session ->
                        Card(
                            onClick = { onSessionTap(session.id) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(session.title, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium)
                                    if (session.rating != null) {
                                        Text("Rating: ${session.rating}/10",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (session.isFavorite) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    ) {
                                        Text("Favorite", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text("Tap a date to see sessions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
