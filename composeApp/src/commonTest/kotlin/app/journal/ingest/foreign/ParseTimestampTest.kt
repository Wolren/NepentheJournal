package app.journal.ingest.foreign

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParseTimestampTest {

    private val tz = TimeZone.UTC

    @Test
    fun invalidEuropeanDatesReturnNull() {
        assertNull(parseTimestamp("31.02.2026 10:00", tz))
        assertNull(parseTimestamp("14.11.2026 25:00", tz))
    }

    @Test
    fun validEuropeanDateParses() {
        assertNotNull(parseTimestamp("14.11.2026 15:30", tz))
    }
}
