package app.journal.ui.settings.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.sync.*
import app.journal.model.SyncConfig
import app.journal.ui.components.*
import kotlinx.coroutines.launch

@Composable
internal fun SyncSettingsContent(
    syncEngine: SyncEngine, status: SyncStatusSnapshot, logLines: List<String>, trustedDevices: List<TrustedDeviceInfo>,
    manualHost: String, manualPort: String, manualToken: String, continuousSync: Boolean,
    hostError: String?, portError: String?, isPortValid: Boolean, isIpValid: Boolean, isSyncing: Boolean,
    syncExpanded: Boolean, isStartingHost: Boolean, isStoppingHost: Boolean,
    scope: kotlinx.coroutines.CoroutineScope, clipboard: ClipboardManager,
    formatTimestamp: (Long) -> String, userMessage: (String) -> String,
    onManualHostChange: (String) -> Unit, onManualPortChange: (String) -> Unit,
    onManualTokenChange: (String) -> Unit, onContinuousSyncChange: (Boolean) -> Unit,
    onSyncExpanded: () -> Unit, onLogLine: (String) -> Unit, onTrustedDevicesChange: (List<TrustedDeviceInfo>) -> Unit,
    onIsSyncingChange: (Boolean) -> Unit, onIsStartingHostChange: (Boolean) -> Unit, onIsStoppingHostChange: (Boolean) -> Unit,
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
                AppTonalButton(onClick = { onSyncExpanded() }) { Text(if (syncExpanded) "Hide" else "Manage") }
            }
            if (syncExpanded) {
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text("Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(if (status.isHosting) Icons.Default.Wifi else Icons.Default.WifiOff, null,
                                modifier = Modifier.size(16.dp), tint = if (status.isHosting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (status.isHosting) "Active" else "Off",
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column {
                        Text("Paired", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${status.pairedDeviceCount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Last Sync", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(status.lastSyncAt?.let { formatTimestamp(it) } ?: "Never",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Conflicts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${status.pendingConflicts}",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            color = if (status.pendingConflicts > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text("Hosting", style = MaterialTheme.typography.titleMedium)
                    }
                    if (isStartingHost || isStoppingHost) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        AppTonalButton(onClick = {
                            scope.launch {
                                if (status.isHosting) {
                                    onIsStoppingHostChange(true)
                                    try {
                                        syncEngine.stopHosting()
                                        onLogLine("Stopped hosting")
                                    } catch (e: Exception) { onLogLine("Stop failed: ${userMessage(e.message ?: "")}") }
                                    finally { onIsStoppingHostChange(false) }
                                } else {
                                    onIsStartingHostChange(true)
                                    try {
                                        val cfg = SyncConfig(id = "config:local", createdAt = 0L, updatedAt = 0L, deviceOrigin = "desktop",
                                            deviceId = "desktop-main", displayName = "Windows Desktop",
                                            listenerPort = manualPort.toIntOrNull() ?: 4984, continuousSync = continuousSync, enableDeltaSync = true)
                                        syncEngine.startHosting(cfg).fold(
                                            onSuccess = { onLogLine("Hosting on port ${it.port}") },
                                            onFailure = { onLogLine("Host start failed: ${userMessage(it.message ?: "")}") })
                                    } catch (e: Exception) { onLogLine("Error: ${userMessage(e.message ?: "")}") }
                                    finally { onIsStartingHostChange(false) }
                                }
                            }
                        }, colors = if (status.isHosting) ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) else null) {
                            Icon(if (status.isHosting) Icons.Default.Cancel else Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(if (status.isHosting) "Stop" else "Start")
                        }
                    }
                }
                if (status.isHosting) {
                    Spacer(Modifier.height(8.dp))
                    Text("Other devices connect to: ${status.hostAddress ?: "..."}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                // Pairing token
                val pairingToken = status.pairingToken
                if (status.isHosting && pairingToken != null) {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Pairing Token", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.height(4.dp))
                                Text(pairingToken, style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold, letterSpacing = 8.sp,
                                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("Enter this on the device you want to pair",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                            }
                            AppIconButton(onClick = { clipboard.setText(AnnotatedString(pairingToken)); onLogLine("Token copied to clipboard") },
                                icon = Icons.Default.ContentCopy, contentDescription = "Copy token", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Continuous sync", style = MaterialTheme.typography.bodyMedium)
                        Text("Automatically sync with paired devices", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = continuousSync, onCheckedChange = { onContinuousSyncChange(it) })
                }

                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("Connect to Device", style = MaterialTheme.typography.titleMedium)
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = manualHost, onValueChange = { onManualHostChange(it) },
                        placeholder = { Text("IP address") }, singleLine = true,
                        isError = hostError != null, supportingText = hostError?.let { { Text(it) } },
                        modifier = Modifier.weight(2f))
                    OutlinedTextField(value = manualPort, onValueChange = { onManualPortChange(it.filter { c -> c.isDigit() }.take(5)) },
                        placeholder = { Text("Port") }, singleLine = true,
                        isError = portError != null, supportingText = portError?.let { { Text(it) } },
                        modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = manualToken, onValueChange = { onManualTokenChange(it.uppercase().take(6)) },
                    placeholder = { Text("Pairing token from host") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(if (manualToken.isEmpty()) "Required for first-time pairing" else "${manualToken.length}/6 characters") })

                // ---- LAN scan ----
                val discoveredPeers by syncEngine.observeDiscoveredPeers().collectAsState(initial = emptyList())
                var isScanning by remember { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("Scan LAN", style = MaterialTheme.typography.titleSmall)
                    }
                    if (isScanning) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Scanning", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        AppTonalButton(onClick = {
                            scope.launch {
                                isScanning = true
                                onLogLine("Scanning LAN for devices...")
                                try {
                                    syncEngine.startDiscovery(DiscoveryMode.HYBRID).collect { }
                                } catch (e: Exception) {
                                    onLogLine("Scan failed: ${e.message ?: e::class.simpleName ?: "Unknown"}")
                                } finally { isScanning = false }
                            }
                        }, colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )) { Text("Scan") }
                    }
                }

                // Discovered peers list
                if (discoveredPeers.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Discovered devices", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    discoveredPeers.forEach { peer ->
                        Surface(shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clickable {
                                    onManualHostChange(peer.host)
                                    onManualPortChange(peer.port.toString())
                                    if (peer.pairingToken != null) onManualTokenChange(peer.pairingToken)
                                    onLogLine("Selected ${peer.displayName} (${peer.host}:${peer.port})")
                                }
                        ) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(peer.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("${peer.host}:${peer.port}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.Link, "Connect", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (isSyncing) {
                    Box(modifier = Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                } else {
                    AppButton(onClick = {
                        scope.launch {
                            val host = manualHost.trim(); val port = manualPort.toIntOrNull()
                            if (host.isEmpty()) { onLogLine("Enter a host IP address"); return@launch }
                            if (port == null || port !in 1..65535) { onLogLine("Enter a valid port (1-65535)"); return@launch }
                            onIsSyncingChange(true)
                            try {
                                val peer = DiscoveredPeer(deviceId = null, displayName = host, host = host, port = port,
                                    isTrusted = false, fingerprint = null, pairingToken = manualToken.ifBlank { null })
                                syncEngine.syncWith(peer, continuousSync).fold(
                                    onSuccess = { onLogLine("Connected to $host:$port") },
                                    onFailure = { onLogLine("Sync failed: ${userMessage(it.message ?: "")}") })
                            } catch (e: Exception) { onLogLine("Error: ${userMessage(e.message ?: "")}") }
                            finally { onIsSyncingChange(false) }
                        }
                    }, enabled = manualHost.isNotBlank() && isPortValid && isIpValid,
                        icon = Icons.Default.Sync, modifier = Modifier.fillMaxWidth()) { Text("Sync Now") }
                }

                if (trustedDevices.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text("Trusted Devices", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    trustedDevices.forEach { device ->
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Paired ${formatTimestamp(device.pairedAt)}" + (device.lastSeenAt?.let { " - seen ${formatTimestamp(it)}" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            AppIconButton(onClick = {
                                scope.launch {
                                    syncEngine.revokeTrustedDevice(device.deviceId)
                                    onTrustedDevicesChange(syncEngine.trustedDevices())
                                    onLogLine("Revoked ${device.displayName}")
                                }
                            }, icon = Icons.Default.LinkOff, contentDescription = "Revoke ${device.displayName}", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                var showDebug by remember { mutableStateOf(false) }
                var debugLines by remember { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(Unit) {
                    syncEngine.observeDebugLog().collect { line ->
                        debugLines = (debugLines + line).take(200)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Event Log", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { showDebug = !showDebug }) {
                        Text(if (showDebug) "Hide debug" else "Show debug",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
                logLines.take(15).forEach { line ->
                    val prefix = when { line.startsWith("+") -> '+'; line.startsWith("!") -> '!'; else -> '-' }
                    val displayText = line.removePrefix("+").removePrefix("!").removePrefix("-")
                    val color = when (prefix) { '+' -> MaterialTheme.colorScheme.primary; '!' -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 1.dp)) {
                        Text("$prefix", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = color)
                        Text(displayText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (logLines.isEmpty()) Text("No events yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))

                // Debug log viewer
                if (showDebug && debugLines.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Debug Log", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    debugLines.takeLast(30).forEach { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }
}
