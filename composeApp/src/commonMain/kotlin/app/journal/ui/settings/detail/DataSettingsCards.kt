package app.journal.ui.settings.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.ui.components.AppOutlinedButton
import app.journal.ui.components.CollapsibleSettingsCard
import app.journal.ui.settings.DataSettingsViewModel

/** Card for dose journals written by other apps; each import reports its own counts. */
@Composable
internal fun ForeignImportSettingsCard(vm: DataSettingsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val status by vm.foreignImportStatus.collectAsState()
    CollapsibleSettingsCard(
        expanded = expanded,
        onToggle = { expanded = !expanded },
        icon = Icons.Default.ImportExport,
        title = "Import from another app",
    ) {
        Text(
            "PsychonautWiki Journal, PsyLog, DrugsPRO, or a CSV/TSV with substance, dose and time columns.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        AppOutlinedButton(onClick = { vm.importForeignJournal() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Import journal from another app")
        }
        StatusLine(status)
    }
}

/** CSV exports for analysis: one row per entity, so R and pandas read them directly. */
@Composable
internal fun CsvExportSettingsCard(vm: DataSettingsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val status by vm.csvStatus.collectAsState()
    CollapsibleSettingsCard(
        expanded = expanded,
        onToggle = { expanded = !expanded },
        icon = Icons.Default.TableChart,
        title = "CSV export (analysis)",
    ) {
        Text(
            "One row per entity, R/Pandas-friendly format.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppOutlinedButton(onClick = { vm.exportSessionsCsv() }, modifier = Modifier.weight(1f)) {
                Text("Sessions CSV", maxLines = 1)
            }
            AppOutlinedButton(onClick = { vm.exportDosesCsv() }, modifier = Modifier.weight(1f)) {
                Text("Doses CSV", maxLines = 1)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppOutlinedButton(onClick = { vm.exportSubstancesCsv() }, modifier = Modifier.weight(1f)) {
                Text("Substances CSV", maxLines = 1)
            }
            AppOutlinedButton(onClick = { vm.exportZip() }, modifier = Modifier.weight(1f)) {
                Text("All (Zip)", maxLines = 1)
            }
        }
        StatusLine(status)
    }
}

/** One status line per card, red when the operation failed. */
@Composable
private fun StatusLine(message: String?) {
    if (message == null) return
    Spacer(Modifier.height(4.dp))
    Text(
        message,
        style = MaterialTheme.typography.labelSmall,
        color = if (message.contains("failed")) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
    )
}
