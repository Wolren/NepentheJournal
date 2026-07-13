package app.journal.log

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import kotlinx.io.files.Path

actual fun initLogging(appDir: String?) {
    val writers = mutableListOf<co.touchlab.kermit.LogWriter>(
        CommonWriter()
    )

    if (appDir != null) {
        writers.add(
            RollingFileLogWriter(
                config = RollingFileLogWriterConfig(
                    logFileName = "nepenthe",
                    logFilePath = Path(appDir),
                    logTag = true,
                    prependTimestamp = true,
                )
            )
        )
    }

    Logger.setLogWriters(writers)
    Logger.i("Logging initialized (Desktop) — crash dir: ${appDir}")
}
