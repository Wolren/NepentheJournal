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

                    SectionCard(title = "Tolerance & Cross-Tolerance") {
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
                    SectionCard(
                        title = "Addiction Potential",
                        accentColor = when {
                            substance.addictionPotential.contains("High") -> MaterialTheme.colorScheme.error
                            substance.addictionPotential.contains("Moderate") -> MaterialTheme.colorScheme.tertiary
                            else -> null
                        }
                    ) {
                        SelectableText(substance.addictionPotential ?: "", style = MaterialTheme.typography.bodyMedium)
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

    // Bar phases (onset/comeup/peak/offset) vs afterglow
    val barPhases = phases.filter { it.label != "Afterglow" }
    val afterglow = phases.find { it.label == "Afterglow" }

    val phaseColors = mapOf(
        "Onset" to listOf(Color(0xFF66BB6A), Color(0xFF81C784)),
        "Comeup" to listOf(Color(0xFF42A5F5), Color(0xFF64B5F6)),
        "Peak" to listOf(Color(0xFFEF5350), Color(0xFFE57373)),
        "Offset" to listOf(Color(0xFFFFA726), Color(0xFFFFB74D)),
        "Afterglow" to listOf(Color(0xFFAB47BC), Color(0xFFCE93D8))
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Duration", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            // Main timeline bar
            val sumOfMax = barPhases.sumOf { it.maxMinutes }
            Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                val barTop = 4f
                val barHeight = 16f
                val w = size.width

                var xOff = 0f
                barPhases.forEach { phase ->
                    val fraction = (phase.maxMinutes / sumOfMax).toFloat().coerceAtLeast(0.02f)
                    val segWidth = w * fraction
                    val colors = phaseColors[phase.label] ?: listOf(Color.Gray)
                    drawRoundRect(
                        color = colors[0],
                        topLeft = Offset(xOff, barTop),
                        size = Size(segWidth, barHeight),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                    xOff += segWidth
                }
            }

            // Phase tiles
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (phase in barPhases) {
                    val colors = phaseColors[phase.label] ?: listOf(Color.Gray)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = colors[0].copy(alpha = 0.15f),
                        tonalElevation = 2.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Text(
                                phase.display,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors[0],
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                phase.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Afterglow shown separately
            if (afterglow != null) {
                val agColors = phaseColors["Afterglow"] ?: listOf(Color.Gray)
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = agColors[0].copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Afterglow", style = MaterialTheme.typography.labelMedium,
                            color = agColors[0], fontWeight = FontWeight.SemiBold)
                        Text(afterglow.display, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Total bar
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

                val totalColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                    val w = size.width
                    drawRoundRect(
                        color = totalColor.copy(alpha = 0.3f),
                        topLeft = Offset(0f, 2f),
                        size = Size(w, 12f),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(if (totalMin == totalMaxStr) totalMaxStr else "${totalMin}\u2013${totalMaxStr}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
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
