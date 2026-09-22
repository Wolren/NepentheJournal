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
    /** Prefix glyph drawn before the text; identical to the old convention. */
    abstract val glyph: Char

    /** The message text, already stripped of any prefix. */
    abstract val text: String

    /** Progress or neutral note: rendered with the "-" glyph. */
    data class Info(override val text: String) : SyncLogEntry() {
        override val glyph: Char = '-'
    }

    /** Completed action: rendered with the "+" glyph in the primary color. */
    data class Success(override val text: String) : SyncLogEntry() {
        override val glyph: Char = '+'
    }

    /** Failed action or invalid input: rendered with the "!" glyph in the error color. */
    data class Error(override val text: String) : SyncLogEntry() {
        override val glyph: Char = '!'
    }
}
