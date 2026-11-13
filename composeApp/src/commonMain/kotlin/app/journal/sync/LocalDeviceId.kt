package app.journal.sync

import app.journal.util.PlatformFile
import app.journal.util.crypto.secureRandomBytes

/**
 * The id this install announces as its sync identity. One per install: every device used to
 * send the same "desktop-main", so two phones could not be told apart.
 */
object LocalDeviceId {

    /** The constant every build announced before this file existed. */
    const val LEGACY = "desktop-main"

    private fun path(): String = "${PlatformFile.dataDir()}/sync-device-id.txt"

    fun current(): String {
        val stored = runCatching { PlatformFile.readText(path()).trim() }.getOrNull()
        val id = chooseDeviceId(stored, hasStoredSyncPeers())
        if (id != stored) runCatching { PlatformFile.writeText(path(), id) }
        return id
    }

    /** A stored id wins; an install that already paired keeps the id its peers hold. */
    internal fun chooseDeviceId(stored: String?, legacyInUse: Boolean): String = when {
        !stored.isNullOrBlank() -> stored
        legacyInUse -> LEGACY
        else -> freshId()
    }

    private fun freshId(): String = "device-" + secureRandomBytes(8).joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

/** True when a trust store exists, so a new id would orphan the pairings in it. */
expect fun hasStoredSyncPeers(): Boolean
