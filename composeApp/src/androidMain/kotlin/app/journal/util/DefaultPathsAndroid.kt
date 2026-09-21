package app.journal.util

import android.os.Environment

actual fun defaultVaultPath(): String {
    @Suppress("DEPRECATION")
    val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    return docs.absolutePath + "/Obsidian Vault"
}
