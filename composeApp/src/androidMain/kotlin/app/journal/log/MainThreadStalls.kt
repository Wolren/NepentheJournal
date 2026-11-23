package app.journal.log

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Names a freeze after it happened: a marker runnable runs on the main looper every
 * [SAMPLE_MS]; when it stops running for [STALL_MS] a watchdog thread samples the
 * main stack into the rolling log. Frame stats say a frame was slow, this says where.
 */
object MainThreadStalls {

    private const val SAMPLE_MS = 50L
    private const val STALL_MS = 150L
    private const val MIN_LOG_GAP_MS = 2_000L
    private const val MAX_FRAMES = 24

    private var started = false

    @Volatile
    private var lastTick = 0L

    fun start() {
        if (started) return
        started = true
        // Stamped before the marker is posted, or the first sample reports the whole uptime.
        lastTick = SystemClock.uptimeMillis()
        val main = Looper.getMainLooper().thread
        val handler = Handler(Looper.getMainLooper())
        val marker = object : Runnable {
            override fun run() {
                lastTick = SystemClock.uptimeMillis()
                handler.postDelayed(this, SAMPLE_MS)
            }
        }
        handler.post(marker)
        thread(name = "nepenthe-stall-watchdog", isDaemon = true) {
            var lastLog = 0L
            while (true) {
                Thread.sleep(SAMPLE_MS)
                val now = SystemClock.uptimeMillis()
                val busy = now - lastTick
                if (busy < STALL_MS || now - lastLog < MIN_LOG_GAP_MS) continue
                lastLog = now
                val stack = main.stackTrace
                Log.withTag("Stall").w {
                    "main thread busy ${busy}ms\n" + stack.take(MAX_FRAMES)
                        .joinToString("\n") { "  at $it" }
                }
            }
        }
    }
}
