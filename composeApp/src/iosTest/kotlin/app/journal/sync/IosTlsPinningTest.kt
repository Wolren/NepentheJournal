package app.journal.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class IosTlsPinningTest {

    @Test
    fun baseUrlUsesTheAdvertisedTlsPort() {
        assertEquals("https://host:9443", syncBaseUrl("host", 8080, 9443))
        assertEquals("http://host:8080", syncBaseUrl("host", 8080, 0))
    }

    @Test
    fun certificateFingerprintIsLowercaseHex() {
        assertEquals("00ff10", byteArrayOf(0, -1, 0x10).toHexLower())
    }

    @Test
    fun legacyTrustedPeerDefaultsToHttp() {
        val peer = IosDeviceTrustStore.IosTrustedPeer(
            deviceId = "d", displayName = "Desktop", fingerprint = "f",
            sharedSecret = "s", pairedAt = 0L
        )
        assertEquals(0, peer.tlsPort)
    }
}
