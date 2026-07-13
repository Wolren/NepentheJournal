package app.journal.ui.substances

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    onEdit: (String) -> Unit = {},
    onCompanion: () -> Unit = {},
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
                item {
                    DurationTimelineSection(profile = substance.durationProfile)
                }
            }

            // Pharmacology (binding affinities: Ki, Kd, IC50, EC50)
            item {
                PharmacologySection(substance = substance)
            }

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
            item {
                EffectsSection(substance = substance)
            }

            // Interactions
            if (relatedInteractions.isNotEmpty()) {
                item {
                    InteractionsSection(interactions = relatedInteractions, substanceId = substanceId)
                }
            }

            // FDA drug interaction data
            item {
                OpenFdaInteractionCard(
                    substanceId = substanceId,
                    substanceName = substance.name
                )
            }

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
                    SectionCard(
                        title = "Addiction Potential",
                        accentColor = apColor
                    ) {
                        if (apSeverity > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOf("Low", "Moderate", "High").forEachIndexed { i, label ->
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
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
                        SelectableText(apText, style = MaterialTheme.typography.bodyMedium)
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
                                SelectableText(warning, style = MaterialTheme.typography.bodySmall.copy(
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
                        SelectableText("Source: ${formatSource(substance.sourceVersion)}",
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

            // Ingestion history (doses of this substance) — timeline chart + stats
            item {
                if (allDosesForSubstance.isNotEmpty()) {
                    SectionCard(title = "Ingestion History") {
                        DoseTimelineChart(
                            doses = allDosesForSubstance,
                            substanceName = substance.name,
                            modifier = Modifier.fillMaxWidth()
                        )
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
                                SelectableText(
                                    buildString {
                                        append("${dose.amount} ")
                                        if (dose.isDoseEstimate) append("±${dose.estimatedDoseStandardDeviation} ")
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
        SelectableText(value, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun EffectsSection(substance: Substance) {
    val repo = remember { JournalRepository.instance }
    val allEffects by repo.effects.collectAsState()
    var selectedEffect by remember { mutableStateOf<String?>(null) }

    // Categorized effects from DoseWiki pipeline, filtered for this substance
    val categorized = remember(substance.id, allEffects) {
        allEffects.filter { substance.id in it.substanceIds }
            .groupBy { it.category ?: "other" }
            .let { grouped ->
                // Order: cognitive, physical, sensory, other
                val ordered = listOf("cognitive", "physical", "sensory")
                val remaining = grouped.keys.filter { it !in ordered }.sorted()
                (ordered.filter { it in grouped } + remaining).mapNotNull { cat ->
                    grouped[cat]?.let { effects -> cat to effects }
                }
            }
    }

    // Fallback: flat SMW effect list if DoseWiki hasn't been ingested
    val flatEffects = substance.effects.filterNot {
        it.matches(Regex(".+effect ?\\d+", RegexOption.IGNORE_CASE))
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
        AlertDialog(
            onDismissRequest = { selectedEffect = null },
            title = { Text(effectName, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "This effect is reported for ${substance.name} on PsychonautWiki. " +
                    "Would you like to read more about it?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pageName = effectName.replace(" ", "_")
                    uriHandler.openUri("https://psychonautwiki.org/wiki/$pageName")
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
private fun EffectFlowRow(
    effects: List<String>,
    onEffectClick: (String) -> Unit
) {
    FlowRow(
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

private data class DurationPhase(
    val label: String,
    val minMinutes: Double,
    val maxMinutes: Double,
    val display: String
)

private fun parseDurationValue(value: String): Pair<Double, Double>? {
    val clean = value.trim()
    val parts = clean.split("\u2013", "-", "–") // en-dash, hyphen, regular dash
    val numPattern = Regex("""([\d.]+)""")
    val unitPattern = Regex("""(minute|minutes|min|hour|hours|hr|day|days)\b""", RegexOption.IGNORE_CASE)

    val nums = numPattern.findAll(clean).map { it.value.toDoubleOrNull() }.filterNotNull().toList()
    val unitMatch = unitPattern.find(clean)
    val unit = unitMatch?.value?.lowercase() ?: ""

    if (nums.isEmpty()) return null

    val min = nums.getOrElse(0) { 0.0 }
    val max = nums.getOrElse(1) { min }
    val multiplier = when {
        unit.startsWith("day") -> 1440.0
        unit.startsWith("hour") || unit.startsWith("hr") -> 60.0
        else -> 1.0
    }

    return Pair(min * multiplier, max * multiplier)
}

private fun parseDurationProfile(profile: Map<String, String>): List<DurationPhase> {
    val phaseOrder = listOf("onset", "comeup", "peak", "offset", "afterglow")
    val labels = mapOf(
        "onset" to "Onset", "comeup" to "Comeup", "peak" to "Peak",
        "offset" to "Offset", "afterglow" to "Afterglow", "total" to "Total"
    )

    return phaseOrder.mapNotNull { key ->
        val value = profile[key] ?: return@mapNotNull null
        val parsed = parseDurationValue(value) ?: return@mapNotNull null
        DurationPhase(labels[key] ?: key, parsed.first, parsed.second, value)
    }
}

private fun routeColor(route: String): Color {
    return when (route.lowercase()) {
        "oral" -> Color(0xFF66BB6A)
        "sublingual" -> Color(0xFF42A5F5)
        "insufflated", "intranasal" -> Color(0xFFAB47BC)
        "inhaled" -> Color(0xFF26C6DA)
        "vaporized" -> Color(0xFFFFA726)
        "intramuscular" -> Color(0xFFEF5350)
        "intravenous" -> Color(0xFFD32F2F)
        "rectal" -> Color(0xFF7E57C2)
        "transdermal" -> Color(0xFF43A047)
        "buccal" -> Color(0xFFEC407A)
        "subcutaneous" -> Color(0xFFFFCA28)
        else -> Color(0xFF78909C)
    }
}

private fun substanceClassColor(cls: String): Color {
    val c = cls.lowercase().trim()
    return when {
        c.contains("psychedelic") || c.contains("hallucinogen") -> Color(0xFFAB47BC)
        c.contains("stimulant") -> Color(0xFFFF7043)
        c.contains("depressant") || c.contains("sedative") -> Color(0xFF42A5F5)
        c.contains("dissociative") -> Color(0xFF26C6DA)
        c.contains("empathogen") || c.contains("entactogen") -> Color(0xFFEC407A)
        c.contains("opioid") || c.contains("opiate") -> Color(0xFFEF5350)
        c.contains("benzodiazepine") || c.contains("z-drug") -> Color(0xFFFFA726)
        c.contains("maoi") -> Color(0xFFFF6D00)
        c.contains("ssri") || c.contains("antidepressant") -> Color(0xFF66BB6A)
        c.contains("antipsychotic") -> Color(0xFF78909C)
        c.contains("anesthetic") || c.contains("nootropic") -> Color(0xFF5C6BC0)
        c.contains("deliriant") -> Color(0xFF8D6E63)
        c.contains("cannabinoid") -> Color(0xFF9CCC65)
        c.contains("alcohol") -> Color(0xFFBDBDBD)
        else -> Color(0xFF78909C)
    }
}

@Composable
private fun RouteChip(route: String) {
    val color = routeColor(route)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Surface(
            modifier = Modifier.size(14.dp),
            shape = RoundedCornerShape(7.dp),
            color = color
        ) {}
        Text(route, style = MaterialTheme.typography.labelSmall,
            color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DurationTimelineSection(profile: Map<String, String>) {
    val phases = remember(profile) { parseDurationProfile(profile) }
    val totalRaw = profile["total"]
    val totalParsed = totalRaw?.let { parseDurationValue(it) }
    val totalMax = totalParsed?.second ?: phases.maxOfOrNull { it.maxMinutes } ?: return

    if (phases.isEmpty()) return

    val barPhases = phases.filter { it.label != "Afterglow" }
    val afterglow = phases.find { it.label == "Afterglow" }

    val phaseColors = mapOf(
        "Onset" to Color(0xFF66BB6A),
        "Comeup" to Color(0xFF42A5F5),
        "Peak" to Color(0xFFEF5350),
        "Offset" to Color(0xFFFFA726),
        "Afterglow" to Color(0xFFAB47BC)
    )

    val sumOfMax = barPhases.sumOf { it.maxMinutes }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Duration", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            // 2D intensity-over-time curve (ggplot2 style)
            val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
            val axisLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)

            // Build curve data points
            data class CurvePt(val x: Float, val y: Float, val label: String, val timeLabel: String, val color: Color)
            val curvePoints = remember(barPhases, sumOfMax) {
                val pts = mutableListOf<CurvePt>()
                var acc = 0f
                barPhases.forEach { phase ->
                    val frac = (phase.maxMinutes / sumOfMax).toFloat().coerceAtLeast(0.04f)
                    val x = acc + frac / 2f
                    val y = when (phase.label) {
                        "Onset" -> 0.25f; "Comeup" -> 0.75f; "Peak" -> 1f; "Offset" -> 0.15f; else -> 0.5f
                    }
                    val time = phase.display.split("–", "-", "—").firstOrNull()?.trim() ?: ""
                    pts.add(CurvePt(x, y, phase.label, time, phaseColors[phase.label] ?: Color.Gray))
                    acc += frac
                }
                pts.toList()
            }

            Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                // Canvas: grid, curve, points
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val lPad = 14f
                    val rPad = 4f
                    val tPad = 4f
                    val bPad = 4f
                    val plotW = w - lPad - rPad
                    val plotH = h - tPad - bPad

                    // Horizontal grid lines
                    for (i in 0..4) {
                        val y = tPad + plotH * (1f - i / 4f)
                        drawLine(gridColor, Offset(lPad, y), Offset(w - rPad, y), strokeWidth = 0.5f)
                    }

                    if (curvePoints.size >= 2) {
                        val path = androidx.compose.ui.graphics.Path()
                        val firstX = lPad + curvePoints[0].x * plotW
                        val firstY = tPad + plotH * (1f - curvePoints[0].y)
                        path.moveTo(firstX, firstY)

                        for (i in 0 until curvePoints.size - 1) {
                            val p0 = curvePoints[i]; val p1 = curvePoints[i + 1]
                            val x0 = lPad + p0.x * plotW; val y0 = tPad + plotH * (1f - p0.y)
                            val x1 = lPad + p1.x * plotW; val y1 = tPad + plotH * (1f - p1.y)
                            path.cubicTo((x0 + x1) / 2f, y0, (x0 + x1) / 2f, y1, x1, y1)
                        }

                        // Fill
                        val fill = androidx.compose.ui.graphics.Path().apply {
                            addPath(path)
                            val last = curvePoints.last()
                            lineTo(lPad + last.x * plotW, tPad + plotH)
                            lineTo(firstX, tPad + plotH)
                            close()
                        }
                        drawPath(fill, curvePoints.last().color.copy(alpha = 0.10f))
                        // Curve line (thick, emulated by drawing 3 overlapping lines)
                        drawPath(path, curvePoints.last().color.copy(alpha = 0.3f), style = Stroke(width = 4f))
                        drawPath(path, curvePoints.last().color, style = Stroke(width = 2.5f))
                    }

                    // Points + drop lines
                    curvePoints.forEach { pt ->
                        val cx = lPad + pt.x * plotW
                        val cy = tPad + plotH * (1f - pt.y)
                        drawLine(gridColor.copy(alpha = 0.15f), Offset(cx, cy), Offset(cx, tPad + plotH), strokeWidth = 0.5f)
                        drawCircle(Color.White, radius = 5f, center = Offset(cx, cy))
                        drawCircle(pt.color, radius = 3.5f, center = Offset(cx, cy))
                    }

                    // X-axis line
                    drawLine(axisLineColor,
                        Offset(lPad, tPad + plotH), Offset(w - rPad, tPad + plotH), strokeWidth = 1f)
                }

                // Y-axis labels (overlaid on the left)
                Column(
                    modifier = Modifier.fillMaxHeight().padding(start = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("100%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("75%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("50%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("25%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text("0%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }

                // Y-axis title
                Text("↑ Intensity",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.TopEnd))

                // X-axis labels (overlaid at bottom)
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(start = 14.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    curvePoints.forEach { pt ->
                        Text(pt.label, style = MaterialTheme.typography.labelSmall,
                            color = pt.color, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Phase tiles with exact time ranges
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                barPhases.forEach { phase ->
                    val color = phaseColors[phase.label] ?: Color.Gray
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = color.copy(alpha = 0.10f),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
                        ) {
                            Text(phase.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = color, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(phase.display,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }

            // Afterglow
            if (afterglow != null) {
                val agColor = phaseColors["Afterglow"] ?: Color.Gray
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = agColor.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Afterglow", style = MaterialTheme.typography.labelSmall,
                            color = agColor, fontWeight = FontWeight.SemiBold)
                        Text(afterglow.display, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Total — visual bar
            if (totalRaw != null) {
                Spacer(Modifier.height(8.dp))
                val totalMin = totalParsed?.first?.let {
                    val h = (it / 60.0)
                    if (h >= 1) "${"%.1f".format(h)} hr" else "${"%.0f".format(it)} min"
                } ?: ""
                val totalMaxStr = totalParsed?.second?.let {
                    val h = (it / 60.0)
                    if (h >= 1) "${"%.1f".format(h)} hr" else "${"%.0f".format(it)} min"
                } ?: ""

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Total duration", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                        // Visual bar
                        Box(modifier = Modifier.weight(1f).height(8.dp)) {
                            val totalColor = MaterialTheme.colorScheme.primary
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = totalColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {}
                            Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                                val frac = if (totalParsed != null && totalParsed.second > 0)
                                    (totalParsed.first / totalParsed.second).toFloat().coerceIn(0.1f, 1f)
                                else 0.3f
                                drawRoundRect(
                                    totalColor.copy(alpha = 0.5f),
                                    size = Size(size.width * frac, size.height),
                                    cornerRadius = CornerRadius(4f, 4f)
                                )
                            }
                        }

                        Text(
                            if (totalMin == totalMaxStr) totalMaxStr else "$totalMin - $totalMaxStr",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractionsSection(
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
    color: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
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

private fun interactionSubstanceName(repo: JournalRepository, id: String): String {
    val sub = repo.getSubstance(id)
    if (sub != null) return sub.name
    if (id.startsWith("pwiki:")) {
        return id.removePrefix("pwiki:").replace("_", " ").replaceFirstChar { it.uppercase() }
    }
    return id
}

/** Strips wiki markup like [[Target|Display]] or [[Target]] from a string. */
private fun cleanWikiMarkup(text: String): String {
    return text.replace(Regex("\\[\\[([^|\\]]+)\\|([^\\]]+)\\]\\]")) { it.groupValues[2] }
        .replace(Regex("\\[\\[([^\\]]+)\\]\\]")) { it.groupValues[1] }
}

/** Converts internal source version codes to human-readable labels. */
private fun formatSource(version: String): String {
    return when {
        version.startsWith("pwiki-") -> "PsychonautWiki"
        version.startsWith("tripsit") -> "TripSit"
        version.startsWith("wikidata") -> "Wikidata"
        version.startsWith("pubchem") || version.startsWith("pubsci") -> "PubChem"
        version.startsWith("chembl") -> "ChEMBL"
        version == "test" -> "Test data"
        else -> version
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

// ── Pharmacology section ─────────────────────────────────────────────────

private val AFFINITY_LOG_MIN = -2.0  // 0.01 nM
private val AFFINITY_LOG_MAX = 4.0   // 10000 nM

private enum class AffinityStrength(
    val label: String,
    val min: Double,
    val max: Double,
) {
    VERY_STRONG("v.strong", Double.NEGATIVE_INFINITY, 1.0),
    STRONG("strong", 1.0, 10.0),
    MODERATE("moderate", 10.0, 100.0),
    WEAK("weak", 100.0, 1000.0),
    VERY_WEAK("v.weak", 1000.0, Double.POSITIVE_INFINITY);

    companion object {
        fun fromNanoMolar(nm: Double?): AffinityStrength? {
            if (nm == null || nm.isNaN()) return null
            return entries.firstOrNull { nm >= it.min && nm < it.max }
        }
    }
}

private fun affinityStrengthColor(strength: AffinityStrength): Color = when (strength) {
    AffinityStrength.VERY_STRONG -> Color(0xFFE53935)
    AffinityStrength.STRONG -> Color(0xFFFB8C00)
    AffinityStrength.MODERATE -> Color(0xFF7CB342)
    AffinityStrength.WEAK -> Color(0xFF42A5F5)
    AffinityStrength.VERY_WEAK -> Color(0xFF78909C)
}

@Composable
private fun PharmacologySection(substance: Substance) {
    val bindingdbRecords = substance.bindingdbData?.records.orEmpty()
    val pdspRecords = substance.pdspData?.records.orEmpty()
    val totalRecords = bindingdbRecords.size + pdspRecords.size
    if (totalRecords == 0) return

    var expanded by remember { mutableStateOf(false) }

    data class RawEntry(
        val targetName: String,
        val species: String?,
        val affinityType: String,
        val affinityNM: Double?,
        val source: String,
    )

    val groups = remember(substance) {
        val raw = mutableListOf<RawEntry>()
        bindingdbRecords.forEach { r ->
            raw.add(
                RawEntry(
                    targetName = r.targetName ?: "Unknown target",
                    species = r.species,
                    affinityType = r.affinityType ?: "?",
                    affinityNM = r.affinityNM,
                    source = "BDB",
                )
            )
        }
        pdspRecords.forEach { r ->
            raw.add(
                RawEntry(
                    targetName = r.targetName ?: "Unknown target",
                    species = r.species ?: "Human",
                    affinityType = "Ki",
                    affinityNM = r.kiNanoMolar,
                    source = "PDSP",
                )
            )
        }
        val byTargetType = raw.groupBy { Pair(it.targetName, it.affinityType) }
        val aggByTarget = mutableMapOf<String, MutableList<Pair<String, AggEntry>>>()
        byTargetType.forEach { (key, entries) ->
            val (target, type) = key
            val values = entries.mapNotNull { it.affinityNM }.sorted()
            val medianNM = if (values.isEmpty()) null
                else if (values.size % 2 == 1) values[values.size / 2]
                else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
            aggByTarget.getOrPut(target) { mutableListOf() }.add(
                target to AggEntry(
                    affinityType = type,
                    minNM = values.minOrNull(),
                    medianNM = medianNM,
                    maxNM = values.maxOrNull(),
                    count = entries.size,
                    sources = entries.map { it.source }.toSet(),
                    species = entries.mapNotNull { it.species }.toSet(),
                )
            )
        }
        aggByTarget.mapValues { (_, es) -> es.sortedBy { (_, a) -> a.medianNM ?: Double.MAX_VALUE } }
            .toList().sortedBy { (_, es) -> es.firstOrNull()?.second?.medianNM ?: Double.MAX_VALUE }
    }

    val totalGroups = groups.sumOf { (_, es) -> es.size }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Clickable header
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = true) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Pharmacology",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!expanded) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                "$totalRecords",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text(
                    "$totalRecords measures in $totalGroups groups across ${groups.size} targets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                groups.forEach { (target, entries) ->
                    val allSpecies = entries.flatMap { (_, a) -> a.species }.toSet()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(target, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        allSpecies.forEach { sp ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    sp,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.8f),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    entries.forEach { (_, a) ->
                        AffinityBar(
                            affinityType = a.affinityType,
                            minNM = a.minNM,
                            medianNM = a.medianNM,
                            maxNM = a.maxNM,
                            count = a.count,
                            sources = a.sources,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                AffinityLegend()
            }
        }
    }
}

private data class AggEntry(
    val affinityType: String,
    val minNM: Double?,
    val medianNM: Double?,
    val maxNM: Double?,
    val count: Int,
    val sources: Set<String>,
    val species: Set<String>,
)

@Composable
private fun AffinityBar(
    affinityType: String,
    minNM: Double?,
    medianNM: Double?,
    maxNM: Double?,
    count: Int,
    sources: Set<String>,
) {
    val medianStrength = medianNM?.let { AffinityStrength.fromNanoMolar(it) }
    val color = medianStrength?.let { affinityStrengthColor(it) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val dimColor = color.copy(alpha = 0.3f)

    val isRange = count > 1 && minNM != null && maxNM != null && minNM != maxNM

    val lStyle = MaterialTheme.typography.labelSmall
    val tinySize = lStyle.fontSize * 0.75f

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Type label
        Text(
            affinityType,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(32.dp),
        )

        // Visual log-scale affinity bar
        Box(modifier = Modifier.width(100.dp).height(18.dp)) {
            if (isRange) {
                val mn = minNM; val mx = maxNM; val md = medianNM
                fun lpos(nm: Double): Float = ((kotlin.math.log10(nm.coerceIn(0.001, 99999.0)) - AFFINITY_LOG_MIN) / (AFFINITY_LOG_MAX - AFFINITY_LOG_MIN)).toFloat().coerceIn(0f, 1f)
                val minP = lpos(mn); val maxP = lpos(mx); val medP = if (md != null) lpos(md) else minP

                Canvas(Modifier.fillMaxSize()) {
                    val by = size.height / 2; val bh = 7.dp.toPx()
                    val bx = minP * size.width; val bw = (maxP - minP) * size.width
                    drawRoundRect(dimColor, Offset(bx, by - bh / 2), Size(bw, bh), CornerRadius(bh / 2))
                    if (medP > minP) {
                        drawRoundRect(color, Offset(bx, by - bh / 2), Size((medP - minP) * size.width, bh), CornerRadius(bh / 2))
                    }
                    drawCircle(color, 4.dp.toPx(), Offset(medP * size.width, by))
                    drawCircle(Color.White.copy(alpha = 0.5f), 2.5f.dp.toPx(), Offset(medP * size.width, by))
                }
            } else if (minNM != null) {
                val pos = ((kotlin.math.log10(minNM.coerceIn(0.001, 99999.0)) - AFFINITY_LOG_MIN) / (AFFINITY_LOG_MAX - AFFINITY_LOG_MIN)).toFloat().coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxSize()) {
                    val by = size.height / 2
                    drawCircle(color, 4.dp.toPx(), Offset(pos * size.width, by))
                    drawCircle(Color.White.copy(alpha = 0.5f), 2.5f.dp.toPx(), Offset(pos * size.width, by))
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Value + metadata
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isRange) "${formatAffinity(minNM)} - ${formatAffinity(maxNM)} nM"
                    else if (minNM != null) "${formatAffinity(minNM)} nM" else "? nM",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (medianStrength != null) {
                    Text(
                        medianStrength.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isRange && medianNM != null) {
                    Text(
                        "median ${formatAffinity(medianNM)} nM",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = tinySize),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (count > 1) {
                    Surface(shape = RoundedCornerShape(3.dp), color = MaterialTheme.colorScheme.surface) {
                        Text(
                            "x$count",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = tinySize),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                        )
                    }
                }
                sources.forEach { src ->
                    Surface(shape = RoundedCornerShape(3.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            src,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = tinySize),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatAffinity(nm: Double): String = when {
    nm < 0.01 -> "%.2e".format(nm)
    nm < 1.0 -> "%.2f".format(nm)
    nm < 100.0 -> "%.1f".format(nm)
    nm < 10000.0 -> "%.0f".format(nm)
    else -> "%.0f".format(nm)
}

@Composable
private fun AffinityLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◀", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("stronger", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AffinityStrength.entries.forEach { s ->
            val c = affinityStrengthColor(s)
            Surface(shape = RoundedCornerShape(3.dp), color = c.copy(alpha = 0.2f)) {
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = c,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Text("weaker", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("▶", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
