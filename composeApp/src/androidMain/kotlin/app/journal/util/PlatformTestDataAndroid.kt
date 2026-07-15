package app.journal.util

actual fun platformTestDataEnabled(): Boolean {
    return java.lang.Boolean.getBoolean("nepenthe.test-data") ||
        System.getenv("NEPENTHE_TEST_DATA") == "1"
}
