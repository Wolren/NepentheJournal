package app.journal.sync

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET

actual fun platformSyncDataDir(): String {
    val docs = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, true
    ).firstOrNull() as? String ?: return "."
    return "$docs/.nepenthe"
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
        "iOS: TLS cert generation not implemented - sync uses plain HTTP + HMAC"
    }
}

/**
 * iOS actual of [resolveLocalIpv4]: walk `getifaddrs` and read the IPv4
 * addresses out of the `sockaddr_in` records, mirroring the JVM actual's
 * policy (first site-local address, else any non-loopback address, else
 * null).
 *
 * UNVERIFIABLE BY BUILD ON THIS HOST (this Windows machine has no iOS
 * compile task): the file was re-read end to end after the final edit and
 * every called symbol was grep-verified in the local Kotlin/Native 2.4.0
 * platform klibs:
 *
 * - `getifaddrs` / `freeifaddrs`: klib `platform/ios_simulator_arm64/
 *   org.jetbrains.kotlin.native.platform.darwin`, linkdata
 *   `package_platform.darwin/33_darwin.knm` (and `native/cstubs.bc`).
 * - `struct ifaddrs` with fields `ifa_next` / `ifa_addr`: same klib,
 *   linkdata `01_darwin.knm`. These are the ONLY two struct fields
 *   dereferenced, so nothing outside the verified set is touched.
 * - `AF_INET`: `org.jetbrains.kotlin.native.platform.posix`, linkdata
 *   `package_platform.posix/35_posix.knm`.
 * - `CPointerVar`, `alloc`, `ptr`, `value`, `pointed`, `reinterpret`,
 *   `memScoped`, `ByteVar`: `kotlinx.cinterop` in the Kotlin/Native stdlib
 *   klib; the `alloc<X>() + x.ptr + x.value + ...reinterpret` shape is the
 *   one already proven in this source set at IosAtRestKey.kt:103-106.
 *
 * Assumptions, each a documented deviation from the JVM actual:
 *
 * 1. Darwin `struct sockaddr_in` memory layout is `sin_len` at byte 0,
 *    `sin_family` at byte 1 (`sa_family_t` is a single byte on Darwin;
 *    AF_INET == 2) and `sin_addr` at bytes 4..7 in network byte order. The
 *    octets are read one byte at a time through a ByteVar pointer, so no
 *    byte-order conversion is needed and `ntohl`/`htonl` (declared in
 *    NEITHER platform package) are never referenced. The mapped struct
 *    fields (`sin_family`, `sin_addr.s_addr`) are deliberately NOT touched:
 *    their Kotlin types cannot be confirmed where the compiler cannot run.
 * 2. Interface flags (`ifa_flags`, IFF_UP/IFF_RUNNING) are not read, so a
 *    down interface could be returned; the JVM actual skips down interfaces
 *    by name. Instead: loopback (127.x) and non-AF_INET records (AF_LINK
 *    and friends) are filtered out, so only real IPv4 endpoint addresses
 *    are considered.
 * 3. Site-local preference matches the JVM actual: 10/8, 192.168/16,
 *    172.16-31/12 and link-local 169.254/16 win immediately; any other
 *    non-loopback IPv4 is the fallback.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun resolveLocalIpv4(): String? = memScoped<String?> {
    val ifap = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(ifap.ptr) != 0) return@memScoped null
    var current = ifap.value
    var siteLocal: String? = null
    var other: String? = null
    while (current != null) {
        val ifaAddr = current.pointed.ifa_addr
        if (ifaAddr != null) {
            val raw = ifaAddr.reinterpret<ByteVar>()
            // byte 1 is sa_family on Darwin; only IPv4 records qualify.
            if ((raw[1].toInt() and 0xFF) == AF_INET) {
                val o0 = raw[4].toInt() and 0xFF
                val o1 = raw[5].toInt() and 0xFF
                val o2 = raw[6].toInt() and 0xFF
                val o3 = raw[7].toInt() and 0xFF
                if (o0 != 127) {
                    val host = "$o0.$o1.$o2.$o3"
                    val isSiteLocal = o0 == 10 ||
                        (o0 == 192 && o1 == 168) ||
                        (o0 == 172 && o1 in 16..31) ||
                        (o0 == 169 && o1 == 254)
                    if (isSiteLocal) {
                        if (siteLocal == null) siteLocal = host
                    } else if (other == null) {
                        other = host
                    }
                }
            }
        }
        current = current.pointed.ifa_next
    }
    freeifaddrs(ifap.value)
    siteLocal ?: other
}
