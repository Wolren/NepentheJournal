package app.journal.ui.substances

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Substance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubstanceScreen() {
    val repo = remember { JournalRepository.instance }
    val substances by repo.substances.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }

    val results = remember(query, substances) {
        if (query.isBlank()) substances
        else repo.searchSubstances(query)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Text(
            text = "Substances",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(8.dp))

        // Search bar
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search substances...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // Results
        if (results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (query.isNotEmpty()) "No substances match \"$query\""
                           else "No substances in database",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = "${results.size} substance${if (results.size != 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id }) { substance ->
                    SubstanceCard(substance)
                }
            }
        }
    }
}

@Composable
private fun SubstanceCard(substance: Substance) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = substance.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (substance.aliases.isNotEmpty()) {
                        Text(
                            text = substance.aliases.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!expanded) {
                    Text(
                        text = "Tap for details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Substance class chips
            if (substance.substanceClass.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    substance.substanceClass.forEach { cls ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(cls, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Expanded details
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Summary
                if (!substance.summary.isNullOrBlank()) {
                    Text(
                        text = substance.summary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Dosage
                if (substance.dosageBands.isNotEmpty()) {
                    Text(
                        text = "Dosage",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    substance.dosageBands.forEach { (band, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = band.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Duration
                if (substance.durationProfile.isNotEmpty()) {
                    Text(
                        text = "Duration",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    substance.durationProfile.forEach { (phase, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = phase.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ROA
                if (substance.routesOfAdministration.isNotEmpty()) {
                    Text(
                        text = "Routes: ${substance.routesOfAdministration.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Addiction potential & toxicity
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (substance.addictionPotential != null) {
                        Text(
                            text = "Addiction potential: ${substance.addictionPotential}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (substance.addictionPotential.contains("High"))
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (substance.cachedAt > 0) {
                        Text(
                            text = "via PsychonautWiki",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Toxicity warnings
                if (substance.toxicity.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            substance.toxicity.forEach { warning ->
                                Text(
                                    text = "  $warning",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
