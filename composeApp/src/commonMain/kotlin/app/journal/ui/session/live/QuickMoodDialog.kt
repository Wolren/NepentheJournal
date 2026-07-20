package app.journal.ui.session.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis

@Composable
internal fun QuickMoodDialog(
    session: Session,
    repo: JournalRepository,
    substances: List<app.journal.model.Substance> = emptyList(),
    onDismiss: () -> Unit,
) {
    val now = currentTimeMillis()
    var mood by remember { mutableStateOf("") }
    var intensity by remember { mutableStateOf(5f) }
    var note by remember { mutableStateOf("") }

    val moodOptions = listOf("Calm", "Euphoric", "Anxious", "Focused", "Tired", "Awestruck", "Introspective", "Happy", "Overwhelmed", "Peaceful")
    val effectOptions = listOf("Euphoria" to "\uD83D\uDE0A", "Stimulation" to "\u26A1", "Sedation" to "\uD83D\uDE0C",
        "Introspection" to "\uD83E\uDDE0", "Anxiety" to "\uD83D\uDE30", "Nausea" to "\uD83E\uDD22", "Body high" to "\uD83D\uDD25", "Clarity" to "\uD83D\uDCA1")
    val activeEffects = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How are you feeling?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Mood", style = MaterialTheme.typography.labelMedium)
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    moodOptions.forEach { opt ->
                        FilterChip(selected = mood == opt, onClick = { mood = if (mood == opt) "" else opt },
                            label = { Text(opt, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    }
                }
                Text("Intensity: ${intensity.toInt()}/10", style = MaterialTheme.typography.labelMedium)
                Slider(value = intensity, onValueChange = { intensity = it }, valueRange = 1f..10f, steps = 8)
                Text("Effects present", style = MaterialTheme.typography.labelMedium)
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    effectOptions.forEach { (name, emoji) ->
                        FilterChip(selected = name in activeEffects, onClick = { if (name in activeEffects) activeEffects.remove(name) else activeEffects.add(name) },
                            label = { Text("$emoji $name", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Notes (optional)") },
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            AppTextButton(onClick = {
                val label = if (mood.isNotBlank()) mood else "Check-in"
                val effectsBody = if (activeEffects.isNotEmpty()) "Effects: ${activeEffects.joinToString(", ")}" else null
                val combinedNote = listOfNotNull(note.ifBlank { null }, effectsBody).joinToString("\n")
                repo.upsertTimelineEvent(TimelineEvent(id = "event:mood:${now}_${session.id}", sessionId = session.id,
                    timestamp = now, eventType = TimelineEventType.OBSERVATION, label = label,
                    body = note.ifBlank { effectsBody } ?: null, intensity = intensity, createdAt = now, updatedAt = now,
                    deviceOrigin = "desktop"))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
