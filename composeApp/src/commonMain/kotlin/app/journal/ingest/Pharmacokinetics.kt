package app.journal.ingest

import app.journal.model.DoseWikiStage
import app.journal.model.DoseWikiStages

/**
 * Turns the seed's free-text kinetics into the two numbers the PK curve needs.
 * Animal and in-vitro values are rejected: a rat half-life would draw a false
 * human curve. Both return null when the text carries nothing usable.
 */
internal object Pharmacokinetics {

    private val RANGE = Regex(
        """(\d+(?:\.\d+)?)\s*(?:-|to|–)?\s*(\d+(?:\.\d+)?)?\s*(min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)""",
        RegexOption.IGNORE_CASE
    )

    private val ANIMAL_STUDY = Regex(
        """\b(rats?|mice|mouse|dogs?|rabbits?|primates?|non-human|in vitro|in vivo)\b""",
        RegexOption.IGNORE_CASE
    )

    /** "1-3 hours" gives 120, "39 min" gives 39, "half-life in rats is 91 min" gives null. */
    fun halfLifeMinutes(raw: String?): Double? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (ANIMAL_STUDY.containsMatchIn(text)) return null
        return parseMinutesRange(text)
    }

    /** Peak-stage midpoint; falls back to onset plus comeup when the peak stage is missing. */
    fun tmaxMinutes(stages: DoseWikiStages?): Double? {
        if (stages == null) return null
        stages.peak?.let { stage -> parseMinutesRange(stageText(stage) ?: "")?.let { return it } }
        val onset = stages.onset?.let { parseMinutesRange(stageText(it) ?: "") } ?: return null
        val comeup = stages.come_up?.let { parseMinutesRange(stageText(it) ?: "") }
        return onset + (comeup ?: 0.0)
    }

    private fun stageText(stage: DoseWikiStage): String? {
        val min = stage.min ?: return null
        val max = stage.max ?: min
        return "$min-$max ${stage.unit.orEmpty()}"
    }

    fun parseMinutesRange(text: String): Double? {
        val match = RANGE.find(text) ?: return null
        val low = match.groupValues[1].toDoubleOrNull() ?: return null
        val high = match.groupValues[2].toDoubleOrNull() ?: low
        val factor = when (match.groupValues[3].lowercase()) {
            "min", "mins", "minute", "minutes" -> 1.0
            "h", "hr", "hrs", "hour", "hours" -> 60.0
            "d", "day", "days" -> 1440.0
            else -> return null
        }
        val midpoint = (low + high) / 2.0 * factor
        return midpoint.takeIf { it > 0.0 }
    }
}
