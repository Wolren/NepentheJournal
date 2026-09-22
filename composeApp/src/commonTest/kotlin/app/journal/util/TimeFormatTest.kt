package app.journal.util

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function boundaries for TimeFormat (audit section 4, pure additions).
 * Every call passes an explicit now/end so no assertion depends on the wall
 * clock.
 */
class TimeFormatTest {

    private val now = 1_700_000_000_000L

    // ==================== formatDuration ====================

    @Test
    fun formatDurationBoundaries() {
        assertEquals("0s", formatDuration(0L, 0L), "zero span")
        assertEquals("45s", formatDuration(0L, 45_000L), "sub-minute renders seconds")
        assertEquals("1m", formatDuration(0L, 60_000L), "exact minute drops the seconds arm")
        assertEquals("59m", formatDuration(0L, 3_599_000L), "last minute before the hour arm")
        assertEquals("1h 0m", formatDuration(0L, 3_600_000L), "exact hour keeps a zero minutes part")
        assertEquals("1h 23m", formatDuration(0L, 5_000_000L), "hours + minutes")
        assertEquals("0m", formatDuration(1_000L, 500L), "negative span clamps to 0m")
    }

    // ==================== formatRelativeTime ====================

    @Test
    fun formatRelativeTimePast() {
        assertEquals("just now", formatRelativeTime(now, now), "zero delta")
        assertEquals("just now", formatRelativeTime(now - 30_000L, now), "under a minute")
        assertEquals("5m ago", formatRelativeTime(now - 300_000L, now), "minutes")
        assertEquals("1h ago", formatRelativeTime(now - 3_600_000L, now), "hours")
        assertEquals("Yesterday", formatRelativeTime(now - 86_400_000L, now), "exactly one day")
        assertEquals("3d ago", formatRelativeTime(now - 3 * 86_400_000L, now), "days before the week arm")
        assertEquals("2w ago", formatRelativeTime(now - 14 * 86_400_000L, now), "weeks")
        assertEquals("2mo ago", formatRelativeTime(now - 60 * 86_400_000L, now), "months = days/30")
        assertEquals("1y ago", formatRelativeTime(now - 400 * 86_400_000L, now), "years = days/365")
    }

    @Test
    fun formatRelativeTimeFuturePinsCurrentBehavior() {
        // A future timestamp makes diff negative; every branch guards on
        // seconds < 60 first, so negatives render "just now". Pinned as-is:
        // if a future-tense form ("in 5m") is ever added, this flips loudly.
        assertEquals("just now", formatRelativeTime(now + 60_000L, now),
            "future input currently falls into the <60s branch")
        assertEquals("just now", formatRelativeTime(now + 400 * 86_400_000L, now),
            "far-future input also lands in the <60s branch")
    }

    // ==================== formatDateShort ====================

    @Test
    fun formatDateShortUsesUniformDayMonthYear() {
        assertEquals("15 Jan 2024", formatDateShort(LocalDate(2024, 1, 15)))
        assertEquals("31 Dec 2024", formatDateShort(LocalDate(2024, 12, 31)))
        assertEquals("1 Mar 2024", formatDateShort(LocalDate(2024, 3, 1)))
    }

    // ==================== formatElapsedSinceStart ====================

    @Test
    fun formatElapsedSinceStartBoundaries() {
        assertEquals("T+0:00", formatElapsedSinceStart(1_000L, 500L), "negative elapsed clamps")
        assertEquals("T+12s", formatElapsedSinceStart(1_000L, 13_000L), "seconds arm")
        assertEquals("T+45m", formatElapsedSinceStart(1_000L, 1_000L + 45 * 60_000L), "minutes arm")
        assertEquals("T+1:03", formatElapsedSinceStart(1_000L, 1_000L + 3_780_000L), "hours arm pads minutes")
    }

    // ==================== DurationStyle parity (wave4 helper dedup) ====================

    /**
     * Verbatim copy of the pre-dedup ui/session/timeline formatDuration body.
     * The single renderer must stay byte-for-byte identical to it for every
     * input: rendered duration text is user-visible and exported.
     */
    private fun oldTimelineFormatDuration(millis: Long): String {
        val totalSec = millis / 1000
        val hours = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    /** Verbatim copy of the pre-dedup ObsidianNoteRenderer.formatDuration body. */
    private fun oldObsidianFormatDuration(start: Long, end: Long?): String? {
        if (end == null) return null
        val diffMs = end - start
        if (diffMs < 0) return null
        val totalMinutes = diffMs / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }

    /** Verbatim copy of the pre-dedup util/TimeFormat formatDuration body. */
    private fun oldUiFormatDuration(start: Long, end: Long): String {
        val diff = end - start
        if (diff < 0) return "0m"
        val totalSeconds = diff / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${totalSeconds}s"
        }
    }

    private val spanCorpus = longArrayOf(
        -7_261_000, -7_200_001, -60_000, -1, 0, 1, 30_000, 45_000, 59_999,
        60_000, 1_799_999, 1_800_000, 3_599_999, 3_600_000, 3_660_000,
        5_000_000, 7_200_000, 9_000_000, 90_061_000
    )

    @Test
    fun uiStyleIsByteForByteIdenticalToPreDedupBody() {
        for (span in spanCorpus) {
            assertEquals(
                oldUiFormatDuration(1_000L, 1_000L + span), formatDuration(1_000L, 1_000L + span),
                "UI duration for span $span must not change"
            )
        }
    }

    @Test
    fun obsidianStyleIsByteForByteIdenticalToPreDedupBody() {
        // Non-negative spans only: the Obsidian wrapper maps null/negative to
        // null BEFORE reaching the core (that policy is pinned by
        // ObsidianNoteRendererTest.formatDurationTests).
        for (span in spanCorpus) {
            if (span < 0) continue
            assertEquals(
                oldObsidianFormatDuration(1_000L, 1_000L + span),
                formatDurationCore(span, DurationStyle.OBSIDIAN_EXPORT),
                "Obsidian duration for span $span must not change"
            )
        }
        assertEquals("<1m", formatDurationCore(30_000, DurationStyle.OBSIDIAN_EXPORT))
        assertEquals("1h", formatDurationCore(3_600_000, DurationStyle.OBSIDIAN_EXPORT))
        assertEquals("2h 30m", formatDurationCore(9_000_000, DurationStyle.OBSIDIAN_EXPORT))
        assertEquals("30m", formatDurationCore(1_800_000, DurationStyle.OBSIDIAN_EXPORT))
    }

    @Test
    fun timelineStyleIsByteForByteIdenticalToPreDedupBody() {
        for (span in spanCorpus) {
            assertEquals(
                oldTimelineFormatDuration(span),
                formatDurationCore(span, DurationStyle.TIMELINE_PHASE),
                "timeline duration for span $span must not change"
            )
        }
        // Explicit historic pins: seconds arm dropped, zero minutes KEPT,
        // negatives render raw.
        assertEquals("0m", formatDurationCore(45_000, DurationStyle.TIMELINE_PHASE))
        assertEquals("5h 0m", formatDurationCore(18_000_000, DurationStyle.TIMELINE_PHASE))
        assertEquals("30m", formatDurationCore(1_800_000, DurationStyle.TIMELINE_PHASE))
        assertEquals("-1m", formatDurationCore(-60_000, DurationStyle.TIMELINE_PHASE))
    }
}
