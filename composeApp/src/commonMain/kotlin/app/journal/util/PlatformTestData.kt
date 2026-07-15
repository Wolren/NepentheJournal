package app.journal.util

/**
 * Returns true when system properties / environment indicates test data should be loaded.
 * Platform-specific implementations read JVM properties (desktop) or Android system properties.
 */
expect fun platformTestDataEnabled(): Boolean
