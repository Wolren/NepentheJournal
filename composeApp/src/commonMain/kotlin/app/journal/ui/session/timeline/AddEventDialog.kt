package app.journal.ui.session.timeline

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddEventDialog(
    session: Session,
    repo: JournalRepository,
    editEvent: TimelineEvent? = null,
    onDismiss: () -> Unit
) {
    val now = currentTimeMillis()
    val eventTypes = listOf(
        TimelineEventType.OBSERVATION to "Observation", TimelineEventType.NOTE to "Note",
        TimelineEventType.ONSET to "Onset", TimelineEventType.COMEUP to "Comeup",
        TimelineEventType.PEAK to "Peak", TimelineEventType.OFFSET to "Offset",
        TimelineEventType.AFTERGLOW to "Afterglow", TimelineEventType.SAFETY_CHECK to "Safety check",
        TimelineEventType.SIDE_EFFECT to "Side effect", TimelineEventType.EMERGENCY to "Emergency"
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
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(value = eventTypes[selectedIndex].second, onValueChange = {}, readOnly = true,
                        label = { Text("Event type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        eventTypes.forEachIndexed { i, (_, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { selectedIndex = i; expanded = false })
                        }
                    }
                }
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") },
                    placeholder = { Text("e.g., Strong visuals") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = useIntensity, onCheckedChange = { useIntensity = it })
                    Column {
                        Text("Intensity", style = MaterialTheme.typography.bodyMedium)
                        if (useIntensity) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Slider(value = intensity, onValueChange = { intensity = it }, valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f))
                                Text("${intensity.toInt()}/10", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val offsetMin = ((timestamp - session.startTime) / 60000).toInt()
                    Text("Time: +${offsetMin}m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        timestamp = (timestamp - 300000L).coerceAtLeast(session.startTime)
                    }) { Text("-5m") }
                    OutlinedButton(onClick = {
                        timestamp = (timestamp + 300000L).coerceAtMost(now)
                    }) { Text("+5m") }
                }
            }
        },
        confirmButton = {
            AppTextButton(onClick = {
                val event = (editEvent ?: TimelineEvent(
                    id = "event:${now}_${session.id}", sessionId = session.id, timestamp = timestamp, eventType = TimelineEventType.OBSERVATION, label = "New event",
                    createdAt = now, updatedAt = now, deviceOrigin = editEvent?.deviceOrigin ?: "desktop"
                )).copy(
                    eventType = eventTypes[selectedIndex].first, label = label.ifBlank { eventTypes[selectedIndex].second },
                    body = notes.ifBlank { null }, intensity = if (useIntensity) intensity else null,
                    timestamp = timestamp, updatedAt = now
                )
                repo.upsertTimelineEvent(event)
                onDismiss()
            }) { Text(if (editEvent != null) "Save" else "Add") }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
