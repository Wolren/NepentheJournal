package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.ui.components.AppTextButton
import app.journal.util.currentTimeMillis
import androidx.compose.ui.text.font.FontWeight

@Composable
fun EditorInlineEventCard(
    event: TimelineEvent,
    repo: JournalRepository,
    onDelete: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var editLabel by remember { mutableStateOf(event.label) }
    var editBody by remember { mutableStateOf(event.body ?: "") }
    var editIntensity by remember { mutableFloatStateOf(event.intensity ?: 5f) }
    var editType by remember { mutableStateOf(event.eventType) }
    var useIntensity by remember { mutableStateOf(event.intensity != null) }

    if (editing) {
        // INLINE EDIT MODE
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            shape = RoundedCornerShape(8.dp),
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
        // VIEW MODE
        val icon = when (event.eventType) {
            TimelineEventType.ONSET -> Icons.Default.ArrowForward
            TimelineEventType.COMEUP -> Icons.Default.TrendingUp
            TimelineEventType.PEAK -> Icons.Default.Star
            TimelineEventType.OFFSET -> Icons.Default.TrendingDown
            TimelineEventType.AFTERGLOW -> Icons.Default.NightsStay
            TimelineEventType.END -> Icons.Default.Stop
            TimelineEventType.OBSERVATION -> Icons.Default.Visibility
            TimelineEventType.SAFETY_CHECK -> Icons.Default.CheckCircle
            TimelineEventType.SIDE_EFFECT -> Icons.Default.Warning
            TimelineEventType.EMERGENCY -> Icons.Default.Error
            TimelineEventType.NOTE -> Icons.Default.Notes
            TimelineEventType.PLATEAU -> Icons.Default.HorizontalRule
        }
        val accent = when (event.eventType) {
            TimelineEventType.EMERGENCY, TimelineEventType.SIDE_EFFECT -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(event.label, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                        }
                        if (!event.body.isNullOrBlank()) {
                            Text(event.body, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (event.intensity != null) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Intensity:", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Surface(shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("${event.intensity.toInt()}/10",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
