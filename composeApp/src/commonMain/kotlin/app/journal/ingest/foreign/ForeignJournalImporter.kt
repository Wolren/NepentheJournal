package app.journal.ingest.foreign

import app.journal.data.IJournalRepository
import app.journal.export.ExportImport
import app.journal.log.Log
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.model.Substance
import app.journal.util.currentTimeMillis
import app.journal.util.slugify
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Reads another app's dose journal into Nepenthe sessions and doses.
 * Formats: PsychonautWiki Journal and PsyLog JSON, DrugsPRO JSON, CSV/TSV tables.
 * Re-importing the same file is safe: doses dedupe on substance, time, amount, unit and route.
 */
object ForeignJournalImporter {

    private val json = Json { ignoreUnknownKeys = true }

    private const val SOURCE = "ForeignImport"

    /** Parses [content], then applies it. A failure returns the reason and touches nothing. */
    fun import(
        repo: IJournalRepository,
        content: String,
        now: Long = currentTimeMillis(),
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): ForeignImportResult {
        val trimmed = content.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.isEmpty()) return ForeignImportResult(error = "The file is empty")
        val parsed = when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> parseJson(trimmed, timeZone)
            else -> parseTable(content, timeZone)
        }
        return when (parsed) {
            is Parsed.Failed -> ForeignImportResult(error = parsed.message)
            is Parsed.Ok -> applyJournal(repo, parsed.journal, now)
        }
    }

    private fun parseJson(content: String, tz: TimeZone): Parsed {
        val root: JsonElement = try {
            json.parseToJsonElement(content)
        } catch (e: Exception) {
            return Parsed.Failed("This is not valid JSON")
        }
        return when {
            DrugsProParser.matches(root) -> Parsed.Ok(DrugsProParser.parse(root, tz))
            PsychonautWikiJournalParser.matches(root) ->
                Parsed.Ok(PsychonautWikiJournalParser.parse(root, tz))
            looksLikeNepentheBackup(root) -> Parsed.Failed(
                "This is a Nepenthe Journal export. Use \"Import backup\" or \"Import\" in Settings > Data."
            )
            else -> Parsed.Failed(
                "Unrecognised JSON. Supported: PsychonautWiki Journal, PsyLog and DrugsPRO exports."
            )
        }
    }

    private fun parseTable(content: String, tz: TimeZone): Parsed {
        val journal = SpreadsheetParser.parse(content, tz) ?: return Parsed.Failed(
            "Unrecognised file. Expected a PsychonautWiki Journal, PsyLog or DrugsPRO export, " +
                "or a CSV with substance, dose and time columns."
        )
        return Parsed.Ok(journal)
    }

    private fun looksLikeNepentheBackup(root: JsonElement): Boolean {
        val obj = root.asObject() ?: return false
        return obj.containsKey("version") && (obj.containsKey("sessions") || obj.containsKey("doses"))
    }

    private fun applyJournal(repo: IJournalRepository, journal: ForeignJournal, now: Long): ForeignImportResult {
        val format = journal.format
        val index = SubstanceIndex(repo.substances.value, now)
        val seen = HashSet<String>()
        repo.doses.value.forEach { seen.add(doseKey(it)) }

        val maxTimestamp = now + ExportImport.TIMESTAMP_FUTURE_MARGIN_MS
        val sessions = ArrayList<Session>()
        val doses = ArrayList<Dose>()
        val sessionOccurrences = mutableMapOf<String, Int>()
        var duplicates = 0

        for (experience in journal.experiences) {
            if (sessions.size >= ExportImport.MAX_IMPORT_SESSIONS) break
            val kept = experience.doses
                .filter { it.timestamp in ExportImport.MIN_VALID_TIMESTAMP..maxTimestamp }
                .take(ExportImport.MAX_IMPORT_DOSES_PER_SESSION)
                .mapNotNull { foreign ->
                    val substanceId = index.resolve(foreign.substance) ?: return@mapNotNull null
                    val unit = RouteNames.unit(foreign.unit)
                    val route = RouteNames.normalize(foreign.route)
                    val key = doseKey(substanceId, foreign.timestamp, foreign.amount, unit, route)
                    if (!seen.add(key)) {
                        duplicates++
                        return@mapNotNull null
                    }
                    Dose(
                        id = "d:import:" + stableHash(key),
                        createdAt = now,
                        updatedAt = now,
                        deviceOrigin = format.origin,
                        sessionId = "",
                        substanceId = substanceId,
                        routeOfAdministration = route,
                        amount = foreign.amount,
                        unit = unit,
                        timestamp = foreign.timestamp,
                        notes = foreign.notes?.take(ExportImport.MAX_IMPORT_FIELD_LEN),
                        isDoseEstimate = foreign.isEstimate,
                        stomachFullness = foreign.stomachFullness,
                    )
                }
            if (kept.isEmpty()) continue

            val start = kept.minOf { it.timestamp }
            val title = (experience.title ?: index.titleOf(kept.map { it.substanceId }))
                .ifBlank { "Imported session" }
                .take(ExportImport.MAX_IMPORT_TITLE_LEN)
            val baseId = sessionId(format, start, title)
            // Two experiences with the same start and title are distinct sessions, not one twice.
            val occurrence = (sessionOccurrences[baseId] ?: 0) + 1
            sessionOccurrences[baseId] = occurrence
            val sessionId = if (occurrence == 1) baseId else "$baseId:$occurrence"
            if (repo.getSession(sessionId) == null) {
                sessions.add(
                    Session(
                        id = sessionId,
                        createdAt = now,
                        updatedAt = now,
                        deviceOrigin = format.origin,
                        title = title,
                        startTime = start,
                        notes = experience.notes?.take(ExportImport.MAX_IMPORT_FIELD_LEN),
                        consumerName = consumerOf(experience),
                    )
                )
            }
            doses.addAll(kept.map { it.copy(sessionId = sessionId) })
        }

        if (sessions.isEmpty() && doses.isEmpty() && index.created.isEmpty()) {
            return ForeignImportResult(format = format, duplicatesSkipped = duplicates)
        }
        repo.applyBatch(sessions = sessions, doses = doses, substances = index.created)
        Log.withTag(SOURCE).i {
            "Imported ${sessions.size} sessions and ${doses.size} doses from ${format.label} " +
                "(${index.created.size} new substances, $duplicates duplicates skipped)"
        }
        return ForeignImportResult(
            format = format,
            sessions = sessions.size,
            doses = doses.size,
            substancesCreated = index.created.size,
            duplicatesSkipped = duplicates,
        )
    }

    private fun consumerOf(experience: ForeignExperience): String? {
        val consumers = experience.doses.mapNotNull { it.consumer?.trim()?.ifEmpty { null } }.distinct()
        return consumers.singleOrNull()
    }

    /** Stable for the same file, so a second import lands on the same session id. */
    private fun sessionId(format: ForeignFormat, start: Long, title: String): String =
        "s:import:${format.name.lowercase()}:" + stableHash("$start|$title")

    private fun doseKey(dose: Dose): String =
        doseKey(dose.substanceId, dose.timestamp, dose.amount, dose.unit, dose.routeOfAdministration)

    private fun doseKey(
        substanceId: String,
        timestamp: Long,
        amount: Double,
        unit: String,
        route: String,
    ): String = "$substanceId|$timestamp|$amount|${unit.lowercase()}|${route.lowercase()}"

    /** FNV-1a: Kotlin hashCode is not pinned across targets, this is. */
    internal fun stableHash(text: String): String {
        var hash = 0xcbf29ce484222325uL
        for (ch in text) {
            hash = hash xor ch.code.toULong()
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    /** Name to substance id, creating a user-owned stub for a name the library does not carry. */
    private class SubstanceIndex(existing: List<Substance>, private val now: Long) {

        private val byName = mutableMapOf<String, String>()
        private val byAlias = mutableMapOf<String, String>()
        private val displayName = mutableMapOf<String, String>()
        private val takenIds = mutableSetOf<String>()

        val created = mutableListOf<Substance>()

        init {
            for (substance in existing) {
                takenIds.add(substance.id)
                displayName[substance.id] = substance.name
                val name = substance.name.trim().lowercase()
                if (name.isNotEmpty()) byName.getOrPut(name) { substance.id }
                for (alias in substance.aliases) {
                    val key = alias.trim().lowercase()
                    if (key.isNotEmpty()) byAlias.getOrPut(key) { substance.id }
                }
            }
        }

        fun resolve(rawName: String): String? {
            val name = rawName.trim()
            if (name.isEmpty()) return null
            val key = name.lowercase()
            byName[key]?.let { return it }
            byAlias[key]?.let { return it }
            val id = stubId(name)
            takenIds.add(id)
            byName[key] = id
            displayName[id] = name
            created.add(
                Substance(
                    id = id,
                    createdAt = now,
                    updatedAt = now,
                    deviceOrigin = "import",
                    name = name,
                    cachedAt = now,
                    sourceVersion = "import",
                )
            )
            return id
        }

        fun titleOf(substanceIds: List<String>): String =
            substanceIds.distinct().mapNotNull { displayName[it] }.joinToString(" + ")

        private fun stubId(name: String): String {
            val base = "import:" + slugify(name, asciiOnly = true, fallback = "substance")
            if (base !in takenIds) return base
            return base + ":" + stableHash(name.lowercase()).take(8)
        }
    }

    private sealed interface Parsed {
        data class Ok(val journal: ForeignJournal) : Parsed
        data class Failed(val message: String) : Parsed
    }
}


