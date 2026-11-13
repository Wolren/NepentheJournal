package app.journal.sync

import platform.Foundation.NSFileManager

/** iOS keeps its peers in a separate store, so the check names that file. */
actual fun hasStoredSyncPeers(): Boolean =
    NSFileManager.defaultManager.fileExistsAtPath("${platformSyncDataDir()}/ios-trusted-devices.json")
