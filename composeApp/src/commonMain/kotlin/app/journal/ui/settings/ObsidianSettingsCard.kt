package app.journal.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.export.obsidian.ObsidianExportConfig
import app.journal.export.obsidian.ObsidianExportManager
import app.journal.export.obsidian.ObsidianVaultOps
import app.journal.ui.components.AppOutlinedButton
import app.journal.ui.components.AppTonalButton
import kotlinx.coroutines.launch

@Composable
fun ObsidianSettingsCard() {
    val repo = remember { JournalRepository.instance }
    val scope = rememberCoroutineScope()

    var obsidianExpanded by remember { mutableStateOf(false) }

    // Reload from repo when expanded — values may have changed since last view
    var vaultPath by remember { mutableStateOf("") }
    var subfolder by remember { mutableStateOf("Nepenthe") }
    var autoExport by remember { mutableStateOf(false) }
    var fileOrg by remember { mutableStateOf("flat") }

    var statusText by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var vaultIsValid by remember { mutableStateOf(false) }
    var vaultChecked by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    // Load saved values when expanding
    LaunchedEffect(obsidianExpanded) {
        if (obsidianExpanded) {
            vaultPath = repo.obsidianVaultPath.value
            subfolder = repo.obsidianSubfolder.value
            autoExport = repo.obsidianAutoExport.value
            fileOrg = repo.obsidianFileOrganization.value
            vaultChecked = false
            vaultIsValid = false
        }
    }

    // Auto-save when toggles change
    fun saveSettings() {
        repo.setObsidianVaultPath(vaultPath)
        repo.setObsidianSubfolder(subfolder)
        repo.setObsidianAutoExport(autoExport)
        repo.setObsidianFileOrganization(fileOrg)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Obsidian Vault", style = MaterialTheme.typography.titleMedium)
                }
                AppTonalButton(onClick = { obsidianExpanded = !obsidianExpanded }) {
                    Text(if (obsidianExpanded) "Hide" else "Manage")
                }
            }

            if (obsidianExpanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // Vault path
                Text("Vault path", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = vaultPath,
                        onValueChange = { vaultPath = it; vaultChecked = false },
                        placeholder = { Text("C:/Users/.../Obsidian Vault") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    if (vaultChecked) {
                        Icon(
                            if (vaultIsValid) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = if (vaultIsValid) "Valid" else "Invalid",
                            tint = if (vaultIsValid) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Root folder of your Obsidian vault. Notes are written into a subfolder inside it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                // Subfolder
                Text("Subfolder", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = subfolder,
                    onValueChange = { subfolder = it },
                    placeholder = { Text("Nepenthe") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Notes go into {vault}/{subfolder}/. Leave empty to write to the vault root.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                // File organization
                Text("File organization", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = fileOrg == "flat",
                        onClick = { fileOrg = "flat"; saveSettings() },
                        label = { Text("Flat") }
                    )
                    FilterChip(
                        selected = fileOrg == "date",
                        onClick = { fileOrg = "date"; saveSettings() },
                        label = { Text("By date") }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (fileOrg == "flat") "All notes in one folder." else "Organized into YYYY/MM/ subdirectories.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                // Auto-export toggle (auto-saves on change)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-export on save", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Export each session to Obsidian when saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoExport,
                        onCheckedChange = { autoExport = it; saveSettings() }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Action buttons row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppOutlinedButton(
                        onClick = {
                            val resolved = if (subfolder.isBlank()) vaultPath else "$vaultPath/$subfolder"
                            vaultIsValid = ObsidianVaultOps.validateVaultPath(vaultPath) &&
                                           ObsidianVaultOps.ensureDir(resolved)
                            vaultChecked = true
                            statusIsError = !vaultIsValid
                            statusText = if (vaultIsValid) "Vault path is valid and writable."
                                else "Vault path does not exist or is not writable."
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Visibility, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Validate", maxLines = 1)
                    }

                    AppOutlinedButton(
                        onClick = {
                            scope.launch {
                                saveSettings()
                                isExporting = true
                                statusText = "Exporting..."
                                statusIsError = false
                                val config = ObsidianExportConfig(
                                    vaultPath = vaultPath,
                                    subfolder = subfolder,
                                    autoExport = autoExport,
                                    fileOrganization = fileOrg
                                )
                                val result = ObsidianExportManager.exportAllSessions(repo, config)
                                isExporting = false
                                statusIsError = result.errors.isNotEmpty()
                                statusText = if (result.errors.isEmpty())
                                    "Exported ${result.written} notes to Obsidian."
                                else
                                    "Exported ${result.written} notes with ${result.errors.size} error(s)."
                            }
                        },
                        enabled = !isExporting && vaultPath.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isExporting) "Exporting..." else "Export all",
                            maxLines = 1
                        )
                    }
                }

                // Action buttons row 2
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppOutlinedButton(
                        onClick = {
                            scope.launch {
                                isImporting = true
                                statusText = "Importing..."
                                statusIsError = false
                                val config = ObsidianExportConfig(
                                    vaultPath = vaultPath,
                                    subfolder = subfolder,
                                    autoExport = autoExport,
                                    fileOrganization = fileOrg
                                )
                                val result = ObsidianExportManager.importFromVault(repo, config)
                                isImporting = false
                                statusIsError = result.errors.isNotEmpty()
                                statusText = if (result.errors.isEmpty())
                                    "Imported: ${result.created} created, ${result.updated} updated, ${result.skipped} skipped."
                                else
                                    "Import finished with ${result.errors.size} error(s)."
                            }
                        },
                        enabled = !isImporting && vaultPath.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isImporting) "Importing..." else "Import from vault",
                            maxLines = 1
                        )
                    }

                    AppOutlinedButton(
                        onClick = {
                            saveSettings()
                            statusIsError = false
                            statusText = "Settings saved."
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save", maxLines = 1)
                    }
                }

                // Status message
                if (statusText != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (statusIsError) Icons.Default.Error else Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (statusIsError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            statusText!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (statusIsError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
