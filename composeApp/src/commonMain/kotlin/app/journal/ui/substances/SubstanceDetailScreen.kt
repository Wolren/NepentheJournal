package app.journal.ui.substances

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.ui.components.*
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubstanceDetailScreen(
    substanceId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit = {}
) {
    val repo = remember { JournalRepository.instance }
    val substance = remember(substanceId) { repo.getSubstance(substanceId) }
    val allInteractions by repo.interactions.collectAsState()
    val allDoses by repo.doses.collectAsState()
    val themeManager = remember { ThemeManager.instance }

    if (substance == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Substance not found", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val relatedInteractions = remember(substanceId, allInteractions) {
        allInteractions.filter {
            it.substanceAId == substanceId || it.substanceBId == substanceId
        }
    }

    // Compute dose history once instead of 4 times
    val allDosesForSubstance = remember(allDoses, substanceId) {
        allDoses.filter { it.substanceId == substanceId }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = substance.name,
        onBack = onBack,
        actions = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    offset = DpOffset(0.dp, 0.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { menuExpanded = false; onEdit(substanceId) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; showDeleteConfirm = true },
                        leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    ) {
        // Greeting / top section
        item {
            Spacer(Modifier.height(4.dp))
                Text(substance.name, style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                if (substance.aliases.isNotEmpty()) {
                    Text(
                        substance.aliases.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (substance.substanceClass.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        substance.substanceClass.forEach { cls ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(cls, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            // Summary
            if (!substance.summary.isNullOrBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            substance.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            // Dosage
            if (substance.dosageBands.isNotEmpty()) {
                item {
                    SectionCard(title = "Dosage") {
                        substance.dosageBands.forEach { (band, value) ->
                            DetailRow(band.replaceFirstChar { it.uppercase() }, value)
                        }
                    }
                }
            }

            // Duration
            if (substance.durationProfile.isNotEmpty()) {
                item {
                    SectionCard(title = "Duration") {
                        substance.durationProfile.forEach { (phase, value) ->
                            DetailRow(phase.replaceFirstChar { it.uppercase() }, value)
                        }
                    }
                }
            }

            // Routes
            if (substance.routesOfAdministration.isNotEmpty()) {
                item {
                    SectionCard(title = "Routes of Administration") {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            substance.routesOfAdministration.forEach { roa ->
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(roa, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }

            // Effects
            item {
                EffectsSection(substance = substance)
            }

            // Interactions
            if (relatedInteractions.isNotEmpty()) {
                item {
                    InteractionsSection(interactions = relatedInteractions, substanceId = substanceId)
                }
            }

            // Cross-tolerances
            if (substance.crossTolerances.isNotEmpty()) {
                item {
                    SectionCard(title = "Cross-Tolerances") {
                        substance.crossTolerances.forEach { tolerance ->
                            Text(tolerance, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }

            // Addiction potential
            if (substance.addictionPotential != null) {
                item {
                    SectionCard(
                        title = "Addiction Potential",
                        accentColor = when {
                            substance.addictionPotential.contains("High") -> MaterialTheme.colorScheme.error
                            substance.addictionPotential.contains("Moderate") -> MaterialTheme.colorScheme.tertiary
                            else -> null
                        }
                    ) {
                        Text(substance.addictionPotential, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Toxicity
            if (substance.toxicity.isNotEmpty()) {
                item {
                    SectionCard(title = "Toxicity", danger = true) {
                        substance.toxicity.forEach { warning ->
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp))
                                Text(warning, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }
            }

            // Source info
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Source: ${substance.sourceVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (substance.cachedAt > 0) {
                            Text("Cached",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Ingestion history (doses of this substance)
            item {
                val now = currentTimeMillis()
                val dayMs = 86400000L
                if (allDosesForSubstance.isNotEmpty()) {
                    val totalDoseLast30 = allDosesForSubstance.filter { now - it.timestamp < 30L * dayMs }
                        .sumOf { it.amount }
                    val lastDose = allDosesForSubstance.maxByOrNull { it.timestamp }

                    SectionCard(title = "Ingestion History") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("All time doses:", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${allDosesForSubstance.size}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Last 30 days:", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$totalDoseLast30 ${lastDose?.unit ?: "dose"}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (lastDose != null) {
                            val tz = TimeZone.currentSystemDefault()
                            val lastLocal = Instant.fromEpochMilliseconds(lastDose.timestamp).toLocalDateTime(tz)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Last dose:", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${lastLocal.year}-${(lastLocal.month.ordinal + 1).toString().padStart(2,'0')}-${lastLocal.day.toString().padStart(2,'0')}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Tolerance timeline
            item {
                if (allDosesForSubstance.isNotEmpty()) {
                    ToleranceTimelineSection(doses = allDosesForSubstance, substanceName = substance.name, isDark = themeManager.isDarkTheme())
                }
            }

            // Chronological ingestions
            item {
                if (allDosesForSubstance.isNotEmpty()) {
                    Text("Dose History", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (allDosesForSubstance.isNotEmpty()) {
                val sorted = allDosesForSubstance.sortedByDescending { it.timestamp }
                items(sorted, key = { it.id }) { dose ->
                    val session = repo.getSession(dose.sessionId)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    buildString {
                                        append("${dose.amount} ")
                                        if (dose.isDoseEstimate) append("±${dose.estimatedDoseStandardDeviation} ")
                                        append("${dose.unit} - ${dose.routeOfAdministration}")
                                        if (dose.redosing) append(" (redose)")
                                    },
                                    style = MaterialTheme.typography.bodySmall)
                                if (session != null) {
                                    Text(session.title, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            val tz = TimeZone.currentSystemDefault()
                            val dLocal = Instant.fromEpochMilliseconds(dose.timestamp).toLocalDateTime(tz)
                            Text("${dLocal.month.ordinal + 1}/${dLocal.day}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${substance.name}?") },
            text = { Text("This will also remove all doses referencing this substance. Sessions referencing it will need updating.") },
            confirmButton = {
                AppTextButton(onClick = {
                    repo.deleteSubstance(substanceId)
                    showDeleteConfirm = false
                    onBack()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                AppTextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: androidx.compose.ui.graphics.Color? = null,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (danger) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = accentColor ?: MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun EffectsSection(substance: Substance) {
    val effects = substance.effects
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Reported Effects", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            if (effects.isEmpty()) {
                Text("No effect data cached for this substance. Import from PsychonautWiki to populate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    userScrollEnabled = false
                ) {
                    items(effects.size) { i ->
                        val effect = effects[i]
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.wrapContentSize()
                        ) {
                            Text(
                                effect,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
                Text("${effects.size} effects reported on PsychonautWiki.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun InteractionsSection(
    interactions: List<Interaction>,
    substanceId: String
) {
    val dangerous = interactions.filter { it.riskLevel == InteractionRisk.DANGEROUS }
    val unsafe = interactions.filter { it.riskLevel == InteractionRisk.UNSAFE }
    val uncertain = interactions.filter {
        it.riskLevel == InteractionRisk.UNCERTAIN || it.riskLevel == InteractionRisk.UNKNOWN
    }

    if (dangerous.isNotEmpty()) {
        InteractionGroup("Dangerous Interactions", dangerous, substanceId,
            MaterialTheme.colorScheme.error)
    }
    if (unsafe.isNotEmpty()) {
        InteractionGroup("Unsafe Interactions", unsafe, substanceId,
            MaterialTheme.colorScheme.tertiary)
    }
    if (uncertain.isNotEmpty()) {
        InteractionGroup("Uncertain Interactions", uncertain, substanceId,
            MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InteractionGroup(
    title: String,
    interactions: List<Interaction>,
    substanceId: String,
    color: androidx.compose.ui.graphics.Color
) {
    val repo = remember { JournalRepository.instance }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = color, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            interactions.forEach { interaction ->
                val otherId = if (interaction.substanceAId == substanceId)
                    interaction.substanceBId else interaction.substanceAId
                val otherSub = repo.getSubstance(otherId)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = when (interaction.riskLevel) {
                            InteractionRisk.DANGEROUS -> Icons.Default.Dangerous
                            InteractionRisk.UNSAFE -> Icons.Default.Warning
                            else -> Icons.Default.Info
                        },
                        contentDescription = null,
                        tint = when (interaction.riskLevel) {
                            InteractionRisk.DANGEROUS -> MaterialTheme.colorScheme.error
                            InteractionRisk.UNSAFE -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = otherSub?.name ?: otherId,
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
    }
}

@Composable
private fun ToleranceTimelineSection(doses: List<Dose>, substanceName: String, isDark: Boolean) {
    val now = currentTimeMillis()
    val dayMs = 86400000L
    val lookbackDays = 90
    val sorted = doses.sortedBy { it.timestamp }
    if (sorted.isEmpty()) return

    SectionCard(title = "Tolerance Timeline (90 days)") {
        val lineColor = AdaptiveColors.colorFor(substanceName).getComposeColor(isDark)
        Canvas(modifier = Modifier.fillMaxWidth().height(60.dp)) {
            val w = size.width
            val h = size.height
            val start = now - lookbackDays * dayMs

            // Baseline
            drawLine(Color.Gray.copy(alpha = 0.2f), Offset(0f, h), Offset(w, h), strokeWidth = 2f)

            // Dose markers as vertical lines
            sorted.forEach { dose ->
                if (dose.timestamp > start) {
                    val x = ((dose.timestamp - start).toFloat() / (lookbackDays * dayMs)) * w
                    val relHeight = (dose.amount / 500.0).coerceIn(0.05, 1.0).toFloat() * h
                    drawLine(
                        lineColor,
                        Offset(x, h), Offset(x, h - relHeight), strokeWidth = 2f
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${lookbackDays}d ago", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Today", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Each vertical line is a dose. Height = relative amount.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
