package app.journal.sync

import java.io.File

/** The trust store is JSON; anything past the empty object means at least one peer. */
actual fun hasStoredSyncPeers(): Boolean {
    val file = File(platformSyncDataDir(), "trusted-devices.json")
    return file.isFile && file.length() > 2L
}
