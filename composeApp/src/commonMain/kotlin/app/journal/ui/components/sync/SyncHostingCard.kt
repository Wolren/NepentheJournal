package app.journal.ui.components.sync

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.ui.components.*
import app.journal.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * Hosting controls card with start/stop button, pairing token display
 * with countdown timer, and continuous sync toggle.
 * Shared between standalone sync screen and settings panel.
 */
@Composable
fun SyncHostingCard(
    isHosting: Boolean,
    hostAddress: String?,
    pairingToken: String?,
    tokenExpiresAt: Long?,
    isStarting: Boolean,
    isStopping: Boolean,
    continuousSync: Boolean,
    onStartHost: () -> Unit,
    onStopHost: () -> Unit,
    onCopyToken: () -> Unit,
    onContinuousSyncChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                        if (isHosting) "Hosting Active" else "Hosting",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (isStarting || isStopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    AppTonalButton(
                        onClick = { if (isHosting) onStopHost() else onStartHost() },
                        colors = if (isHosting)
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ) else null
                    ) {
                        Icon(
                            if (isHosting) Icons.Default.Cancel else Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isHosting) "Stop" else "Start")
                    }
                }
            }

            if (isHosting) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Other devices connect to: ${hostAddress ?: "..."}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Pairing token display
            if (isHosting) {
                Spacer(Modifier.height(12.dp))
                if (pairingToken != null) {
                    TokenDisplayCard(
                        pairingToken = pairingToken,
                        tokenExpiresAt = tokenExpiresAt,
                        onCopyToken = onCopyToken
                    )
                } else {
                    // Placeholder — token will appear shortly
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Generating pairing code...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                Column {
                    Text(
                        text = "Continuous sync",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Automatically sync with paired devices",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = continuousSync,
                    onCheckedChange = onContinuousSyncChange
                )
            }
        }
    }
}

@Composable
private fun TokenDisplayCard(
    pairingToken: String,
    tokenExpiresAt: Long?,
    onCopyToken: () -> Unit
) {
    // Live countdown: how many full seconds remain
    var secondsRemaining by remember { mutableStateOf(0) }
    var expired by remember { mutableStateOf(false) }

    LaunchedEffect(tokenExpiresAt) {
        if (tokenExpiresAt == null) return@LaunchedEffect
        while (true) {
            val now = currentTimeMillis()
            val remaining = ((tokenExpiresAt - now) / 1000).toInt()
            if (remaining <= 0) {
                expired = true
                secondsRemaining = 0
                break
            }
            expired = false
            secondsRemaining = remaining
            delay(1000)
        }
    }

    val surfaceColor = if (expired)
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    else
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)

    val labelColor = if (expired)
        MaterialTheme.colorScheme.onErrorContainer
    else
        MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = surfaceColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (expired) "Token Expired" else "Pairing Token",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor.copy(alpha = if (expired) 1f else 0.8f)
                )

                if (expired) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Stop and restart hosting to generate a new code",
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor.copy(alpha = 0.7f)
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pairingToken,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = labelColor
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        if (secondsRemaining <= 30) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (secondsRemaining <= 10)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Text(
                            text = when {
                                secondsRemaining > 60 -> "${secondsRemaining / 60}m ${secondsRemaining % 60}s"
                                else -> "${secondsRemaining}s"
                            } + " remaining",
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                secondsRemaining <= 10 -> MaterialTheme.colorScheme.error
                                secondsRemaining <= 30 -> MaterialTheme.colorScheme.tertiary
                                else -> labelColor.copy(alpha = 0.7f)
                            }
                        )
                        Text(
                            text = ". Enter this code on the other device",
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (!expired) {
                AppIconButton(
                    onClick = onCopyToken,
                    icon = Icons.Default.ContentCopy,
                    contentDescription = "Copy token",
                    tint = labelColor
                )
            }
        }
    }
}
