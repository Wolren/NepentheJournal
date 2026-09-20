package app.journal.ui.settings.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.data.IJournalRepository
import app.journal.model.Person
import app.journal.ui.components.PersonEditorDialog

/**
 * Individuals settings: the roster behind trip demographics. The individual
 * assigned to a session owns that trip's age, gender, height, weight and
 * medications at dose.wiki export time. Individuals are device-local and never sync.
 */
@Composable
fun PeopleSettingsContent(repo: IJournalRepository) {
    var expanded by remember { mutableStateOf(false) }
    val persons by repo.persons.collectAsState(initial = emptyList())
    val sessions by repo.sessions.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Person?>(null) }
    var creating by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Group, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Individuals", style = MaterialTheme.typography.titleMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${persons.size} individuals",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Text(
                    "Trip demographics live here, on the individual assigned to each session. " +
                        "The dose.wiki export reads age, gender, height, weight and " +
                        "medications from that individual.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                persons.forEachIndexed { index, person ->
                    val trips = sessions.count { it.personId == person.id }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(person.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${person.role.name.lowercase().replaceFirstChar { it.uppercase() }}" +
                                    (if (trips > 0) " · $trips trip${if (trips == 1) "" else "s"}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { editing = person },
                            modifier = Modifier.size(48.dp)
                        ) { Icon(Icons.Default.Edit, "Edit ${person.displayName}") }
                        IconButton(
                            onClick = { repo.deletePerson(person.id) },
                            modifier = Modifier.size(48.dp)
                        ) { Icon(Icons.Default.Delete, "Delete ${person.displayName}") }
                    }
                    if (index < persons.size - 1) HorizontalDivider()
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { creating = true },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add individual")
                }
            }
        }
    }

    if (creating || editing != null) {
        PersonEditorDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { person ->
                repo.upsertPerson(person)
                creating = false; editing = null
            }
        )
    }
}
