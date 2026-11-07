package app.journal.ui.components

/**
 * Typed line for the sync event log.
 *
 * Replaces the old string convention where producers prefixed every line
 * with "+" (success), "!" (error) or "-" (info) and the render site parsed
 * the prefix back out. Producers (SyncSettingsContent via
 * [app.journal.ui.settings.detail.SyncSettingsCallbacks.onLogLine]) build an
 * entry; exactly one place renders it (SyncEventLogCard), and the glyph it
 * draws is the old prefix character, so rendered output is unchanged.
 */
sealed class SyncLogEntry {
    abstract val glyph: Char

    abstract val text: String

    data class Info(override val text: String) : SyncLogEntry() {
        override val glyph: Char = '-'
    }

    data class Success(override val text: String) : SyncLogEntry() {
        override val glyph: Char = '+'
    }

    data class Error(override val text: String) : SyncLogEntry() {
        override val glyph: Char = '!'
    }
}
