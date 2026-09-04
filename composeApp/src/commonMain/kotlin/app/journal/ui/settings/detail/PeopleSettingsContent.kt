package app.journal.ui.settings.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.journal.data.IJournalRepository
import app.journal.model.Person
import app.journal.model.PersonRole
import app.journal.util.currentTimeMillis
import app.journal.util.platformDeviceOrigin

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
                persons.forEach { person ->
                    val trips = sessions.count { it.personId == person.id }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(person.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${person.role.name.lowercase().replaceFirstChar { it.uppercase() }}" +
                                    (if (trips > 0) " : $trips trip${if (trips == 1) "" else "s"}" else ""),
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
                    HorizontalDivider()
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

@Composable
private fun PersonEditorDialog(
    initial: Person?,
    onDismiss: () -> Unit,
    onSave: (Person) -> Unit
) {
    var displayName by remember { mutableStateOf(initial?.displayName ?: "") }
    var role by remember { mutableStateOf(initial?.role ?: PersonRole.PARTICIPANT) }
    var roleMenu by remember { mutableStateOf(false) }
    var age by remember { mutableStateOf(initial?.age?.toString() ?: "") }
    var gender by remember { mutableStateOf(initial?.gender ?: "") }
    var height by remember { mutableStateOf(initial?.height ?: "") }
    var weight by remember { mutableStateOf(initial?.weight ?: "") }
    var medications by remember { mutableStateOf(initial?.medications ?: "") }
    var contactEmail by remember { mutableStateOf(initial?.contactEmail ?: "") }
    var mayContact by remember { mutableStateOf(initial?.mayContact == true) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var nameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add individual" else "Edit individual") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it; nameError = false },
                    label = { Text("Name or pseudonym") },
                    singleLine = true,
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text("A name is required") }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Role", modifier = Modifier.padding(end = 8.dp))
                    Box {
                        OutlinedButton(onClick = { roleMenu = true }) {
                            Text(role.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                        DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                            PersonRole.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(option.name.lowercase().replaceFirstChar { it.uppercase() })
                                    },
                                    onClick = { role = option; roleMenu = false }
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = age,
                        onValueChange = { age = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Age") },
                        placeholder = { Text("28") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = gender,
                        onValueChange = { gender = it },
                        label = { Text("Gender") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it },
                        label = { Text("Height") },
                        placeholder = { Text("5 ft 8 in") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it },
                        label = { Text("Weight") },
                        placeholder = { Text("150 lb") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = medications,
                    onValueChange = { medications = it },
                    label = { Text("Medications") },
                    placeholder = { Text("None") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = contactEmail,
                    onValueChange = { contactEmail = it },
                    label = { Text("Contact email (optional)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Email
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mayContact, onCheckedChange = { mayContact = it })
                    Text("Editors may contact me about an exported report")
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (private, never exported)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (displayName.isBlank()) { nameError = true; return@TextButton }
                    val now = currentTimeMillis()
                    onSave(
                        Person(
                            id = initial?.id ?: "person:$now",
                            createdAt = initial?.createdAt ?: now,
                            updatedAt = now,
                            deviceOrigin = initial?.deviceOrigin ?: platformDeviceOrigin(),
                            displayName = displayName.trim(),
                            role = role,
                            linkedSessionIds = initial?.linkedSessionIds ?: emptyList(),
                            age = age.toIntOrNull(),
                            gender = gender.trim().ifEmpty { null },
                            height = height.trim().ifEmpty { null },
                            weight = weight.trim().ifEmpty { null },
                            medications = medications.trim().ifEmpty { null },
                            contactEmail = contactEmail.trim().ifEmpty { null },
                            mayContact = mayContact,
                            notes = notes.trim().ifEmpty { null }
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
