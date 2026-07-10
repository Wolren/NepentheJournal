package app.journal

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import app.journal.data.DataInitializer
import app.journal.data.JournalRepository
import app.journal.ui.App
import app.journal.util.AndroidFilePickerBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Launcher for ACTION_CREATE_DOCUMENT — user picks where to save a file. */
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        AndroidFilePickerBridge.continuation?.let { cont ->
            cont.resume(uri?.toString())
        }
        AndroidFilePickerBridge.reset()
    }

    /** Launcher for ACTION_OPEN_DOCUMENT — user picks a file to open. */
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        AndroidFilePickerBridge.continuation?.let { cont ->
            cont.resume(uri?.toString())
        }
        AndroidFilePickerBridge.reset()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize repository + load data + wire auto-save
        val repo = JournalRepository.instance
        DataInitializer.ensureInitialized(repo, activityScope)

        // Wire up the FilePicker bridge so FilePickerAndroid can launch dialogs
        AndroidFilePickerBridge.launchCreateDocument = { _, defaultName ->
            createDocumentLauncher.launch(defaultName)
        }
        AndroidFilePickerBridge.launchOpenDocument = { mimeTypes ->
            openDocumentLauncher.launch(mimeTypes)
        }

        setContent { App() }
    }

    override fun onDestroy() {
        super.onDestroy()
        AndroidFilePickerBridge.launchCreateDocument = null
        AndroidFilePickerBridge.launchOpenDocument = null
    }
}
