package app.journal.log

/**
 * Collect crash log contents from the platform's rolling log file directory.
 * Returns an empty string if no logs are available.
 * @param appDir Application data directory where crashlogs/ resides.
 */
expect fun collectLogs(appDir: String): String
