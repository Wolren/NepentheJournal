package app.journal.ui.components.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.sync.TrustedDeviceInfo
import app.journal.ui.components.*

/**
 * Trusted devices list with revoke button.
 * Shared between standalone sync screen and settings panel.
 */
@Composable
fun SyncTrustedDevicesCard(
    devices: List<TrustedDeviceInfo>,
    formatTimestamp: (Long) -> String,
    onRevoke: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (devices.isEmpty()) return

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
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text("Trusted Devices", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))

            devices.forEach { device ->
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
                        onClick = { onRevoke(device.deviceId) },
                        icon = Icons.Default.LinkOff,
                        contentDescription = "Revoke ${device.displayName}",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Event and debug log viewer. Accepts log lines as strings with
 * prefix convention: "+" for success, "!" for error, "-" for info.
 * Shared between standalone sync screen and settings panel.
 */
@Composable
fun SyncEventLogCard(
    logLines: List<String>,
    debugLines: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var showDebug by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
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
                    TextButton(onClick = { showDebug = !showDebug }) {
                        Text(if (showDebug) "Hide debug" else "Show debug",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            logLines.take(15).forEach { line ->
                val prefix = when {
                    line.startsWith("+") -> '+'
                    line.startsWith("!") -> '!'
                    else -> '-'
                }
                val displayText = line.removePrefix("+").removePrefix("!").removePrefix("-")
                val color = when (prefix) {
                    '+' -> MaterialTheme.colorScheme.primary
                    '!' -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "$prefix",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                    Text(
                        text = displayText,
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
            if (showDebug && debugLines.isNotEmpty()) {
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
