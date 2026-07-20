package app.journal.ui.substances.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.Substance

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
internal fun PharmacologySection(substance: Substance) {
    val bindingdbRecords = substance.bindingdbData?.records.orEmpty()
    val pdspRecords = substance.pdspData?.records.orEmpty()
    val wikipediaRecords = substance.wikipediaData?.records.orEmpty()
    val totalRecords = bindingdbRecords.size + pdspRecords.size + wikipediaRecords.size
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
        wikipediaRecords.forEach { r ->
            val affinityNM = r.kiNM
                ?: if (r.kiNMMin != null && r.kiNMMax != null) (r.kiNMMin + r.kiNMMax) / 2.0
                   else r.kiNMMin ?: r.ec50NM ?: r.ic50NM
            val affinityType = when {
                r.kiNM != null || r.kiNMMin != null -> "Ki"
                r.ec50NM != null -> "EC50"
                r.ic50NM != null -> "IC50"
                else -> "?"
            }
            raw.add(
                RawEntry(
                    targetName = r.targetName ?: "Unknown target",
                    species = r.species ?: "Human",
                    affinityType = affinityType,
                    affinityNM = affinityNM,
                    source = "WP",
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
        Text(
            affinityType,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(32.dp),
        )

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
        Text("\u25C0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("\u25B6", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
