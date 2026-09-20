package app.journal.ui.session.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.data.IJournalRepository
import app.journal.model.Session
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.util.currentTimeMillis

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhaseChip(label: String, eventType: TimelineEventType, session: Session, repo: IJournalRepository) {
    val phaseColor = when (eventType) {
        TimelineEventType.ONSET -> Color(0xFF80CBC4)
        TimelineEventType.COMEUP -> Color(0xFFA5D6A7)
        TimelineEventType.PEAK -> Color(0xFFFFAB91)
        TimelineEventType.OFFSET -> Color(0xFFFFF59D)
        else -> MaterialTheme.colorScheme.primary
    }
    val chipColor = phaseColor.copy(alpha = 0.15f)
    val textColor = phaseColor.copy(alpha = 0.9f)
    var showNoteDialog by remember { mutableStateOf(false) }

    fun logPhase(note: String?) {
        val now = currentTimeMillis()
        repo.upsertTimelineEvent(TimelineEvent(
            id = "event:phase:${now}_${session.id}",
            sessionId = session.id, timestamp = now,
            eventType = eventType, label = label,
            body = note?.ifBlank { null },
            createdAt = now, updatedAt = now, deviceOrigin = "desktop",
        ))
    }

    if (showNoteDialog) {
        var noteText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text("$label note") },
            text = {
                OutlinedTextField(value = noteText, onValueChange = { noteText = it },
                    label = { Text("What is happening in this stage?") },
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { logPhase(noteText); showNoteDialog = false }) { Text("Log") }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = chipColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = { logPhase(null) },
                onLongClick = { showNoteDialog = true },
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium, color = textColor,
                modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}
