package app.journal.model

import kotlinx.serialization.Serializable

/**
 * Tab the app opens on. Stored in the journal snapshot so the choice travels
 * with the data and survives a restart; the default keeps the historic
 * behaviour (dashboard first).
 */
@Serializable
enum class StartTab(val label: String, val description: String) {
    DASHBOARD("Dashboard", "Stats, activity heatmap and tolerance"),
    SESSIONS("Sessions", "The session list"),
    SUBSTANCES("Substances", "The reference library"),
    SAFETY("Safety", "Harm reduction and resources"),
}

/**
 * Idle delay before the live session writes its free-form draft. Short
 * delays write more often; long delays leave more typing at risk if the
 * process dies before the write lands.
 */
@Serializable
enum class AutosaveDelay(val delayMs: Long, val label: String, val description: String) {
    FAST(600L, "0.6 seconds", "Writes almost as you type; the most disk writes"),
    NORMAL(2_000L, "2 seconds", "Up to 2 seconds of typing at risk"),
    SLOW(5_000L, "5 seconds", "Fewest writes, up to 5 seconds of typing at risk"),
}
