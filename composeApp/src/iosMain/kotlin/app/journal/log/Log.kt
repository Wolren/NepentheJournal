package app.journal.log

import co.touchlab.kermit.CommonWriter
import co.touchlab.kermit.Logger

actual fun initLogging(appDir: String?) {
    Logger.setLogWriters(CommonWriter())
    Logger.i("Logging initialized (iOS)")
}
