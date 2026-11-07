package app.journal.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import app.journal.NepentheApp
import app.journal.log.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Android LAN discovery and advertisement via NsdManager.
 *
 * The manager serves one resolve at a time: a concurrent resolve comes back
 * as FAILURE_ALREADY_ACTIVE, so resolves run through a queue instead of
 * straight from onServiceFound callback to callback.
 */
actual class LanDiscovery {
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val deviceIdByService = ConcurrentHashMap<String, String>()

    actual fun startDiscovery(): Flow<LanDiscoveryEvent> = callbackFlow {
        val nsd = NepentheApp.appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val seen = HashSet<String>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // NsdManager re-reports the same service on every mDNS refresh.
                if (seen.add(serviceInfo.serviceName)) found.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                seen.remove(serviceInfo.serviceName)
                val deviceId = deviceIdByService.remove(serviceInfo.serviceName)
                trySend(LanDiscoveryEvent.PeerLost(deviceId ?: serviceInfo.serviceName))
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(LanDiscoveryEvent.DiscoveryError("Start failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(LanDiscoveryEvent.DiscoveryError("Stop failed: $errorCode"))
            }
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        discoveryListener = listener

        val resolver = launch {
            for (info in found) {
                val peer = resolve(nsd, info) ?: continue
                deviceIdByService[info.serviceName] = peer.deviceId ?: info.serviceName
                trySend(LanDiscoveryEvent.PeerFound(peer))
            }
        }

        awaitClose {
            resolver.cancel()
            found.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
            discoveryListener = null
        }
    }

    /** One queued resolve; null when the peer vanished or never answered, which is routine. */
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): DiscoveredPeer? {
        val resolved = try {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (continuation.isActive) continuation.resume(serviceInfo)
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.withTag("LanDiscovery").w { "Resolve threw: ${e.message}" }
            null
        } ?: return null

        val host = resolved.host?.hostAddress ?: return null
        val attrs = resolved.attributes
        fun attr(key: String): String? = attrs?.get(key)?.let { bytes -> String(bytes, Charsets.UTF_8) }
        return DiscoveredPeer(
            deviceId = attr("deviceId"),
            displayName = resolved.serviceName ?: info.serviceName,
            host = host,
            port = resolved.port,
            isTrusted = false,
            fingerprint = attr("fingerprint")
        )
    }

    /** Advertises this device so a desktop host can find the phone instead of typing its address. */
    actual fun registerService(port: Int, deviceId: String, fingerprint: String) {
        val nsd = NepentheApp.appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd
        unregisterService()
        val info = NsdServiceInfo().apply {
            serviceName = platformDeviceName()
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("deviceId", deviceId)
            setAttribute("fingerprint", fingerprint)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.withTag("LanDiscovery").i { "Advertising as ${serviceInfo.serviceName} on port $port" }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.withTag("LanDiscovery").w { "Service registration failed: $errorCode" }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.withTag("LanDiscovery").w { "Service unregistration failed: $errorCode" }
            }
        }
        registrationListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.withTag("LanDiscovery").w { "registerService threw: ${it.message}" } }
    }

    actual fun unregisterService() {
        val nsd = nsdManager
        val listener = registrationListener
        registrationListener = null
        if (nsd != null && listener != null) {
            runCatching { nsd.unregisterService(listener) }
        }
    }

    actual fun stop() {
        val nsd = nsdManager
        val listener = discoveryListener
        discoveryListener = null
        if (nsd != null && listener != null) {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        unregisterService()
        nsdManager = null
    }

    private companion object {
        const val SERVICE_TYPE = "_nepenthe._tcp"
    }
}
