package app.journal.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Session
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class DashboardStats(
    val totalSessions: Int,
    val totalSubstances: Int,
    val pendingConflicts: Int,
    val recentSessions: List<Session>
)

@Composable
fun DashboardScreen() {
    val repo = remember { JournalRepository.instance }
    val sessions by repo.sessions.collectAsState()
    val substances by repo.substances.collectAsState()
    val notes by repo.notes.collectAsState()

    val recentSessions = remember(sessions) {
        sessions.sortedByDescending { it.startTime }.take(5)
    }
    val totalSessions = sessions.size
    val totalSubstances = substances.size
    val pendingConflicts = notes.count { it.conflictSiblings.isNotEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Psychonautica Journal",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Stats row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "Sessions",
                    value = totalSessions.toString()
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "Substances",
                    value = totalSubstances.toString()
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "Conflicts",
                    value = pendingConflicts.toString(),
                    warn = pendingConflicts > 0
                )
            }
        }

        // Conflict banner
        if (pendingConflicts > 0) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error)
                        Text("$pendingConflicts note conflict(s) need review",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        // Recent sessions header
        item {
            Text(
                text = "Recent Sessions",
                style = MaterialTheme.typography.titleLarge
            )
        }

        // Recent session cards
        items(recentSessions, key = { it.id }) { session ->
            SessionCard(session)
        }

        // Empty state
        if (recentSessions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No sessions yet. Tap + to start.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // FAB spacer
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    warn: Boolean = false
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = if (warn) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionCard(session: Session) {
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (session.rating != null) {
                    Text(
                        text = "${session.rating}/10",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Date
            val instant = Instant.fromEpochMilliseconds(session.startTime)
            val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
            val monthNum = local.month.ordinal + 1
            val dayNum = local.day
            Text(
                text = "${local.year}-${monthNum.toString().padStart(2, '0')}-${dayNum.toString().padStart(2, '0')}  " +
                       "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Substances in this session
            if (doses.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    doses.forEach { dose ->
                        val substance = repo.getSubstance(dose.substanceId)
                        if (substance != null) {
                            SuggestionChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "${dose.amount} ${dose.unit} ${substance.name}",
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
                    session.tags.take(3).forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    if (session.tags.size > 3) {
                        Text(
                            "+${session.tags.size - 3}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}
