package app.journal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import app.journal.data.DataInitializer
import app.journal.data.JournalRepository
import app.journal.ui.App
import app.journal.util.AndroidFilePickerBridge
import app.journal.util.PendingQuickLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        AndroidFilePickerBridge.continuation?.let { cont ->
            cont.resume(uri?.toString())
        }
        AndroidFilePickerBridge.reset()
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        AndroidFilePickerBridge.continuation?.let { cont ->
            cont.resume(uri?.toString())
        }
        AndroidFilePickerBridge.reset()
    }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
        }
        AndroidFilePickerBridge.continuation?.let { cont ->
            cont.resume(if (uri != null) treeUriToPath(uri) else null)
        }
        AndroidFilePickerBridge.reset()
    }

    /**
     * Best-effort mapping of a Storage Access tree URI to a plain
     * filesystem path. Only volumes with a stable path work (primary
     * shared storage); anything else returns null so the caller keeps
     * the manual path field instead of a URI the vault layer cannot use.
     */
    private fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return null
        } ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val base = if (parts[0] == "primary") {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/${parts[0]}"
        }
        return "$base/${parts[1]}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repo = JournalRepository.instance
        activityScope.launch {
            DataInitializer.ensureInitialized(repo, activityScope)
        }

        AndroidFilePickerBridge.launchCreateDocument = { _, defaultName ->
            createDocumentLauncher.launch(defaultName)
        }
        AndroidFilePickerBridge.launchOpenDocument = { mimeTypes ->
            openDocumentLauncher.launch(mimeTypes)
        }
        AndroidFilePickerBridge.launchOpenFolder = {
            openFolderLauncher.launch(null)
        }

        PendingQuickLog.offer(intent?.dataString)

        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PendingQuickLog.offer(intent.dataString)
    }

    override fun onDestroy() {
        super.onDestroy()
        AndroidFilePickerBridge.launchCreateDocument = null
        AndroidFilePickerBridge.launchOpenDocument = null
        AndroidFilePickerBridge.launchOpenFolder = null
        activityScope.cancel()
    }
}
