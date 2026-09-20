package app.journal.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.model.Person
import app.journal.ui.components.PersonEditorDialog

/**
 * Assigns the individual who took the substances in this trip. The assigned
 * individual owns the trip demographics at dose.wiki export time. Demographics
 * themselves are edited in Settings, Individuals.
 */
@Composable
fun PersonPickerSection(
    persons: List<Person>,
    selectedPersonId: String?,
    onSelect: (String?) -> Unit,
    onCreatePerson: (Person) -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    val selected = persons.firstOrNull { it.id == selectedPersonId }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Individual", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(4.dp))
        if (persons.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "No individuals yet. Create your profile so this trip carries " +
                            "the right demographics on export.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showCreate = true },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Create profile") }
                }
            }
        } else {
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(
                    selected?.displayName ?: "No individual assigned",
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("No individual assigned") },
                    onClick = { onSelect(null); menuOpen = false }
                )
                persons.forEach { person ->
                    DropdownMenuItem(
                        text = { Text(person.displayName) },
                        onClick = { onSelect(person.id); menuOpen = false }
                    )
                }
            }
            }
        }
        if (selected != null) {
            Text(
                "Demographics for this trip come from ${selected.displayName} " +
                    "(Settings, Individuals).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "Without an individual, the export falls back to the consumer name " +
                    "and demographics below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showCreate) {
        PersonEditorDialog(
            initial = null,
            onDismiss = { showCreate = false },
            onSave = { person -> onCreatePerson(person); showCreate = false }
        )
    }
}
