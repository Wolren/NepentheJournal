package app.journal.sync

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UInt8Var
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanFalse
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSNumber
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateKeyExchange
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecPublicKeyAttrs
import platform.posix.memcpy

/**
 * iOS P-256 ECDH key agreement (contract section g, docs/HARDENING-CONTRACTS-2026-09.md).
 *
 * Wire spec (pinned in commonMain [PairingEcdh]): NIST P-256, public keys
 * encoded as the ANSI X9.62 uncompressed point 0x04 || X || Y (65 bytes),
 * standard base64. The host holds ONE STATIC keypair persisted by
 * [IosEcdhIdentityStore]; clients generate a fresh EPHEMERAL keypair per
 * pairing attempt ([generateEphemeral]) and discard it afterwards.
 *
 * Key agreement runs through the Security framework:
 * SecKeyCreateWithData imports a peer point and REJECTS points that are not
 * on the curve (the import returns null), which is the on-curve check the
 * contract requires; SecKeyCreateKeyExchange performs the agreement and may
 * return either the 32-byte X coordinate or the 65-byte X9.63 form, both of
 * which [PairingEcdh.normalizeSharedSecret] accepts.
 *
 * Nothing in this file ever logs key material.
 *
 * CF memory rules used here (same discipline as IosAtRestKey and
 * IosDeviceIdentityStore): dictionaries are created with null callbacks, so
 * values are NOT retained by the dictionary; every created value stays alive
 * in `owned` until after the SecKey call, then everything is released once.
 */
@OptIn(ExperimentalForeignApi::class)
object IosEcdh {

    /** Generate a fresh non-permanent P-256 keypair; returns the private key. */
    fun generateKey(): SecKeyRef? = memScoped {
        val attrs = CFDictionaryCreateMutable(null, 0, null, null) ?: return@memScoped null
        val owned = mutableListOf<COpaquePointer?>()
        try {
            CFDictionaryAddValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            // Key type and size are the only required generation attributes
            // (Apple: "Key Generation Attributes").
            val sizeBits = CFBridgingRetain(NSNumber(int = KEY_BITS)) ?: return@memScoped null
            owned.add(sizeBits)
            CFDictionaryAddValue(attrs, kSecAttrKeySizeInBits, sizeBits)
            // kSecAttrIsPermanent defaults to true; force false for BOTH keys:
            // contract g persists the static key as a sandboxed file and the
            // client key is ephemeral, so nothing may land in the Keychain.
            val privateAttrs = CFDictionaryCreateMutable(null, 0, null, null) ?: return@memScoped null
            owned.add(privateAttrs)
            CFDictionaryAddValue(privateAttrs, kSecAttrIsPermanent, kCFBooleanFalse)
            val publicAttrs = CFDictionaryCreateMutable(null, 0, null, null) ?: return@memScoped null
            owned.add(publicAttrs)
            CFDictionaryAddValue(publicAttrs, kSecAttrIsPermanent, kCFBooleanFalse)
            CFDictionaryAddValue(attrs, kSecPrivateKeyAttrs, privateAttrs)
            CFDictionaryAddValue(attrs, kSecPublicKeyAttrs, publicAttrs)
            SecKeyCreateRandomKey(attrs, null)
        } finally {
            CFRelease(attrs)
            owned.forEach { CFRelease(it) }
        }
    }

    /** Client side: a fresh ephemeral keypair for ONE pairing attempt. */
    fun generateEphemeral(): IosEcdhEphemeral? {
        val key = generateKey() ?: return null
        return IosEcdhEphemeral(key)
    }

    /**
     * Public point of [privateKey] as base64(0x04 || X || Y). Returns null
     * when the key has no exportable public point or the bytes are not a
     * well-formed uncompressed point.
     */
    fun exportPublicKeyB64(privateKey: SecKeyRef): String? {
        val publicKey = SecKeyCopyPublicKey(privateKey) ?: return null
        return try {
            val cfData = SecKeyCopyExternalRepresentation(publicKey, null) ?: return null
            val bytes = try {
                cfDataToBytes(cfData)
            } finally {
                CFRelease(cfData)
            }
            if (bytes.size != PairingEcdh.PUBLIC_KEY_BYTES ||
                bytes[0] != PairingEcdh.UNCOMPRESSED_PREFIX
            ) {
                null
            } else {
                base64Encode(bytes)
            }
        } finally {
            CFRelease(publicKey)
        }
    }

    /**
     * Private key external representation, base64. This is the exact format
     * [importPrivateKey] restores, so the file round trip is lossless.
     */
    fun exportPrivateKeyB64(privateKey: SecKeyRef): String? {
        val cfData = SecKeyCopyExternalRepresentation(privateKey, null) ?: return null
        val bytes = try {
            cfDataToBytes(cfData)
        } finally {
            CFRelease(cfData)
        }
        if (bytes.isEmpty()) return null
        return base64Encode(bytes)
    }

    /**
     * Import an UNCOMPRESSED P-256 public point (65 bytes, 0x04 prefix).
     * Returns null for any malformed input; SecKeyCreateWithData also rejects
     * points that are not on the curve, which is the contract's on-curve check.
     */
    fun importPublicKey(rawBytes: ByteArray): SecKeyRef? {
        if (rawBytes.size != PairingEcdh.PUBLIC_KEY_BYTES ||
            rawBytes[0] != PairingEcdh.UNCOMPRESSED_PREFIX
        ) return null
        return memScoped {
            val cfData = bytesToCfData(rawBytes) ?: return@memScoped null
            val attrs = CFDictionaryCreateMutable(null, 0, null, null)
            if (attrs == null) {
                CFRelease(cfData)
                return@memScoped null
            }
            try {
                CFDictionaryAddValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
                CFDictionaryAddValue(attrs, kSecAttrKeyClass, kSecAttrKeyClassPublic)
                SecKeyCreateWithData(cfData, attrs, null)
            } finally {
                CFRelease(attrs)
                CFRelease(cfData)
            }
        }
    }

    /**
     * Restore a private key previously exported by [exportPrivateKeyB64].
     * Import never makes the key permanent: kSecPrivateKeyAttrs forces
     * kSecAttrIsPermanent to false, matching [generateKey].
     */
    fun importPrivateKey(privateKeyBytes: ByteArray): SecKeyRef? {
        if (privateKeyBytes.isEmpty()) return null
        return memScoped {
            val cfData = bytesToCfData(privateKeyBytes) ?: return@memScoped null
            val attrs = CFDictionaryCreateMutable(null, 0, null, null)
            if (attrs == null) {
                CFRelease(cfData)
                return@memScoped null
            }
            val owned = mutableListOf<COpaquePointer?>()
            try {
                CFDictionaryAddValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
                CFDictionaryAddValue(attrs, kSecAttrKeyClass, kSecAttrKeyClassPrivate)
                val privateAttrs = CFDictionaryCreateMutable(null, 0, null, null)
                if (privateAttrs != null) {
                    owned.add(privateAttrs)
                    CFDictionaryAddValue(privateAttrs, kSecAttrIsPermanent, kCFBooleanFalse)
                    CFDictionaryAddValue(attrs, kSecPrivateKeyAttrs, privateAttrs)
                }
                SecKeyCreateWithData(cfData, attrs, null)
            } finally {
                CFRelease(attrs)
                owned.forEach { CFRelease(it) }
                CFRelease(cfData)
            }
        }
    }

    /**
     * Raw P-256 ECDH between [privateKey] and peer point [peerPublicBytes].
     * Returns null when the peer point will not import (malformed or off
     * curve) or the exchange is unsupported. The result is either the
     * 32-byte X coordinate or the 65-byte X9.63 form; pass it to
     * [PairingEcdh.wrapSharedSecret]/[PairingEcdh.unwrapSharedSecret],
     * which normalize both shapes.
     */
    fun agreeRaw(privateKey: SecKeyRef, peerPublicBytes: ByteArray): ByteArray? {
        val peerKey = importPublicKey(peerPublicBytes) ?: return null
        val sharedCfData = try {
            SecKeyCreateKeyExchange(
                privateKey,
                peerKey,
                kSecKeyAlgorithmECDHKeyExchangeStandard,
                null,
                null
            )
        } finally {
            CFRelease(peerKey)
        }
        val shared = sharedCfData ?: return null
        return try {
            cfDataToBytes(shared)
        } finally {
            CFRelease(shared)
        }
    }

    private fun cfDataToBytes(cfData: CFDataRef): ByteArray {
        val len = CFDataGetLength(cfData).toInt()
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), CFDataGetBytePtr(cfData), len.toULong())
        }
        return out
    }

    private fun bytesToCfData(bytes: ByteArray): CFDataRef? {
        if (bytes.isEmpty()) return null
        return bytes.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret<UInt8Var>(), bytes.size.toLong())
        }
    }

    companion object {
        /** P-256 key size in bits. */
        const val KEY_BITS = 256
    }
}

/**
 * One client-side EPHEMERAL P-256 keypair for a single pairing attempt
 * (contract g: fresh per attempt, discarded afterwards). Wraps the native
 * SecKey so callers outside [IosEcdh] handle only base64 and byte arrays;
 * call [close] exactly once when the attempt ends ([close] is idempotent).
 */
@OptIn(ExperimentalForeignApi::class)
class IosEcdhEphemeral internal constructor(
    private val privateKey: SecKeyRef
) {
    @kotlin.concurrent.Volatile
    private var closed = false

    /** Base64 uncompressed point to send in PairingVerifyRequest.clientEcdhPublicKeyB64. */
    fun publicKeyB64(): String? = IosEcdh.exportPublicKeyB64(privateKey)

    /** Raw ECDH output against the host's STATIC public key. */
    fun agreeWith(hostPublicKeyBytes: ByteArray): ByteArray? =
        IosEcdh.agreeRaw(privateKey, hostPublicKeyBytes)

    /** Release the native key. Idempotent; safe from a finally block. */
    fun close() {
        if (closed) return
        closed = true
        CFRelease(privateKey)
    }
}
