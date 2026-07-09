package app.journal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.journal.ui.dashboard.DashboardScreen
import app.journal.ui.session.SessionListScreen
import app.journal.ui.substances.SubstanceScreen
import app.journal.ui.sync.SyncScreen

enum class Screen(
    val label: String,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector
) {
    DASHBOARD("Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    SESSIONS("Sessions", Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
    SUBSTANCES("Substances", Icons.Filled.Science, Icons.Outlined.Science),
    SYNC("Sync", Icons.Filled.Refresh, Icons.Outlined.Refresh)
}

@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var selected by remember { mutableStateOf(Screen.DASHBOARD) }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    Screen.entries.forEach { screen ->
                        NavigationBarItem(
                            selected = selected == screen,
                            onClick = { selected = screen },
                            icon = {
                                Icon(
                                    imageVector = if (selected == screen) screen.filledIcon else screen.outlinedIcon,
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                when (selected) {
                    Screen.DASHBOARD   -> DashboardScreen()
                    Screen.SESSIONS    -> SessionListScreen()
                    Screen.SUBSTANCES  -> SubstanceScreen()
                    Screen.SYNC        -> SyncScreen()
                }
            }
        }
    }
}
