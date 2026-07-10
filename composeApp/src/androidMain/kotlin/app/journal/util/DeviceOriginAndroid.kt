package app.journal.util

import android.os.Build

actual fun platformDeviceOrigin(): String {
    return "android:${Build.MODEL ?: "unknown"}"
}
