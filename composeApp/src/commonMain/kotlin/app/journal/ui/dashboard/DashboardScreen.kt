package app.journal.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dashboard: recent sessions, quick check-in FAB, pending conflict count badge.
 * TODO: wire to VaultRepository<Session>.observeAll() filtered to last 7 days.
 * TODO: badge count from VaultRepository<Note>.observeByQuery("... conflictSiblings != []")
 */
@Composable
fun DashboardScreen(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Dashboard", style = MaterialTheme.typography.headlineMedium)
        Text("Recent sessions will appear here.", style = MaterialTheme.typography.bodyMedium)
        // TODO: LazyColumn of session cards
        // TODO: FloatingActionButton for new session / quick check-in
    }
}
