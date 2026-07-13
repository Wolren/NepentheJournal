package app.journal.log

import co.touchlab.kermit.Logger

/**
 * Initialize Kermit logging with platform-appropriate writers.
 * Call once at app startup before any log calls.
 * @param appDir Path to app data directory (null = skip file logging).
 */
expect fun initLogging(appDir: String? = null)

/**
 * Convenience alias: `Log.withTag("Tag").i { "message" }`
 */
val Log: Logger = Logger
