package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.ui.components.*
import app.journal.ui.components.routeColor
import app.journal.model.CustomUnit
import app.journal.model.Dose
import app.journal.model.StomachFullness
import app.journal.model.Substance
import app.journal.util.currentTimeMillis
import kotlinx.coroutines.launch

val ROA_OPTIONS = listOf(
    "Oral", "Sublingual", "Insufflated", "Inhaled", "Vaporized",
    "Intranasal", "Intramuscular", "Intravenous", "Subcutaneous",
    "Rectal", "Transdermal", "Buccal"
)

@OptIn(ExperimentalMaterial3Api::class)
/** Route selector: wide dropdown with colored items. */
@Composable
fun RoaDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val selectedColor = routeColor(selected)
    Text("Route", style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = selectedColor) {}
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            ROA_OPTIONS.forEach { option ->
                val color = routeColor(option)
                DropdownMenuItem(
                    leadingIcon = {
                        Surface(modifier = Modifier.size(10.dp), shape = CircleShape, color = color) {}
                    },
                    text = { Text(option) },
                    onClick = { onSelect(option); onExpandedChange(false) }
                )
            }
        }
    }
}

/** Common dose unit options for the unit dropdown. */
val UNIT_OPTIONS = listOf("µg", "mg", "g", "ml", "drops", "IU", "tablets", "blotters", "sprays", "capsules", "hits", "lines", "seeds", "ounces", "grams", "nanograms")

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
    onSave: (Dose) -> Unit,
    customUnits: List<CustomUnit> = emptyList(),
) {
    var selectedSubstanceId by remember { mutableStateOf(initialDose?.substanceId ?: "") }
    var amount by remember { mutableStateOf(initialDose?.amount?.toString() ?: "") }
    var unit by remember { mutableStateOf(initialDose?.unit ?: "mg") }
    var roa by remember { mutableStateOf(initialDose?.routeOfAdministration ?: "Oral") }
    var isRedose by remember { mutableStateOf(initialDose?.redosing ?: false) }
    var isEstimate by remember { mutableStateOf(initialDose?.isDoseEstimate ?: false) }
    var estimateStddev by remember { mutableStateOf(initialDose?.estimatedDoseStandardDeviation?.toString() ?: "") }
    var substanceSearch by remember { mutableStateOf("") }
    var stomachFullness by remember { mutableStateOf(initialDose?.stomachFullness) }
    var expandedStomach by remember { mutableStateOf(false) }
    var expandedUnit by remember { mutableStateOf(false) }
    var expandedRoa by remember { mutableStateOf(false) }
    var doseTimestamp by remember {
        mutableStateOf(initialDose?.timestamp ?: sessionStartTime)
    }

    fun buildDose(): Dose? {
        val amt = amount.toDoubleOrNull() ?: return null
        if (selectedSubstanceId.isBlank()) return null
        val now = currentTimeMillis()
        val customUnit = customUnits.find { it.substanceId == selectedSubstanceId && it.name == unit }
        return Dose(
            id = initialDose?.id ?: "dose:${now}",
            sessionId = initialDose?.sessionId ?: "",
            substanceId = selectedSubstanceId,
            routeOfAdministration = roa,
            amount = amt,
            unit = unit,
            timestamp = doseTimestamp,
            redosing = isRedose,
            isDoseEstimate = isEstimate,
            estimatedDoseStandardDeviation = if (isEstimate) estimateStddev.toDoubleOrNull() else null,
            createdAt = initialDose?.createdAt ?: now,
            updatedAt = now,
            deviceOrigin = "desktop",
            stomachFullness = stomachFullness,
            customUnitId = customUnit?.id
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
                    OutlinedTextField(
                        value = if (selectedSubstanceId.isNotEmpty())
                            substances.find { it.id == selectedSubstanceId }?.name ?: substanceSearch
                        else substanceSearch,
                        onValueChange = {
                            substanceSearch = it
                            selectedSubstanceId = ""
                        },
                        placeholder = { Text("Search substance...") },
                        trailingIcon = {
                            if (selectedSubstanceId.isNotEmpty()) {
                                IconButton(onClick = {
                                    selectedSubstanceId = ""
                                    substanceSearch = ""
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear",
                                        modifier = Modifier.size(16.dp))
                                }
                            } else {
                                Icon(Icons.Default.Search, contentDescription = "Search",
                                    modifier = Modifier.size(20.dp))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                // Search results — inline, same window, no popup
                val filtered = if (substanceSearch.isBlank()) emptyList()
                else substances.filter {
                    it.name.contains(substanceSearch, ignoreCase = true) ||
                    it.aliases.any { a -> a.contains(substanceSearch, ignoreCase = true) }
                }
                if (selectedSubstanceId.isEmpty() && substanceSearch.isNotBlank()) {
                    if (filtered.isEmpty()) {
                        item {
                            Text("No substances match",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 6.dp))
                        }
                    } else {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val scrollState = rememberScrollState()
                                Column(
                                    modifier = Modifier.heightIn(max = 200.dp).verticalScroll(scrollState)
                                ) {
                                    filtered.take(50).forEachIndexed { idx, sub ->
                                        Surface(
                                            onClick = {
                                                selectedSubstanceId = sub.id
                                                substanceSearch = ""
                                            },
                                            color = Color.Transparent,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                sub.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                            )
                                        }
                                        if (idx < filtered.size - 1 && idx < 49) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 14.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                            )
                                        }
                                    }
                                    if (filtered.size > 50) {
                                        Text(
                                            "+ ${filtered.size - 50} more...",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                    }
                                }
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
                        ExposedDropdownMenuBox(
                            expanded = expandedUnit,
                            onExpandedChange = { expandedUnit = it },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = unit,
                                onValueChange = { unit = it },
                                label = { Text("Unit") },
                                singleLine = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedUnit) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expandedUnit,
                                onDismissRequest = { expandedUnit = false }
                            ) {
                                UNIT_OPTIONS.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(opt) },
                                        onClick = { unit = opt; expandedUnit = false }
                                    )
                                }
                                if (customUnits.isNotEmpty()) {
                                    HorizontalDivider()
                                    val filteredCustom = customUnits.filter { it.substanceId == selectedSubstanceId }
                                    if (filteredCustom.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("Custom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = {},
                                            enabled = false,
                                        )
                                        filteredCustom.forEach { cu ->
                                            DropdownMenuItem(
                                                text = { Text(cu.name) },
                                                onClick = { unit = cu.name; expandedUnit = false },
                                            )
                                        }
                                        if (customUnits.any { it.substanceId != selectedSubstanceId }) {
                                            DropdownMenuItem(
                                                text = { Text("(define in Settings)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                onClick = { expandedUnit = false },
                                                enabled = false,
                                            )
                                        }
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("No custom units — add in Settings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                            onClick = { expandedUnit = false },
                                            enabled = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Route of administration
                item { RoaDropdown(selected = roa, onSelect = { roa = it }, expanded = expandedRoa, onExpandedChange = { expandedRoa = it }) }

                // Dose time
                item {
                    TimeField(
                        label = "Dose time",
                        epochMs = doseTimestamp,
                        onChanged = { doseTimestamp = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

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
