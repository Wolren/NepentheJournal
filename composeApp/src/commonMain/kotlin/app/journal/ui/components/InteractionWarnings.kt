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
import app.journal.model.InteractionRisk
import app.journal.model.rules.ActiveInteractionResult
import app.journal.model.rules.InteractionWarningLevel

/** One warning line. [message] is set for class-rule warnings, null for catalog pairs. */
private data class WarningRow(val a: String, val b: String, val message: String?)

private data class WarningGroup(
    val title: String,
    val riskLevel: InteractionRisk,
    val rows: List<WarningRow>,
)

/**
 * Renders every warning the active set produces: catalog pair risks plus class-rule
 * risks, including standing medications and doses that are still active.
 */
@Composable
fun InteractionWarnings(
    result: ActiveInteractionResult,
    substanceNameLookup: (String) -> String,
    modifier: Modifier = Modifier,
) {
    if (result.isEmpty) return
    val groups = remember(result) { buildGroups(result, substanceNameLookup) }
    val medicationNames = remember(result.medicationNames) {
        result.medicationNames.map { it.trim().lowercase() }.toSet()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        sourceCaption(result)?.let { caption ->
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        groups.forEach { group ->
            if (group.rows.isNotEmpty()) {
                InteractionGroup(group, substanceNameLookup, medicationNames)
            }
        }
    }
}

/** Explains which non-session sources were checked, so a warning is never unexplained. */
private fun sourceCaption(result: ActiveInteractionResult): String? {
    val parts = buildList {
        val meds = result.medicationNames.size
        if (meds > 0) add(if (meds == 1) "1 standing medication" else "$meds standing medications")
        val recent = result.recentSubstanceNames.size
        if (recent > 0) add(if (recent == 1) "1 recent dose" else "$recent recent doses")
    }
    if (parts.isEmpty()) return null
    return "Includes ${parts.joinToString(" and ")}."
}

private fun buildGroups(
    result: ActiveInteractionResult,
    substanceNameLookup: (String) -> String,
): List<WarningGroup> {
    val dangerous = linkedMapOf<String, WarningRow>()
    val unsafe = linkedMapOf<String, WarningRow>()
    val uncertain = linkedMapOf<String, WarningRow>()
    val notes = linkedMapOf<String, WarningRow>()

    fun keyOf(row: WarningRow): String {
        val a = substanceNameLookup(row.a).trim().lowercase()
        val b = substanceNameLookup(row.b).trim().lowercase()
        return if (a <= b) "$a|$b" else "$b|$a"
    }

    fun put(into: MutableMap<String, WarningRow>, row: WarningRow) {
        val key = keyOf(row)
        val existing = into[key]
        if (existing == null || existing.message == null) into[key] = row
    }

    result.pairs.dangerous.forEach { (a, b) -> put(dangerous, WarningRow(a, b, null)) }
    result.pairs.unsafe.forEach { (a, b) -> put(unsafe, WarningRow(a, b, null)) }
    result.pairs.uncertain.forEach { (a, b) -> put(uncertain, WarningRow(a, b, null)) }
    result.classWarnings.forEach { warning ->
        val row = WarningRow(warning.substanceA, warning.substanceB, warning.message)
        when (warning.level) {
            InteractionWarningLevel.DANGER -> put(dangerous, row)
            InteractionWarningLevel.CAUTION -> put(unsafe, row)
            InteractionWarningLevel.NOTE -> put(notes, row)
        }
    }

    return listOf(
        WarningGroup("Dangerous Combinations", InteractionRisk.DANGEROUS, dangerous.values.toList()),
        WarningGroup("Unsafe Combinations", InteractionRisk.UNSAFE, unsafe.values.toList()),
        WarningGroup("Uncertain Combinations", InteractionRisk.UNCERTAIN, uncertain.values.toList()),
        WarningGroup("Notes", InteractionRisk.UNCERTAIN, notes.values.toList()),
    )
}

@Composable
private fun InteractionGroup(
    group: WarningGroup,
    substanceNameLookup: (String) -> String,
    medicationNames: Set<String>,
) {
    val color = InteractionColors.color(group.riskLevel)
    val bgColor = InteractionColors.backgroundColor(group.riskLevel)

    AppCard(
        colors = appCardColors(container = bgColor),
        contentPadding = appCardPadding(dense = true),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (group.riskLevel == InteractionRisk.UNCERTAIN) {
                        Icons.Default.Info
                    } else {
                        Icons.Default.Warning
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${group.title} (${group.rows.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
            Spacer(Modifier.height(4.dp))
            group.rows.forEach { row ->
                Column(modifier = Modifier.padding(start = 22.dp, top = 2.dp)) {
                    Text(
                        text = "${displayName(row.a, substanceNameLookup, medicationNames)} + " +
                            displayName(row.b, substanceNameLookup, medicationNames),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    row.message?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

private fun displayName(
    raw: String,
    substanceNameLookup: (String) -> String,
    medicationNames: Set<String>,
): String {
    val resolved = substanceNameLookup(raw)
    return if (resolved.trim().lowercase() in medicationNames) "$resolved (medication)" else resolved
}
