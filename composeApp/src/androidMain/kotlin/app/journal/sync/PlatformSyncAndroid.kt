package app.journal.sync

import app.journal.NepentheApp
import java.io.File

actual fun platformSyncDataDir(): String {
    val filesDir = NepentheApp.appContext.filesDir
    return File(filesDir, ".psychonautica").absolutePath
}

actual fun platformDeviceName(): String {
    val manufacturer = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL
    return "$manufacturer $model"
}
