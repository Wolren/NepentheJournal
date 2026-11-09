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
import app.journal.model.Medication
import app.journal.model.MedicationPreset
import app.journal.ui.components.AppTextButton
import app.journal.util.currentTimeMillis
import app.journal.util.formatDateShort
import app.journal.util.platformDeviceOrigin

/**
 * Standing medications: entries that stay in the interaction check while active,
 * even on days the user logs no dose. Device-local and never synced.
 */
@Composable
fun MedicationsSettingsContent(repo: IJournalRepository) {
    var expanded by remember { mutableStateOf(false) }
    val medications by repo.medications.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Medication?>(null) }
    var creating by remember { mutableStateOf(false) }
    val now = currentTimeMillis()
    val activeCount = medications.count { it.isActiveAt(now) }
    val ordered = remember(medications, now) {
        medications.sortedWith(compareByDescending<Medication> { it.isActiveAt(now) }.thenBy { it.displayName })
    }

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
                    Icon(Icons.Default.Medication, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Medications", style = MaterialTheme.typography.titleMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            medications.isEmpty() -> "none"
                            activeCount == 1 -> "1 active"
                            else -> "$activeCount active"
                        },
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
                    "Medications you take regularly. They stay in the interaction check while " +
                        "active, even on days you do not log a dose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ordered.forEachIndexed { index, medication ->
                    MedicationRow(
                        medication = medication,
                        now = now,
                        onEdit = { editing = medication },
                        onToggleActive = {
                            val stamp = currentTimeMillis()
                            repo.upsertMedication(
                                if (medication.isActiveAt(stamp)) {
                                    medication.copy(endedAt = stamp, updatedAt = stamp)
                                } else {
                                    medication.copy(endedAt = null, updatedAt = stamp)
                                }
                            )
                        },
                        onDelete = { repo.deleteMedication(medication.id) },
                    )
                    if (index < ordered.size - 1) HorizontalDivider()
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { creating = true },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add medication")
                }
            }
        }
    }

    if (creating || editing != null) {
        MedicationEditorDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { medication ->
                repo.upsertMedication(medication)
                creating = false; editing = null
            }
        )
    }
}

@Composable
private fun MedicationRow(
    medication: Medication,
    now: Long,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
) {
    val active = medication.isActiveAt(now)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(medication.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append(medication.preset.label)
                    medication.doseLabel?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    if (active) {
                        append(" · ongoing since ").append(formatDateShort(medication.startedAt))
                    } else {
                        medication.endedAt?.let { append(" · stopped ").append(formatDateShort(it)) }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (active) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Edit, "Edit ${medication.displayName}")
        }
        IconButton(onClick = onToggleActive, modifier = Modifier.size(48.dp)) {
            if (active) {
                Icon(Icons.Default.PauseCircle, "Stop ${medication.displayName}")
            } else {
                Icon(Icons.Default.PlayCircle, "Resume ${medication.displayName}")
            }
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Delete, "Delete ${medication.displayName}")
        }
    }
}

/**
 * Create or edit one medication. The type is required because it supplies the
 * interaction classes; the name is the user's own wording for the drug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedicationEditorDialog(
    initial: Medication?,
    onDismiss: () -> Unit,
    onSave: (Medication) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var presetId by remember(initial?.id) { mutableStateOf(initial?.presetId) }
    var doseLabel by remember(initial?.id) { mutableStateOf(initial?.doseLabel ?: "") }
    var presetExpanded by remember { mutableStateOf(false) }
    val preset = presetId?.let { MedicationPreset.fromId(it) }
    val canSave = name.isNotBlank() && preset != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add medication" else "Edit medication") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. sertraline") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Type",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExposedDropdownMenuBox(
                    expanded = presetExpanded,
                    onExpandedChange = { presetExpanded = it }
                ) {
                    OutlinedTextField(
                        value = preset?.label ?: "",
                        onValueChange = {},
                        readOnly = true,
                        placeholder = { Text("Choose type") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = presetExpanded,
                        onDismissRequest = { presetExpanded = false }
                    ) {
                        MedicationPreset.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(option.label)
                                        Text(
                                            option.examples,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = { presetId = option.id; presetExpanded = false }
                            )
                        }
                    }
                }
                preset?.let {
                    Text(
                        if (it.interactionClasses.isEmpty()) {
                            "This type adds no interaction checks."
                        } else {
                            "Examples: ${it.examples}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = doseLabel,
                    onValueChange = { doseLabel = it },
                    label = { Text("Dose (optional)") },
                    placeholder = { Text("e.g. 50 mg daily") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            AppTextButton(
                onClick = {
                    val stamp = currentTimeMillis()
                    val chosen = preset ?: return@AppTextButton
                    onSave(
                        Medication(
                            id = initial?.id ?: "medication:$stamp",
                            createdAt = initial?.createdAt ?: stamp,
                            updatedAt = stamp,
                            deviceOrigin = initial?.deviceOrigin ?: platformDeviceOrigin(),
                            name = name.trim(),
                            presetId = chosen.id,
                            doseLabel = doseLabel.trim().ifBlank { null },
                            startedAt = initial?.startedAt ?: stamp,
                            endedAt = initial?.endedAt,
                            notes = initial?.notes,
                        )
                    )
                },
                enabled = canSave
            ) { Text("Save") }
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
