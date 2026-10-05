package app.journal

import androidx.compose.ui.window.ComposeUIViewController
import app.journal.log.initLogging
import app.journal.ui.App
import app.journal.util.PlatformFile

fun MainViewController() = run {
    // Mirror Android's NepentheApp and desktop's Main.kt: install the rolling
    // file writer and the crash hook before the first frame. The iOS
    // initLogging actual creates the data dir when it is still missing, so the
    // first launch on a fresh install cannot drop early log lines.
    initLogging(PlatformFile.dataDir())
    ComposeUIViewController { App() }
}
