package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.InteractionCheckResult
import app.journal.log.Log
import app.journal.model.Dose
import app.journal.ui.components.InteractionWarnings
import app.journal.ui.components.AppTonalButton
import app.journal.ui.components.routeColor
import app.journal.util.formatDateShort
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun SessionDoseSection(
    sessionDoses: List<Dose>,
    interactionCheckResult: InteractionCheckResult,
    substanceNameLookup: (String) -> String,
    onAddDose: () -> Unit,
    onEditDose: (Dose) -> Unit,
    onDeleteDose: (String) -> Unit
) {
    // Doses section header
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Substances & Doses",
            style = MaterialTheme.typography.titleMedium)
        AppTonalButton(onClick = onAddDose) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add Dose")
        }
    }
    if (sessionDoses.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Science, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("No doses added yet. Tap Add Dose to start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }

    // Dose list — clickable to edit, delete button inline
    Column {
        sessionDoses.forEach { dose ->
            key(dose.id) {
                val substanceName = substanceNameLookup(dose.substanceId)
                val roaColor = routeColor(dose.routeOfAdministration)
                val doseLocal = remember(dose.timestamp) {
                    try {
                        Instant.fromEpochMilliseconds(dose.timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
                    } catch (e: Exception) { Log.withTag("SessionEdit").w(e) { "Failed to parse dose timestamp" }; null }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onEditDose(dose) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Route color indicator
                        Surface(
                            modifier = Modifier.size(4.dp, 40.dp),
                            shape = RoundedCornerShape(2.dp),
                            color = roaColor
                        ) {}
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = substanceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Surface(
                                    modifier = Modifier.size(6.dp),
                                    shape = CircleShape,
                                    color = roaColor
                                ) {}
                                Text(dose.routeOfAdministration,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = roaColor)
                            }
                            Spacer(Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = buildString {
                                        val prefix = if (dose.isDoseEstimate) "~" else ""
                                        append("$prefix${dose.amount} ${dose.unit}")
                                        if (dose.redosing) append(" · redose")
                                        if (dose.isDoseEstimate) append(" · est. ±${dose.estimatedDoseStandardDeviation}")
                                        if (dose.stomachFullness != null) append(" · ${dose.stomachFullness.label}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (doseLocal != null) {
                                Text(
                                    text = "${formatDateShort(doseLocal.date)} ${doseLocal.hour.toString().padStart(2,'0')}:${doseLocal.minute.toString().padStart(2,'0')}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                        IconButton(onClick = { onDeleteDose(dose.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    // Interaction warnings for combined substances
    if (interactionCheckResult.hasIssues || interactionCheckResult.uncertain.isNotEmpty()) {
        InteractionWarnings(
            result = interactionCheckResult,
            substanceNameLookup = substanceNameLookup,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
