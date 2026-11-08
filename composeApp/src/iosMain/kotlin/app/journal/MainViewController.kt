package app.journal

import androidx.compose.ui.window.ComposeUIViewController
import app.journal.data.DataInitializer
import app.journal.data.JournalRepository
import app.journal.log.initLogging
import app.journal.ui.App
import app.journal.util.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val startupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

fun MainViewController() = run {
    // Mirror Android's NepentheApp and desktop's Main.kt: install the rolling file writer and the crash hook before the first frame.
    initLogging(PlatformFile.dataDir())
    // iOS has no Activity to start this, so without it the library gate waits forever on a blank loading screen.
    startupScope.launch {
        DataInitializer.ensureInitialized(JournalRepository.instance, startupScope)
    }
    ComposeUIViewController { App() }
}
