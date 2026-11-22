package app.journal.ui.substances.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.rules.InteractionDedupe
import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.model.Substance
import app.journal.ui.components.AppCard

@Composable
internal fun InteractionsSection(
    substancesById: Map<String, Substance>,
    interactions: List<Interaction>,
    substanceId: String,
    onOpenSubstance: (String) -> Unit = {},
) {
    val shown = remember(interactions, substancesById) {
        InteractionDedupe.dedupe(interactions) { interactionSubstanceName(substancesById, it) }
    }
    val dangerous = shown.filter { it.riskLevel == InteractionRisk.DANGEROUS }
    val unsafe = shown.filter { it.riskLevel == InteractionRisk.UNSAFE }
    val uncertain = shown.filter {
        it.riskLevel == InteractionRisk.UNCERTAIN || it.riskLevel == InteractionRisk.UNKNOWN
    }
    val low = shown.filter { it.riskLevel == InteractionRisk.LOW }

    if (dangerous.isEmpty() && unsafe.isEmpty() && uncertain.isEmpty() && low.isEmpty()) return

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("Interactions", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            if (dangerous.isNotEmpty()) {
                InteractionSubgroup("Dangerous", dangerous, substanceId, substancesById,
                    MaterialTheme.colorScheme.error,
                    Icons.Default.Dangerous, onOpenSubstance)
            }
            if (unsafe.isNotEmpty()) {
                if (dangerous.isNotEmpty()) Spacer(Modifier.height(6.dp))
                InteractionSubgroup("Unsafe", unsafe, substanceId, substancesById,
                    MaterialTheme.colorScheme.tertiary,
                    Icons.Default.Warning, onOpenSubstance)
            }
            if (uncertain.isNotEmpty()) {
                if (dangerous.isNotEmpty() || unsafe.isNotEmpty()) Spacer(Modifier.height(6.dp))
                InteractionSubgroup("Uncertain", uncertain, substanceId, substancesById,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Icons.Default.Info, onOpenSubstance)
            }
            if (low.isNotEmpty()) {
                if (dangerous.isNotEmpty() || unsafe.isNotEmpty() || uncertain.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                }
                InteractionSubgroup("Low risk", low, substanceId, substancesById,
                    MaterialTheme.colorScheme.secondary,
                    Icons.Default.CheckCircle, onOpenSubstance)
            }
        }
    }
}

@Composable
private fun InteractionSubgroup(
    label: String,
    interactions: List<Interaction>,
    substanceId: String,
    substancesById: Map<String, Substance>,
    color: Color,
    icon: ImageVector,
    onOpenSubstance: (String) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium,
        color = color, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    interactions.forEach { interaction ->
        val otherId = if (interaction.substanceAId == substanceId)
            interaction.substanceBId else interaction.substanceAId
        val otherName = interactionSubstanceName(substancesById, otherId)
        val canOpen = otherId in substancesById
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .then(if (canOpen) Modifier.clickable { onOpenSubstance(otherId) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = otherName,
                style = MaterialTheme.typography.bodySmall,
                color = if (canOpen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (interaction.description != null) {
            Text(interaction.description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp))
        }
    }
}

internal fun interactionSubstanceName(substancesById: Map<String, Substance>, id: String): String {
    val sub = substancesById[id]
    if (sub != null) return sub.name
    if (id.startsWith("pwiki:")) {
        return id.removePrefix("pwiki:").replace("_", " ").replaceFirstChar { it.uppercase() }
    }
    if (id.startsWith("dw:")) {
        return id.removePrefix("dw:").replace("_", " ").replace("-", " ")
            .replaceFirstChar { it.uppercase() }
    }
    return id
}
