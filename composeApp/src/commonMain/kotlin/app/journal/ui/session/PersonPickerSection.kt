package app.journal.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
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

/**
 * Assigns the person who took the substances in this trip. The assigned
 * person owns the trip demographics at dose.wiki export time. Demographics
 * themselves are edited in Settings, People.
 */
@Composable
fun PersonPickerSection(
    persons: List<Person>,
    selectedPersonId: String?,
    onSelect: (String?) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = persons.firstOrNull { it.id == selectedPersonId }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Person", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(
                    selected?.displayName ?: "No person assigned",
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("No person assigned") },
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
        if (selected != null) {
            Text(
                "Demographics for this trip come from ${selected.displayName} " +
                    "(Settings, People).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "Without a person, the export falls back to the consumer name " +
                    "and demographics below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
