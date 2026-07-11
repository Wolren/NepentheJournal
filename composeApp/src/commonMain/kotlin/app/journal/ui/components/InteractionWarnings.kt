package app.journal.ui.components

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
import app.journal.data.InteractionCheckResult
import app.journal.model.InteractionRisk

/**
 * Displays interaction warnings for a set of substance pairs.
 * Shows dangerous (red), unsafe (amber), and uncertain (gray) groups.
 */
@Composable
fun InteractionWarnings(
    result: InteractionCheckResult,
    substanceNameLookup: (String) -> String,
    modifier: Modifier = Modifier
) {
    if (!result.hasIssues && result.uncertain.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (result.dangerous.isNotEmpty()) {
            InteractionGroup(
                title = "Dangerous Combinations",
                pairs = result.dangerous,
                riskLevel = InteractionRisk.DANGEROUS,
                substanceNameLookup = substanceNameLookup
            )
        }
        if (result.unsafe.isNotEmpty()) {
            InteractionGroup(
                title = "Unsafe Combinations",
                pairs = result.unsafe,
                riskLevel = InteractionRisk.UNSAFE,
                substanceNameLookup = substanceNameLookup
            )
        }
        if (result.uncertain.isNotEmpty()) {
            InteractionGroup(
                title = "Uncertain Combinations",
                pairs = result.uncertain,
                riskLevel = InteractionRisk.UNCERTAIN,
                substanceNameLookup = substanceNameLookup
            )
        }
    }
}

@Composable
private fun InteractionGroup(
    title: String,
    pairs: List<Pair<String, String>>,
    riskLevel: InteractionRisk,
    substanceNameLookup: (String) -> String
) {
    val color = InteractionColors.color(riskLevel)
    val bgColor = InteractionColors.backgroundColor(riskLevel)

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = when (riskLevel) {
                        InteractionRisk.DANGEROUS -> Icons.Default.Warning
                        InteractionRisk.UNSAFE -> Icons.Default.Warning
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "$title (${pairs.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
            Spacer(Modifier.height(4.dp))
            pairs.forEach { (a, b) ->
                Row(
                    modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${substanceNameLookup(a)} + ${substanceNameLookup(b)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Compact inline warning that summarizes dangerous interactions
 * for placement next to a dose entry.
 */
@Composable
fun InlineInteractionWarning(
    level: InteractionRisk,
    otherSubstanceName: String,
    modifier: Modifier = Modifier
) {
    val color = InteractionColors.color(level)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = when (level) {
                InteractionRisk.DANGEROUS -> "Dangerous with $otherSubstanceName"
                InteractionRisk.UNSAFE -> "Unsafe with $otherSubstanceName"
                else -> "Interaction with $otherSubstanceName"
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}
