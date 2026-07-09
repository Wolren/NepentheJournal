package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Session
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun SessionListScreen() {
    val repo = remember { JournalRepository.instance }
    val sessions by repo.sessions.collectAsState(initial = emptyList())
    var filterTag by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    val allTags = remember(sessions) {
        sessions.flatMap { it.tags }.distinct().sorted()
    }

    val filteredSessions = remember(sessions, filterTag) {
        if (filterTag == null) sessions.sortedByDescending { it.startTime }
        else sessions.filter { filterTag in it.tags }.sortedByDescending { it.startTime }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sessions",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${filteredSessions.size} total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // Tag filter chips
            if (allTags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = filterTag == null,
                        onClick = { filterTag = null },
                        label = { Text("All") }
                    )
                    allTags.take(8).forEach { tag ->
                        FilterChip(
                            selected = filterTag == tag,
                            onClick = { filterTag = if (filterTag == tag) null else tag },
                            label = { Text(tag) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Session list
            if (filteredSessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (filterTag != null) "No sessions with tag: $filterTag"
                               else "No sessions yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            onDelete = { showDeleteConfirm = session.id }
                        )
                    }
                }
            }
        }

        // FAB
        FloatingActionButton(
            onClick = { /* TODO: navigate to SessionEditorScreen */ },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "New Session")
        }
    }

    // Delete confirmation
    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete session?") },
            text = { Text("This will also remove all doses, notes, and timeline events for this session.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm?.let { repo.deleteSession(it) }
                    showDeleteConfirm = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SessionCard(
    session: Session,
    onDelete: () -> Unit
) {
    val repo = remember { JournalRepository.instance }
    val doses = remember(session.id) { repo.dosesForSession(session.id) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(2.dp))

                    val instant = Instant.fromEpochMilliseconds(session.startTime)
                    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
                    val monthNum = local.month.ordinal + 1
                    val dayNum = local.day
                    val dateStr = "${local.year}-${monthNum.toString().padStart(2, '0')}-${dayNum.toString().padStart(2, '0')}"
                    val timeStr = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
                    Text(
                        text = "$dateStr  $timeStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (session.rating != null) {
                        Text(
                            text = "${session.rating}/10",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Substances
            if (doses.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    doses.forEach { dose ->
                        val substance = repo.getSubstance(dose.substanceId)
                        if (substance != null) {
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "${substance.name} ${dose.amount}${dose.unit}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Tags
            if (session.tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    session.tags.take(4).forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    if (session.tags.size > 4) {
                        Text("+${session.tags.size - 4}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }

            // Intention / outcome preview
            if (!session.intention.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Intention: ${session.intention}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Duration
            if (session.endTime != null) {
                val durationHours = (session.endTime - session.startTime) / 3600000f
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Duration: ${"%.1f".format(durationHours)}h",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
