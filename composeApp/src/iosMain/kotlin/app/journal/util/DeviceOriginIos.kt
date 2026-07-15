package app.journal.util

import platform.UIKit.UIDevice

actual fun platformDeviceOrigin(): String {
    val model = UIDevice.currentDevice.model ?: "unknown"
    val systemName = UIDevice.currentDevice.systemName ?: "iOS"
    return "ios:${model}_${systemName}"
}
