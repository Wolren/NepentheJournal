package app.journal.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.JournalRepository
import app.journal.model.SyncConfig
import app.journal.sync.*
import kotlinx.coroutines.launch
import app.journal.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen() {
    val repo = remember { JournalRepository.instance }
    val syncEngine = remember { createSyncEngine(repo) }
    val status by syncEngine.observeStatus().collectAsState(initial = SyncStatusSnapshot(
        isHosting = false, hostAddress = null,
        activeConnections = emptyList(), lastSyncAt = null,
        pendingConflicts = 0, lastError = null
    ))
    val scope = rememberCoroutineScope()
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("4984") }
    var manualToken by remember { mutableStateOf("") }
    var logLines by remember { mutableStateOf(listOf("Sync engine ready")) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Device Sync",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "P2P sync over LAN, no cloud, no accounts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Hosting card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (status.isHosting) Icons.Default.CheckCircle else Icons.Default.Computer,
                                contentDescription = null,
                                tint = if (status.isHosting) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column {
                                Text(
                                    text = if (status.isHosting) "Hosting Active" else "Hosting",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (status.hostAddress != null) {
                                    Text(
                                        text = status.hostAddress!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                // Show pairing token when hosting
                                if (status.isHosting && status.pairingToken != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(
                                                text = "Pairing Token",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Text(
                                                text = status.pairingToken!!,
                                                style = MaterialTheme.typography.headlineLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                            Text(
                                                text = "Enter this token on the device you want to pair",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                                // Show paired device count
                                if (status.pairedDeviceCount > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "${status.pairedDeviceCount} paired device(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        AppTonalButton(
                            onClick = {
                                scope.launch {
                                    if (status.isHosting) {
                                        syncEngine.stopHosting()
                                        logLines = listOf("Hosting stopped") + logLines
                                    } else {
                                        val cfg = SyncConfig(
                                            id = "config:${manualHost.ifEmpty { "local" }}",
                                            createdAt = 0L,
                                            updatedAt = 0L,
                                            deviceOrigin = "desktop",
                                            deviceId = "desktop-main",
                                            displayName = "Windows Desktop",
                                            listenerPort = manualPort.toIntOrNull() ?: 4984,
                                            continuousSync = false,
                                            enableDeltaSync = true
                                        )
                                        val result = syncEngine.startHosting(cfg)
                                        result.onSuccess {
                                            logLines = listOf("Hosting on port ${it.port}") + logLines
                                        }.onFailure {
                                            logLines = listOf("Host start failed: ${it.message}") + logLines
                                        }
                                    }
                                }
                            }
                        ) {
                            Text(if (status.isHosting) "Stop" else "Start")
                        }
                    }

                    if (status.activeConnections.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("Connected devices:", style = MaterialTheme.typography.labelMedium)
                        status.activeConnections.forEach { peer ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.Person, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Text(peer.displayName, style = MaterialTheme.typography.bodySmall)
                                }
                                AppTextButton(onClick = {
                                    scope.launch { syncEngine.disconnectFrom(peer.deviceId) }
                                }) { Text("Disconnect", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
        }

        // Manual connect
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Connect to Device", style = MaterialTheme.typography.titleMedium)
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            placeholder = { Text("IP address") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it },
                            placeholder = { Text("Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it.uppercase().take(6) },
                        placeholder = { Text("Pairing token (from host device)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    AppButton(
                        onClick = {
                            scope.launch {
                                val host = manualHost.trim()
                                val port = manualPort.toIntOrNull() ?: 4984
                                if (host.isEmpty()) {
                                    logLines = listOf("Enter a host IP address") + logLines
                                    return@launch
                                }
                                val peer = DiscoveredPeer(
                                    deviceId = null, displayName = host,
                                    host = host, port = port,
                                    isTrusted = false, fingerprint = null,
                                    pairingToken = manualToken.ifBlank { null }
                                )
                                val result = syncEngine.syncWith(peer)
                                result.onSuccess {
                                    logLines = listOf("Synced with $host:$port") + logLines
                                }.onFailure {
                                    logLines = listOf("Sync failed: ${it.message}") + logLines
                                }
                            }
                        },
                        enabled = manualHost.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sync Now") }
                }
            }
        }

        // Status + conflicts
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Sync Status", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Last sync: ${status.lastSyncAt?.let { "completed" } ?: "never"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Active connections: ${status.activeConnections.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (status.pendingConflicts > 0) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Error, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp))
                                Text(
                                    "${status.pendingConflicts} conflict(s) to review",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    if (status.lastError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Error: ${status.lastError}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Sync log
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Log", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    logLines.take(10).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Info footer
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No cloud dependency. Data stays on your devices",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
