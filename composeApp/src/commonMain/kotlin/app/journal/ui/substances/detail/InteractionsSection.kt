package app.journal.ui.substances.detail

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
import app.journal.data.JournalRepository
import app.journal.model.Interaction
import app.journal.model.InteractionRisk

@Composable
internal fun InteractionsSection(
    interactions: List<Interaction>,
    substanceId: String
) {
    val repo = remember { JournalRepository.instance }
    val dangerous = interactions.filter { it.riskLevel == InteractionRisk.DANGEROUS }
    val unsafe = interactions.filter { it.riskLevel == InteractionRisk.UNSAFE }
    val uncertain = interactions.filter {
        it.riskLevel == InteractionRisk.UNCERTAIN || it.riskLevel == InteractionRisk.UNKNOWN
    }

    if (dangerous.isEmpty() && unsafe.isEmpty() && uncertain.isEmpty()) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Interactions", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            if (dangerous.isNotEmpty()) {
                InteractionSubgroup("Dangerous", dangerous, substanceId, repo,
                    MaterialTheme.colorScheme.error,
                    Icons.Default.Dangerous)
            }
            if (unsafe.isNotEmpty()) {
                if (dangerous.isNotEmpty()) Spacer(Modifier.height(6.dp))
                InteractionSubgroup("Unsafe", unsafe, substanceId, repo,
                    MaterialTheme.colorScheme.tertiary,
                    Icons.Default.Warning)
            }
            if (uncertain.isNotEmpty()) {
                if (dangerous.isNotEmpty() || unsafe.isNotEmpty()) Spacer(Modifier.height(6.dp))
                InteractionSubgroup("Uncertain", uncertain, substanceId, repo,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Icons.Default.Info)
            }
        }
    }
}

@Composable
private fun InteractionSubgroup(
    label: String,
    interactions: List<Interaction>,
    substanceId: String,
    repo: JournalRepository,
    color: Color,
    icon: ImageVector
) {
    Text(label, style = MaterialTheme.typography.labelMedium,
        color = color, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    interactions.forEach { interaction ->
        val otherId = if (interaction.substanceAId == substanceId)
            interaction.substanceBId else interaction.substanceAId
        val otherName = interactionSubstanceName(repo, otherId)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
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
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (interaction.description != null) {
            Text(interaction.description, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp))
        }
    }
}

internal fun interactionSubstanceName(repo: JournalRepository, id: String): String {
    val sub = repo.getSubstance(id)
    if (sub != null) return sub.name
    if (id.startsWith("pwiki:")) {
        return id.removePrefix("pwiki:").replace("_", " ").replaceFirstChar { it.uppercase() }
    }
    return id
}
