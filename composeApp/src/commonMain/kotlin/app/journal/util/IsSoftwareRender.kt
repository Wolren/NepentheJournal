package app.journal.util

/**
 * Detect whether the app is running in software rendering mode.
 * Software rendering disables animations to avoid visual tearing/flashing.
 *
 * Desktop: checks System property "skiko.renderApi".
 * Android: always returns false (hardware-accelerated).
 * iOS: always returns false (Metal-accelerated).
 */
expect fun isSoftwareRender(): Boolean
