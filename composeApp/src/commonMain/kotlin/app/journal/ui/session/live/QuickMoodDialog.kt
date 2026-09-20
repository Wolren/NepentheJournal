package app.journal.ui.session.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.SentimentVerySatisfied
import androidx.compose.material.icons.filled.Sick
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.data.IJournalRepository
import app.journal.model.Session
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import app.journal.util.platformDeviceOrigin

private val MoodOptions = listOf("Calm", "Euphoric", "Anxious", "Focused", "Tired", "Awestruck", "Introspective", "Happy", "Overwhelmed", "Peaceful")

// System emoji render inconsistently across platforms (notably Windows),
// so effects use themed Material icons instead of unicode emoji.
private val EffectOptions = listOf(
    "Euphoria" to Icons.Default.SentimentVerySatisfied,
    "Stimulation" to Icons.Default.Bolt,
    "Sedation" to Icons.Default.Bedtime,
    "Introspection" to Icons.Default.Psychology,
    "Anxiety" to Icons.Default.SentimentDissatisfied,
    "Nausea" to Icons.Default.Sick,
    "Body high" to Icons.Default.Whatshot,
    "Clarity" to Icons.Default.Lightbulb
)

@Composable
internal fun QuickMoodDialog(
    session: Session,
    repo: IJournalRepository,
    substances: List<app.journal.model.Substance> = emptyList(),
    onDismiss: () -> Unit,
) {
    val now = remember(session.id) { currentTimeMillis() }
    var mood by remember { mutableStateOf("") }
    var intensity by remember { mutableStateOf(5f) }
    var note by remember { mutableStateOf("") }

    val moodOptions = MoodOptions
    val effectOptions = EffectOptions
    val activeEffects = remember { mutableStateListOf<String>() }
    val canSave = mood.isNotBlank() || note.isNotBlank() || activeEffects.isNotEmpty()

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
                    effectOptions.forEach { (name, icon) ->
                        FilterChip(selected = name in activeEffects, onClick = { if (name in activeEffects) activeEffects.remove(name) else activeEffects.add(name) },
                            leadingIcon = {
                                Icon(icon, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                            },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(28.dp))
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
                    body = combinedNote.ifBlank { null }, intensity = intensity, createdAt = now, updatedAt = now,
                    deviceOrigin = platformDeviceOrigin()))
                onDismiss()
            }, enabled = canSave) { Text("Save") }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
