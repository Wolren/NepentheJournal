package app.journal.util

import android.content.ContentValues
import android.net.Uri
import android.provider.OpenableColumns
import app.journal.NepentheApp
import java.io.File

actual object PlatformFile {
    actual fun writeText(path: String, content: String) {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            NepentheApp.appContext.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Cannot open output stream for $path")
        } else {
            File(path).writeText(content)
        }
    }

    actual fun readText(path: String): String {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            NepentheApp.appContext.contentResolver.openInputStream(uri)?.use { `is` ->
                return `is`.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("Cannot open input stream for $path")
        } else {
            return File(path).readText()
        }
    }

    actual fun dataDir(): String {
        return NepentheApp.appContext.filesDir.absolutePath
    }
}
