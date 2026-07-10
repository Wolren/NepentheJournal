package app.journal.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.io.File

actual object FilePicker {

    actual suspend fun saveFile(
        defaultName: String,
        description: String,
        extensions: List<String>
    ): String? = withContext(Dispatchers.IO) {
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
            file.absolutePath
        } else null
    }

    actual suspend fun openFile(
        description: String,
        extensions: List<String>
    ): String? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Import"
            fileFilter = FileNameExtensionFilter(description, *extensions.toTypedArray())
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.absolutePath
        } else null
    }
}
