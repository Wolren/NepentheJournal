package app.journal.ui.substances

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.data.DoseWikiLookup
import app.journal.model.*
import app.journal.ui.components.*
import app.journal.ui.theme.AdaptiveColors
import app.journal.ui.theme.ThemeManager
import app.journal.util.currentTimeMillis
import app.journal.util.formatDateShort
import app.journal.ui.substances.detail.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubstanceDetailScreen(
    repo: JournalRepository = JournalRepository.instance,
    substanceId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit = {},
    onCompanion: () -> Unit = {},
) {
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

    val allDosesForSubstance = remember(allDoses, substanceId) {
        allDoses.filter { it.substanceId == substanceId }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Pre-load DoseWiki duration data for the duration curve
    val doseWikiDuration = remember(substance.name) {
        DoseWikiLookup.getDuration(substance.name)
    }

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
                        text = { Text("Usage history") },
                        onClick = { menuExpanded = false; onCompanion() },
                        leadingIcon = { Icon(Icons.Default.Timeline, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { menuExpanded = false; onEdit(substanceId) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) }
                    )
                    if (substance.deviceOrigin != "system") {
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; showDeleteConfirm = true },
                            leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        }
    ) {
        // Greeting / top section
        item {
            Spacer(Modifier.height(4.dp))
            SelectableText(substance.name, style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold
            ))
            if (substance.aliases.isNotEmpty()) {
                SelectableText(
                    text = substance.aliases.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Spacer(Modifier.height(4.dp))
            if (substance.substanceClass.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    substance.substanceClass.forEach { cls ->
                        val clsColor = substanceClassColor(cls)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = clsColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                cls,
                                style = MaterialTheme.typography.labelSmall,
                                color = clsColor,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
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
                    Column(modifier = Modifier.padding(16.dp)) {
                        substance.summary?.split("\n\n")?.forEachIndexed { i, paragraph ->
                            if (i > 0) Spacer(Modifier.height(8.dp))
                            SelectableText(
                                text = paragraph.trim(),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }

        // Dosage
        if (substance.dosageBands.isNotEmpty()) {
            item {
                SectionCard(title = "Dosage") {
                    val orderedBands = listOf("threshold", "light", "common", "strong", "heavy")
                    val bandLabels = mapOf(
                        "threshold" to "Thresh", "light" to "Light",
                        "common" to "Common", "strong" to "Strong", "heavy" to "Heavy"
                    )
                    val bandColors = mapOf(
                        "threshold" to Color(0xFF9E9E9E),
                        "light" to Color(0xFF66BB6A),
                        "common" to Color(0xFF42A5F5),
                        "strong" to Color(0xFFFFA726),
                        "heavy" to Color(0xFFEF5350)
                    )
                    val entries = orderedBands.mapNotNull { band ->
                        substance.dosageBands[band]?.let { band to it }
                    }
                    if (entries.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            entries.forEach { (band, value) ->
                                val color = bandColors[band] ?: Color.Gray
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        value,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = color,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().height(3.dp),
                                        color = color.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(2.dp)
                                    ) {}
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        bandLabels[band] ?: band,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color.copy(alpha = 0.8f)
                                    )
                                }
                                if (band != entries.last().first) {
                                    Text("-", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        modifier = Modifier.padding(horizontal = 2.dp))
                                }
                            }
                        }
                    } else {
                        substance.dosageBands.forEach { (band, value) ->
                            DetailRow(band.replaceFirstChar { it.uppercase() }, value)
                        }
                    }
                }
            }
        }

        // Duration
        if (substance.durationProfile.isNotEmpty()) {
            item { DurationTimelineSection(profile = substance.durationProfile, doseWikiDuration = doseWikiDuration) }
        }

        // Pharmacology
        item { PharmacologySection(substance = substance) }

        // Routes
        if (substance.routesOfAdministration.isNotEmpty()) {
            item {
                SectionCard(title = "Routes of Administration") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        substance.routesOfAdministration.forEach { roa ->
                            RouteChip(route = roa)
                        }
                    }
                }
            }
        }

        // Effects
        item { EffectsSection(substance = substance) }

        // Interactions
        if (relatedInteractions.isNotEmpty()) {
            item { InteractionsSection(interactions = relatedInteractions, substanceId = substanceId) }
        }

        // FDA drug interaction data
        item { OpenFdaInteractionCard(substanceId = substanceId, substanceName = substance.name) }

        // Cross-tolerances
        if (substance.crossTolerances.isNotEmpty()) {
            item {
                val toleranceTimes = substance.crossTolerances.filter {
                    it.startsWith("Full tolerance:") || it.startsWith("Half tolerance:") ||
                    it.startsWith("Zero tolerance:")
                }.map { cleanWikiMarkup(it) }

                val crossSubstances = substance.crossTolerances.filter {
                    !it.startsWith("Full tolerance:") && !it.startsWith("Half tolerance:") &&
                    !it.startsWith("Zero tolerance:")
                }.map { cleanWikiMarkup(it) }

                SectionCard(title = "Tolerance") {
                    if (toleranceTimes.isNotEmpty()) {
                        toleranceTimes.forEach { entry ->
                            val icon = when {
                                entry.startsWith("Full tolerance:") -> Icons.Default.Timer
                                entry.startsWith("Half tolerance:") -> Icons.Default.TimerOff
                                else -> Icons.Default.Restore
                            }
                            val label = when {
                                entry.startsWith("Full tolerance:") -> "Full"
                                entry.startsWith("Half tolerance:") -> "Half"
                                else -> "Zero"
                            }
                            val time = entry.substringAfter(": ").trim()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            ) {
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp))
                                Text(label, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold)
                                Text(time, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (crossSubstances.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (crossSubstances.isNotEmpty()) {
                        Text("Cross-tolerance with:", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            crossSubstances.forEach { sub ->
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.secondaryContainer,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Addiction potential
        if (substance.addictionPotential != null) {
            item {
                val apText = substance.addictionPotential ?: ""
                val apSeverity = when {
                    apText.contains("High") -> 3
                    apText.contains("Moderate") -> 2
                    apText.contains("Low") -> 1
                    else -> 0
                }
                val apColor = when (apSeverity) {
                    3 -> MaterialTheme.colorScheme.error
                    2 -> MaterialTheme.colorScheme.tertiary
                    1 -> Color(0xFF66BB6A)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                SectionCard(title = "Addiction Potential", accentColor = apColor) {
                    if (apSeverity > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf("Low", "Moderate", "High").forEachIndexed { i, label ->
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = if (i <= apSeverity - 1) apColor.copy(alpha = 0.6f)
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(2.dp)
                                    ) {}
                                    Spacer(Modifier.height(2.dp))
                                    Text(label, style = MaterialTheme.typography.labelSmall,
                                        color = if (i == apSeverity - 1) apColor
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(apText, style = MaterialTheme.typography.bodyMedium)
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
                            Text(warning, style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onErrorContainer))
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
                    Text("Source: ${formatSource(substance.sourceVersion)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant))
                    if (substance.cachedAt > 0) {
                        Text("Cached",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Ingestion history chart
        item {
            if (allDosesForSubstance.isNotEmpty()) {
                SectionCard(title = "Ingestion History") {
                    DoseTimelineChart(doses = allDosesForSubstance, substanceName = substance.name)
                }
            }
        }

        // Tolerance timeline
        item {
            if (allDosesForSubstance.isNotEmpty()) {
                ToleranceTimelineSection(
                    doses = allDosesForSubstance,
                    substanceName = substance.name,
                    isDark = themeManager.isDarkTheme()
                )
            }
        }

        // Dose history
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
                                    if (dose.isDoseEstimate) append("\u00B1${dose.estimatedDoseStandardDeviation} ")
                                    append("${dose.unit} - ${dose.routeOfAdministration}")
                                    if (dose.redosing) append(" (redose)")
                                }.toString(),
                                style = MaterialTheme.typography.bodySmall)
                            if (session != null) {
                                Text(session.title, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val tz = TimeZone.currentSystemDefault()
                        val dLocal = Instant.fromEpochMilliseconds(dose.timestamp).toLocalDateTime(tz)
                        Text(formatDateShort(dLocal.date),
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
