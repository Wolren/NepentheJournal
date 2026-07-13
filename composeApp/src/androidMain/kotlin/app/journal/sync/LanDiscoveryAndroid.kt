package app.journal.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.journal.NepentheApp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android LAN discovery via NsdManager (Network Service Discovery).
 * Discovers _nepenthe._tcp services on the local WiFi network.
 */
actual class LanDiscovery {
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    actual fun startDiscovery(): Flow<LanDiscoveryEvent> = callbackFlow {
        val context = NepentheApp.appContext
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        trySend(LanDiscoveryEvent.DiscoveryError("Resolve failed: $errorCode"))
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val attrs = serviceInfo.attributes
                        fun attr(key: String): String? =
                            attrs?.get(key)?.let { bytes -> String(bytes, Charsets.UTF_8) }
                        val peer = DiscoveredPeer(
                            deviceId = attr("deviceId"),
                            displayName = serviceInfo.serviceName,
                            host = serviceInfo.host?.hostAddress ?: return,
                            port = serviceInfo.port,
                            isTrusted = false,
                            fingerprint = attr("fingerprint")
                        )
                        trySend(LanDiscoveryEvent.PeerFound(peer))
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                trySend(LanDiscoveryEvent.PeerLost(serviceInfo.serviceName))
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(LanDiscoveryEvent.DiscoveryError("Start failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(LanDiscoveryEvent.DiscoveryError("Stop failed: $errorCode"))
            }
        }

        nsd.discoverServices("_nepenthe._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        discoveryListener = listener

        awaitClose {
            nsd.stopServiceDiscovery(listener)
            discoveryListener = null
        }
    }

    actual fun registerService(port: Int, deviceId: String, fingerprint: String) {
        // NsdManager registration is complex on Android and requires a running service.
        // For now Android devices are discoverable via manual IP entry only.
    }

    actual fun unregisterService() {
        // No-op for Android
    }

    actual fun stop() {
        discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        discoveryListener = null
        nsdManager = null
    }
}
