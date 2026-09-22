package app.journal.sync

import app.journal.util.crypto.sha256

/**
 * iOS HMAC-SHA256 using a pure Kotlin HMAC construction.
 *
 * Avoids cinterop issues with CommonCrypto (CCHmac) across Kotlin/iOS SDK
 * versions. The hash primitive itself is NOT duplicated here: the former
 * private pure-Kotlin sha256 copy was verified identical to the shared
 * one in util/crypto/CryptoIos.kt (both are plain FIPS 180-4 SHA-256 over
 * the whole message: same eight 32-bit initial hash values, same 64 round
 * constants, same MD-style padding, 32-byte digest), so the copy and its
 * rotr helper were deleted and [sha256] now resolves to the shared
 * CommonCrypto CC_SHA256 actual for the commonMain expect.
 * The `actual` keyword makes this the platform implementation for the `expect`
 * declaration in commonMain SyncContract.kt.
 */
actual fun hmacSha256Hex(secret: ByteArray, data: ByteArray): String {
    val hmac = HmacSha256(secret)
    return hmac.digest(data).joinToString("") { b ->
        val v = b.toInt() and 0xFF
        "${hexChars[v shr 4]}${hexChars[v and 0xF]}"
    }
}

private val hexChars = "0123456789abcdef"

// ====================================================================
// Pure-Kotlin HMAC-SHA256
// ====================================================================

private class HmacSha256(key: ByteArray) {
    companion object {
        private const val BLOCK_SIZE = 64 // SHA-256 processes 64-byte blocks
    }

    private val ipad: ByteArray
    private val opad: ByteArray

    init {
        // If key is longer than block size, hash it first
        val k = if (key.size > BLOCK_SIZE) sha256(key) else key.copyOf()
        val padded = k.copyOf(BLOCK_SIZE)
        ipad = ByteArray(BLOCK_SIZE) { (padded[it].toInt() xor 0x36).toByte() }
        opad = ByteArray(BLOCK_SIZE) { (padded[it].toInt() xor 0x5c).toByte() }
    }

    fun digest(data: ByteArray): ByteArray {
        // HMAC(K, m) = H((K' ⊕ opad) || H((K' ⊕ ipad) || m))
        val innerHash = sha256(ipad + data)
        return sha256(opad + innerHash)
    }
}
