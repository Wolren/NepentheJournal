package app.journal.ui.session.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.model.Session
import app.journal.ui.components.AppCard
import app.journal.ui.components.appCardColors
import app.journal.util.currentTimeMillis
import kotlinx.coroutines.delay

/** Live timer display: wall time minus accumulated pauses. No controls here;
 *  stopping happens through the screen's Stop button with confirmation. */
@Composable
internal fun TimerCard(session: Session) {
    var nowMs by remember { mutableStateOf(currentTimeMillis()) }

    LaunchedEffect(session.startTime) {
        while (true) {
            delay(1000L)
            nowMs = currentTimeMillis()
        }
    }

    val frozenAt = session.pausedAt
    val elapsedMs = (frozenAt ?: nowMs) - session.startTime - session.pausedMs

    val totalSec = (elapsedMs.coerceAtLeast(0L)) / 1000
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    val timeStr = if (hours > 0) "${hours}h ${mins.toString().padStart(2, '0')}m ${secs.toString().padStart(2, '0')}s"
        else "${mins}m ${secs.toString().padStart(2, '0')}s"

    AppCard(
        colors = appCardColors(container = MaterialTheme.colorScheme.primaryContainer, alpha = 0.6f),
        contentPadding = PaddingValues(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val cycleMs = 12 * 3600000L
            val progress = (elapsedMs.coerceAtLeast(0L) % cycleMs).toFloat() / cycleMs.toFloat()
            Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(120.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer, strokeWidth = 6.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(timeStr, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Elapsed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
