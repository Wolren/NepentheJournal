package app.journal.ui.substances.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.Substance

@Composable
internal fun EffectsSection(substance: Substance) {
    val repo = remember { JournalRepository.instance }
    val allEffects by repo.effects.collectAsState()
    var selectedEffect by remember { mutableStateOf<String?>(null) }

    // Categorized effects from DoseWiki pipeline, filtered for this substance
    val categorized = remember(substance.id, allEffects) {
        allEffects.filter { substance.id in it.substanceIds }
            .groupBy { it.category ?: "other" }
            .let { grouped ->
                val ordered = listOf("cognitive", "physical", "sensory")
                val remaining = grouped.keys.filter { it !in ordered }.sorted()
                (ordered.filter { it in grouped } + remaining).mapNotNull { cat ->
                    grouped[cat]?.let { effects -> cat to effects }
                }
            }
    }

    // Fallback: flat SMW effect list if DoseWiki hasn't been ingested
    val flatEffects = substance.effects.filterNot {
        it.matches(Regex(".+effect ?\\\\d+", RegexOption.IGNORE_CASE))
    }.sorted()

    val hasCategorized = categorized.isNotEmpty()
    val hasFlat = flatEffects.isNotEmpty()

    if (!hasCategorized && !hasFlat) return

    val categoryLabel: (String) -> String = { cat ->
        when (cat) {
            "cognitive" -> "Cognitive"
            "physical" -> "Physical"
            "sensory" -> "Sensory"
            else -> cat.replaceFirstChar { it.uppercase() }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Reported Effects", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            if (hasCategorized) {
                categorized.forEachIndexed { index, (cat, effects) ->
                    if (index > 0) {
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(
                            color = when (cat) {
                                "physical" -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                                "sensory" -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            },
                            thickness = 1.dp
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        categoryLabel(cat),
                        style = MaterialTheme.typography.titleSmall,
                        color = when (cat) {
                            "physical" -> MaterialTheme.colorScheme.tertiary
                            "sensory" -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    EffectFlowRow(effects.map { it.name }, onEffectClick = { selectedEffect = it })
                    Spacer(Modifier.height(8.dp))
                }
            } else if (hasFlat) {
                EffectFlowRow(flatEffects, onEffectClick = { selectedEffect = it })
            }

            val count = if (hasCategorized) categorized.sumOf { (_, effects) -> effects.size } else flatEffects.size
            Text("$count effects reported.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val uriHandler = LocalUriHandler.current

    selectedEffect?.let { effectName ->
        // Resolve the backing record to attribute the right source:
        // DoseWiki-ingested effects carry effect:dw: ids and often a
        // description; flat seed strings are PsychonautWiki data.
        val record = allEffects.find { it.name == effectName && substance.id in it.substanceIds }
        val isDoseWiki = record?.id?.startsWith("effect:dw:") == true
        val sourceName = if (isDoseWiki) "DoseWiki" else "PsychonautWiki"
        // Many per-substance writeups are empty; fall back to the same named
        // effect documented under another substance before giving up.
        val description = record?.description?.takeIf { it.isNotBlank() }
            ?: allEffects.firstOrNull { it.name == effectName && !it.description.isNullOrBlank() }?.description
        // PsychonautWiki effect index pages resolve as /wiki/<Name_with_underscores>
        // (verified 20 Sep 2026: Euphoria, Geometry return 200). DoseWiki effect
        // writeups are PW-forked content, so the same page applies to both.
        val readMoreUrl = record?.url
            ?: "https://psychonautwiki.org/wiki/${effectName.replace(" ", "_")}"
        AlertDialog(
            onDismissRequest = { selectedEffect = null },
            title = { Text(effectName, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    description?.let { desc ->
                        Text(desc, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "This effect is reported for ${substance.name} on $sourceName. " +
                        "Would you like to read more about it?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uriHandler.openUri(readMoreUrl)
                    selectedEffect = null
                }) {
                    Text("Read more")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEffect = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
internal fun EffectFlowRow(
    effects: List<String>,
    onEffectClick: (String) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        for (effect in effects) {
            Text(
                effect,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onEffectClick(effect) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
