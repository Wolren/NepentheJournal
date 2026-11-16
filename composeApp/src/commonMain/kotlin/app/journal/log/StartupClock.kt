package app.journal.log

import app.journal.util.currentTimeMillis

/**
 * Launch-phase clock: logs each mark as the time since the process started, so a
 * slow launch is readable from the log without a profiler.
 */
object StartupClock {

    private var startMs: Long = 0L

    /** Anchors the clock. Pass the OS process start instant when the platform knows it. */
    fun start(processStartEpochMs: Long) {
        startMs = if (startMs == 0L) processStartEpochMs else startMs
    }

    fun elapsedMs(): Long = if (startMs == 0L) 0L else currentTimeMillis() - startMs

    /** Logs "+NNNms label"; unanchored calls anchor on the first mark. */
    fun mark(label: String) {
        if (startMs == 0L) startMs = currentTimeMillis()
        Log.withTag("Startup").i { "+${elapsedMs()}ms $label" }
    }
}
