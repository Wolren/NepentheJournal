package app.journal.ui.settings.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.unit.dp
import app.journal.model.SyncConfig
import app.journal.sync.*
import app.journal.ui.components.*
import app.journal.ui.components.sync.*
import kotlinx.coroutines.launch

@Composable
internal fun SyncSettingsContent(
    syncEngine: SyncEngine,
    status: SyncStatusSnapshot,
    state: SyncSettingsUiState,
    callbacks: SyncSettingsCallbacks,
    scope: kotlinx.coroutines.CoroutineScope,
    clipboard: ClipboardManager,
    formatTimestamp: (Long) -> String,
    userMessage: (String) -> String,
) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Device Sync", style = MaterialTheme.typography.titleMedium)
                }
                AppTonalButton(onClick = { callbacks.onSyncExpanded() }) { Text(if (state.syncExpanded) "Hide" else "Manage") }
            }
            if (state.syncExpanded) {
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))

                SyncStatusOverview(
                    isHosting = status.isHosting,
                    pairedDeviceCount = status.pairedDeviceCount,
                    lastSyncAt = status.lastSyncAt,
                    pendingConflicts = status.pendingConflicts,
                    formatTimestamp = formatTimestamp,
                )

                Spacer(Modifier.height(12.dp))

                SyncHostingCard(
                    isHosting = status.isHosting,
                    hostAddress = status.hostAddress,
                    pairingToken = status.pairingToken,
                    tokenExpiresAt = status.tokenExpiresAt,
                    isStarting = state.isStartingHost,
                    isStopping = state.isStoppingHost,
                    continuousSync = state.continuousSync,
                    onStartHost = {
                        callbacks.onIsStartingHostChange(true)
                        scope.launch {
                            try {
                                val cfg = SyncConfig(id = "config:local", createdAt = 0L, updatedAt = 0L, deviceOrigin = "desktop",
                                    deviceId = "desktop-main", displayName = "Windows Desktop",
                                    listenerPort = state.manualPort.toIntOrNull() ?: SyncConfig.DEFAULT_PORT, continuousSync = state.continuousSync, enableDeltaSync = true)
                                syncEngine.startHosting(cfg).fold(
                                    onSuccess = { callbacks.onLogLine("+Hosting on port ${it.port}") },
                                    onFailure = { callbacks.onLogLine("!Host start failed: ${userMessage(it.message ?: "")}") })
                            } catch (e: Exception) { callbacks.onLogLine("!Error: ${userMessage(e.message ?: "")}") }
                            finally { callbacks.onIsStartingHostChange(false) }
                        }
                    },
                    onStopHost = {
                        callbacks.onIsStoppingHostChange(true)
                        scope.launch {
                            try {
                                syncEngine.stopHosting()
                                callbacks.onLogLine("-Stopped hosting")
                            } catch (e: Exception) { callbacks.onLogLine("!Stop failed: ${userMessage(e.message ?: "")}") }
                            finally { callbacks.onIsStoppingHostChange(false) }
                        }
                    },
                    onCopyToken = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(status.pairingToken ?: ""))
                        callbacks.onLogLine("+Token copied to clipboard")
                    },
                    onContinuousSyncChange = { callbacks.onContinuousSyncChange(it) },
                )

                Spacer(Modifier.height(12.dp))

                SyncConnectCard(
                    manualHost = state.manualHost,
                    manualPort = state.manualPort,
                    manualToken = state.manualToken,
                    hostError = state.hostError,
                    portError = state.portError,
                    isPortValid = state.isPortValid,
                    isIpValid = state.isIpValid,
                    isSyncing = state.isSyncing,
                    syncEngine = syncEngine,
                    scope = scope,
                    onManualHostChange = { callbacks.onManualHostChange(it) },
                    onManualPortChange = { callbacks.onManualPortChange(it) },
                    onManualTokenChange = { callbacks.onManualTokenChange(it) },
                    onSync = {
                        scope.launch {
                            val host = state.manualHost.trim(); val port = state.manualPort.toIntOrNull()
                            if (host.isEmpty()) { callbacks.onLogLine("!Enter a host IP address"); return@launch }
                            if (port == null || port !in 1..65535) { callbacks.onLogLine("!Enter a valid port (1-65535)"); return@launch }
                            callbacks.onIsSyncingChange(true)
                            try {
                                val peer = DiscoveredPeer(deviceId = null, displayName = host, host = host, port = port,
                                    isTrusted = false, fingerprint = null, pairingToken = state.manualToken.ifBlank { null })
                                syncEngine.syncWith(peer, state.continuousSync).fold(
                                    onSuccess = { callbacks.onLogLine("+Connected to $host:$port") },
                                    onFailure = { callbacks.onLogLine("!Sync failed: ${userMessage(it.message ?: "")}") })
                            } catch (e: Exception) { callbacks.onLogLine("!Error: ${userMessage(e.message ?: "")}") }
                            finally { callbacks.onIsSyncingChange(false) }
                        }
                    },
                    onSelectPeer = {
                        callbacks.onManualHostChange(it.host)
                        callbacks.onManualPortChange(it.port.toString())
                        if (it.pairingToken != null) callbacks.onManualTokenChange(it.pairingToken)
                        callbacks.onLogLine("-Selected ${it.displayName} (${it.host}:${it.port})")
                    },
                    onScanEnd = { callbacks.onLogLine("-Scan complete") },
                )

                SyncTrustedDevicesCard(
                    devices = state.trustedDevices,
                    formatTimestamp = formatTimestamp,
                    onRevoke = { deviceId ->
                        scope.launch {
                            syncEngine.revokeTrustedDevice(deviceId)
                            callbacks.onTrustedDevicesChange(syncEngine.trustedDevices())
                            callbacks.onLogLine("-Revoked device")
                        }
                    },
                )

                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))

                SyncEventLogCard(
                    logLines = state.logLines,
                    debugLines = emptyList(), // observed locally via LaunchedEffect in parent
                )
            }
        }
    }
}
