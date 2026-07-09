package app.journal.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Sync management:
 *   HostingCard    — toggle hosting on/off; shows IP:port + 6-char token + QR
 *   PeerList       — discovered LAN peers (mDNS / NSD / Bonjour)
 *   ManualConnect  — enter IP:port directly
 *   DeviceList     — paired devices, last-seen, revoke button
 *   ConflictBanner — if pendingConflicts > 0, prompt user to review note siblings
 *
 * QR payload (JSON PairingOffer):
 * {
 *   "token": "7KM3NX",
 *   "hostFingerprint": "a1b2c3...",
 *   "hostAddress": "192.168.1.42",
 *   "listenerPort": 4984,
 *   "expiresAt": 1751234567890
 * }
 */
@Composable
fun SyncScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Device Sync", style = MaterialTheme.typography.headlineMedium)
        // TODO: HostingCard (toggle + QR display)
        // TODO: DiscoveredPeersList
        // TODO: ManualConnectField
        // TODO: TrustedDevicesList with revoke
        // TODO: ConflictBanner
    }
}
