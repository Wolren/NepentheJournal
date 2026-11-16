package app.journal.log

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import kotlinx.io.files.Path

/**
 * Buffers lines until the rolling file writer exists. Building that writer costs
 * ~200 ms of one-time static init, so it runs behind the first frame instead.
 */
private class DeferredFileWriter : LogWriter() {

    private class Line(val severity: Severity, val message: String, val tag: String, val throwable: Throwable?)

    private val lock = Any()
    private val pending = ArrayList<Line>()
    private var target: LogWriter? = null

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val installed = synchronized(lock) {
            if (target == null) {
                if (pending.size < MAX_PENDING) pending.add(Line(severity, message, tag, throwable))
                null
            } else {
                target
            }
        }
        installed?.log(severity, message, tag, throwable)
    }

    /** Switches to [writer] and replays what was logged before it existed. */
    fun install(writer: LogWriter) {
        val replay: List<Line>
        synchronized(lock) {
            replay = ArrayList(pending)
            pending.clear()
            target = writer
        }
        replay.forEach { writer.log(it.severity, it.message, it.tag, it.throwable) }
    }

    private companion object {
        const val MAX_PENDING = 2000
    }
}

actual fun initLogging(appDir: String?) {
    if (appDir == null) {
        Logger.setLogWriters(listOf(CommonWriter()))
        Logger.i("Logging initialized (Desktop), no file writer")
        return
    }
    try {
        java.io.File(appDir).mkdirs()
    } catch (_: Exception) { /* the file writer install below fails quietly too */ }

    val fileWriter = DeferredFileWriter()
    Logger.setLogWriters(listOf(CommonWriter(), fileWriter))
    Logger.i("Logging initialized (Desktop), dir: ${appDir}")

    Thread({ installFileWriter(appDir, fileWriter) }, "log-file-writer").apply { isDaemon = true }.start()
}

/** Runs off the main thread: the rotation scan and the timestamp format init are not free. */
private fun installFileWriter(appDir: String, deferred: DeferredFileWriter) {
    try {
        deferred.install(
            RollingFileLogWriter(
                config = RollingFileLogWriterConfig(
                    logFileName = "nepenthe",
                    logFilePath = Path(appDir),
                    logTag = true,
                    prependTimestamp = true,
                    rollOnSize = 5L * 1024 * 1024, // 5MB per file
                    maxLogFiles = 10                // keep 10 rotating files
                )
            )
        )
        StartupClock.mark("log file writer")
        cleanOldCrashLogs(appDir, maxAgeDays = 30)
    } catch (e: Exception) {
        Log.withTag("Logging").w { "File log writer unavailable: ${e.message}" }
    }
}
