package app.journal.ui.settings.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.data.JournalRepository
import app.journal.data.JournalStore
import app.journal.model.SyncConfig
import app.journal.sync.*
import app.journal.ui.components.*
import app.journal.util.FilePicker
import app.journal.util.PlatformFile
import app.journal.util.ExportImport
import app.journal.util.CsvExporter
import app.journal.util.ZipExporter
import app.journal.log.Log
import app.journal.log.collectLogs
import kotlinx.coroutines.launch

@Composable
internal fun DataSettingsContent(
    repo: JournalRepository,
    sessionCount: Int,
    substanceCount: Int,
    statusText: String?,
    dataExpanded: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    onDataExpanded: () -> Unit,
    onStatusChange: (String?) -> Unit,
) {
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
                    AppOutlinedButton(onClick = {
                        scope.launch {
                            val path = FilePicker.saveFile("sessions-export.json", "JSON files", listOf("json"))
                            if (path != null) {
                                try {
                                    val json = ExportImport.exportSessions(repo)
                                    PlatformFile.writeText(path, json)
                                    onStatusChange("Exported ${repo.sessions.value.size} sessions")
                                } catch (e: Exception) {
                                    onStatusChange("Export failed: ${e.message}")
                                }
                            }
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export", maxLines = 1)
                    }
                    AppOutlinedButton(onClick = {
                        scope.launch {
                            val path = FilePicker.openFile("JSON files", listOf("json"))
                            if (path != null) {
                                try {
                                    val content = PlatformFile.readText(path)
                                    val count = ExportImport.importSessions(repo, content)
                                    onStatusChange("Imported $count sessions")
                                } catch (e: Exception) {
                                    onStatusChange("Import failed: ${e.message}")
                                }
                            }
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = {
                        scope.launch {
                            val path = FilePicker.saveFile("nepenthe-journal.json", "JSON files", listOf("json"))
                            if (path != null) {
                                try {
                                    val json = ExportImport.exportFullJournal(repo)
                                    PlatformFile.writeText(path, json)
                                    onStatusChange("Exported full journal as JSON")
                                } catch (e: Exception) {
                                    onStatusChange("Full JSON export failed: ${e.message}")
                                }
                            }
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Schema, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export as JSON", maxLines = 1)
                    }
                    // Spacer to keep alignment
                    Box(modifier = Modifier.weight(1f))
                }
                if (statusText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(statusText, style = MaterialTheme.typography.labelSmall,
                        color = if (statusText.startsWith("Import") || statusText.startsWith("Export"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Text("CSV Export (analysis)", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text("One row per entity, R/Pandas-friendly format.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = {
                        scope.launch {
                            val path = FilePicker.saveFile("sessions.csv", "CSV files", listOf("csv"))
                            if (path != null) {
                                try {
                                    val csv = CsvExporter.exportSessionsCsv(repo)
                                    PlatformFile.writeText(path, csv)
                                    onStatusChange("Exported ${repo.sessions.value.size} sessions as CSV")
                                } catch (e: Exception) { onStatusChange("CSV export failed: ${e.message}") }
                            }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Sessions CSV", maxLines = 1) }
                    AppOutlinedButton(onClick = {
                        scope.launch {
                            val path = FilePicker.saveFile("doses.csv", "CSV files", listOf("csv"))
                            if (path != null) {
                                try {
                                    val csv = CsvExporter.exportDosesCsv(repo)
                                    PlatformFile.writeText(path, csv)
                                    onStatusChange("Exported doses as CSV")
                                } catch (e: Exception) { onStatusChange("CSV export failed: ${e.message}") }
                            }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Doses CSV", maxLines = 1) }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = {
                        scope.launch {
                            val path = FilePicker.saveFile("substances.csv", "CSV files", listOf("csv"))
                            if (path != null) {
                                try {
                                    val csv = CsvExporter.exportSubstancesCsv(repo)
                                    PlatformFile.writeText(path, csv)
                                    onStatusChange("Exported ${repo.substances.value.size} substances as CSV")
                                } catch (e: Exception) { onStatusChange("CSV export failed: ${e.message}") }
                            }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Substances CSV", maxLines = 1) }
                    AppOutlinedButton(onClick = {
                        scope.launch {
                            val path = FilePicker.saveFile("nepenthe-export.zip", "ZIP archives", listOf("zip"))
                            if (path != null) {
                                try {
                                    val count = ZipExporter.exportAll(repo, path)
                                    onStatusChange("Exported $count CSV files as zip")
                                } catch (e: Exception) { onStatusChange("ZIP export failed: ${e.message}") }
                            }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("All (Zip)", maxLines = 1) }
                }
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                Text("Backup", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = {
                        scope.launch {
                            try {
                                val store = JournalStore(repo)
                                store.save()
                                onStatusChange("Backup saved to ${store.dataPath()}")
                            } catch (e: Exception) {
                                onStatusChange("Backup failed: ${e.message}")
                            }
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Create Backup", maxLines = 1)
                    }
                }
            }
        }
    }
}
