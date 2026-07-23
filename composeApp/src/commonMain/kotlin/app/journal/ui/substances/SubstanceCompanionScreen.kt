package app.journal.ui.substances

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.ui.components.*
import app.journal.ui.substances.detail.SectionCard
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubstanceCompanionScreen(
    substanceId: String,
    onBack: () -> Unit,
    onSessionClick: (String) -> Unit,
) {
    val repo = remember { JournalRepository.instance }
    val substances by repo.substances.collectAsState()
    val sessions by repo.sessions.collectAsState()
    val doses by repo.doses.collectAsState()
    val calculator = remember { ToleranceCalculator(repo) }

    val substance = remember(substanceId, substances) {
        substances.find { it.id == substanceId }
    }

    if (substance == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Substance not found", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Filter doses and sessions for this substance
    val dosesForSubstance = remember(substanceId, doses) {
        doses.filter { it.substanceId == substanceId }
    }

    val sessionIdsWithSubstance = remember(dosesForSubstance) {
        dosesForSubstance.map { it.sessionId }.toSet()
    }

    val sortedSessions = remember(sessionIdsWithSubstance, sessions) {
        sessions.filter { it.id in sessionIdsWithSubstance }
            .sortedByDescending { it.startTime }
    }

    val totalSessionCount = sortedSessions.size
    val totalDoseCount = dosesForSubstance.size

    // Cumulative lifetime amount grouped by unit
    val amountsByUnit = remember(dosesForSubstance) {
        dosesForSubstance.groupBy { it.unit }
            .mapValues { (_, ds) -> ds.sumOf { it.amount } }
    }

    // Tolerance info
    val toleranceInfo = remember(substanceId, doses) {
        calculator.calculate()
            .find { it.substanceId == substanceId }
    }

    ScreenScaffold(
        title = substance.name,
        onBack = onBack,
    ) {
        // Summary stats row
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatItem(
                        label = "Sessions",
                        value = totalSessionCount.toString()
                    )
                    StatItem(
                        label = "Doses",
                        value = totalDoseCount.toString()
                    )
                    StatItem(
                        label = "Total",
                        value = amountsByUnit.entries.joinToString(", ") { (unit, amount) ->
                            formatAmount(amount, unit)
                        }
                    )
                }
            }
        }

        // Dose Amounts trend chart
        if (dosesForSubstance.isNotEmpty()) {
            item {
                SectionCard(title = "Dose Amounts") {
                    val lastDoses = remember(dosesForSubstance) {
                        dosesForSubstance
                            .sortedByDescending { it.timestamp }
                            .take(20)
                            .reversed()
                    }
                    val maxAmount = remember(lastDoses) {
                        lastDoses.maxOfOrNull { it.amount } ?: 1.0
                    }

                    // Color per unit
                    val unitColors = remember(lastDoses) {
                        val units = lastDoses.map { it.unit }.distinct()
                        val palette = listOf(
                            Color(0xFF42A5F5),
                            Color(0xFF66BB6A),
                            Color(0xFFEF5350),
                            Color(0xFFFFA726),
                            Color(0xFFAB47BC),
                            Color(0xFF26C6DA),
                        )
                        units.mapIndexed { i, u -> u to palette[i % palette.size] }.toMap()
                    }

                    val tz = TimeZone.currentSystemDefault()

                    Column {
                        // Bar chart
                        Canvas(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                            val w = size.width
                            val h = size.height
                            val barCount = lastDoses.size
                            if (barCount == 0) return@Canvas
                            val barSpacing = 3.dp.toPx()
                            val totalSpacing = barSpacing * (barCount + 1)
                            val barWidth = ((w - totalSpacing) / barCount).coerceAtLeast(2f)

                            lastDoses.forEachIndexed { index, dose ->
                                val x = barSpacing + index * (barWidth + barSpacing)
                                val barHeight = (dose.amount / maxAmount * h * 0.85f).toFloat().coerceAtLeast(1f)
                                val color = unitColors[dose.unit] ?: Color.Gray

                                drawRoundRect(
                                    color = color,
                                    topLeft = Offset(x, h - barHeight),
                                    size = Size(barWidth, barHeight),
                                    cornerRadius = CornerRadius(2f, 2f)
                                )
                            }
                        }

                        // Date labels (first, middle, last)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val firstDate = remember(lastDoses, tz) {
                                val dt = Instant.fromEpochMilliseconds(lastDoses.first().timestamp)
                                    .toLocalDateTime(tz)
                                "${dt.monthNumber}/${dt.dayOfMonth}"
                            }
                            val midIdx = lastDoses.size / 2
                            val midDate = remember(lastDoses, tz) {
                                val dt = Instant.fromEpochMilliseconds(lastDoses[midIdx].timestamp)
                                    .toLocalDateTime(tz)
                                "${dt.monthNumber}/${dt.dayOfMonth}"
                            }
                            val lastDate = remember(lastDoses, tz) {
                                val dt = Instant.fromEpochMilliseconds(lastDoses.last().timestamp)
                                    .toLocalDateTime(tz)
                                "${dt.monthNumber}/${dt.dayOfMonth}"
                            }

                            Text(firstDate, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Text(midDate, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Text(lastDate, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }

                        // Unit legend
                        if (unitColors.size > 1) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                unitColors.forEach { (unit, color) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(8.dp),
                                            shape = RoundedCornerShape(2.dp),
                                            color = color
                                        ) {}
                                        Text(unit, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Tolerance section
        if (toleranceInfo != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Tolerance",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ToleranceStat(
                                label = "Level",
                                value = toleranceInfo.level.name
                            )
                            ToleranceStat(
                                label = "Since last dose",
                                value = "${"%.1f".format(toleranceInfo.daysSinceLastDose)} days"
                            )
                            ToleranceStat(
                                label = "Doses last 30d",
                                value = toleranceInfo.totalDosesLast30Days.toString()
                            )
                        }
                    }
                }
            }
        }

        // Sessions list header
        if (sortedSessions.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Session cards
        items(sortedSessions, key = { it.id }) { session ->
            val doseCountForSession = remember(session.id, dosesForSubstance) {
                dosesForSubstance.count { it.sessionId == session.id }
            }
            val dateStr = remember(session.startTime) {
                val dt = Instant.fromEpochMilliseconds(session.startTime)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                "${dt.year}-${dt.monthNumber.toString().padStart(2, '0')}-${dt.dayOfMonth.toString().padStart(2, '0')}"
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSessionClick(session.id) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            session.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            dateStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "$doseCountForSession dose${if (doseCountForSession != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToleranceStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatAmount(amount: Double, unit: String): String {
    return if (amount == amount.toLong().toDouble()) {
        "${amount.toLong()} $unit"
    } else {
        "${"%.2f".format(amount)} $unit"
    }
}
