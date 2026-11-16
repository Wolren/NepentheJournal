package app.journal.ui.substances.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.model.Substance
import app.journal.model.TargetNormalizer
import app.journal.model.pharmacologySources
import app.journal.ui.components.AppCard
import app.journal.ui.components.AppTextButton
import app.journal.ui.components.ReceptorText
import app.journal.ui.components.compareReceptorLabels
import app.journal.model.Citation
import app.journal.model.PharmacologyContribution
import app.journal.model.sortBySourcePriority
import app.journal.model.spreadOf
import androidx.compose.ui.platform.LocalUriHandler
import app.journal.ui.theme.LocalThemeConfig
import app.journal.ui.theme.PharmacologyChart
import app.journal.ui.theme.ThemeManager
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

private const val AFFINITY_LOG_MIN = -2.0  // 0.01 nM
private const val AFFINITY_LOG_MAX = 4.0   // 10000 nM
private const val GUTTER_DP = 150
/** Measure column: Ki, Kd, IC50. Its own column keeps a target name out of the label. */
private const val TYPE_COLUMN = 34
/** Median column, printed for every row so a value never needs a click. */
private const val VALUE_COLUMN = 52
/** Action view: Emax number, a gap, then the source's own action wording. */
private const val EMAX_COLUMN = 64
private const val ACTION_GAP = 12

private enum class AffinityStrength(val label: String, val min: Double, val max: Double) {
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

private fun affinityStrengthColor(strength: AffinityStrength?): Color = when (strength) {
    AffinityStrength.VERY_STRONG -> Color(0xFFE53935)
    AffinityStrength.STRONG -> Color(0xFFFB8C00)
    AffinityStrength.MODERATE -> Color(0xFF7CB342)
    AffinityStrength.WEAK -> Color(0xFF42A5F5)
    AffinityStrength.VERY_WEAK -> Color(0xFF78909C)
    null -> Color.Gray
}

/** One plotted target-metric row: the dot position is the median, the whisker the observed range. */
private data class PlotRow(
    val key: String,
    val label: String,
    val plainLabel: String,
    val type: String,
    val unit: String,
    val family: String,
    val minNM: Double?,
    val medianNM: Double?,
    val maxNM: Double?,
    val count: Int,
    val sources: List<String>,
    val action: String? = null,
    /** Every source's own row, so a disagreement can be shown instead of a single number. */
    val contributions: List<PharmacologyContribution> = emptyList(),
) {
    val citations: List<Citation> get() = contributions.mapNotNull { it.citation }.distinct()
}

/** Receptor order inside a family: 5-HT1A, 5-HT1B, 5-HT2A, then the measure type. */
private val receptorRowOrder = Comparator<PlotRow> { a, b ->
    val byLabel = compareReceptorLabels(a.plainLabel, b.plainLabel)
    if (byLabel != 0) byLabel else a.type.compareTo(b.type)
}

private fun familyOf(label: String, key: String): String {
    val l = label.trim().lowercase()
    val s = "$l $key".lowercase()
    return when {
        s.contains("muscarinic") || s.contains("nicotinic") || s.contains("acetylcholine") -> "Cholinergic"
        s.contains("gaba") || s.contains("gamma-aminobutyric") -> "GABAergic"
        s.contains("nmda") || s.contains("ampa") || s.contains("kainate") ||
            s.contains("glutamate") || s.contains("mglu") -> "Glutamatergic"
        s.contains("5-ht") || s.contains("serotonin") || s.contains("htr") -> "Serotonergic"
        Regex("""\bd[1-5]\b""").containsMatchIn(s) || s.contains("dopamine") || s.contains("drd") -> "Dopaminergic"
        s.contains("sigma") -> "Sigma"
        s.contains("opioid") || s.contains("nociceptin") ||
            Regex("""\b(mor|kor|dor)\b""").containsMatchIn(s) -> "Opioid"
        s.contains("cannabinoid") || s.contains("cb1") || s.contains("cb2") || s.contains("cnr") -> "Cannabinoid"
        s.contains("histamine") || Regex("""^h[1-4](\s+receptor)?$""").matches(l) -> "Histaminergic"
        s.contains("adren") || s.contains("adrenoceptor") ||
            Regex("""^(alpha|beta|a|b|α|β)[- ]?[12][a-d]?$""").matches(l) -> "Adrenergic"
        s.contains("transporter") || s.contains("sert") || s.contains("dat") || s.contains("net") -> "Transporters"
        s.contains("purinergic") || s.contains("p2x") || s.contains("p2y") -> "Purinergic"
        s.contains("channel") || s.contains("voltage-dependent") -> "Ion channel"
        s.contains("taar") || s.contains("trace amine") -> "Trace amine"
        else -> "Other"
    }
}

/** UniProt accessions leak through when a source carries no protein name; they plot nothing useful. */
private fun looksLikeAccession(label: String): Boolean =
    Regex("""^[OPQ][0-9][A-Z0-9]{3}[0-9]$""").matches(label.trim()) ||
        Regex("""^[A-NR-Z][0-9][A-Z][A-Z0-9]{2}[0-9]$""").matches(label.trim())

private fun logFraction(nm: Double): Float {
    val clamped = log10(nm.coerceIn(0.001, 99999.0))
    return ((clamped - AFFINITY_LOG_MIN) / (AFFINITY_LOG_MAX - AFFINITY_LOG_MIN)).toFloat().coerceIn(0f, 1f)
}

@Composable
internal fun PharmacologySection(substance: Substance) {
    val sources = substance.pharmacologySources
    val bindingdbRecords = sources?.bindingdb?.records.orEmpty()
    val pdspRecords = sources?.pdsp?.records.orEmpty()
    val wikipediaRecords = sources?.wikipedia?.records.orEmpty()
    val chemblRecords = sources?.chembl?.bioactivities.orEmpty()
    val iupharRecords = sources?.iuphar?.interactions.orEmpty()
    val totalRecords = bindingdbRecords.size + pdspRecords.size + wikipediaRecords.size +
        chemblRecords.size + iupharRecords.size
    if (totalRecords == 0) return

    var expanded by remember(substance) { mutableStateOf(false) }
    var selected by remember(substance) { mutableStateOf<String?>(null) }
    var compareRow by remember(substance) { mutableStateOf<PlotRow?>(null) }
    val themeConfig = LocalThemeConfig.current
    val chart = themeConfig.pharmacologyChart
    fun setChart(mode: PharmacologyChart) {
        ThemeManager.instance.update(themeConfig.copy(pharmacologyChart = mode))
    }

    val rows = remember(substance) {
        data class Raw(
            val name: String,
            val type: String,
            val nm: Double?,
            val unit: String,
            val source: String,
            val action: String? = null,
            val citation: Citation? = null,
        )
        val raw = mutableListOf<Raw>()
        bindingdbRecords.forEach {
            raw.add(
                Raw(
                    it.targetName ?: "Unknown", it.affinityType ?: "?", it.affinityNM, "nM", "BindingDB",
                    citation = it.pmid?.let { pmid -> Citation(pmid = pmid) },
                ),
            )
        }
        pdspRecords.forEach {
            val reference = it.reference?.trim()
            raw.add(
                Raw(
                    it.targetName ?: "Unknown", "Ki", it.kiNanoMolar, "nM", "PDSP",
                    citation = reference?.let { Citation(pmid = it.takeIf { ref -> ref.all(Char::isDigit) }, text = it) },
                ),
            )
        }
        chemblRecords.forEach {
            raw.add(Raw(it.targetName ?: it.targetId ?: "Unknown", it.type, it.value, it.units ?: "nM", "ChEMBL", it.action))
        }
        iupharRecords.forEach {
            raw.add(
                Raw(
                    it.targetName ?: "Unknown",
                    it.type ?: it.affinityParameter ?: "Ki",
                    it.affinity?.toDoubleOrNull(),
                    "nM",
                    "IUPHAR",
                    it.action,
                ),
            )
        }
        wikipediaRecords.forEach {
            val name = it.targetName ?: "Unknown"
            val ki = it.kiNM ?: if (it.kiNMMin != null && it.kiNMMax != null) (it.kiNMMin + it.kiNMMax) / 2.0
                else it.kiNMMin
            if (ki != null) raw.add(Raw(name, "Ki", ki, "nM", "Wikipedia"))
            it.ec50NM?.let { value -> raw.add(Raw(name, "EC50", value, "nM", "Wikipedia")) }
            it.ic50NM?.let { value -> raw.add(Raw(name, "IC50", value, "nM", "Wikipedia")) }
            it.emaxPercent?.let { value -> raw.add(Raw(name, "Emax", value, "%", "Wikipedia")) }
        }

        raw.groupBy {
            val n = TargetNormalizer.normalize(it.name)
            listOf(n.key, n.label, it.type, it.unit)
        }.map { (key, entries) ->
            val label = key[1]
            val type = key[2]
            val unit = key[3]
            val values = entries.mapNotNull { it.nm }.sorted()
            val median = when {
                values.isEmpty() -> null
                values.size % 2 == 1 -> values[values.size / 2]
                else -> (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
            }
            PlotRow(
                key = "${key[0]}|$type|$unit",
                label = label,
                plainLabel = label,
                type = type,
                unit = unit,
                family = familyOf(label, key[0]),
                minNM = values.minOrNull(),
                medianNM = median,
                maxNM = values.maxOrNull(),
                count = entries.size,
                sources = entries.map { it.source }.distinct(),
                action = entries.firstNotNullOfOrNull { it.action },
                contributions = entries.map {
                    PharmacologyContribution(
                        source = it.source,
                        type = it.type,
                        nanoMolar = it.nm,
                        action = it.action,
                        citation = it.citation,
                    )
                },
            )
        }.filter { it.medianNM != null && !looksLikeAccession(it.plainLabel) }
            .sortedBy { it.medianNM ?: Double.MAX_VALUE }
    }

    val bindingRows = rows.filter { it.unit == "nM" && (it.type == "Ki" || it.type == "Kd") }
    val potencyRows = rows.filter { it.unit == "nM" && (it.type == "EC50" || it.type == "IC50") }
    val affinityRows = bindingRows
    val efficacyRows = rows.filter { it.unit == "%" }
    val families = affinityRows.groupBy { it.family }
        .toList()
        .sortedWith(compareBy({ it.first == "Other" }, { it.first }))

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        ViewModeButton(
                            icon = Icons.Default.ViewList,
                            selected = chart == PharmacologyChart.GROUPED,
                            description = "Grouped receptor view",
                        ) { setChart(PharmacologyChart.GROUPED) }
                        ViewModeButton(
                            icon = Icons.Default.BarChart,
                            selected = chart == PharmacologyChart.BARS,
                            description = "Bar chart view",
                        ) { setChart(PharmacologyChart.BARS) }
                        ViewModeButton(
                            icon = Icons.Default.SwapVert,
                            selected = chart == PharmacologyChart.ACTION,
                            description = "Action view",
                        ) { setChart(PharmacologyChart.ACTION) }
                    }
                    if (!expanded) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
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
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Text(
                    "$totalRecords measures \u00b7 ${rows.size} rows" +
                        when (chart) {
                            PharmacologyChart.GROUPED -> " \u00b7 ${families.size} families"
                            PharmacologyChart.BARS -> " \u00b7 ${potencyRows.size} potency"
                            PharmacologyChart.ACTION -> " \u00b7 ${rows.count { actionKind(it.action).isNotEmpty() }} actions"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                if (chart == PharmacologyChart.ACTION) {
                    ActionPlot(rows)
                } else if (chart == PharmacologyChart.BARS) {
                    BarsPlot(potencyRows)
                } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // The gutter scales with the pane, so a phone still shows the plot.
                    val gutter = (maxWidth * 0.30f).coerceIn(124.dp, 200.dp)
                    Column {
                families.forEach { (family, familyRows) ->
                    PlotSectionHeader(
                        title = family,
                        count = familyRows.size,
                        unit = if (familyRows.size == 1) "target" else "targets",
                        accent = MaterialTheme.colorScheme.primary,
                    )
                    familyRows.sortedWith(receptorRowOrder).forEach { row ->
                        val isSelected = selected == row.key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    else Color.Transparent,
                                )
                                .clickable { selected = if (isSelected) null else row.key },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReceptorText(
                                text = row.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(gutter).padding(end = 8.dp),
                            )
                            AffinityDot(row = row, highlighted = isSelected, modifier = Modifier.weight(1f).height(14.dp))
                            Text(
                                row.type,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                modifier = Modifier.width(TYPE_COLUMN.dp).padding(start = 6.dp),
                            )
                            Text(
                                row.medianNM?.let { formatAffinity(it) } ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                modifier = Modifier.width(VALUE_COLUMN.dp).padding(start = 4.dp),
                            )
                        }
                        if (isSelected) {
                            val openRow = row
                            Column(Modifier.padding(start = gutter, bottom = 2.dp)) {
                                Text(
                                    describe(row),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (row.contributions.size > 1 || row.citations.isNotEmpty()) {
                                    val handler = LocalUriHandler.current
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        if (row.contributions.size > 1) {
                                            Text(
                                                "Compare ${row.contributions.size} rows",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.clickable { compareRow = openRow },
                                            )
                                        }
                                        row.citations.firstOrNull()?.let { citation ->
                                            Text(
                                                citation.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (citation.isLink) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .then(
                                                        if (citation.isLink) {
                                                            Modifier.clickable { citation.url?.let(handler::openUri) }
                                                        } else {
                                                            Modifier
                                                        },
                                                    ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                    Spacer(Modifier.height(4.dp))
                    if (affinityRows.isNotEmpty()) {
                        AffinityAxis(
                            leading = gutter,
                            trailing = (TYPE_COLUMN + VALUE_COLUMN).dp,
                        )
                    }
                    }
                }
                }
                Spacer(Modifier.height(2.dp))
                if (chart != PharmacologyChart.ACTION) AffinityLegend(
                    trailing = if (chart == PharmacologyChart.ACTION) "action class"
                    else "nM, log scale",
                )
            }
        }
    }
    compareRow?.let { row ->
        SourceComparisonDialog(
            row = row,
            priority = themeConfig.pharmacologySourcePriority,
            onDismiss = { compareRow = null },
        )
    }
}

/**
 * Every source's own row for one target, in the user's priority order. One
 * resolved number hides a real disagreement, so the hardest case is shown first.
 */
@Composable
private fun SourceComparisonDialog(
    row: PlotRow,
    priority: List<String>,
    onDismiss: () -> Unit,
) {
    val ordered = remember(row, priority) { sortBySourcePriority(row.contributions, priority) }
    val spread = remember(row) { spreadOf(row.contributions) }
    val handler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${row.plainLabel} · ${row.type}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    spread?.let { (low, high) ->
                        "${row.contributions.size} rows from ${row.sources.size} sources, ${formatAffinity(low)} to ${formatAffinity(high)} nM"
                    } ?: "${row.contributions.size} row from ${row.sources.size} source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ordered.forEach { contribution ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            contribution.source,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(78.dp),
                        )
                        Text(
                            contribution.nanoMolar?.let { "${formatAffinity(it)} nM" } ?: "no value",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(82.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOfNotNull(contribution.type, contribution.action)
                                    .joinToString(" \u00b7 ")
                                    .ifEmpty { contribution.type },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            contribution.citation?.let { citation ->
                                Text(
                                    citation.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (citation.isLink) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (citation.isLink) {
                                        Modifier.clickable { citation.url?.let(handler::openUri) }
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { AppTextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Efficacy against potency: Emax percent on Y, EC50 on X. A partial agonist
 * sits low at any potency; a full agonist sits near the 100 percent line.
 */
/**
 * Action classes per receptor: agonist, partial agonist, antagonist, modulator,
 * each with the best potency the sources give for that receptor.
 */
/**
 * One row per receptor: action class, best potency and Emax as a short meter.
 * Emax stays visible; the meter keeps it compact.
 */
@Composable
private fun ActionPlot(rows: List<PlotRow>, modifier: Modifier = Modifier) {
    val entries = remember(rows) {
        val byLabel = rows.groupBy { it.plainLabel }
        byLabel.map { (label, group) ->
            // Functional data outranks a binding annotation: IUPHAR calls bufotenin a Ki antagonist at 5-HT2A.
            val withAction = group.filter { actionKind(it.action).isNotEmpty() }
            val actionRow = withAction.firstOrNull { it.unit == "%" }
                ?: withAction.firstOrNull { it.type == "EC50" || it.type == "IC50" }
                ?: withAction.firstOrNull()
            ActionEntry(
                label = label,
                kind = actionKind(actionRow?.action),
                rawAction = actionRow?.action,
                actionParameter = actionRow?.type,
                actionIsBinding = actionRow?.type == "Ki" || actionRow?.type == "Kd",
                emax = group.firstOrNull { it.unit == "%" }?.medianNM,
                potency = group.filter { it.unit == "nM" && it.medianNM != null }
                    .minByOrNull { it.medianNM ?: Double.MAX_VALUE },
            )
        }.filter { it.kind.isNotEmpty() || it.emax != null }
            .sortedWith(compareBy({ actionOrder(it.kind) }, { it.potency?.medianNM ?: Double.MAX_VALUE }))
    }
    if (entries.isEmpty()) {
        Text(
            "No action or Emax data for this substance",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
    // The action column prints the source's own words, so it takes the width it can get.
    val actionWidth = (maxWidth * 0.26f).coerceIn(110.dp, 210.dp)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(170.dp))
            Spacer(Modifier.weight(1f))
            Text(
                "Emax %",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(EMAX_COLUMN.dp).padding(start = 6.dp),
            )
            Text(
                "Action",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(actionWidth).padding(start = ACTION_GAP.dp),
            )
        }
        entries.groupBy { it.kind.ifEmpty { "Unclassified" } }.forEach { (kind, group) ->
            PlotSectionHeader(
                title = kind,
                count = group.size,
                unit = if (group.size == 1) "receptor" else "receptors",
                accent = actionColor(kind),
            )
            group.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReceptorText(
                        text = entry.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(170.dp).padding(end = 8.dp),
                    )
                    val tint = actionColor(entry.kind)
                    val guide = MaterialTheme.colorScheme.outlineVariant
                    Canvas(Modifier.weight(1f).height(14.dp)) {
                        val y = size.height / 2f
                        val span = size.width
                        val x100 = (100.0 / 150.0).toFloat() * span
                        drawLine(guide.copy(alpha = 0.35f), Offset(0f, y), Offset(span, y), strokeWidth = 1f)
                        drawLine(guide.copy(alpha = 0.9f), Offset(x100, 0f), Offset(x100, size.height), strokeWidth = 1f)
                        entry.emax?.let { value ->
                            val x = (value / 150.0).coerceIn(0.0, 1.0).toFloat() * span
                            drawLine(tint.copy(alpha = 0.55f), Offset(0f, y), Offset(x, y), strokeWidth = 2.5f)
                            drawCircle(tint.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = Offset(x, y))
                            drawCircle(tint, radius = 3.5.dp.toPx(), center = Offset(x, y))
                        }
                    }
                    Text(
                        entry.emax?.let { formatAffinity(it) } ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(EMAX_COLUMN.dp).padding(start = 6.dp),
                    )
                    Text(
                        actionDisplay(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(actionWidth).padding(start = ACTION_GAP.dp),
                    )
                }
            }
        }
        Text(
            "${entries.size} receptors \u00b7 dot is Emax, tick marks 100 %",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        )
    }
    }
}

private data class ActionEntry(
    val label: String,
    val kind: String,
    val rawAction: String?,
    /** Measure the action label came with, so a Ki annotation reads as one. */
    val actionParameter: String? = null,
    val actionIsBinding: Boolean = false,
    val emax: Double? = null,
    val potency: PlotRow? = null,
)

/** Exact source action, tagged with its measure when the label came from a binding row. */
private fun actionDisplay(entry: ActionEntry): String {
    val raw = entry.rawAction.orEmpty()
    if (raw.isEmpty() || !entry.actionIsBinding) return raw
    val parameter = entry.actionParameter ?: return raw
    return "$raw \u00b7 $parameter"
}

/**
 * Category band above a group of rows. A family such as GABAergic is a heading,
 * so it carries a tinted band and an accent bar; the row style stays for targets.
 */
@Composable
private fun PlotSectionHeader(
    title: String,
    count: Int,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 12.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count $unit",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}

private fun actionOrder(kind: String): Int = when (kind) {
    "Agonist" -> 0
    "Partial agonist" -> 1
    "Antagonist" -> 2
    "Modulator" -> 3
    else -> 4
}

private fun actionKind(raw: String?): String {
    val text = raw?.lowercase().orEmpty()
    // "antagonist" contains "agonist", so the antagonist tests run first.
    return when {
        "partial" in text -> "Partial agonist"
        "invers" in text -> "Antagonist"
        "antagon" in text || "inhibi" in text || "block" in text -> "Antagonist"
        "agonis" in text || "activation" in text -> "Agonist"
        "potentiat" in text || "modulat" in text || "allosteric" in text -> "Modulator"
        text == "positive" -> "Modulator"
        else -> ""
    }
}

@Composable
private fun actionColor(kind: String): Color = when (kind) {
    "Agonist" -> MaterialTheme.colorScheme.primary
    "Partial agonist" -> MaterialTheme.colorScheme.tertiary
    "Antagonist" -> MaterialTheme.colorScheme.error
    "Modulator" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}


@Composable
private fun ViewModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Compact bar chart: one bar per receptor, sorted strongest to weakest, bar height
 * on the log scale. Labels cover the three strongest receptors and the weakest.
 */
/**
 * Compact bar chart: one bar per receptor, sorted strongest to weakest, bar height
 * on the log scale. Labels cover the three strongest receptors and the weakest.
 */
/**
 * Bar grid sorted strongest first. Each bar carries its receptor name, so every
 * value is labelled. Hover shows metric, value, record count and sources.
 */
/**
 * Bar grid sorted strongest first. Each bar carries its receptor name. Hover
 * previews the stats, click pins them: low, median, high and measure count.
 */
/**
 * Horizontal bar chart of EC50 and IC50 on one log axis. Bar length is the
 * value, the label sits in a fixed gutter, the number sits at the end.
 */
@Composable
private fun BarsPlot(rows: List<PlotRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
        Text(
            "No EC50 or IC50 measurements for this substance",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val sorted = remember(rows) { rows.sortedBy { it.medianNM ?: Double.MAX_VALUE } }
    var hovered by remember(rows) { mutableStateOf<String?>(null) }
    var pinned by remember(rows) { mutableStateOf<String?>(null) }
    val activeKey = hovered ?: pinned
    val valueWidth = 84.dp
    val guide = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gutter = (maxWidth * 0.34f).coerceIn(120.dp, 280.dp)
        Column(Modifier.fillMaxWidth()) {
        val sections = sorted.groupBy { it.type }
        sections.forEach { (type, sectionRows) ->
            PlotSectionHeader(
                title = type,
                count = sectionRows.size,
                unit = if (sectionRows.size == 1) "measure" else "measures",
                accent = MaterialTheme.colorScheme.tertiary,
            )
        sectionRows.forEach { row ->
            val nm = row.medianNM ?: return@forEach
            val isPinned = pinned == row.key
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)
                    .clickable { pinned = if (isPinned) null else row.key }
                    .pointerInput(row.key) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                                hovered = row.key
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReceptorText(
                    text = row.plainLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(gutter).padding(end = 6.dp),
                )
                Canvas(Modifier.weight(1f).height(12.dp)) {
                    for (decade in -2..4) {
                        val gx = ((decade - AFFINITY_LOG_MIN) / (AFFINITY_LOG_MAX - AFFINITY_LOG_MIN)).toFloat() * size.width
                        drawLine(guide, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 0.5f)
                    }
                    val color = affinityStrengthColor(AffinityStrength.fromNanoMolar(nm))
                    val barWidth = (logFraction(nm) * size.width).coerceAtLeast(2f.dp.toPx())
                    val height = if (activeKey == row.key) size.height else size.height * 0.8f
                    val top = (size.height - height) / 2f
                    drawRoundRect(
                        color.copy(alpha = if (activeKey == row.key) 1f else 0.85f),
                        Offset(0f, top),
                        Size(barWidth, height),
                        CornerRadius(2f, 2f),
                    )
                }
                Text(
                    "${formatAffinity(nm)} nM",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(valueWidth).padding(start = 6.dp),
                )
            }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(gutter))
            Box(Modifier.weight(1f)) { AffinityAxis(leading = 0.dp, inset = 0.dp) }
            Spacer(Modifier.width(valueWidth))
        }
        }
        }
        Text(
            sorted.firstOrNull { it.key == activeKey }?.let { describe(it) }
                ?: "${sorted.size} potency values \u00b7 strongest first \u00b7 hover or click a bar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp),
        )
    }
}

/** One line of stats for a selected receptor: range, median, measure count and sources. */
private fun describe(row: PlotRow): String {
    fun value(nm: Double?) = nm?.let { formatAffinity(it) } ?: "?"
    val range = if (row.count > 1 && row.minNM != null && row.maxNM != null && row.minNM != row.maxNM) {
        "low ${value(row.minNM)} \u00b7 median ${value(row.medianNM)} \u00b7 high ${value(row.maxNM)} nM"
    } else {
        "${value(row.medianNM)} nM"
    }
    val measures = if (row.count == 1) "1 measure" else "${row.count} measures"
    return "${row.label} \u00b7 ${row.type} \u00b7 $range \u00b7 $measures \u00b7 " +
        row.sources.joinToString(", ")
}


@Composable
private fun AffinityDot(row: PlotRow, highlighted: Boolean, modifier: Modifier = Modifier) {
    val median = row.medianNM
    val strength = AffinityStrength.fromNanoMolar(median)
    val color = affinityStrengthColor(strength)
    val guide = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    Canvas(modifier) {
        val h = size.height
        val mid = h / 2f
        val inset = 6.dp.toPx()
        val plot = (size.width - 2 * inset).coerceAtLeast(1f)
        fun x(nm: Double): Float = inset + logFraction(nm) * plot
        for (decade in -2..4) {
            val f = ((decade - AFFINITY_LOG_MIN) / (AFFINITY_LOG_MAX - AFFINITY_LOG_MIN)).toFloat()
            val gx = inset + f * plot
            drawLine(guide, Offset(gx, 0f), Offset(gx, h), strokeWidth = 0.5f)
        }
        if (median == null) {
            drawLine(track, Offset(inset, mid), Offset(size.width - inset, mid), strokeWidth = 1f)
            return@Canvas
        }
        val mx = x(median)
        if (row.count > 1 && row.minNM != null && row.maxNM != null && row.minNM != row.maxNM) {
            drawLine(color.copy(alpha = 0.45f), Offset(x(row.minNM), mid), Offset(x(row.maxNM), mid), strokeWidth = 2.5f)
        }
        val radius = if (highlighted) 5f.dp.toPx() else 4f.dp.toPx()
        drawCircle(color, radius = radius, center = Offset(mx, mid))
        if (highlighted) {
            drawCircle(Color.White.copy(alpha = 0.85f), radius = 1.5f.dp.toPx(), center = Offset(mx, mid))
        }
    }
}

@Composable
private fun AffinityAxis(leading: Dp = GUTTER_DP.dp, inset: Dp = 6.dp, trailing: Dp = 0.dp) {
    val labelStyle = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Spacer(Modifier.width(leading))
        BoxWithConstraints(Modifier.weight(1f)) {
            val plotWidth = maxWidth - inset * 2
            // Seven decades crowd into each other on a narrow pane; keep four ticks.
            val decades = if (plotWidth < 240.dp) listOf(-2, 0, 2, 4) else listOf(-2, -1, 0, 1, 2, 3, 4)
            Box(Modifier.fillMaxWidth().height(1.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(muted, Offset(inset.toPx(), 0f), Offset(size.width - inset.toPx(), 0f), strokeWidth = 1f)
                }
            }
            decades.forEach { decade ->
                val fraction = ((decade - AFFINITY_LOG_MIN) / (AFFINITY_LOG_MAX - AFFINITY_LOG_MIN)).toFloat()
                Text(
                    axisLabel(decade),
                    style = labelStyle,
                    color = muted,
                    modifier = Modifier
                        .offset(x = inset + plotWidth * fraction - 12.dp, y = 2.dp)
                        .width(28.dp),
                )
            }
        }
        Spacer(Modifier.width(trailing))
    }
}

private fun axisLabel(decade: Int): String = when (decade) {
    -2 -> "0.01"
    -1 -> "0.1"
    0 -> "1"
    1 -> "10"
    2 -> "100"
    3 -> "1k"
    else -> "10k"
}

@Composable
private fun AffinityLegend(trailing: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("stronger", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AffinityStrength.entries.forEach { s ->
            val c = affinityStrengthColor(s)
            Box(
                Modifier
                    .size(6.dp)
                    .then(Modifier)
                    .padding(0.dp),
            ) {
                Canvas(Modifier.fillMaxSize()) { drawCircle(c) }
            }
        }
        Text("weaker", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

private fun formatAffinity(nm: Double): String = when {
    nm < 0.01 -> {
        var exp = 0
        var m = nm
        while (m < 1.0) { m *= 10; exp-- }
        while (m >= 10.0) { m /= 10; exp++ }
        "${(m * 100).roundToInt() / 100.0}e$exp"
    }
    nm < 1.0 -> "${(nm * 100).roundToInt() / 100.0}"
    nm < 100.0 -> "${(nm * 10).roundToInt() / 10.0}"
    else -> "${nm.toInt()}"
}

