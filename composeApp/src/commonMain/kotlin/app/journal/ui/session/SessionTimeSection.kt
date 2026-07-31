package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@Composable
fun SessionTimeSection(
    startTime: Long,
    endTime: Long?,
    onStartTimeChange: (Long) -> Unit,
    onEndTimeChange: (Long?) -> Unit
) {
    val endTimeError = endTime != null && endTime <= startTime
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeField(
                label = "Start",
                epochMs = startTime,
                onChanged = onStartTimeChange,
                modifier = Modifier.weight(1f)
            )
            TimeField(
                label = "End",
                epochMs = endTime,
                onChanged = onEndTimeChange,
                modifier = Modifier.weight(1f),
                clearable = endTime != null
            )
        }
    }
    if (endTimeError) {
        Text("End time must be after start time",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
    // Duration display
    val durationMs = if (endTime != null && !endTimeError) endTime - startTime else null
    if (durationMs != null && durationMs > 0) {
        val hours = durationMs / 3600000
        val minutes = (durationMs % 3600000) / 60000
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        ) {
            Icon(Icons.Default.Schedule, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp))
            Text("Duration: ${hours}h ${minutes}m",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
