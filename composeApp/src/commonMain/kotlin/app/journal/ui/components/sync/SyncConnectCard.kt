package app.journal.ui.components.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.sync.DiscoveredPeer
import app.journal.sync.DiscoveryMode
import app.journal.sync.SyncEngine
import app.journal.ui.components.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Connect-to-device card with IP/port/token fields, LAN scan,
 * discovered peers list, and sync now button.
 * Shared between standalone sync screen and settings panel.
 */
@Composable
fun SyncConnectCard(
    manualHost: String,
    manualPort: String,
    manualToken: String,
    hostError: String?,
    portError: String?,
    isPortValid: Boolean,
    isIpValid: Boolean,
    isSyncing: Boolean,
    syncEngine: SyncEngine,
    scope: CoroutineScope,
    onManualHostChange: (String) -> Unit,
    onManualPortChange: (String) -> Unit,
    onManualTokenChange: (String) -> Unit,
    onSync: () -> Unit,
    onScanEnd: () -> Unit = {},
    onSelectPeer: (DiscoveredPeer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val discoveredPeers by syncEngine.observeDiscoveredPeers().collectAsState(initial = emptyList())
    var isScanning by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
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
                    onValueChange = onManualHostChange,
                    placeholder = { Text("IP address") },
                    singleLine = true,
                    isError = hostError != null,
                    supportingText = hostError?.let { { Text(it) } },
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = manualPort,
                    onValueChange = onManualPortChange,
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
                onValueChange = onManualTokenChange,
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

            // LAN scan
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                try {
                                    syncEngine.startDiscovery(DiscoveryMode.HYBRID).collect { }
                                    onScanEnd()
                                } finally { isScanning = false }
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) { Text("Scan") }
                }
            }

            // Discovered peers
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
                            .clickable { onSelectPeer(peer) }
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
                                Icons.Default.Link, "Connect",
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
                    onClick = onSync,
                    enabled = manualHost.isNotBlank() && isPortValid && isIpValid,
                    icon = Icons.Default.Sync,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sync Now") }
            }
        }
    }
}
