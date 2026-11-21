package app.journal.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A lost mDNS service must be reported with the peer's device id.
 *
 * [LanDiscoveryEvent.PeerLost.deviceId] is a device id: the transport removes
 * the discovered peer by matching it against that field. The desktop listener
 * used to send the mDNS service NAME instead, so a vanished phone stayed in
 * the peer list and could be tapped after it left the network.
 */
class LanDiscoveryLossTest {

    @Test
    fun `peer lost carries the device id`() = runBlocking {
        val discovery = LanDiscovery()
        val events = mutableListOf<LanDiscoveryEvent>()
        val collector = launch { discovery.startDiscovery().collect { events += it } }
        val deviceId = "device-mdns-loss-${System.nanoTime()}"
        try {
            delay(750)
            discovery.registerService(41523, deviceId, "fingerprint-mdns")
            withTimeout(20_000) {
                while (events.none { it is LanDiscoveryEvent.PeerFound && it.peer.deviceId == deviceId }) {
                    delay(100)
                }
            }

            discovery.unregisterService()
            withTimeout(20_000) {
                while (events.none { it is LanDiscoveryEvent.PeerLost }) {
                    delay(100)
                }
            }
            val lost = events.filterIsInstance<LanDiscoveryEvent.PeerLost>().first()
            assertEquals(
                deviceId,
                lost.deviceId,
                "PeerLost must carry the device id the peer list is keyed by, not the mDNS service name",
            )
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (events.none { it is LanDiscoveryEvent.PeerFound }) {
                fail("mDNS did not deliver the locally registered service; cannot judge PeerLost: $events")
            }
            throw e
        } finally {
            discovery.unregisterService()
            collector.cancel()
            discovery.stop()
        }
    }
}
