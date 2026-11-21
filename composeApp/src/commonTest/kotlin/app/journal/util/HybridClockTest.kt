package app.journal.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HLC must stay monotonic when the wall clock does not: same-millisecond writes
 * take the next counter, a backward step keeps the last physical time, and receiving
 * a peer stamp advances past it so the next local write is causally later.
 */
class HybridClockTest {

    @Test
    fun sameMillisecondIncrementsTheCounter() {
        val clock = HybridLogicalClock()
        assertEquals(Hlc(1_000L, 0L), clock.now(1_000L))
        assertEquals(Hlc(1_000L, 1L), clock.now(1_000L))
        assertEquals(Hlc(1_000L, 2L), clock.now(1_000L))
    }

    @Test
    fun aForwardWallClockResetsTheCounter() {
        val clock = HybridLogicalClock()
        clock.now(1_000L)
        clock.now(1_000L)
        val afterJump = clock.now(2_000L)
        assertEquals(Hlc(2_000L, 0L), afterJump)
        assertEquals(Hlc(2_000L, 1L), clock.now(2_000L))
    }

    @Test
    fun aBackwardWallClockNeverRegresses() {
        val clock = HybridLogicalClock()
        clock.now(5_000L)
        val afterBackward = clock.now(1_000L)
        assertTrue(afterBackward > Hlc(5_000L, 0L), "a backward step must increment the counter")
        assertEquals(5_000L, afterBackward.physicalMs)
        assertEquals(1L, afterBackward.counter)
    }

    @Test
    fun observeAdvancesPastTheRemoteStamp() {
        val clock = HybridLogicalClock()
        clock.observe(Hlc(9_000L, 4L))
        assertEquals(Hlc(9_000L, 5L), clock.current, "the receive step lands just past the remote tick")
        val next = clock.now(1_000L)
        assertTrue(next > Hlc(9_000L, 4L), "the next local write must be causally later")
        assertEquals(Hlc(9_000L, 6L), next)
    }

    @Test
    fun observeAtTheSameTickTakesTheHigherCounter() {
        val clock = HybridLogicalClock()
        clock.now(7_000L)
        clock.observe(Hlc(7_000L, 9L))
        assertEquals(Hlc(7_000L, 10L), clock.current)
    }

    @Test
    fun observeAtAnOlderTickOnlyBumpsTheCounter() {
        val clock = HybridLogicalClock()
        clock.now(7_000L)
        clock.observe(Hlc(1_000L, 3L))
        assertEquals(Hlc(7_000L, 1L), clock.current)
    }

    @Test
    fun orderingIsPhysicalThenCounter() {
        assertTrue(Hlc(2_000L, 0L) > Hlc(1_000L, 99L))
        assertTrue(Hlc(1_000L, 99L) > Hlc(1_000L, 98L))
        assertEquals(0, Hlc(1_000L, 5L).compareTo(Hlc(1_000L, 5L)))
    }

    @Test
    fun encodeRoundTrips() {
        val value = Hlc.of(1_700_000_000_000L, 12L)
        assertEquals("1700000000000:12", value.encoded)
        assertEquals(value, Hlc.parseOrNull(value.encoded))
    }

    @Test
    fun malformedStampsAreRejected() {
        val bad = listOf(
            "", ":", "1", "1:", ":2", "1:2:3", "-1:0", "1:-2", "1:2x", " 1:2",
            "a:b", "+1:2", "1:+2", "9".repeat(Hlc.MAX_ENCODED_LENGTH),
        )
        for (text in bad) assertNull(Hlc.parseOrNull(text), "must reject '$text'")
    }

    @Test
    fun ofClampsNegativeInput() {
        assertEquals(Hlc(0L, 0L), Hlc.of(-5L, -2L))
    }
}
