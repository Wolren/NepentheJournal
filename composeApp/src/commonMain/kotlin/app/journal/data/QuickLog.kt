package app.journal.data

import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.StomachFullness
import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant

/** One quick-log link: nepenthe://quicklog?substance=X&amount=27&unit=mg&route=oral&time=... */
data class QuickLogRequest(
    val substance: String,
    val amount: Double? = null,
    val unit: String? = null,
    val route: String? = null,
    val timeMillis: Long? = null,
    val notes: String? = null,
    val stomachFullness: StomachFullness? = null,
)

sealed interface QuickLogOutcome {
    data class Logged(val sessionId: String, val summary: String) : QuickLogOutcome
    data class Failed(val reason: String) : QuickLogOutcome
}

/**
 * Parses and applies `nepenthe://quicklog` links, so a shortcut, a script or
 * another app can log a dose without filling the editor by hand.
 */
object QuickLog {

    private const val SCHEME = "nepenthe"
    private const val HOST = "quicklog"

    /** Returns null for any other link so the caller can ignore it. */
    fun parse(uri: String): QuickLogRequest? {
        if (!uri.startsWith("$SCHEME://", ignoreCase = true)) return null
        val rest = uri.substringAfter("://")
        if (rest.substringBefore('?').substringBefore('/').lowercase() != HOST) return null
        val query = if ('?' in rest) rest.substringAfter('?').substringBefore('#') else ""
        val params = query.split('&').filter { it.isNotBlank() }.associate { pair ->
            decode(pair.substringBefore('=').lowercase()) to decode(pair.substringAfter('=', ""))
        }
        val substance = params["substance"]?.trim().orEmpty()
        if (substance.isEmpty()) return null
        return QuickLogRequest(
            substance = substance,
            amount = params["amount"]?.trim()?.toDoubleOrNull(),
            unit = params["unit"]?.trim()?.ifEmpty { null },
            route = params["route"]?.trim()?.ifEmpty { null },
            timeMillis = params["time"]?.let { parseTime(it) },
            notes = params["notes"]?.trim()?.ifEmpty { null },
            stomachFullness = params["meal"]?.let { meal ->
                StomachFullness.entries.firstOrNull { it.name.equals(meal, ignoreCase = true) }
            }
        )
    }

    /** Writes the session and its dose; an unknown substance fails without touching the journal. */
    fun apply(repo: IJournalRepository, request: QuickLogRequest): QuickLogOutcome {
        val substances = repo.substances.value
        val match = substances.firstOrNull { it.name.equals(request.substance, ignoreCase = true) }
            ?: substances.firstOrNull { it.name.contains(request.substance, ignoreCase = true) }
            ?: return QuickLogOutcome.Failed("No substance matches \"${request.substance}\"")

        val now = currentTimeMillis()
        val time = request.timeMillis ?: now
        val session = Session(
            id = "s:quicklog:$now",
            createdAt = now,
            updatedAt = now,
            deviceOrigin = "quicklog",
            title = match.name,
            startTime = time,
        )
        repo.upsertSession(session)

        val amount = request.amount
        if (amount != null && amount > 0.0) {
            repo.upsertDose(
                Dose(
                    id = "d:quicklog:$now",
                    createdAt = now,
                    updatedAt = now,
                    deviceOrigin = "quicklog",
                    sessionId = session.id,
                    substanceId = match.id,
                    routeOfAdministration = request.route
                        ?: match.routesOfAdministration.firstOrNull()
                        ?: "oral",
                    amount = amount,
                    unit = request.unit ?: "mg",
                    timestamp = time,
                    notes = request.notes,
                    stomachFullness = request.stomachFullness,
                )
            )
        }
        val summary = buildString {
            append(match.name)
            if (amount != null) append(" ${formatAmount(amount)} ${request.unit ?: "mg"}")
        }
        return QuickLogOutcome.Logged(session.id, summary)
    }

    private fun formatAmount(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

    private fun decode(value: String): String {
        var out = value.replace("+", " ")
        var index = out.indexOf('%')
        while (index >= 0 && index + 2 < out.length) {
            val hex = out.substring(index + 1, index + 3)
            val code = hex.toIntOrNull(16) ?: break
            out = out.substring(0, index) + code.toChar() + out.substring(index + 3)
            index = out.indexOf('%', index + 1)
        }
        return out
    }

    /** Accepts ISO 8601 (`2026-10-18T09:30:00Z`) or epoch seconds. */
    private fun parseTime(raw: String): Long? {
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { value ->
            return if (value > 100_000_000_000L) value else value * 1000L
        }
        return runCatching {
            Instant.parse(if (trimmed.endsWith("Z")) trimmed else "${trimmed}Z").toEpochMilliseconds()
        }.getOrNull()
    }
}
