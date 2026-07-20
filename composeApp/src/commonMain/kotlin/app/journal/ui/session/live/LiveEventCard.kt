package app.journal.ui.session.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Substance
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis

sealed class LiveTimelineItem {
    data class Event(val event: TimelineEvent) : LiveTimelineItem()
    data class Dosage(val dose: Dose, val substance: Substance?) : LiveTimelineItem()
}

@Composable
internal fun LiveEventCard(
    event: TimelineEvent,
    sessionStart: Long,
    repo: JournalRepository,
    onDelete: ((TimelineEvent) -> Unit)? = null
) {
    var editing by remember { mutableStateOf(false) }
    var editLabel by remember { mutableStateOf(event.label) }
    var editBody by remember { mutableStateOf(event.body ?: "") }
    var editIntensity by remember { mutableFloatStateOf(event.intensity ?: 5f) }
    var editType by remember { mutableStateOf(event.eventType) }
    var useIntensity by remember { mutableStateOf(event.intensity != null) }

    val elapsed = event.timestamp - sessionStart
    val mins = elapsed / 60000
    val secs = (elapsed % 60000) / 1000
    val timeStr = "+${mins}m${secs}s"

    if (editing) {
        Card(shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    val quickTypes = listOf(TimelineEventType.OBSERVATION to "Obs", TimelineEventType.NOTE to "Note",
                        TimelineEventType.PEAK to "Peak", TimelineEventType.OFFSET to "Off",
                        TimelineEventType.SIDE_EFFECT to "SE", TimelineEventType.EMERGENCY to "!")
                    quickTypes.forEach { (type, lbl) ->
                        FilterChip(selected = editType == type, onClick = { editType = type },
                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(26.dp))
                    }
                }
                OutlinedTextField(value = editLabel, onValueChange = { editLabel = it }, label = { Text("Label") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = editBody, onValueChange = { editBody = it }, label = { Text("Notes") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = useIntensity, onClick = { useIntensity = !useIntensity },
                        label = { Text("Intensity", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(26.dp))
                    if (useIntensity) {
                        Text("${editIntensity.toInt()}/10", style = MaterialTheme.typography.labelSmall)
                        Slider(value = editIntensity, onValueChange = { editIntensity = it }, valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextButton(onClick = { editing = false }) { Text("Cancel") }
                    AppTextButton(onClick = {
                        repo.upsertTimelineEvent(event.copy(eventType = editType, label = editLabel.ifBlank { event.label },
                            body = editBody.ifBlank { null }, intensity = if (useIntensity) editIntensity else null, updatedAt = currentTimeMillis()))
                        editing = false
                    }) { Text("Save") }
                }
            }
        }
    } else {
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Surface(modifier = Modifier.size(36.dp), shape = CircleShape,
                    color = when (event.eventType) {
                        TimelineEventType.SIDE_EFFECT, TimelineEventType.EMERGENCY -> Color(0xFFD32F2F).copy(alpha = 0.15f)
                        TimelineEventType.OBSERVATION, TimelineEventType.NOTE -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }) {
                    Box(contentAlignment = Alignment.Center) {
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
                        Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(event.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!event.body.isNullOrBlank()) Text(event.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (event.intensity != null) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Intensity:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text("${event.intensity.toInt()}/10", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onDelete != null) {
                        IconButton(onClick = { onDelete(event) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "Delete", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveDoseCard(dose: Dose, substance: Substance?, sessionStart: Long) {
    val elapsed = dose.timestamp - sessionStart
    val mins = elapsed / 60000
    val timeStr = "+${mins}m"

    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(36.dp), shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Science, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(substance?.name ?: dose.substanceId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("${dose.amount} ${dose.unit} - ${dose.routeOfAdministration}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
