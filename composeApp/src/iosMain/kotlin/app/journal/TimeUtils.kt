package app.journal.ingest
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
actual fun currentTimeMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
