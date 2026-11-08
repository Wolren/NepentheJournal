package app.journal.ingest

import app.journal.model.DoseWikiSubstance
import app.journal.serde.AppJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real seed, not a stub: the parsing must yield human half-lives for most
 * substances that carry a duration route, and the known values must land.
 */
class SeedKineticsTest {

    private fun seedFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "composeApp/src/desktopMain/resources/dosewiki_slim.json")
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("dosewiki_slim.json not found above ${System.getProperty("user.dir")}")
    }

    private fun seed(): List<DoseWikiSubstance> =
        AppJson.json.decodeFromString(seedFile().readText())

    private fun primaryRoute(halfLife: String?, route: String = "oral"): Double? =
        Pharmacokinetics.halfLifeMinutes(halfLife)

    @Test
    fun mostSubstancesWithADurationRouteCarryAHalfLife() {
        val records = seed()
        val withValue = records.count { dw ->
            dw.duration?.routes.orEmpty().any { Pharmacokinetics.halfLifeMinutes(it.half_life) != null }
        }
        assertTrue(withValue >= 60, "only $withValue of ${records.size} records yielded a half-life")
    }

    @Test
    fun knownSubstancesLandOnTheirPublishedValues() {
        val byName = seed().associateBy { it.title }
        val lsd = byName.getValue("LSD").duration?.routes.orEmpty().first { it.route == "oral" }
        assertEquals(180.0, primaryRoute(lsd.half_life)!!, 0.001)

        val caffeine = byName.getValue("Caffeine").duration?.routes.orEmpty().first { it.route == "oral" }
        assertEquals(300.0, primaryRoute(caffeine.half_life)!!, 0.001)

        val amphetamine = byName.getValue("Amphetamine").duration?.routes.orEmpty().first { it.route == "oral" }
        assertEquals(690.0, primaryRoute(amphetamine.half_life)!!, 0.001)
    }

    @Test
    fun timeToPeakComesFromThePeakStageOfTheRealRoute() {
        val lsd = seed().first { it.title == "LSD" }.duration?.routes.orEmpty().first { it.route == "oral" }
        // Peak stage is 3-5 hours, so the midpoint sits at 4 hours.
        assertEquals(240.0, Pharmacokinetics.tmaxMinutes(lsd.stages)!!, 0.001)
    }
}
