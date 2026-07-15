package app.journal.sync

import kotlin.test.*

class SyncAuthenticatorTest {

    private val trustStore = DeviceTrustStore(System.getProperty("java.io.tmpdir") + "/sync-auth-${System.nanoTime()}").also {
        DeviceTrustStore.pbkdf2Iterations = 1000
    }
    private val authenticator = SyncAuthenticator(trustStore)

    @AfterTest
    fun cleanup() {
        trustStore.clearAll()
        authenticator.clearSeenNonces()
        authenticator.clearPendingPairing()
    }

    // ==================== Pairing tokens ====================

    @Test
    fun generatePairingTokenReturns6CharToken() {
        val token = authenticator.generatePairingToken()
        assertEquals(6, token.length)
        // Should only contain chars from the allowed alphabet
        assertTrue(token.all { it in "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" })
    }

    @Test
    fun verifyPairingTokenSucceedsForValidToken() {
        val token = authenticator.generatePairingToken(60L)
        assertTrue(authenticator.verifyPairingToken(token))
    }

    @Test
    fun verifyPairingTokenFailsForWrongToken() {
        authenticator.generatePairingToken(60L)
        assertFalse(authenticator.verifyPairingToken("WRONG1"))
    }

    @Test
    fun pairingTokenIsSingleUse() {
        val token = authenticator.generatePairingToken(60L)
        assertTrue(authenticator.verifyPairingToken(token))
        assertFalse(authenticator.verifyPairingToken(token))
    }

    @Test
    fun expiredTokenIsRejected() {
        // Generate with 0 TTL so it's immediately expired
        val token = authenticator.generatePairingToken(0L)
        assertFalse(authenticator.verifyPairingToken(token))
    }

    // ==================== HMAC signing and verification ====================

    @Test
    fun signAndVerifyRoundtrip() {
        // First pair a device
        val deviceId = "test-device-1"
        val secret = authenticator.generateSharedSecret()
        trustStore.addPeer(DeviceTrustStore.TrustedPeer(
            deviceId = deviceId, displayName = "Test",
            fingerprint = "test-fp", sharedSecret = secret,
            pairedAt = System.currentTimeMillis()
        ))

        val body = """{"test":"data","number":42}"""
        val authHeader = authenticator.signRequest(deviceId, body, secret)
        val parts = authHeader.split(":")
        assertEquals(3, parts.size, "auth header should be timestamp:nonce:signature")
        assertTrue(parts[0].toLongOrNull() != null, "timestamp should be a number")
        assertEquals(32, parts[1].length, "nonce should be 32 hex chars (16 bytes)")
        assertEquals(64, parts[2].length, "signature should be 64 hex chars (SHA-256)")
        assertTrue(authenticator.verifyRequest(deviceId, body, authHeader))
    }

    @Test
    fun verifyFailsForWrongDevice() {
        val deviceId = "test-device-1"
        val secret = authenticator.generateSharedSecret()
        trustStore.addPeer(DeviceTrustStore.TrustedPeer(
            deviceId = deviceId, displayName = "Test",
            fingerprint = "test-fp", sharedSecret = secret,
            pairedAt = System.currentTimeMillis()
        ))

        val body = "test"
        val authHeader = authenticator.signRequest(deviceId, body, secret)
        assertFalse(authenticator.verifyRequest("wrong-device", body, authHeader))
    }

    @Test
    fun verifyFailsForTamperedBody() {
        val deviceId = "test-device-1"
        val secret = authenticator.generateSharedSecret()
        trustStore.addPeer(DeviceTrustStore.TrustedPeer(
            deviceId = deviceId, displayName = "Test",
            fingerprint = "test-fp", sharedSecret = secret,
            pairedAt = System.currentTimeMillis()
        ))

        val body = "original body"
        val authHeader = authenticator.signRequest(deviceId, body, secret)
        assertFalse(authenticator.verifyRequest(deviceId, "tampered body", authHeader))
    }

    @Test
    fun verifyFailsForMalformedAuthHeader() {
        assertFalse(authenticator.verifyRequest("any", "body", "not-enough-parts"))
        assertFalse(authenticator.verifyRequest("any", "body", "a:b"))
        assertFalse(authenticator.verifyRequest("any", "body", ""))
    }

    @Test
    fun verifyFailsWhenDeviceNotTrusted() {
        val body = "test"
        val authHeader = "1234567890:abcdef:signature"
        assertFalse(authenticator.verifyRequest("untrusted-device", body, authHeader))
    }

    // ==================== Nonce replay protection ====================

    @Test
    fun sameNonceIsRejectedOnSecondUse() {
        val deviceId = "test-replay"
        val secret = authenticator.generateSharedSecret()
        trustStore.addPeer(DeviceTrustStore.TrustedPeer(
            deviceId = deviceId, displayName = "Replay",
            fingerprint = "replay-fp", sharedSecret = secret,
            pairedAt = System.currentTimeMillis()
        ))

        val body = "test body"
        val authHeader = authenticator.signRequest(deviceId, body, secret)

        // First use should pass
        assertTrue(authenticator.verifyRequest(deviceId, body, authHeader))
        // Same auth header (same nonce + timestamp) should fail
        assertFalse(authenticator.verifyRequest(deviceId, body, authHeader))
    }

    // ==================== Shared secrets ====================

    @Test
    fun sharedSecretIs64HexChars() {
        val secret = authenticator.generateSharedSecret()
        assertEquals(64, secret.length)
        assertTrue(secret.all { it in "0123456789abcdef" })
    }

    @Test
    fun sharedSecretsAreRandom() {
        val s1 = authenticator.generateSharedSecret()
        val s2 = authenticator.generateSharedSecret()
        assertNotEquals(s1, s2)
    }

    // ==================== Pairing response ====================

    @Test
    fun pairingResponseRoundtrip() {
        val deviceId = "test-pairing"
        val secret = authenticator.generateSharedSecret()
        trustStore.addPeer(DeviceTrustStore.TrustedPeer(
            deviceId = deviceId, displayName = "Pair",
            fingerprint = "pair-fp", sharedSecret = secret,
            pairedAt = System.currentTimeMillis()
        ))

        val header = authenticator.signPairingResponse(deviceId, secret)
        assertTrue(authenticator.verifyPairingResponse(deviceId, header, secret))
    }

    @Test
    fun pairingResponseFailsForWrongSecret() {
        val deviceId = "test-wrong-secret"
        val secret = authenticator.generateSharedSecret()
        val wrongSecret = authenticator.generateSharedSecret()
        trustStore.addPeer(DeviceTrustStore.TrustedPeer(
            deviceId = deviceId, displayName = "Wrong",
            fingerprint = "wrong-fp", sharedSecret = secret,
            pairedAt = System.currentTimeMillis()
        ))

        val header = authenticator.signPairingResponse(deviceId, secret)
        assertFalse(authenticator.verifyPairingResponse(deviceId, header, wrongSecret))
    }

    // ==================== Constants ====================

    @Test
    fun maxSyncBodyBytesIsReasonable() {
        assertTrue(SyncAuthenticator.MAX_SYNC_BODY_BYTES >= 1_000_000, "should allow at least 1MB payloads")
        assertTrue(SyncAuthenticator.MAX_SYNC_BODY_BYTES <= 100_000_000, "should not exceed 100MB")
    }
}
