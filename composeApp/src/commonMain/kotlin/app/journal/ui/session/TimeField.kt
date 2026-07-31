package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.journal.log.Log
import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Month names for date formatting. */
internal val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
)
internal val SHORT_MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

/**
 * Date/time field with visual picker. Accepts "17 Jan 2026", "2026-01-17",
 * "17/01/2026", "01/17/2026" formats. Click the calendar icon for a date
 * picker dialog. Click the clock icon for time selection.
 * Reused by SessionEditorScreen and any other screen needing time input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    epochMs: Long?,
    onChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = false
) {
    val tz = TimeZone.currentSystemDefault()
    val local = remember(epochMs) {
        if (epochMs != null && epochMs > 1000L)
            Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
        else null
    }
    var dateStr by remember(local) {
        mutableStateOf(local?.let {
            "${it.dayOfMonth} ${SHORT_MONTH_NAMES[it.month.ordinal]} ${it.year}"
        } ?: "")
    }
    var timeStr by remember(local) {
        mutableStateOf(local?.let {
            "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        } ?: "")
    }
    var dateError by remember { mutableStateOf(false) }
    var timeError by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Try multiple date formats
    fun parseDate(input: String): Triple<Int, Int, Int>? { // year, month, day
        val trimmed = input.trim()

        // "17 January 2026" or "17 Jan 2026"
        val namedMonth = Regex("""(\d{1,2})\s+([A-Za-z]+)\s+(\d{4})""").find(trimmed)
        if (namedMonth != null) {
            val (_, day, monthName, year) = namedMonth.groupValues
            val mi = MONTH_NAMES.indexOfFirst { it.startsWith(monthName, ignoreCase = true) }
            val miShort = SHORT_MONTH_NAMES.indexOfFirst { it.equals(monthName, ignoreCase = true) }
            val idx = if (mi >= 0) mi else miShort
            if (idx >= 0) return Triple(year.toInt(), idx, day.toInt())
        }

        // "2026-01-17" (ISO)
        val iso = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(trimmed)
        if (iso != null) {
            val (_, y, m, d) = iso.groupValues
            return Triple(y.toInt(), m.toInt() - 1, d.toInt())
        }

        // "17/01/2026" or "01/17/2026" (DD/MM or MM/DD)
        val slash = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""").find(trimmed)
        if (slash != null) {
            val (_, a, b, y) = slash.groupValues
            val ia = a.toInt(); val ib = b.toInt()
            return if (ia > 12) Triple(y.toInt(), ib - 1, ia) // DD/MM
            else Triple(y.toInt(), ia - 1, ib)                // MM/DD
        }

        return null
    }

    fun tryParse() {
        val parsed = parseDate(dateStr)
        val timeMatch = Regex("""(\d{1,2}):(\d{2})""").find(timeStr.trim())

        dateError = dateStr.isNotBlank() && parsed == null
        timeError = timeStr.isNotBlank() && timeMatch == null

        if (parsed != null && timeMatch != null) {
            val (year, monthIdx, day) = parsed
            val (_, hourStr, minStr) = timeMatch.groupValues
            try {
                val dt = LocalDateTime(year, Month.entries[monthIdx], day,
                    hourStr.toInt(), minStr.toInt())
                onChanged(dt.toInstant(tz).toEpochMilliseconds())
            } catch (e: Exception) {
                Log.withTag("TimeField").w(e) { "Parse failed" }; dateError = true
            }
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = local?.let { dt ->
                LocalDateTime(dt.year, dt.month, dt.dayOfMonth, 0, 0)
                    .toInstant(TimeZone.of("UTC")).toEpochMilliseconds()
            } ?: currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.of("UTC"))
                        dateStr = "${picked.dayOfMonth} ${SHORT_MONTH_NAMES[picked.month.ordinal]} ${picked.year}"
                        tryParse()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Time picker dialog
    if (showTimePicker) {
        val currentLocal = local ?: Instant.fromEpochMilliseconds(currentTimeMillis()).toLocalDateTime(tz)
        val timePickerState = rememberTimePickerState(
            initialHour = currentLocal.hour,
            initialMinute = currentLocal.minute,
            is24Hour = true
        )
        Dialog(
            onDismissRequest = { showTimePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(min = 320.dp, max = 360.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text("Select time", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    TimePicker(state = timePickerState, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            timeStr = "${timePickerState.hour.toString().padStart(2, '0')}:${timePickerState.minute.toString().padStart(2, '0')}"
                            tryParse()
                            showTimePicker = false
                        }) { Text("OK") }
                    }
                }
            }
        }
    }

    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        // Date row: text field + calendar button
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = dateStr,
                onValueChange = { dateStr = it; tryParse() },
                placeholder = { Text("Date") },
                singleLine = true,
                isError = dateError,
                supportingText = if (dateError) {{ Text("Invalid") }} else null,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall
            )
            IconButton(onClick = { showDatePicker = true },
                modifier = Modifier.padding(top = if (dateError) 24.dp else 0.dp)) {
                Icon(Icons.Default.CalendarMonth, contentDescription = "Pick date",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(2.dp))
        // Time row: text field + clock button
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = timeStr,
                onValueChange = { timeStr = it; tryParse() },
                placeholder = { Text("HH:MM") },
                singleLine = true,
                isError = timeError,
                supportingText = if (timeError) {{ Text("Invalid") }} else null,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall
            )
            IconButton(onClick = { showTimePicker = true },
                modifier = Modifier.padding(top = if (timeError) 24.dp else 0.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = "Pick time",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (clearable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { onChanged(0L) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Clear end time", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
