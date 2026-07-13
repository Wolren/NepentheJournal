package app.journal.sync

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Desktop LAN discovery via JmDNS (mDNS).
 * Listens for _nepenthe._tcp.local. services on the local network.
 */
actual class LanDiscovery {
    private var jmdns: JmDNS? = null
    private val serviceType = "_nepenthe._tcp.local."

    actual fun startDiscovery(): Flow<LanDiscoveryEvent> = callbackFlow {
        val mdns = JmDNS.create()
        jmdns = mdns

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                mdns.requestServiceInfo(event.type, event.name)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                trySend(LanDiscoveryEvent.PeerLost(event.name))
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.hostAddress ?: return
                val port = info.port
                val deviceId = info.getPropertyString("deviceId")
                val fingerprint = info.getPropertyString("fingerprint")
                trySend(
                    LanDiscoveryEvent.PeerFound(
                        DiscoveredPeer(
                            deviceId = deviceId,
                            displayName = info.name,
                            host = host,
                            port = port,
                            isTrusted = fingerprint != null,
                            fingerprint = fingerprint
                        )
                    )
                )
            }
        }

        mdns.addServiceListener(serviceType, listener)

        awaitClose {
            mdns.close()
            jmdns = null
        }
    }

    actual fun registerService(port: Int, deviceId: String, fingerprint: String) {
        val info = ServiceInfo.create(
            serviceType,
            "Nepenthe Journal",
            port, 0, 0,
            mapOf("deviceId" to deviceId, "fingerprint" to fingerprint)
        )
        jmdns?.registerService(info)
    }

    actual fun unregisterService() {
        jmdns?.unregisterAllServices()
    }

    actual fun stop() {
        jmdns?.close()
        jmdns = null
    }
}
