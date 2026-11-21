package app.journal.ui.settings

import app.journal.ui.components.SyncLogEntry

/** The debug flow replays 200 lines, so the screen keeps exactly that many. */
internal const val SYNC_LOG_CAP = 200

/**
 * Newest-first append with a hard cap. The settings screen lives for the whole
 * session and every sync event appends, so an uncapped list grows without bound.
 */
internal fun appendSyncLog(
    current: List<SyncLogEntry>,
    entry: SyncLogEntry,
    cap: Int = SYNC_LOG_CAP,
): List<SyncLogEntry> = if (cap <= 0) emptyList() else (listOf(entry) + current).take(cap)
