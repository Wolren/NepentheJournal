package app.journal.ui.substances

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.data.OpenFdaClient
import app.journal.util.createHttpClient
import io.ktor.client.*
import kotlinx.coroutines.launch

/**
 * Parsed FDA interaction entry — a drug class/name followed by its interaction description.
 */
private data class FdaInteractionEntry(
    val drugClass: String,
    val description: String
)

/**
 * Split dense FDA drug-interaction prose into individual entries.
 * Pattern: drug class/name followed by colon + description, possibly with section refs like (7.2).
 */
private fun parseInteractionText(raw: String): List<FdaInteractionEntry> {
    // Remove the leading "7 DRUG INTERACTIONS" header and preamble
    val text = raw.replaceFirst(Regex("\\d+\\s+DRUG\\s+INTERACTIONS\\s*"), "")
        .trim()

    val entries = mutableListOf<FdaInteractionEntry>()

    // Split on likely entry boundaries: word(s) ending with colon
    val regex = Regex("([A-Z][A-Za-z0-9\\s/&,()-]+?):\\s*")
    val parts = regex.split(text)

    if (parts.size < 2) {
        // No parseable structure — return whole text as one entry
        return listOf(FdaInteractionEntry("Overview", text.take(500)))
    }

    // First part is the preamble
    val preamble = parts[0].trim()
    if (preamble.isNotBlank() && preamble.length > 10) {
        entries.add(FdaInteractionEntry("General", preamble))
    }

    var i = 1
    while (i + 1 < parts.size) {
        val drugClass = parts[i].trim()
        val desc = parts[i + 1].trim()
        if (drugClass.isNotBlank() && desc.isNotBlank()) {
            // Clean up: remove trailing section refs like "( 7.7 )"
            val cleanDesc = desc.replace(Regex("\\(\\s*[\\d.,\\s]+\\s*\\)"), "").trim()
            entries.add(FdaInteractionEntry(drugClass, cleanDesc))
        }
        i += 2
    }

    return entries
}

/**
 * Card that fetches and displays FDA drug interaction data for a substance.
 * Parses the raw FDA label prose into structured drug-class entries.
 * Only visible for FDA-approved substances (pharmaceuticals). Collapsed by default.
 */
@Composable
fun OpenFdaInteractionCard(
    substanceId: String,
    substanceName: String,
    modifier: Modifier = Modifier
) {
    val repo = remember { JournalRepository.instance }
    val substance = remember(substanceId) { repo.getSubstance(substanceId) }

    val shouldQuery = substance?.drugbankId != null || substance?.cid != null
    if (!shouldQuery) return

    var result by remember { mutableStateOf<OpenFdaClient.FdaInteractionResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client = remember { createHttpClient() }

    LaunchedEffect(substanceId) {
        loading = true
        val searchName = substance?.name ?: substanceName
        val fetchResult = OpenFdaClient.fetchInteractions(searchName, client)
        fetchResult.onSuccess {
            result = it
        }.onFailure {
            error = it.message
        }
        loading = false
    }

    val data = result ?: return
    if (!data.hasData && !loading) return

    // Parse interaction text into entries
    val interactionEntries = remember(data.drugInteractions) {
        data.drugInteractions.flatMap { parseInteractionText(it) }
    }

    val totalCount = interactionEntries.size

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header — clickable to expand/collapse
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.MedicalServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "FDA Drug Interactions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (totalCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "$totalCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (error != null) {
                Text(
                    text = "Could not load FDA data",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                return@Column
            }

            // Brand name (always visible)
            if (data.brandName != null && expanded) {
                Text(
                    text = "Brand: ${data.brandName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // Parsed interaction entries — collapsed by default
            if (expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    interactionEntries.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "-",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Column {
                                Text(
                                    text = entry.drugClass,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = entry.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                        }
                    }

                    // Contraindications section
                    if (data.contraindications.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Contraindications",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        data.contraindications.forEach { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    // Source
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Source: FDA (open.fda.gov)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
