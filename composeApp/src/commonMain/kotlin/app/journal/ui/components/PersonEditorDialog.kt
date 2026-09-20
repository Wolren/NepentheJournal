package app.journal.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.journal.model.Person
import app.journal.model.PersonRole
import app.journal.util.currentTimeMillis
import app.journal.util.platformDeviceOrigin
import kotlin.random.Random

/**
 * Shared individual editor. Used by Settings (Individuals), the first-run
 * welcome dialog, the dashboard nudge card and the session editor empty
 * state, so creating a profile works the same everywhere.
 */
@Composable
internal fun PersonEditorDialog(
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
                            id = initial?.id ?: "person:${now}_${Random.nextInt(0, 0x10000).toString(16)}",
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
