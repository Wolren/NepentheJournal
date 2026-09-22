package app.journal.data

import app.journal.log.Log
import app.journal.model.*
import app.journal.serde.AppJson
import app.journal.util.currentTimeMillis
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/**
 * Shared snapshot decoding and partial recovery for journal persistence.
 *
 * Single home for logic that used to be triplicated byte-for-byte across the
 * three JournalStore actuals (desktop, Android, iOS), plus the seed decode in
 * DataInitializer (audit row "recoverSnapshot key list"). Platform actuals keep
 * only path resolution, IO, and backup rotation; everything that understands
 * the JournalSnapshot JSON shape lives here in commonMain.
 */

/**
 * Outcome of decoding journal JSON text.
 *
 * [snapshot] is always usable: on a whole-file parse failure it is the
 * per-field recovery result of [recoverSnapshot]. [parseError] is null when
 * the whole file decoded cleanly, otherwise it carries the original parse
 * failure message so each platform can set its own issue flags.
 */
internal data class SnapshotDecodeResult(
    val snapshot: JournalSnapshot,
    val parseError: String?
)

/**
 * Decode [text] as a [JournalSnapshot], falling back to per-field recovery
 * when the whole-file parse fails. Never throws: unreadable JSON degrades to
 * the recovered snapshot with [SnapshotDecodeResult.parseError] set.
 */
internal fun decodeSnapshotWithRecovery(text: String): SnapshotDecodeResult {
    val decoded = runCatching { AppJson.json.decodeFromString<JournalSnapshot>(text) }
    val failure = decoded.exceptionOrNull()
    if (failure != null) {
        Log.withTag("JournalStore").w {
            "Journal data failed full parse, attempting per-list recovery: ${failure.message}"
        }
        return SnapshotDecodeResult(recoverSnapshot(text), failure.message)
    }
    return SnapshotDecodeResult(decoded.getOrThrow(), null)
}

/**
 * Strict whole-file decode with no recovery fallback. Callers that must abort
 * on a bad file instead of salvaging it (DataInitializer's bundled-seed load)
 * unwrap this Result themselves and keep their own error reporting.
 */
internal fun decodeSnapshot(text: String): Result<JournalSnapshot> =
    runCatching { AppJson.json.decodeFromString<JournalSnapshot>(text) }

/**
 * Best-effort recovery when the whole-file parse fails: decode each top-level
 * field on its own so one malformed record only drops that record, not the
 * whole file. Tombstones, persons, and prefs are recovered the same way so a
 * partial load never silently wipes them.
 */
internal fun recoverSnapshot(text: String): JournalSnapshot {
    val root: JsonObject =
        runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return JournalSnapshot(savedAt = currentTimeMillis())
    val tombstones: Map<String, Long> = runCatching {
        root["tombstones"]?.jsonObject?.entries?.mapNotNull { (k, v) ->
            runCatching { k to v.jsonPrimitive.long }.getOrNull()
        }?.toMap() ?: emptyMap()
    }.getOrDefault(emptyMap())
    val ratingScaleMode: RatingScaleMode? = runCatching {
        val el = root["ratingScaleMode"] ?: return@runCatching null
        if (el is JsonNull) null
        else AppJson.json.decodeFromJsonElement(RatingScaleMode.serializer(), el)
    }.getOrNull()
    val sessions = decodeList(root, "sessions", Session.serializer())
    val substances = decodeList(root, "substances", Substance.serializer())
    val doses = decodeList(root, "doses", Dose.serializer())
    val notes = decodeList(root, "notes", Note.serializer())
    val timelineEvents = decodeList(root, "timelineEvents", TimelineEvent.serializer())
    val interactions = decodeList(root, "interactions", Interaction.serializer())
    val effects = decodeList(root, "effects", Effect.serializer())
    val customUnits = decodeList(root, "customUnits", CustomUnit.serializer())
    val persons = decodeList(root, "persons", Person.serializer())
    Log.withTag("JournalStore").i {
        "Recovered journal: ${sessions.size} sessions, ${substances.size} substances, " +
        "${doses.size} doses, ${notes.size} notes, ${timelineEvents.size} events, " +
        "${interactions.size} interactions, ${effects.size} effects, ${customUnits.size} units, " +
        "${persons.size} persons, ${tombstones.size} tombstones"
    }
    return JournalSnapshot(
        savedAt = currentTimeMillis(),
        sessions = sessions,
        substances = substances,
        doses = doses,
        notes = notes,
        timelineEvents = timelineEvents,
        interactions = interactions,
        effects = effects,
        customUnits = customUnits,
        persons = persons,
        tombstones = tombstones,
        ratingScaleMode = ratingScaleMode,
        useShulginRating = decodeBoolean(root, "useShulginRating", false),
        useSubstanceColors = decodeBoolean(root, "useSubstanceColors", true),
        welcomeCompleted = decodeBoolean(root, "welcomeCompleted", false),
        seedFingerprint = decodeNullableString(root, "seedFingerprint"),
        obsidianVaultPath = decodeString(root, "obsidianVaultPath", ""),
        obsidianAutoExport = decodeBoolean(root, "obsidianAutoExport", false),
        obsidianSubfolder = decodeString(root, "obsidianSubfolder", "Nepenthe"),
        obsidianFileOrganization = decodeString(root, "obsidianFileOrganization", "flat"),
        showSessionsTrendChart = decodeBoolean(root, "showSessionsTrendChart", false)
    )
}

/** Decode one top-level list field, dropping individual elements that fail. */
private fun <T : Any> decodeList(root: JsonObject, key: String, serializer: KSerializer<T>): List<T> {
    return try {
        val arr = root[key]?.jsonArray ?: return emptyList()
        arr.mapNotNull { element ->
            runCatching {
                AppJson.json.decodeFromJsonElement(serializer, element)
            }.getOrNull()
        }
    } catch (_: Exception) { emptyList() }
}

/** Decode one top-level boolean field, falling back to [default]. */
private fun decodeBoolean(root: JsonObject, key: String, default: Boolean): Boolean =
    runCatching { root[key]?.jsonPrimitive?.boolean ?: default }.getOrDefault(default)

/** Decode one top-level string field, falling back to [default]. */
private fun decodeString(root: JsonObject, key: String, default: String): String =
    runCatching {
        val el = root[key] ?: return@runCatching default
        if (el is JsonNull) default else el.jsonPrimitive.content
    }.getOrDefault(default)

/** Decode one top-level nullable string field. */
private fun decodeNullableString(root: JsonObject, key: String): String? =
    runCatching {
        val el = root[key] ?: return@runCatching null
        if (el is JsonNull) null else el.jsonPrimitive.content
    }.getOrNull()
