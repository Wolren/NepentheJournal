package app.journal.sync

import java.io.File

actual fun platformSyncDataDir(): String {
    val home = System.getProperty("user.home") ?: "."
    return "$home${File.separator}.psychonautica"
}

actual fun platformDeviceName(): String =
    "${System.getProperty("os.name")} Desktop"
