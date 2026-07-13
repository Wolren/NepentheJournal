package app.journal.log

actual fun collectLogs(appDir: String): String {
    // iOS file-based crash logging not implemented yet
    return "iOS crash logging: use Xcode Console or OSLog"
}
