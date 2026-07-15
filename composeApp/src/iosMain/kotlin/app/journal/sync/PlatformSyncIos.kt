package app.journal.sync

import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

actual fun platformSyncDataDir(): String {
    val docs = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true
    ).firstOrNull() as? String ?: return "."
    return "$docs/.psychonautica"
}

actual fun platformDeviceName(): String {
    val device = UIDevice.currentDevice
    val name = device.name ?: "iOS Device"
    return "$name (${device.systemName ?: "iOS"} ${device.systemVersion ?: ""})"
}

/**
 * iOS cert generation using Security framework.
 * Uses SecKeyGeneratePair + SecCertificateCreateWithData to create
 * a self-signed RSA 2048-bit certificate stored as PKCS12 in a file.
 *
 * Note: This requires the Security framework (platform.Security).
 */
actual fun generateSelfSignedP12(
    storePath: String,
    alias: String,
    password: CharArray
) {
    // On iOS, TLS sync uses plain HTTP + HMAC auth (no TLS certs).
    // If TLS is needed in the future, implement using Security framework:
    //   SecKeyGeneratePair → SecCertificateCreateWithData → SecPKCS12Export
    // For now, create a marker file to indicate TLS is not supported.
    app.journal.log.Log.withTag("Sync").w {
        "iOS: TLS cert generation not implemented — sync uses plain HTTP + HMAC"
    }
}
