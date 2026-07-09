package app.journal.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.journal.ui.dashboard.DashboardScreen
import app.journal.ui.session.SessionListScreen
import app.journal.ui.substances.SubstanceScreen
import app.journal.ui.sync.SyncScreen

enum class Screen { DASHBOARD, SESSIONS, SUBSTANCES, SYNC }

@Composable
fun App() {
    MaterialTheme {
        var selected by remember { mutableStateOf(Screen.DASHBOARD) }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    listOf(
                        Screen.DASHBOARD  to ("🏠" to "Dashboard"),
                        Screen.SESSIONS   to ("📓" to "Sessions"),
                        Screen.SUBSTANCES to ("🔬" to "Substances"),
                        Screen.SYNC       to ("🔄" to "Sync")
                    ).forEach { (screen, label) ->
                        NavigationBarItem(
                            selected = selected == screen,
                            onClick = { selected = screen },
                            icon = { Text(label.first) },
                            label = { Text(label.second) }
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                Screen.DASHBOARD   -> DashboardScreen(padding)
                Screen.SESSIONS    -> SessionListScreen(padding)
                Screen.SUBSTANCES  -> SubstanceScreen(padding)
                Screen.SYNC        -> SyncScreen(padding)
            }
        }
    }
}
