package app.journal.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.SyncConfig
import app.journal.sync.*
import app.journal.ui.components.*
import app.journal.ui.components.sync.*
import app.journal.util.currentTimeMillis
import app.journal.util.formatRelativeTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(syncEngine: SyncEngine) {
    val status by syncEngine.observeStatus().collectAsState(initial = SyncStatusSnapshot(
        isHosting = false, hostAddress = null,
        activeConnections = emptyList(), lastSyncAt = null,
        pendingConflicts = 0, lastError = null
    ))
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("4984") }
    var manualToken by remember { mutableStateOf("") }
    var continuousSync by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf(listOf("-Sync engine ready")) }
    var debugLines by remember { mutableStateOf<List<String>>(emptyList()) }

    // Loading states
    var isStartingHost by remember { mutableStateOf(false) }
    var isStoppingHost by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    // Collect debug log lines
    LaunchedEffect(Unit) {
        syncEngine.observeDebugLog().collect { line ->
            debugLines = (debugLines + line).take(200)
        }
    }

    val isIpValid = manualHost.isBlank() || manualHost.matches(Regex("^[\\d.]+$"))
    val isPortValid = (manualPort.toIntOrNull() ?: 0) in 1..65535
    val hostError = if (manualHost.isNotBlank() && !isIpValid) "Invalid IP format" else null
    val portError = if (manualPort.isNotBlank() && !isPortValid) "Port must be 1-65535" else null

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        // Status overview
        item {
            SyncStatusOverview(
                isHosting = status.isHosting,
                pairedDeviceCount = status.pairedDeviceCount,
                lastSyncAt = status.lastSyncAt,
                pendingConflicts = status.pendingConflicts,
                formatTimestamp = { formatRelativeTime(it) },
            )
        }

        // Hosting card
        item {
            SyncHostingCard(
                isHosting = status.isHosting,
                hostAddress = status.hostAddress,
                pairingToken = status.pairingToken,
                tokenExpiresAt = status.tokenExpiresAt,
                isStarting = isStartingHost,
                isStopping = isStoppingHost,
                continuousSync = continuousSync,
                onStartHost = {
                    isStartingHost = true
                    scope.launch {
                        try {
                            val cfg = SyncConfig(
                                id = "config:${manualHost.ifEmpty { "local" }}",
                                createdAt = 0L, updatedAt = 0L,
                                deviceOrigin = "desktop",
                                deviceId = "desktop-main",
                                displayName = "Windows Desktop",
                                listenerPort = manualPort.toIntOrNull() ?: SyncConfig.DEFAULT_PORT,
                                continuousSync = continuousSync,
                                enableDeltaSync = true
                            )
                            syncEngine.startHosting(cfg).fold(
                                onSuccess = { logLines = listOf("+Hosting on port ${it.port}") + logLines },
                                onFailure = { logLines = listOf("!Host start failed: ${it.message ?: it::class.simpleName ?: "Unknown"}") + logLines }
                            )
                        } catch (e: Exception) {
                            logLines = listOf("!Error: ${e.message ?: e::class.simpleName ?: "Unknown"}") + logLines
                        } finally {
                            isStartingHost = false
                        }
                    }
                },
                onStopHost = {
                    isStoppingHost = true
                    scope.launch {
                        try {
                            syncEngine.stopHosting()
                            logLines = listOf("-Hosting stopped") + logLines
                        } catch (e: Exception) {
                            logLines = listOf("!Stop failed: ${e.message ?: e::class.simpleName ?: "Unknown"}") + logLines
                        } finally {
                            isStoppingHost = false
                        }
                    }
                },
                onCopyToken = {
                    clipboard.setText(AnnotatedString(status.pairingToken ?: ""))
                    logLines = listOf("+Token copied to clipboard") + logLines
                },
                onContinuousSyncChange = { continuousSync = it },
            )
        }

        // Connect to device
        item {
            SyncConnectCard(
                manualHost = manualHost,
                manualPort = manualPort,
                manualToken = manualToken,
                hostError = hostError,
                portError = portError,
                isPortValid = isPortValid,
                isIpValid = isIpValid,
                isSyncing = isSyncing,
                syncEngine = syncEngine,
                scope = scope,
                onManualHostChange = { manualHost = it },
                onManualPortChange = { manualPort = it.filter { c -> c.isDigit() }.take(5) },
                onManualTokenChange = { manualToken = it.uppercase().take(6) },
                onSync = {
                    scope.launch {
                        val host = manualHost.trim()
                        val port = manualPort.toIntOrNull()
                        if (host.isEmpty()) { logLines = listOf("!Enter a host IP address") + logLines; return@launch }
                        if (port == null || port !in 1..65535) { logLines = listOf("!Enter a valid port (1-65535)") + logLines; return@launch }
                        isSyncing = true
                        try {
                            val peer = DiscoveredPeer(
                                deviceId = null, displayName = host,
                                host = host, port = port,
                                isTrusted = false, fingerprint = null,
                                pairingToken = manualToken.ifBlank { null }
                            )
                            syncEngine.syncWith(peer, continuousSync).fold(
                                onSuccess = { logLines = listOf("+Connected to $host:$port") + logLines },
                                onFailure = { logLines = listOf("!Sync failed: ${it.message ?: it::class.simpleName ?: "Unknown"}") + logLines }
                            )
                        } catch (e: Exception) {
                            logLines = listOf("!Error: ${e.message ?: e::class.simpleName ?: "Unknown"}") + logLines
                        } finally {
                            isSyncing = false
                        }
                    }
                },
                onSelectPeer = {
                    manualHost = it.host
                    manualPort = it.port.toString()
                    if (it.pairingToken != null) manualToken = it.pairingToken
                    logLines = listOf("-Selected ${it.displayName} (${it.host}:${it.port})") + logLines
                },
            )
        }

        // Trusted devices
        item {
            var trustedDevices by remember { mutableStateOf(syncEngine.trustedDevices()) }
            LaunchedEffect(status.pairedDeviceCount, status.isHosting) {
                trustedDevices = syncEngine.trustedDevices()
            }
            SyncTrustedDevicesCard(
                devices = trustedDevices,
                formatTimestamp = { formatRelativeTime(it) },
                onRevoke = { deviceId ->
                    scope.launch {
                        syncEngine.revokeTrustedDevice(deviceId)
                        trustedDevices = syncEngine.trustedDevices()
                        logLines = listOf("-Revoked device") + logLines
                    }
                },
            )
        }

        // Conflicts warning
        if (status.pendingConflicts > 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.SyncProblem,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${status.pendingConflicts} Sync Conflict(s)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Check session notes for details",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // Error display
        val lastError = status.lastError
        if (lastError != null && status.pendingConflicts == 0) {
            item {
                Text(
                    text = lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        // Event log + debug
        item {
            SyncEventLogCard(
                logLines = logLines,
                debugLines = debugLines,
            )
        }

        // Footer
        item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No cloud dependency. Data stays on your devices.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
