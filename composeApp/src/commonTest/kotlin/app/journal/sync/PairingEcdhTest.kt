package app.journal.sync

import kotlin.test.*

/**
 * Tests for the shared ECDH pairing helpers (PairingEcdh, contract section g).
 *
 * The key agreement itself is platform code; what must be byte-identical
 * across platforms is the KDF, the normalization rules, and the seal format,
 * and those live here in commonMain.
 */
class PairingEcdhTest {

    // Any 32 bytes work as a stand-in for the raw ECDH X coordinate.
    private val sharedSecretX = ByteArray(32) { it.toByte() }
    private val otherSharedSecretX = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `wrap and unwrap round-trip`() {
        val sealed = PairingEcdh.wrapSharedSecret(sharedSecretX, "hex-secret-64-chars")
        assertEquals("hex-secret-64-chars", PairingEcdh.unwrapSharedSecret(sharedSecretX, sealed))
    }

    @Test
    fun `65-byte X9 point shared secret normalizes to the same key`() {
        // X9.63 form: 0x04 || X || Y (65 bytes); only X feeds the KDF.
        val prefixed = byteArrayOf(PairingEcdh.UNCOMPRESSED_PREFIX) + sharedSecretX + ByteArray(32) { 9 }
        assertContentEquals(sharedSecretX, PairingEcdh.normalizeSharedSecret(prefixed))

        val sealedWithX = PairingEcdh.wrapSharedSecret(sharedSecretX, "secret-a")
        val sealedWithPoint = PairingEcdh.wrapSharedSecret(prefixed, "secret-a")
        // Seals are randomized (IV prefix), so compare by cross-unwrapping:
        // both forms must derive the same key.
        assertEquals("secret-a", PairingEcdh.unwrapSharedSecret(prefixed, sealedWithX))
        assertEquals("secret-a", PairingEcdh.unwrapSharedSecret(sharedSecretX, sealedWithPoint))
    }

    @Test
    fun `wrong shared secret cannot unseal`() {
        val sealed = PairingEcdh.wrapSharedSecret(sharedSecretX, "secret-b")
        assertFails { PairingEcdh.unwrapSharedSecret(otherSharedSecretX, sealed) }
    }

    @Test
    fun `malformed shared secret lengths are rejected`() {
        assertFails { PairingEcdh.normalizeSharedSecret(ByteArray(31)) }
        assertFails { PairingEcdh.normalizeSharedSecret(ByteArray(65)) } // wrong prefix byte
    }

    @Test
    fun `wire field names match the pinned contract`() {
        assertEquals("ecdhPublicKeyB64", PairingEcdh.HOST_PUBLIC_KEY_FIELD)
        assertEquals("clientEcdhPublicKeyB64", PairingEcdh.CLIENT_PUBLIC_KEY_FIELD)
        assertEquals("ecdhSecretB64", PairingEcdh.SEALED_SECRET_FIELD)
        assertEquals("nepenthe-pairing-ecdh-v1", PairingEcdh.KDF_INFO)
        assertEquals(65, PairingEcdh.PUBLIC_KEY_BYTES)
        assertEquals(32, PairingEcdh.SHARED_SECRET_BYTES)
    }

    @Test
    fun `URL scheme constant stays pinned to http under the ECDH contract`() {
        assertEquals("http", SyncEndpoints.URL_SCHEME)
    }

    @Test
    fun `HostInfo defaults keep old peers decodable in both directions`() {
        val legacyJson = """{"deviceId":"h","deviceName":"PC","fingerprint":"fp","protocolVersion":2}"""
        val decoded = app.journal.data.AppJson.json.decodeFromString<HostInfo>(legacyJson)
        assertFalse(decoded.wsSupported, "absent wsSupported must decode as false")
        assertNull(decoded.ecdhPublicKeyB64)

        val modern = decoded.copy(wsSupported = true, ecdhPublicKeyB64 = "base64key")
        val roundTrip = app.journal.data.AppJson.json.decodeFromString<HostInfo>(
            app.journal.data.AppJson.json.encodeToString(modern)
        )
        assertTrue(roundTrip.wsSupported)
        assertEquals("base64key", roundTrip.ecdhPublicKeyB64)
    }
}
