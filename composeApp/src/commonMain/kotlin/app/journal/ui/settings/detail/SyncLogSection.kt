package app.journal.ui.settings.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.ui.components.*

@Composable
internal fun SyncLogSection(logLines: List<String>) {
    Text("Event Log", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 1.dp)) {
            Text("$prefix", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall, color = color)
            Text(displayText, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (logLines.isEmpty()) {
        Text("No events yet", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}
