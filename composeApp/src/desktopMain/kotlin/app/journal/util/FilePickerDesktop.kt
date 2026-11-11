package app.journal.util

import java.awt.FileDialog
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
        // Swing dialogs must run on the EDT; no repository io happens here, so nothing is dispatched to Dispatchers.IO.
        SwingUtilities.invokeLater {
            try {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Export"
                    selectedFile = File(defaultName)
                    fileFilter = FileNameExtensionFilter(description, *extensions.toTypedArray())
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    var file = chooser.selectedFile
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
        extensions: List<String>,
        title: String
    ): String? = suspendCancellableCoroutine { cont ->
        // A FileDialog is the native Windows dialog; JFileChooser draws its own Swing chrome.
        SwingUtilities.invokeLater {
            try {
                while (true) {
                    val dialog = FileDialog(null as java.awt.Frame?, title, FileDialog.LOAD)
                    dialog.isMultipleMode = false
                    dialog.file = extensions.joinToString(";") { "*.$it" }
                    dialog.isVisible = true
                    val name = dialog.file
                    val directory = dialog.directory
                    if (name == null) {
                        cont.resume(null)
                        return@invokeLater
                    }
                    val file = if (directory != null) File(directory, name) else File(name)
                    if (file.isFile && file.extension.lowercase() in extensions) {
                        cont.resume(file.absolutePath)
                        return@invokeLater
                    }
                    if (!file.isFile) {
                        cont.resume(null)
                        return@invokeLater
                    }
                    // A file of another type cannot be a backdrop, so the dialog opens again.
                }
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
