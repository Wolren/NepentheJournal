package app.journal.ingest.foreign

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SpreadsheetParserTest {

    private val tz = TimeZone.UTC

    @Test
    fun quotedThousandsAreNotDecimals() {
        val csv = "substance,amount,unit,timestamp\nLSD,\"1,000 mg\",mg,14.11.2026 15:30"
        val journal = assertNotNull(SpreadsheetParser.parse(csv, tz))
        val dose = journal.experiences.single().doses.single()
        assertEquals(1000.0, dose.amount)
        assertEquals("mg", dose.unit)
    }

    @Test
    fun aStrayQuoteDoesNotSwallowTheRestOfTheRow() {
        val csv = "substance,amount,unit,timestamp\nLSD,25 mg \"test\",mg,14.11.2026 15:30"
        val journal = assertNotNull(SpreadsheetParser.parse(csv, tz))
        assertEquals(1, journal.experiences.single().doses.size)
    }

    @Test
    fun multipleThousandsGroupsParseAsOneNumber() {
        val csv = "substance,amount,unit,timestamp\nLSD,\"1,234,567 mg\",mg,14.11.2026 15:30"
        val journal = assertNotNull(SpreadsheetParser.parse(csv, tz))
        assertEquals(1234567.0, journal.experiences.single().doses.single().amount)
    }
}
