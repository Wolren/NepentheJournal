package app.journal.ui.substances

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.InteractionClasses
import app.journal.model.Substance
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubstanceEditorScreen(
    substanceToEdit: Substance? = null,
    onBack: () -> Unit
) {
    val repo = remember { JournalRepository.instance }
    val isEditing = substanceToEdit != null
    val now = currentTimeMillis()

    var name by remember { mutableStateOf(substanceToEdit?.name ?: "") }
    var aliases by remember { mutableStateOf(substanceToEdit?.aliases?.joinToString(", ") ?: "") }
    var summary by remember { mutableStateOf(substanceToEdit?.summary ?: "") }
    var substanceClass by remember { mutableStateOf(substanceToEdit?.substanceClass ?: emptyList()) }
    var classInput by remember { mutableStateOf("") }
    var interactionClasses by remember { mutableStateOf(substanceToEdit?.interactionClasses ?: emptyList()) }
    var roas by remember { mutableStateOf(substanceToEdit?.routesOfAdministration ?: emptyList()) }
    var addictionPotential by remember { mutableStateOf(substanceToEdit?.addictionPotential ?: "") }
    var toxicityInput by remember { mutableStateOf("") }
    var toxicity by remember { mutableStateOf(substanceToEdit?.toxicity ?: emptyList()) }

    // Dosage bands
    var doseThreshold by remember { mutableStateOf(substanceToEdit?.dosageBands?.get("threshold") ?: "") }
    var doseLight by remember { mutableStateOf(substanceToEdit?.dosageBands?.get("light") ?: "") }
    var doseCommon by remember { mutableStateOf(substanceToEdit?.dosageBands?.get("common") ?: "") }
    var doseStrong by remember { mutableStateOf(substanceToEdit?.dosageBands?.get("strong") ?: "") }
    var doseHeavy by remember { mutableStateOf(substanceToEdit?.dosageBands?.get("heavy") ?: "") }

    // Duration
    var durOnset by remember { mutableStateOf(substanceToEdit?.durationProfile?.get("onset") ?: "") }
    var durComeup by remember { mutableStateOf(substanceToEdit?.durationProfile?.get("comeup") ?: "") }
    var durPeak by remember { mutableStateOf(substanceToEdit?.durationProfile?.get("peak") ?: "") }
    var durOffset by remember { mutableStateOf(substanceToEdit?.durationProfile?.get("offset") ?: "") }
    var durAfterglow by remember { mutableStateOf(substanceToEdit?.durationProfile?.get("afterglow") ?: "") }
    var durTotal by remember { mutableStateOf(substanceToEdit?.durationProfile?.get("total") ?: "") }

    val roaOptions = listOf("Oral", "Sublingual", "Insufflated", "Inhaled", "Vaporized",
        "Intranasal", "Intramuscular", "Intravenous", "Subcutaneous", "Rectal", "Transdermal", "Buccal")

    fun save() {
        if (name.isBlank()) return
        val id = substanceToEdit?.id ?: "sub:${name.lowercase().replace(" ", "_")}_${now}"

        val dosageBands = buildMap {
            if (doseThreshold.isNotBlank()) put("threshold", doseThreshold)
            if (doseLight.isNotBlank()) put("light", doseLight)
            if (doseCommon.isNotBlank()) put("common", doseCommon)
            if (doseStrong.isNotBlank()) put("strong", doseStrong)
            if (doseHeavy.isNotBlank()) put("heavy", doseHeavy)
        }
        val durationProfile = buildMap {
            if (durOnset.isNotBlank()) put("onset", durOnset)
            if (durComeup.isNotBlank()) put("comeup", durComeup)
            if (durPeak.isNotBlank()) put("peak", durPeak)
            if (durOffset.isNotBlank()) put("offset", durOffset)
            if (durAfterglow.isNotBlank()) put("afterglow", durAfterglow)
            if (durTotal.isNotBlank()) put("total", durTotal)
        }

        val substance = Substance(
            id = id,
            name = name.trim(),
            aliases = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            summary = summary.ifBlank { null },
            substanceClass = substanceClass,
            interactionClasses = interactionClasses,
            routesOfAdministration = roas,
            dosageBands = dosageBands,
            durationProfile = durationProfile,
            addictionPotential = addictionPotential.ifBlank { null },
            toxicity = toxicity,
            cachedAt = now, sourceVersion = "manual",
            createdAt = substanceToEdit?.createdAt ?: now,
            updatedAt = now,
            deviceOrigin = "desktop"
        )
        repo.upsertSubstance(substance)
        onBack()
    }

    ScreenScaffold(
        title = if (isEditing) "Edit Substance" else "New Substance",
        onBack = onBack,
        actions = {
            AppTextButton(onClick = ::save, enabled = name.isNotBlank()) { Text("Save") }
        }
    ) {
            // Name
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Aliases
            item {
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it },
                    label = { Text("Aliases (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Class
            item {
                Text("Chemical Class", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = classInput,
                        onValueChange = { classInput = it },
                        placeholder = { Text("Add class...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    AppTonalButton(
                        onClick = {
                            val c = classInput.trim()
                            if (c.isNotEmpty() && c !in substanceClass) {
                                substanceClass = substanceClass + c
                                classInput = ""
                            }
                        },
                        enabled = classInput.isNotBlank()
                    ) { Text("Add") }
                }
                if (substanceClass.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        substanceClass.forEach { cls ->
                            InputChip(
                                selected = false,
                                onClick = { substanceClass = substanceClass - cls },
                                label = { Text(cls, style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp))
                                }
                            )
                        }
                    }
                }
            }

            // Interaction Classes (Pharmacological)
            item {
                Spacer(Modifier.height(8.dp))
                Text("Interaction Classes (Pharmacological)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("These classes are used by the interaction checker to flag dangerous combinations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                // Render all 13 classes as FilterChips
                val allClasses = InteractionClasses.ALL
                val rows = allClasses.chunked(4)
                for (row in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        row.forEach { cls ->
                            val label = InteractionClasses.CLASS_LABELS[cls] ?: cls
                            val selected = cls in interactionClasses
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    interactionClasses = if (selected) {
                                        interactionClasses - cls
                                    } else {
                                        interactionClasses + cls
                                    }
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (interactionClasses.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        interactionClasses.forEach { cls ->
                            val label = InteractionClasses.CLASS_LABELS[cls] ?: cls
                            InputChip(
                                selected = true,
                                onClick = { interactionClasses = interactionClasses - cls },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp))
                                }
                            )
                        }
                    }
                }
            }

            // ROAs
            item {
                Spacer(Modifier.height(8.dp))
                Text("Routes of Administration", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val visibleRoa = roaOptions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    visibleRoa.forEach { option ->
                        FilterChip(
                            selected = option in roas,
                            onClick = {
                                roas = if (option in roas) roas - option else roas + option
                            },
                            label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }
            }

            // Summary
            item {
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Summary") },
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Dosage section header
            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Dosage", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = doseThreshold, onValueChange = { doseThreshold = it },
                        label = { Text("Threshold") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = doseLight, onValueChange = { doseLight = it },
                        label = { Text("Light") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = doseCommon, onValueChange = { doseCommon = it },
                        label = { Text("Common") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = doseStrong, onValueChange = { doseStrong = it },
                        label = { Text("Strong") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            item {
                OutlinedTextField(value = doseHeavy, onValueChange = { doseHeavy = it },
                    label = { Text("Heavy") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            // Duration section header
            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Duration", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = durOnset, onValueChange = { durOnset = it },
                        label = { Text("Onset") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = durComeup, onValueChange = { durComeup = it },
                        label = { Text("Comeup") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = durPeak, onValueChange = { durPeak = it },
                        label = { Text("Peak") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = durOffset, onValueChange = { durOffset = it },
                        label = { Text("Offset") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = durAfterglow, onValueChange = { durAfterglow = it },
                        label = { Text("Afterglow") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = durTotal, onValueChange = { durTotal = it },
                        label = { Text("Total") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }

            // Addiction potential
            item {
                OutlinedTextField(
                    value = addictionPotential,
                    onValueChange = { addictionPotential = it },
                    label = { Text("Addiction Potential") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Toxicity
            item {
                Text("Toxicity Warnings", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = toxicityInput,
                        onValueChange = { toxicityInput = it },
                        placeholder = { Text("Add warning...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    AppTonalButton(
                        onClick = {
                            val t = toxicityInput.trim()
                            if (t.isNotEmpty() && t !in toxicity) {
                                toxicity = toxicity + t
                                toxicityInput = ""
                            }
                        },
                        enabled = toxicityInput.isNotBlank()
                    ) { Text("Add") }
                }
                if (toxicity.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    toxicity.forEach { warning ->
                        InputChip(
                            selected = false,
                            onClick = { toxicity = toxicity - warning },
                            label = { Text(warning, style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = "Remove",
                                    modifier = Modifier.size(14.dp))
                            },
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
    }
}
