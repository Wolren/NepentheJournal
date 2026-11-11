package app.journal.ui.settings.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.journal.export.EncryptedBackup
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
    var passphraseAction by remember { mutableStateOf<PassphraseAction?>(null) }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column {
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
                Spacer(Modifier.height(4.dp))
                Text(
                    "Backup (full) is readable JSON and includes your medications. Use Seal backup for an encrypted file.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(
                        onClick = { passphraseAction = PassphraseAction.SEAL },
                        enabled = EncryptedBackup.isSupported,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Seal backup", maxLines = 1)
                    }
                    AppOutlinedButton(
                        onClick = { passphraseAction = PassphraseAction.RESTORE },
                        enabled = EncryptedBackup.isSupported,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Restore sealed", maxLines = 1)
                    }
                }
                Text(
                    "A sealed backup is zstd-compressed and encrypted with your passphrase, so it is safe to store off-device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

    passphraseAction?.let { action ->
        PassphraseDialog(
            action = action,
            onDismiss = { passphraseAction = null },
            onConfirm = { passphrase ->
                passphraseAction = null
                if (action == PassphraseAction.SEAL) vm.exportEncryptedBackup(passphrase)
                else vm.importEncryptedBackup(passphrase)
            }
        )
    }
}

internal enum class PassphraseAction { SEAL, RESTORE }

/** Asks for the backup passphrase; sealing asks twice so a typo cannot lock the file. */
@Composable
private fun PassphraseDialog(
    action: PassphraseAction,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val sealing = action == PassphraseAction.SEAL
    val tooShort = first.length < 8
    val mismatch = sealing && first != second

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (sealing) "Seal a backup" else "Restore a sealed backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (sealing) "Choose a passphrase of at least 8 characters. Without it the backup cannot be opened."
                    else "Enter the passphrase this backup was sealed with.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (sealing) {
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        label = { Text("Repeat passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = mismatch,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (mismatch) {
                    Text("The passphrases differ", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = first.isNotEmpty() && !tooShort && !mismatch,
                onClick = { onConfirm(first.toCharArray()) }
            ) { Text(if (sealing) "Seal" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
