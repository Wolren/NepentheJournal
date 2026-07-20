package app.journal.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.data.JournalRepository
import app.journal.model.SyncConfig
import app.journal.sync.*
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import kotlinx.coroutines.launch

private data class LogEntry(
    val text: String,
    val type: LogType,
    val timestamp: Long = currentTimeMillis()
)

private enum class LogType { INFO, SUCCESS, ERROR }

private fun userMessage(error: Throwable): String {
    val msg = error.message ?: error::class.simpleName ?: "Unknown"
    return when {
        msg.contains("Connection refused") -> "Device not reachable. Check IP and port."
        msg.contains("timed out") -> "Connection timed out. Device may be offline."
        msg.contains("Certificate pinning failed") -> "Device certificate changed. Re-pair required."
        msg.contains("Invalid or expired token") -> "Pairing token expired or wrong. Generate a new one."
        msg.contains("Authentication failed") -> "Sync auth failed. Try re-pairing."
        msg.contains("Not paired") -> "Not paired with this device. Enter a pairing token."
        msg.contains("keytool") || msg.contains("Certificate") -> "TLS setup failed. Restart the app."
        msg.contains("port") && msg.contains("available") -> "Port already in use. Try a different port."
        else -> msg
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val diff = currentTimeMillis() - epochMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(syncEngine: SyncEngine) {
    val status by syncEngine.observeStatus().collectAsState(initial = SyncStatusSnapshot(
        isHosting = false, hostAddress = null,
        activeConnections = emptyList(), lastSyncAt = null,
        pendingConflicts = 0, lastError = null
    ))
    val trustedDevices = remember { mutableStateOf(syncEngine.trustedDevices()) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("4984") }
    var manualToken by remember { mutableStateOf("") }
    var continuousSync by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf(listOf(LogEntry("Sync engine ready", LogType.INFO))) }

    // LAN discovery state
    var discoveredPeers by remember { mutableStateOf<List<DiscoveredPeer>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    // Debug log state
    var debugLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDebugLog by remember { mutableStateOf(false) }

    // Start/stop LAN discovery collection when screen enters
    LaunchedEffect(Unit) {
        syncEngine.observeDiscoveredPeers().collect { peers ->
            discoveredPeers = peers
        }
    }

    // Collect debug log lines
    LaunchedEffect(Unit) {
        syncEngine.observeDebugLog().collect { line ->
            debugLines = (debugLines + line).take(200)
        }
    }

    // Loading states for async operations
    var isStartingHost by remember { mutableStateOf(false) }
    var isStoppingHost by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }

    // Refresh trusted devices whenever status changes
    LaunchedEffect(status.pairedDeviceCount, status.isHosting) {
        trustedDevices.value = syncEngine.trustedDevices()
    }

    // Validate IP (basic)
    val isIpValid = manualHost.isBlank() || manualHost.matches(Regex("^[\\d.]+$"))
    val isPortValid = (manualPort.toIntOrNull() ?: 0) in 1..65535

    val hostError = if (manualHost.isNotBlank() && !isIpValid) "Invalid IP format" else null
    val portError = if (manualPort.isNotBlank() && !isPortValid) "Port must be 1-65535" else null

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        // ---- Status overview card ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
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
                                if (status.isHosting) Icons.Default.Wifi else Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = if (status.isHosting) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Text("Sync", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            text = if (status.isHosting) "Active" else "Off",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (status.isHosting) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Column {
                            Text("Paired Devices", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${status.pairedDeviceCount}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("Last Sync", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                status.lastSyncAt?.let { formatTimestamp(it) } ?: "Never",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text("Conflicts", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${status.pendingConflicts}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (status.pendingConflicts > 0)
                                        MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        // ---- Hosting card ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
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
                                Icons.Default.Devices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                if (status.isHosting) "Hosting Active" else "Hosting",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        if (isStartingHost || isStoppingHost) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            AppTonalButton(
                                onClick = {
                                    scope.launch {
                                        if (status.isHosting) {
                                            isStoppingHost = true
                                            try {
                                                syncEngine.stopHosting()
                                                logLines = listOf(LogEntry("Hosting stopped", LogType.INFO)) + logLines
                                            } catch (e: Exception) {
                                                logLines = listOf(LogEntry("Stop failed: ${userMessage(e)}", LogType.ERROR)) + logLines
                                            } finally {
                                                isStoppingHost = false
                                            }
                                        } else {
                                            isStartingHost = true
                                            try {
                                                val cfg = SyncConfig(
                                                    id = "config:${manualHost.ifEmpty { "local" }}",
                                                    createdAt = 0L, updatedAt = 0L,
                                                    deviceOrigin = "desktop",
                                                    deviceId = "desktop-main",
                                                    displayName = "Windows Desktop",
                                                    listenerPort = manualPort.toIntOrNull() ?: 4984,
                                                    continuousSync = continuousSync,
                                                    enableDeltaSync = true
                                                )
                                                val result = syncEngine.startHosting(cfg)
                                                result.fold(
                                                    onSuccess = {
                                                        logLines = listOf(
                                                            LogEntry("Hosting on port ${it.port}", LogType.SUCCESS)
                                                        ) + logLines
                                                    },
                                                    onFailure = {
                                                        logLines = listOf(
                                                            LogEntry("Host start failed: ${userMessage(it)}", LogType.ERROR)
                                                        ) + logLines
                                                    }
                                                )
                                            } catch (e: Exception) {
                                                logLines = listOf(LogEntry("Error: ${userMessage(e)}", LogType.ERROR)) + logLines
                                            } finally {
                                                isStartingHost = false
                                            }
                                        }
                                    }
                                },
                                colors = if (status.isHosting)
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ) else null
                            ) {
                                Icon(
                                    if (status.isHosting) Icons.Default.Cancel else Icons.Default.Wifi,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (status.isHosting) "Stop" else "Start")
                            }
                        }
                    }

                    if (status.isHosting) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Other devices connect to: ${status.hostAddress ?: "..."}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Pairing token display
                    val pairingToken = status.pairingToken
                    if (status.isHosting && pairingToken != null) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Pairing Token",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = pairingToken,
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "Enter this on the device you want to pair",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                AppIconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(pairingToken))
                                        logLines = listOf(LogEntry("Token copied to clipboard", LogType.SUCCESS)) + logLines
                                    },
                                    icon = Icons.Default.ContentCopy,
                                    contentDescription = "Copy token",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Continuous sync toggle
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Continuous sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = continuousSync,
                            onCheckedChange = { continuousSync = it }
                        )
                    }
                }
            }
        }

        // ---- Connect to device card ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text("Connect to Device", style = MaterialTheme.typography.titleMedium)
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            placeholder = { Text("IP address") },
                            singleLine = true,
                            isError = hostError != null,
                            supportingText = hostError?.let { { Text(it) } },
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it.filter { c -> c.isDigit() }.take(5) },
                            placeholder = { Text("Port") },
                            singleLine = true,
                            isError = portError != null,
                            supportingText = portError?.let { { Text(it) } },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it.uppercase().take(6) },
                        placeholder = { Text("Pairing token from host") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                if (manualToken.isEmpty()) "Required for first-time pairing"
                                else "${manualToken.length}/6 characters"
                            )
                        }
                    )

                    // ---- LAN scan ----
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
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
                                Icons.Default.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text("Scan LAN", style = MaterialTheme.typography.titleSmall)
                        }
                        if (isScanning) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Scanning",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            AppTonalButton(
                                onClick = {
                                    scope.launch {
                                        isScanning = true
                                        logLines = listOf(LogEntry("Scanning LAN for devices...", LogType.INFO)) + logLines
                                        try {
                                            syncEngine.startDiscovery(DiscoveryMode.HYBRID).collect {
                                                // Events are routed to observeDiscoveredPeers() by the engine
                                            }
                                        } catch (e: Exception) {
                                            logLines = listOf(LogEntry("Scan failed: ${e.message ?: e::class.simpleName ?: "Unknown"}", LogType.ERROR)) + logLines
                                        } finally {
                                            isScanning = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Text("Scan")
                            }
                        }
                    }

                    // Discovered peers list
                    if (discoveredPeers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Discovered devices",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        discoveredPeers.forEach { peer ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable {
                                        manualHost = peer.host
                                        manualPort = peer.port.toString()
                                        if (peer.pairingToken != null) manualToken = peer.pairingToken
                                        logLines = listOf(LogEntry("Selected ${peer.displayName} (${peer.host}:${peer.port})", LogType.INFO)) + logLines
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(peer.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium)
                                        Text("${peer.host}:${peer.port}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = "Connect",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    if (isSyncing) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        AppButton(
                            onClick = {
                                scope.launch {
                                    val host = manualHost.trim()
                                    val port = manualPort.toIntOrNull()
                                    if (host.isEmpty()) {
                                        logLines = listOf(LogEntry("Enter a host IP address", LogType.ERROR)) + logLines
                                        return@launch
                                    }
                                    if (port == null || port !in 1..65535) {
                                        logLines = listOf(LogEntry("Enter a valid port (1-65535)", LogType.ERROR)) + logLines
                                        return@launch
                                    }
                                    isSyncing = true
                                    try {
                                        val peer = DiscoveredPeer(
                                            deviceId = null, displayName = host,
                                            host = host, port = port,
                                            isTrusted = false, fingerprint = null,
                                            pairingToken = manualToken.ifBlank { null }
                                        )
                                        val result = syncEngine.syncWith(peer, continuousSync)
                                        result.fold(
                                            onSuccess = {
                                                logLines = listOf(
                                                    LogEntry("Connected to $host:$port", LogType.SUCCESS)
                                                ) + logLines
                                            },
                                            onFailure = {
                                                logLines = listOf(
                                                    LogEntry("Sync failed: ${userMessage(it)}", LogType.ERROR)
                                                ) + logLines
                                            }
                                        )
                                    } catch (e: Exception) {
                                        logLines = listOf(LogEntry("Error: ${userMessage(e)}", LogType.ERROR)) + logLines
                                    } finally {
                                        isSyncing = false
                                    }
                                }
                            },
                            enabled = manualHost.isNotBlank() && isPortValid && isIpValid,
                            icon = Icons.Default.Sync,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sync Now")
                        }
                    }
                }
            }
        }

        // ---- Trusted devices card ----
        if (trustedDevices.value.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text("Trusted Devices", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))

                        trustedDevices.value.forEach { device ->
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Paired ${formatTimestamp(device.pairedAt)}" +
                                                   (device.lastSeenAt?.let { " - seen ${formatTimestamp(it)}" } ?: ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                AppIconButton(
                                    onClick = {
                                        scope.launch {
                                            syncEngine.revokeTrustedDevice(device.deviceId)
                                            trustedDevices.value = syncEngine.trustedDevices()
                                            logLines = listOf(
                                                LogEntry("Revoked ${device.displayName}", LogType.INFO)
                                            ) + logLines
                                        }
                                    },
                                    icon = Icons.Default.LinkOff,
                                    contentDescription = "Revoke ${device.displayName}",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Conflicts warning ----
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
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // ---- Error display ----
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

        // ---- Event log ----
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Event Log", style = MaterialTheme.typography.labelLarge)
                        if (debugLines.isNotEmpty()) {
                            TextButton(onClick = { showDebugLog = !showDebugLog }) {
                                Text(if (showDebugLog) "Hide debug" else "Show debug",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    logLines.take(15).forEach { entry ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = when (entry.type) {
                                    LogType.SUCCESS -> "+"
                                    LogType.ERROR -> "!"
                                    LogType.INFO -> "-"
                                },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (entry.type) {
                                    LogType.SUCCESS -> MaterialTheme.colorScheme.primary
                                    LogType.ERROR -> MaterialTheme.colorScheme.error
                                    LogType.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                text = entry.text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (logLines.isEmpty()) {
                        Text(
                            text = "No events yet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    // Debug log toggle
                    if (showDebugLog && debugLines.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("Debug Log", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        debugLines.takeLast(30).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---- Footer ----
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
