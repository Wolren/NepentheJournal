package app.journal.util

/**
 * Returns a string identifying the current platform, used as the deviceOrigin
 * tag in sessions created by this device.
 */
expect fun platformDeviceOrigin(): String
