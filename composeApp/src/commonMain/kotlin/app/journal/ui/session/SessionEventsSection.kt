package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.ui.components.AppTextButton
import app.journal.ui.components.AppTonalButton
import app.journal.util.currentTimeMillis

@Composable
fun SessionEventsSection(
    sessionEvents: List<TimelineEvent>,
    sessionId: String,
    repo: JournalRepository,
    onEventAdded: (TimelineEvent) -> Unit,
    onEventDeleted: (String) -> Unit
) {
    // ── Timeline Events section ──
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Timeline Events",
            style = MaterialTheme.typography.titleMedium)
        AppTonalButton(onClick = {
            val now = currentTimeMillis()
            val newEvent = TimelineEvent(
                id = "evt:${now}",
                sessionId = sessionId,
                eventType = TimelineEventType.OBSERVATION,
                label = "Check-in",
                timestamp = now,
                body = "Quick event",
                createdAt = now, updatedAt = now,
                deviceOrigin = "desktop"
            )
            // Persist immediately so it appears with a real id
            repo.upsertTimelineEvent(newEvent)
            onEventAdded(newEvent)
        }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add Event")
        }
    }

    // Inline-editable event cards
    Column {
        sessionEvents.sortedBy { it.timestamp }.forEach { event ->
            key(event.id) {
                EditorInlineEventCard(
                    event = event,
                    repo = repo,
                    onDelete = { onEventDeleted(event.id) }
                )
            }
        }
    }
}
