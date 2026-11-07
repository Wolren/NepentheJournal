package app.journal

import androidx.compose.ui.window.ComposeUIViewController
import app.journal.log.initLogging
import app.journal.ui.App
import app.journal.util.PlatformFile

fun MainViewController() = run {
    // Mirror Android's NepentheApp and desktop's Main.kt: install the rolling file writer and the crash hook before the first frame.
    initLogging(PlatformFile.dataDir())
    ComposeUIViewController { App() }
}
