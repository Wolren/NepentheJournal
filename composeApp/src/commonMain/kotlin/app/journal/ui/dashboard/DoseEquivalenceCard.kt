package app.journal.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.journal.model.DoseEquivalence
import app.journal.ui.components.AppCard
import app.journal.ui.components.AppTextButton

private enum class EquivalenceClass(val label: String) {
    BENZODIAZEPINE("Benzodiazepine"),
    OPIOID("Opioid"),
}

/**
 * Converts a dose inside one drug class, so a logged dose answers "how much
 * alprazolam is my 10 mg diazepam". Bands stay bands: Ashton gives a range for
 * seven benzodiazepines, and CDC gives no factor for methadone, fentanyl or
 * buprenorphine.
 */
@Composable
internal fun DoseEquivalenceCard(modifier: Modifier = Modifier) {
    var kind by remember { mutableStateOf(EquivalenceClass.BENZODIAZEPINE) }
    var from by remember { mutableStateOf("Diazepam") }
    var to by remember { mutableStateOf("Alprazolam") }
    var amountText by remember { mutableStateOf("10") }
    val handler = LocalUriHandler.current

    val amount = amountText.toDoubleOrNull()
    val benzoResult = if (kind == EquivalenceClass.BENZODIAZEPINE && amount != null) {
        DoseEquivalence.convertBenzodiazepine(from, to, amount)
    } else {
        null
    }
    val opioidResult = if (kind == EquivalenceClass.OPIOID && amount != null) {
        DoseEquivalence.convertOpioid(from, to, amount)
    } else {
        null
    }
    val refusal = DoseEquivalence.refusalFor(from) ?: DoseEquivalence.refusalFor(to)

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("Dose equivalence", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EquivalenceClass.entries.forEach { entry ->
                    FilterChip(
                        selected = kind == entry,
                        onClick = {
                            kind = entry
                            if (entry == EquivalenceClass.BENZODIAZEPINE) {
                                from = "Diazepam"; to = "Alprazolam"
                            } else {
                                from = "Oxycodone"; to = "Morphine"
                            }
                        },
                        label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            val names = if (kind == EquivalenceClass.BENZODIAZEPINE) {
                DoseEquivalence.benzodiazepines.keys.toList() + DoseEquivalence.refusals.keys
            } else {
                DoseEquivalence.opioidMme.keys.toList() + DoseEquivalence.refusals.keys
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DrugPicker(label = "From", names = names.sorted(), selected = from, modifier = Modifier.weight(1f)) { from = it }
                DrugPicker(label = "To", names = names.sorted(), selected = to, modifier = Modifier.weight(1f)) { to = it }
            }
            OutlinedTextField(
                value = amountText,
                onValueChange = { text -> amountText = text.filter { it.isDigit() || it == '.' } },
                label = { Text("Amount (mg)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            val resultText: String? = when {
                amount == null -> null
                kind == EquivalenceClass.BENZODIAZEPINE -> benzoResult?.let { band ->
                    val low = band.start
                    val high = band.endInclusive
                    if (high - low < 0.05) {
                        "${formatMg(amount)} mg $from \u2248 ${formatMg(low)} mg $to"
                    } else {
                        "${formatMg(amount)} mg $from \u2248 ${formatMg(low)} to ${formatMg(high)} mg $to"
                    }
                }
                else -> opioidResult?.let { "${formatMg(amount)} mg $from = ${formatMg(it)} mg $to" }
            }
            if (refusal != null) {
                Text(
                    "${if (DoseEquivalence.refusalFor(from) != null) from else to}: $refusal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (resultText != null) {
                Text(resultText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            } else {
                Text(
                    "Pick two drugs and an amount.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val citation = if (kind == EquivalenceClass.BENZODIAZEPINE) {
                    DoseEquivalence.ASHTON_CITATION to "benzo.org.uk/manual/bzcha01.htm"
                } else {
                    DoseEquivalence.CDC_CITATION to null
                }
                Text(
                    citation.first,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f),
                )
                citation.second?.let { url ->
                    AppTextButton(onClick = { handler.openUri("https://$url") }) { Text("Source") }
                }
            }
        }
    }
}

private fun formatMg(value: Double): String =
    if (value >= 10.0) value.toLong().toString() else ((value * 100).toLong() / 100.0).toString()

@Composable
private fun DrugPicker(
    label: String,
    names: List<String>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            names.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(name); open = false },
                )
            }
        }
    }
}
