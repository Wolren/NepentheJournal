package app.journal.ui.session.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.Session
import app.journal.util.currentTimeMillis
import kotlinx.coroutines.delay

/**
 * Live timer: wall time minus accumulated pauses. While paused the clock
 * freezes and the card offers resume. Timeline T+ labels stay wall-clock
 * truthful; pause/resume markers on the timeline show the gaps.
 */
@Composable
internal fun TimerCard(
    session: Session,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val paused = session.pausedAt != null
    var nowMs by remember { mutableStateOf(currentTimeMillis()) }

    LaunchedEffect(paused, session.startTime, session.pausedMs) {
        if (paused) return@LaunchedEffect
        while (true) {
            delay(1000L)
            nowMs = currentTimeMillis()
        }
    }

    val frozenAt = session.pausedAt
    val elapsedMs = (if (paused && frozenAt != null) frozenAt else nowMs) -
        session.startTime - session.pausedMs

    val totalSec = (elapsedMs.coerceAtLeast(0L)) / 1000
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    val timeStr = if (hours > 0) "${hours}h ${mins.toString().padStart(2, '0')}m ${secs.toString().padStart(2, '0')}s"
        else "${mins}m ${secs.toString().padStart(2, '0')}s"

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val cycleMs = 12 * 3600000L
            val progress = (elapsedMs.coerceAtLeast(0L) % cycleMs).toFloat() / cycleMs.toFloat()
            Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(120.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer, strokeWidth = 6.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(timeStr, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(if (paused) "Paused" else "Elapsed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (paused) {
                Button(onClick = onResume) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Resume")
                }
            } else {
                OutlinedButton(onClick = onPause) {
                    Icon(Icons.Default.Pause, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pause")
                }
            }
        }
    }
}
