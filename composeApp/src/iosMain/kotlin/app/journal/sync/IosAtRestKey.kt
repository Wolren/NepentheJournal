package app.journal.sync

import app.journal.log.Log
import app.journal.util.crypto.secureRandomBytes
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.*
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * Random 32-byte at-rest key backed by the iOS Keychain.
 *
 * iOS equivalent of the JVM AtRestKey file: the key lives in the Keychain
 * (kSecClassGenericPassword, this-device-only, available after first
 * unlock so background sync can read it) instead of a file, and encrypts
 * the trust and identity stores with AES-256-GCM. The Keychain entry is
 * app namespaced by the OS, so no access group is needed.
 *
 * When the Keychain is unavailable (error -25291, and in unit-test bundles no
 * keychain exists) the key falls back to a 32-byte file inside the app
 * container, which has the same protection class as the desktop key file.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAtRestKey(
    private val service: String,
    private val account: String = "at-rest-key",
    private val fallbackFile: String? = null
) {

    /** Raised when no Keychain exists at all, which is the only case that may fall back. */
    private class KeychainUnavailable(message: String) : IllegalStateException(message)

    @kotlin.concurrent.Volatile
    private var cached: ByteArray? = null

    @Throws(IllegalStateException::class)
    fun loadOrCreate(): ByteArray {
        cached?.let { return it }
        try {
            loadFromKeychain()?.let {
                cached = it
                return it
            }
            val fresh = secureRandomBytes(KEY_BYTES)
            saveToKeychain(fresh)
            cached = fresh
            return fresh
        } catch (e: KeychainUnavailable) {
            Log.withTag("IosAtRestKey").w { "${e.message}; using $fallbackFile" }
            return loadOrCreateFallback()
        }
    }

    fun loadOrNull(): ByteArray? {
        cached?.let { return it }
        return try {
            loadFromKeychain()?.also { cached = it }
        } catch (e: KeychainUnavailable) {
            loadOrCreateFallback().also { cached = it }
        } catch (e: Exception) {
            Log.withTag("IosAtRestKey").w { "Keychain read failed: ${e.message}" }
            null
        }
    }

    /** Container-file key for hosts with no Keychain at all (test bundles). */
    private fun loadOrCreateFallback(): ByteArray {
        val path = fallbackFile ?: throw IllegalStateException("No keychain and no fallback file for $service")
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(path)) {
            val data = NSData.dataWithContentsOfFile(path)
            if (data != null && data.length.toInt() == KEY_BYTES) {
                val bytes = ByteArray(KEY_BYTES)
                bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, KEY_BYTES.toULong()) }
                return bytes
            }
        }
        val fresh = secureRandomBytes(KEY_BYTES)
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && !manager.fileExistsAtPath(parent)) {
            manager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val data = fresh.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = KEY_BYTES.toULong())
        }
        if (!data.writeToFile(path, atomically = true)) {
            throw IllegalStateException("Cannot write fallback key file: $path")
        }
        return fresh
    }

    private fun loadFromKeychain(): ByteArray? = memScoped {
        val owned = mutableListOf<COpaquePointer?>()
        val query = CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw IllegalStateException("Cannot create Keychain query")
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, cfString(service, this, owned))
            CFDictionaryAddValue(query, kSecAttrAccount, cfString(account, this, owned))
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            val result = alloc<CFTypeRefVar>()
            when (val status = SecItemCopyMatching(query, result.ptr)) {
                errSecSuccess -> {
                    val data = result.value as? CFDataRef
                        ?: throw IllegalStateException("Keychain returned no data")
                    try {
                        val len = CFDataGetLength(data).toInt()
                        if (len != KEY_BYTES) {
                            throw IllegalStateException("Keychain key has bad length $len")
                        }
                        val out = ByteArray(len)
                        out.usePinned { pinned ->
                            memcpy(pinned.addressOf(0), CFDataGetBytePtr(data), len.toULong())
                        }
                        out
                    } finally {
                        CFRelease(data)
                    }
                }
                errSecItemNotFound -> null
                ERR_SEC_NOT_AVAILABLE, ERR_SEC_MISSING_ENTITLEMENT ->
                    throw KeychainUnavailable("Keychain read failed with status $status")
                else -> throw IllegalStateException("Keychain read failed with status $status")
            }
        } finally {
            CFRelease(query)
            owned.forEach { CFRelease(it) }
        }
    }

    @Throws(IllegalStateException::class)
    private fun saveToKeychain(key: ByteArray) = memScoped {
        val owned = mutableListOf<COpaquePointer?>()
        val addDict = CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw IllegalStateException("Cannot create Keychain entry")
        try {
            CFDictionaryAddValue(addDict, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(addDict, kSecAttrService, cfString(service, this, owned))
            CFDictionaryAddValue(addDict, kSecAttrAccount, cfString(account, this, owned))
            val cfData = key.usePinned { pinned ->
                CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), key.size.toLong())
            } ?: throw IllegalStateException("Cannot create Keychain data")
            try {
                CFDictionaryAddValue(addDict, kSecValueData, cfData)
                CFDictionaryAddValue(
                    addDict,
                    kSecAttrAccessible,
                    kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                )
                when (val status = SecItemAdd(addDict, null)) {
                    errSecSuccess -> Unit
                    errSecDuplicateItem -> updateKeychain(key)
                    ERR_SEC_NOT_AVAILABLE, ERR_SEC_MISSING_ENTITLEMENT ->
                        throw KeychainUnavailable("Keychain write failed with status $status")
                    else -> throw IllegalStateException("Keychain write failed with status $status")
                }
            } finally {
                CFRelease(cfData)
            }
        } finally {
            CFRelease(addDict)
            owned.forEach { CFRelease(it) }
        }
    }

    @Throws(IllegalStateException::class)
    private fun updateKeychain(key: ByteArray) = memScoped {
        val owned = mutableListOf<COpaquePointer?>()
        val query = CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw IllegalStateException("Cannot create Keychain update query")
        val update = CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw IllegalStateException("Cannot create Keychain update")
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, cfString(service, this, owned))
            CFDictionaryAddValue(query, kSecAttrAccount, cfString(account, this, owned))
            val cfData = key.usePinned { pinned ->
                CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), key.size.toLong())
            } ?: throw IllegalStateException("Cannot create Keychain data")
            try {
                CFDictionaryAddValue(update, kSecValueData, cfData)
                val status = SecItemUpdate(query, update)
                if (status != errSecSuccess) {
                    throw IllegalStateException("Keychain update failed with status $status")
                }
            } finally {
                CFRelease(cfData)
            }
        } finally {
            CFRelease(query)
            CFRelease(update)
            owned.forEach { CFRelease(it) }
        }
    }

    private fun cfString(value: String, scope: MemScope, owned: MutableList<COpaquePointer?>): COpaquePointer? {
        val created = CFStringCreateWithCString(
            null,
            value,
            kCFStringEncodingUTF8
        ) ?: throw IllegalStateException("Cannot encode Keychain string")
        owned.add(created)
        return created
    }

    companion object {
        const val KEY_BYTES = 32
        /** errSecNotAvailable: no keychain in this host. */
        private const val ERR_SEC_NOT_AVAILABLE = -25291
        /** errSecMissingEntitlement: the bundle carries no keychain entitlement. */
        private const val ERR_SEC_MISSING_ENTITLEMENT = -34018
    }
}
