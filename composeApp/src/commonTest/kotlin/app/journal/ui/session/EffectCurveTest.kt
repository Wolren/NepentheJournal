package app.journal.ui.session

import app.journal.model.CheckIn
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import app.journal.util.parseDurationProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour of the effect curve that feeds the session card's timeline: how
 * logged samples are merged and ordered, how a curve is derived from a
 * substance's duration profile when nothing is logged, and how intensity is
 * read back between samples.
 */
class EffectCurveTest {

    private fun event(
        timestamp: Long,
        intensity: Float?,
        type: TimelineEventType = TimelineEventType.OBSERVATION,
    ) = TimelineEvent(
        id = "e-$timestamp",
        createdAt = timestamp,
        updatedAt = timestamp,
        deviceOrigin = "test",
        sessionId = "s1",
        timestamp = timestamp,
        eventType = type,
        label = "sample",
        intensity = intensity,
    )

    private fun checkIn(timestamp: Long, intensity: Float) =
        CheckIn(timestamp = timestamp, overallIntensity = intensity)

    // ==================== loggedEffectSamples ====================

    @Test
    fun loggedEffectSamplesMergesEventsAndCheckInsChronologically() {
        val samples = loggedEffectSamples(
            events = listOf(event(300L, 7f), event(100L, 2f)),
            checkins = listOf(checkIn(200L, 5f), checkIn(50L, 1f)),
        )
        assertEquals(
            listOf(50L, 100L, 200L, 300L),
            samples.map { it.timestampMs },
            "both sources are merged and sorted by time"
        )
        assertEquals(listOf(1f, 2f, 5f, 7f), samples.map { it.intensity }, "intensity travels with its sample")
    }

    @Test
    fun loggedEffectSamplesSkipsEventsWithoutIntensity() {
        val samples = loggedEffectSamples(
            events = listOf(event(100L, null), event(200L, 4f)),
            checkins = emptyList(),
        )
        assertEquals(1, samples.size, "phase notes without intensity are not data points")
        assertEquals(200L, samples[0].timestampMs, "the one real sample remains")
    }

    @Test
    fun loggedEffectSamplesEmptyWhenNothingLogged() {
        assertTrue(loggedEffectSamples(emptyList(), emptyList()).isEmpty(), "no logs, no curve")
    }

    // ==================== effectIntensityAt ====================

    @Test
    fun effectIntensityAtInterpolatesBetweenSamples() {
        val samples = listOf(
            EffectSample(0L, 0f),
            EffectSample(100L, 10f),
            EffectSample(200L, 4f),
        )
        assertEquals(0f, effectIntensityAt(samples, 0L), 0.001f, "at the first sample")
        assertEquals(10f, effectIntensityAt(samples, 100L), 0.001f, "at a sample")
        assertEquals(5f, effectIntensityAt(samples, 50L), 0.001f, "halfway up")
        assertEquals(7f, effectIntensityAt(samples, 150L), 0.001f, "halfway down")
        assertEquals(0f, effectIntensityAt(samples, -50L), 0.001f, "before the curve clamps to its start")
        assertEquals(4f, effectIntensityAt(samples, 500L), 0.001f, "after the curve clamps to its end")
        assertEquals(0f, effectIntensityAt(emptyList(), 100L), 0.001f, "no samples means no intensity")
    }

    // ==================== synthesizedEffectSamples ====================

    @Test
    fun synthesizedCurveFollowsTheDurationProfile() {
        val phases = parseDurationProfile(
            mapOf(
                "onset" to "1 hour",
                "comeup" to "1 hour",
                "peak" to "2 hours",
                "offset" to "2 hours",
                "afterglow" to "1 hour",
            )
        )
        val anchorMs = 1_000_000L
        val samples = synthesizedEffectSamples(phases, anchorMs)

        assertEquals(anchorMs, samples.first().timestampMs, "the curve starts at the first dose")
        assertEquals(
            anchorMs + 7 * 3_600_000L,
            samples.last().timestampMs,
            "phases are laid end to end at their midpoint duration"
        )
        assertEquals(0f, samples.first().intensity, 0.001f, "it starts from nothing")
        assertEquals(0f, samples.last().intensity, 0.001f, "and comes back to the baseline")
        assertEquals(10f, samples.maxOf { it.intensity }, "the peak reaches the top of the scale")
        assertTrue(
            samples.zipWithNext().all { (a, b) -> b.timestampMs > a.timestampMs },
            "timestamps only move forward, never overlap"
        )
        assertTrue(
            samples.all { it.intensity in 0f..10f },
            "every point stays on the 0..10 scale"
        )
    }

    @Test
    fun synthesizedCurveOfAMeasuredProfileStillStartsAtZero() {
        // Onset alone: a ramp from nothing to a third of full intensity.
        val phases = parseDurationProfile(mapOf("onset" to "30 min"))
        val samples = synthesizedEffectSamples(phases, anchorMs = 0L)
        assertEquals(0f, samples.first().intensity, 0.001f, "starts at baseline")
        assertEquals(3f, samples.maxOf { it.intensity }, 0.01f, "onset tops out at 30%")
        assertEquals(
            0f,
            samples.last().intensity,
            0.001f,
            "the open tail still bleeds off after the onset"
        )
        assertTrue(
            samples.any { it.timestampMs == 30 * 60_000L },
            "one onset long, then the tail"
        )
    }

    @Test
    fun synthesizedCurveBleedsToZeroWhenProfileHasNoAfterglow() {
        val phases = parseDurationProfile(mapOf("peak" to "1 hour"))
        val samples = synthesizedEffectSamples(phases, anchorMs = 0L)
        assertEquals(0f, samples.last().intensity, 0.001f, "an open-ended profile still lands on the baseline")
        assertTrue(
            samples.last().timestampMs > 3_600_000L,
            "the tail extends past the last phase instead of stopping mid-air"
        )
    }

    @Test
    fun synthesizedCurveEmptyWithoutPhases() {
        assertTrue(synthesizedEffectSamples(emptyList(), 0L).isEmpty(), "no profile, nothing to draw")
    }
}
