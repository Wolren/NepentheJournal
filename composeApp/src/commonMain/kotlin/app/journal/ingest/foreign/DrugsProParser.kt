package app.journal.ingest.foreign

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * DrugsPRO (protestkit.eu) journal export: `v` version, `e` experiences, `u` the usage ledger.
 * Neither list is complete: `e` has the clock and the offset but no unit or route name,
 * `u` has route and unit but only a calendar day. They join on (day, name, amount).
 */
internal object DrugsProParser {

    fun matches(root: JsonElement): Boolean {
        val root = root.asObject() ?: return false
        return root.containsKey("v") && root["e"] is JsonArray && root["u"] is JsonArray
    }

    fun parse(root: JsonElement, tz: TimeZone): ForeignJournal {
        val file = root.asObject() ?: return ForeignJournal(ForeignFormat.DRUGSPRO, emptyList())
        val ledger = UsageLedger((file["u"] as? JsonArray).orEmptyJson())
        val experiences = (file["e"] as? JsonArray).orEmptyJson().mapNotNull { element ->
            experience(element.asObject() ?: return@mapNotNull null, ledger, tz)
        }.sortedBy { it.doses.first().timestamp }
        return ForeignJournal(ForeignFormat.DRUGSPRO, experiences)
    }

    private fun experience(experience: JsonObject, ledger: UsageLedger, tz: TimeZone): ForeignExperience? {
        val day = runCatching { LocalDate.parse(experience.str("date") ?: return null) }.getOrNull()
            ?: return null
        val substances = (experience["substances"] as? JsonArray).orEmptyJson()
            .mapNotNull { it.asObject() }
            .filter { (it.num("doseMult") ?: 0.0) > 0.0 }
        if (substances.isEmpty()) return null
        val first = substances.minByOrNull { it.num("minutesAhead")?.toInt() ?: 0 } ?: return null
        val firstClock = clockMinutes(first.str("timeHHMM")) ?: 0
        val zero = floorMod(firstClock - (first.num("minutesAhead")?.toInt() ?: 0), MINUTES_PER_DAY)

        val doses = substances.mapNotNull { substance ->
            dose(substance, day, zero, ledger, tz)
        }.sortedBy { it.timestamp }
        if (doses.isEmpty()) return null

        val summary = experience.str("substance")
        val title = experience.str("title")
        return ForeignExperience(
            title = title?.takeIf { it != summary },
            notes = experience.str("description"),
            doses = doses,
        )
    }

    private fun dose(
        substance: JsonObject,
        day: LocalDate,
        zero: Int,
        ledger: UsageLedger,
        tz: TimeZone,
    ): ForeignDose? {
        val name = substance.str("name") ?: return null
        val amount = substance.num("doseMult") ?: return null
        val elapsed = zero + (substance.num("minutesAhead")?.toInt() ?: 0)
        val clock = clockMinutes(substance.str("timeHHMM"))
            ?: floorMod(elapsed, MINUTES_PER_DAY)
        // The day follows the offset, the minute follows the clock: minutesAhead is rounded.
        val dayOffset = ((elapsed - clock).toDouble() / MINUTES_PER_DAY).roundToInt()
        val local = LocalDateTime(day.year, day.month, day.dayOfMonth, clock / 60, clock % 60)
        // Day offsets add whole 24-hour spans, so a DST change moves the clock by one hour.
        val timestamp = local.toInstant(tz).toEpochMilliseconds() + dayOffset * MILLIS_PER_DAY

        val row = ledger.take(day.toString(), name, amount)
        val unit = row?.unit ?: ledger.unitFor(name) ?: "mg"
        val route = row?.route ?: substance.str("roaName")
        val goal = substance.str("goal")
        val notes = listOfNotNull(substance.str("tag")?.let { "#$it" }, goal).joinToString(" ")
        return ForeignDose(
            substance = name,
            amount = amount,
            unit = RouteNames.unit(unit),
            route = RouteNames.normalize(route),
            timestamp = timestamp,
            notes = notes.ifBlank { null },
        )
    }

    /** Usage rows indexed for the join; each row is handed out once, so twin doses pair with twin rows. */
    private class UsageLedger(rows: List<JsonElement>) {

        private val byKey = mutableMapOf<String, MutableList<Row>>()
        private val unitByName = mutableMapOf<String, String>()

        init {
            for (element in rows) {
                val row = element.asObject() ?: continue
                val amount = amountOf(row.str("dose")) ?: continue
                val name = row.str("name") ?: continue
                val day = row.str("dateISO") ?: row.str("date") ?: continue
                val unit = unitOf(row.str("dose"))
                byKey.getOrPut(key(day, name, amount)) { mutableListOf() }
                    .add(Row(route = row.str("roa"), unit = unit))
                if (unit != null) unitByName.putIfAbsent(name.lowercase(), unit)
            }
        }

        fun take(day: String, name: String, amount: Double): Row? {
            val matches = byKey[key(day, name, amount)] ?: return null
            return if (matches.isEmpty()) null else matches.removeAt(0)
        }

        fun unitFor(name: String): String? = unitByName[name.lowercase()]

        private fun key(day: String, name: String, amount: Double): String =
            "$day|${name.lowercase()}|$amount"
    }

    private data class Row(val route: String?, val unit: String?)

    /** "3.6 g" -> 3.6; the number alone when DrugsPRO wrote no unit. */
    private fun amountOf(raw: String?): Double? =
        raw?.trim()?.substringBefore(' ')?.replace(',', '.')?.toDoubleOrNull()

    /** "3.6 g" -> "g"; null when DrugsPRO wrote a bare number. */
    private fun unitOf(raw: String?): String? =
        raw?.trim()?.substringAfter(' ', "")?.trim()?.ifEmpty { null }

    private fun floorMod(value: Int, modulus: Int): Int = ((value % modulus) + modulus) % modulus

    private fun JsonArray?.orEmptyJson(): List<JsonElement> = this ?: emptyList()

    private const val MINUTES_PER_DAY = 1440
    private const val MILLIS_PER_DAY = 86_400_000L
}
