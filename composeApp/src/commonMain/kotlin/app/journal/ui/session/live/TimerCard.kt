package app.journal.ui.session.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.util.currentTimeMillis
import kotlinx.coroutines.delay

@Composable
internal fun TimerCard(startTime: Long) {
    var elapsedMs by remember { mutableStateOf(currentTimeMillis() - startTime) }

    LaunchedEffect(startTime) {
        while (true) {
            delay(1000L)
            elapsedMs = currentTimeMillis() - startTime
        }
    }

    val totalSec = elapsedMs / 1000
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
            val progress = (elapsedMs % cycleMs).toFloat() / cycleMs.toFloat()
            Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(120.dp),
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer, strokeWidth = 6.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(timeStr, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("elapsed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
