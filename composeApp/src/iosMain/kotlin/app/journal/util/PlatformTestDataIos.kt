package app.journal.util

import platform.Foundation.NSProcessInfo

/**
 * iOS: check for NEPENTHE_TEST_DATA in the process environment.
 * Xcode schemes can set environment variables for debug builds.
 */
actual fun platformTestDataEnabled(): Boolean {
    val env = NSProcessInfo.processInfo.environment
    return env["NEPENTHE_TEST_DATA"] == "1"
}
