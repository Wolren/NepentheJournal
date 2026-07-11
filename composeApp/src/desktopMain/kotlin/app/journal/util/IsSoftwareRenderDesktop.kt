package app.journal.util

actual fun isSoftwareRender(): Boolean {
    val api = System.getProperty("skiko.renderApi", "").uppercase()
    return api == "SOFTWARE" || api == "SOFTWARE_FAST"
}
