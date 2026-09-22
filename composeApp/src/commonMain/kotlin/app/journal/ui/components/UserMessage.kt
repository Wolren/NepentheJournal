package app.journal.ui.components

import app.journal.log.Log

/**
 * The single point where a caught exception becomes user-visible text.
 *
 * Every catch-that-surfaces-to-UI site funnels through here: the caller
 * passes a log tag and a short, species-neutral summary ("Export failed"),
 * and gets that summary back for display. Raw exception text and the
 * string "null" never reach the screen; the full exception always goes to
 * the app log under [tag].
 */
internal fun userMessage(tag: String, summary: String, e: Throwable): String {
    Log.withTag(tag).e(e) { summary }
    return summary
}

/**
 * Sync flavour of [userMessage]: the same app-log step, plus the known
 * network/sync failure shapes mapped to actionable copy. Unknown shapes
 * fall back to [summary]; raw exception text stays in the log either way.
 * The substring table lives here (and only here) so sync screens cannot
 * drift into their own translations.
 */
internal fun syncUserMessage(summary: String, e: Throwable): String {
    Log.withTag("Sync").e(e) { summary }
    val detail = e.message ?: return summary
    val hint = when {
        detail.contains("Connection refused") -> "Device not reachable. Check IP and port."
        detail.contains("timed out") -> "Connection timed out. Device may be offline."
        detail.contains("Certificate pinning failed") -> "Device certificate changed. Re-pair required."
        detail.contains("Invalid or expired token") -> "Pairing token expired or wrong. Generate a new one."
        detail.contains("Authentication failed") -> "Sync auth failed. Try re-pairing."
        detail.contains("Not paired") -> "Not paired with this device. Enter a pairing token."
        detail.contains("keytool") || detail.contains("Certificate") -> "TLS setup failed. Restart the app."
        detail.contains("port") && detail.contains("available") -> "Port already in use. Try a different port."
        else -> null
    }
    return if (hint != null) "$summary: $hint" else summary
}
