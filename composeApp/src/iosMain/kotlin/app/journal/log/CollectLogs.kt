package app.journal.log

/**
 * iOS collectLogs — not yet implemented for file-based crash logs.
 * Use Xcode Console or OSLog for iOS diagnostics.
 */
actual fun collectLogs(appDir: String): String {
    return "iOS crash logging: use Xcode Console or OSLog"
}

/**
 * Clean old crash log files older than [maxAgeDays].
 * Not implemented on iOS (crash dumps use OSLog, not files).
 */
fun cleanOldCrashLogs(appDir: String, maxAgeDays: Int = 30) {
    // No-op: iOS crash logs are managed by OSLog/OS
}
