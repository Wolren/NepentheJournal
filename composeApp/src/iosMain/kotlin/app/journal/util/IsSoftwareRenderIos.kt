package app.journal.util

/**
 * iOS always uses Metal (hardware-accelerated).
 */
actual fun isSoftwareRender(): Boolean = false
