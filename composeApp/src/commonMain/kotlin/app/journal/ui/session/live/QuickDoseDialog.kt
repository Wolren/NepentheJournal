package app.journal.ui.session.live

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.Substance
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickDoseDialog(
    substances: List<Substance>,
    session: Session,
    repo: JournalRepository,
    onDismiss: () -> Unit,
) {
    val now = currentTimeMillis()
    var selectedSubstanceId by remember { mutableStateOf("") }
    var customSubstanceName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("mg") }
    var route by remember { mutableStateOf("Oral") }
    var note by remember { mutableStateOf("") }
    var doseMinutesAgo by remember { mutableStateOf("") }

    val roaOptions = listOf("Oral", "Sublingual", "Insufflated", "Inhaled", "Vaporized",
        "Intranasal", "Intramuscular", "Intravenous", "Rectal")

    val chosenSubstanceId = if (selectedSubstanceId.isNotBlank()) selectedSubstanceId
        else if (customSubstanceName.isNotBlank()) "live:${customSubstanceName.lowercase().replace(" ", "_")}"
        else ""
    val canSubmit = chosenSubstanceId.isNotBlank() && amount.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Dose") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (substances.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(value = substances.find { it.id == selectedSubstanceId }?.name ?: "",
                            onValueChange = {}, readOnly = true, label = { Text("Substance") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            substances.forEach { sub -> DropdownMenuItem(text = { Text(sub.name) }, onClick = { selectedSubstanceId = sub.id; expanded = false }) }
                        }
                    }
                } else Text("No substances in database. Add one first.", color = MaterialTheme.colorScheme.error)

                OutlinedTextField(value = customSubstanceName, onValueChange = { customSubstanceName = it; if (it.isNotBlank()) selectedSubstanceId = "" },
                    label = { Text("Or type a new substance name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = selectedSubstanceId.isBlank())

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.width(80.dp))
                }

                var routeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = routeExpanded, onExpandedChange = { routeExpanded = it }) {
                    OutlinedTextField(value = route, onValueChange = {}, readOnly = true, label = { Text("Route") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(routeExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = routeExpanded, onDismissRequest = { routeExpanded = false }) {
                        roaOptions.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { route = opt; routeExpanded = false }) }
                    }
                }

                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = doseMinutesAgo, onValueChange = { doseMinutesAgo = it.filter { c -> c.isDigit() } },
                        label = { Text("Min ago") }, singleLine = true, modifier = Modifier.width(100.dp), placeholder = { Text("0") })
                    Text("min ago (0 = now)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            AppTextButton(onClick = {
                if (canSubmit) {
                    if (selectedSubstanceId.isBlank() && customSubstanceName.isNotBlank()) {
                        val now2 = currentTimeMillis()
                        repo.upsertSubstance(Substance(id = "live:${customSubstanceName.lowercase().replace(" ", "_")}", name = customSubstanceName,
                            createdAt = now2, updatedAt = now2, deviceOrigin = "desktop", cachedAt = now2, sourceVersion = "live"))
                        selectedSubstanceId = "live:${customSubstanceName.lowercase().replace(" ", "_")}"
                    }
                    val doseTime = now - (doseMinutesAgo.toLongOrNull() ?: 0L) * 60000L
                    repo.upsertDose(Dose(id = "dose:live:${now}_${session.id}", sessionId = session.id, substanceId = chosenSubstanceId,
                        routeOfAdministration = route, amount = amount.toDoubleOrNull() ?: 0.0, unit = unit,
                        timestamp = doseTime, createdAt = now, updatedAt = now, deviceOrigin = "desktop"))
                    repo.upsertTimelineEvent(TimelineEvent(id = "event:dose:${now}_${session.id}", sessionId = session.id,
                        timestamp = doseTime, eventType = TimelineEventType.NOTE, label = "Dose: ${substances.find { it.id == chosenSubstanceId }?.name ?: customSubstanceName.ifBlank { chosenSubstanceId }}",
                        body = "$amount $unit $route".takeIf { it.isNotBlank() }, createdAt = now, updatedAt = now, deviceOrigin = "desktop"))
                    onDismiss()
                }
            }, enabled = canSubmit) { Text("Log") }
        },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
