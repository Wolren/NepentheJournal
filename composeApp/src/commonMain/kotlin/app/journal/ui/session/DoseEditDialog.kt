package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.model.Dose
import app.journal.model.StomachFullness
import app.journal.model.Substance
import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import app.journal.ui.components.*

/**
 * Date/time input field. Parses YYYY-MM-DD and HH:MM strings into epoch millis.
 * Reused by SessionEditorScreen and any other screen needing time input.
 */
@Composable
fun TimeField(
    label: String,
    epochMs: Long?,
    onChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = false
) {
    val tz = TimeZone.currentSystemDefault()
    val local = remember(epochMs) {
        if (epochMs != null)
            Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
        else null
    }
    var dateStr by remember(local) {
        mutableStateOf(local?.let {
            "${it.year}-${(it.month.ordinal + 1).toString().padStart(2, '0')}-${it.day.toString().padStart(2, '0')}"
        } ?: "")
    }
    var timeStr by remember(local) {
        mutableStateOf(local?.let {
            "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        } ?: "")
    }

    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = dateStr,
            onValueChange = { dateStr = it },
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = timeStr,
            onValueChange = { timeStr = it },
            placeholder = { Text("HH:MM") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall
        )
        if (clearable) {
            AppTextButton(
                onClick = { onChanged(0L) },
                modifier = Modifier.height(24.dp)
            ) {
                Text("Clear end time", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** ROA options shared across dose dialogs and editor screens. */
val ROA_OPTIONS = listOf(
    "Oral", "Sublingual", "Insufflated", "Inhaled", "Vaporized",
    "Intranasal", "Intramuscular", "Intravenous", "Subcutaneous",
    "Rectal", "Transdermal", "Buccal"
)

/** Preset ROA chip rows: first 6 in top row, remaining 6 below. */
@Composable
fun RoaChipRow(
    selected: String,
    onSelect: (String) -> Unit
) {
    Text("Route", style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        ROA_OPTIONS.take(6).forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ROA_OPTIONS.drop(6).forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

/**
 * Full-screen dialog for adding or editing a dose within a session.
 * Encapsulates substance selector, amount, ROA, stomach fullness, redose, estimate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoseEditDialog(
    substances: List<Substance>,
    sessionStartTime: Long,
    initialDose: Dose?,
    onDismiss: () -> Unit,
    onSave: (Dose) -> Unit
) {
    var selectedSubstanceId by remember { mutableStateOf(initialDose?.substanceId ?: "") }
    var amount by remember { mutableStateOf(initialDose?.amount?.toString() ?: "") }
    var unit by remember { mutableStateOf(initialDose?.unit ?: "mg") }
    var roa by remember { mutableStateOf(initialDose?.routeOfAdministration ?: "Oral") }
    var isRedose by remember { mutableStateOf(initialDose?.redosing ?: false) }
    var isEstimate by remember { mutableStateOf(initialDose?.isDoseEstimate ?: false) }
    var estimateStddev by remember { mutableStateOf(initialDose?.estimatedDoseStandardDeviation?.toString() ?: "") }
    var doseTime by remember {
        mutableStateOf(initialDose?.timestamp?.toString() ?: sessionStartTime.toString())
    }
    var expandedSubstance by remember { mutableStateOf(false) }
    var stomachFullness by remember { mutableStateOf(initialDose?.stomachFullness) }
    var expandedStomach by remember { mutableStateOf(false) }

    fun buildDose(): Dose? {
        val amt = amount.toDoubleOrNull() ?: return null
        if (selectedSubstanceId.isBlank()) return null
        val now = currentTimeMillis()
        return Dose(
            id = initialDose?.id ?: "dose:${now}",
            sessionId = initialDose?.sessionId ?: "",
            substanceId = selectedSubstanceId,
            routeOfAdministration = roa,
            amount = amt,
            unit = unit,
            timestamp = doseTime.toLongOrNull() ?: sessionStartTime,
            redosing = isRedose,
            isDoseEstimate = isEstimate,
            estimatedDoseStandardDeviation = if (isEstimate) estimateStddev.toDoubleOrNull() else null,
            createdAt = initialDose?.createdAt ?: now,
            updatedAt = now,
            deviceOrigin = "desktop",
            stomachFullness = stomachFullness
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialDose != null) "Edit Dose" else "Add Dose") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Substance selector
                item {
                    Text("Substance", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = expandedSubstance,
                        onExpandedChange = { expandedSubstance = it }
                    ) {
                        OutlinedTextField(
                            value = substances.find { it.id == selectedSubstanceId }?.name ?: "",
                            onValueChange = {},
                            readOnly = true,
                            placeholder = { Text("Select substance...") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSubstance) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = expandedSubstance,
                            onDismissRequest = { expandedSubstance = false }
                        ) {
                            substances.forEach { sub ->
                                DropdownMenuItem(
                                    text = { Text(sub.name) },
                                    onClick = {
                                        selectedSubstanceId = sub.id
                                        expandedSubstance = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Amount + Unit
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it },
                            label = { Text("Amount") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text("Unit") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Route of administration
                item { RoaChipRow(selected = roa, onSelect = { roa = it }) }

                // Stomach fullness
                item {
                    Text("Stomach fullness", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = expandedStomach,
                        onExpandedChange = { expandedStomach = it }
                    ) {
                        OutlinedTextField(
                            value = stomachFullness?.label ?: "Not specified",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedStomach) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = expandedStomach,
                            onDismissRequest = { expandedStomach = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Not specified") },
                                onClick = { stomachFullness = null; expandedStomach = false }
                            )
                            StomachFullness.entries.forEach { sf ->
                                DropdownMenuItem(
                                    text = { Text(sf.label) },
                                    onClick = { stomachFullness = sf; expandedStomach = false }
                                )
                            }
                        }
                    }
                }

                // Redose toggle
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(checked = isRedose, onCheckedChange = { isRedose = it })
                        Text("Redose", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Estimate toggle
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(checked = isEstimate, onCheckedChange = { isEstimate = it })
                        Text("Estimated dose", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Std dev field (only when estimate checked)
                if (isEstimate) {
                    item {
                        OutlinedTextField(
                            value = estimateStddev,
                            onValueChange = { estimateStddev = it },
                            label = { Text("Std deviation (±)") },
                            placeholder = { Text("e.g. 10") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            AppTextButton(
                onClick = {
                    val dose = buildDose()
                    if (dose != null) onSave(dose)
                },
                enabled = selectedSubstanceId.isNotBlank() && amount.toDoubleOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
