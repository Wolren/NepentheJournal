package app.journal.ui.settings.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.ui.components.*
import app.journal.ui.settings.DataSettingsViewModel

/**
 * Settings > Data card. Pure presentation: every export, import and backup
 * operation, its file picker and its status string live in [DataSettingsViewModel],
 * so this composable only lays out buttons and reads the status line.
 */
@Composable
internal fun DataSettingsContent(
    vm: DataSettingsViewModel,
    sessionCount: Int,
    substanceCount: Int,
    dataExpanded: Boolean,
    onDataExpanded: () -> Unit,
) {
    val statusText by vm.dataStatus.collectAsState()

    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onDataExpanded() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Data", style = MaterialTheme.typography.titleMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$sessionCount sessions | $substanceCount substances",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp))
                    Icon(
                        if (dataExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (dataExpanded) {
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Text("Export / Import", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = { vm.exportSessionsJson() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export", maxLines = 1)
                    }
                    AppOutlinedButton(onClick = { vm.importSessionsJson() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = { vm.exportFullJournalJson() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Schema, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Backup (full)", maxLines = 1)
                    }
                    AppOutlinedButton(onClick = { vm.importFullJournalJson() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import backup", maxLines = 1)
                    }
                }
                // Failures all read "... failed"; everything else here is a
                // success line, so color off the word instead of the prefix
                // (which "Export failed" and "Import failed" both match).
                statusText?.let { msg ->
                    Spacer(Modifier.height(4.dp))
                    Text(msg, style = MaterialTheme.typography.labelSmall,
                        color = if (msg.contains("failed"))
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Text("CSV Export (analysis)", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text("One row per entity, R/Pandas-friendly format.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = { vm.exportSessionsCsv() }, modifier = Modifier.weight(1f)) {
                        Text("Sessions CSV", maxLines = 1)
                    }
                    AppOutlinedButton(onClick = { vm.exportDosesCsv() }, modifier = Modifier.weight(1f)) {
                        Text("Doses CSV", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = { vm.exportSubstancesCsv() }, modifier = Modifier.weight(1f)) {
                        Text("Substances CSV", maxLines = 1)
                    }
                    AppOutlinedButton(onClick = { vm.exportZip() }, modifier = Modifier.weight(1f)) {
                        Text("All (Zip)", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Text("Backup", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = { vm.createBackup() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Create Backup", maxLines = 1)
                    }
                }
            }
        }
    }
}
