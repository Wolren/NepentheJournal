package app.journal.util

actual object Platform {
    actual val osName: String get() = System.getProperty("os.name")
}
