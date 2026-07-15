package app.journal.util

actual fun platformTestDataEnabled(): Boolean {
    return System.getProperty("nepenthe.test-data") == "true" ||
        System.getenv("NEPENTHE_TEST_DATA") == "1"
}
