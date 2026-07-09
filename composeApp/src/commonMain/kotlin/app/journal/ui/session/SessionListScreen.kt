package app.journal.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Session list: chronological, filterable by tag and substance.
 * Tap → SessionDetailScreen (timeline, doses, notes, attachments).
 * FAB → create new Session.
 *
 * SQL++ query (newest first):
 *   SELECT * FROM sessions WHERE docType = "session"
 *   ORDER BY startTime DESC
 *
 * Tag filter:
 *   WHERE docType = "session" AND ARRAY_CONTAINS(tags, $tag)
 */
@Composable
fun SessionListScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Sessions", style = MaterialTheme.typography.headlineMedium)
        // TODO: filter chips (by tag, by substance, archived toggle)
        // TODO: LazyColumn of SessionCard composables
        // TODO: FAB → SessionEditorScreen
    }
}
