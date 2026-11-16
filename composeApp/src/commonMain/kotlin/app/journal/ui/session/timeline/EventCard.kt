package app.journal.ui.session.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import app.journal.data.IJournalRepository
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import app.journal.util.formatClockTime
import app.journal.util.formatDateShort
import app.journal.ui.session.timeline.phaseColors

@Composable
internal fun EventCard(
    event: TimelineEvent,
    sessionStart: Long,
    repo: IJournalRepository,
    onDelete: ((TimelineEvent) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editLabel by remember { mutableStateOf(event.label) }
    var editBody by remember { mutableStateOf(event.body ?: "") }
    var editIntensity by remember { mutableFloatStateOf(event.intensity ?: 5f) }
    var editType by remember { mutableStateOf(event.eventType) }
    var useIntensity by remember { mutableStateOf(event.intensity != null) }

    val elapsed = event.timestamp - sessionStart
    val mins = elapsed / 60000

    val icon = eventTypeIcon(event.eventType)

    val accent = when (event.eventType) {
        TimelineEventType.EMERGENCY, TimelineEventType.SIDE_EFFECT, TimelineEventType.NOTE -> MaterialTheme.colorScheme.error
        TimelineEventType.SAFETY_CHECK, TimelineEventType.OBSERVATION -> MaterialTheme.colorScheme.primary
        else -> phaseColors[event.eventType] ?: MaterialTheme.colorScheme.primary
    }

    if (editing) {
        AppCard(colors = appCardColors(alpha = 0.8f), contentPadding = appCardPadding(dense = true), modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    val quickTypes = listOf(TimelineEventType.OBSERVATION to "Obs", TimelineEventType.NOTE to "Note", TimelineEventType.ONSET to "On", TimelineEventType.COMEUP to "Up", TimelineEventType.PEAK to "Peak", TimelineEventType.OFFSET to "Off", TimelineEventType.AFTERGLOW to "Glow", TimelineEventType.SIDE_EFFECT to "SE", TimelineEventType.EMERGENCY to "!")
                    quickTypes.forEach { (type, lbl) -> FilterChip(selected = editType == type, onClick = { editType = type }, label = { Text(lbl, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(26.dp)) }
                }
                OutlinedTextField(value = editLabel, onValueChange = { editLabel = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = editBody, onValueChange = { editBody = it }, label = { Text("Notes") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = useIntensity, onClick = { useIntensity = !useIntensity }, label = { Text("Intensity", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(26.dp))
                    if (useIntensity) { Text("${editIntensity.toInt()}/10", style = MaterialTheme.typography.labelSmall); Slider(value = editIntensity, onValueChange = { editIntensity = it }, valueRange = 1f..10f, steps = 8, modifier = Modifier.weight(1f)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextButton(onClick = { editing = false }) { Text("Cancel") }
                    AppTextButton(onClick = { repo.upsertTimelineEvent(event.copy(eventType = editType, label = editLabel.ifBlank { event.label }, body = editBody.ifBlank { null }, intensity = if (useIntensity) editIntensity else null, updatedAt = currentTimeMillis())); editing = false }) { Text("Save") }
                }
            }
        }
    } else {
        AppCard(
            border = appCardBorder() ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 12.dp)) {
                        Box(
                            Modifier.size(26.dp).background(accent.copy(alpha = 0.14f), CircleShape)
                        )
                        Surface(
                            modifier = Modifier.size(30.dp),
                            shape = CircleShape,
                            color = accent.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Row(Modifier.padding(12.dp).weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(event.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = accent.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
                                ) {
                                    Text("+${mins}m", style = MaterialTheme.typography.labelSmall, color = accent,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            if (!event.body.isNullOrBlank()) { Spacer(Modifier.height(2.dp)); Text(event.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (event.intensity != null) {
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Intensity:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) { Text("${event.intensity}/10", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)) }
                                }
                            }
                        }
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // Editing and removing sit behind the expansion: a timeline row cannot be deleted by accident.
                if (expanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${formatDateShort(event.timestamp)} ${formatClockTime(event.timestamp)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        AppTextButton(onClick = { editing = true }) { Text("Edit") }
                        if (onDelete != null) {
                            AppTextButton(onClick = { onDelete(event) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
