package app.journal.util

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

actual object FilePicker {

    actual suspend fun saveFile(
        defaultName: String,
        description: String,
        extensions: List<String>
    ): String? = suspendCancellableCoroutine { cont ->
        // Swing dialogs must run on the EDT; no repository IO happens here,
        // so nothing is dispatched to Dispatchers.IO.
        SwingUtilities.invokeLater {
            try {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Export"
                    selectedFile = File(defaultName)
                    fileFilter = FileNameExtensionFilter(description, *extensions.toTypedArray())
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    var file = chooser.selectedFile
                    // Ensure extension
                    if (!file.name.contains(".")) {
                        file = File(file.absolutePath + ".${extensions.first()}")
                    }
                    cont.resume(file.absolutePath)
                } else cont.resume(null)
            } catch (_: Exception) {
                cont.resume(null)
            }
        }
    }

    actual suspend fun openFile(
        description: String,
        extensions: List<String>
    ): String? = suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater {
            try {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Import"
                    fileFilter = FileNameExtensionFilter(description, *extensions.toTypedArray())
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    cont.resume(chooser.selectedFile.absolutePath)
                } else cont.resume(null)
            } catch (_: Exception) {
                cont.resume(null)
            }
        }
    }

    actual suspend fun openFolder(): String? = suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater {
            try {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Select folder"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    cont.resume(chooser.selectedFile.absolutePath)
                } else cont.resume(null)
            } catch (_: Exception) {
                cont.resume(null)
            }
        }
    }
}
