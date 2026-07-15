package app.journal.sync

import app.journal.log.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Foundation.*

/**
 * iOS LAN discovery via Bonjour (NSNetServiceBrowser).
 * Discovers _nepenthe._tcp services on the local network.
 */
actual class LanDiscovery {
    private var browser: NSNetServiceBrowser? = null

    actual fun startDiscovery(): Flow<LanDiscoveryEvent> = callbackFlow {
        val b = NSNetServiceBrowser()
        browser = b

        val delegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol {
            override fun netServiceBrowserWillSearch(aBrowser: NSNetServiceBrowser) {
                // Discovery started
            }

            override fun netServiceBrowserDidStopSearch(aBrowser: NSNetServiceBrowser) {
                // Discovery stopped
            }

            override fun netServiceBrowser(aBrowser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
                // Resolve the service to get address/port
                val service = didFindService
                service.delegate = object : NSObject(), NSNetServiceDelegateProtocol {
                    override fun netServiceDidResolveAddress(sender: NSNetService) {
                        val addresses = sender.addresses?.firstOrNull() as? NSData
                        val host = sender.hostName ?: return
                        val port = sender.port.toInt()
                        val deviceId = sender.TXTRecordData()?.let { data ->
                            NSNetService.dictionaryFromTXTRecordData(data)
                                ?.get("deviceId".encodeToByteArray())
                                ?.let { bytes -> bytes.decodeToString() }
                        }
                        val fingerprint = sender.TXTRecordData()?.let { data ->
                            NSNetService.dictionaryFromTXTRecordData(data)
                                ?.get("fingerprint".encodeToByteArray())
                                ?.let { bytes -> bytes.decodeToString() }
                        }
                        trySend(
                            LanDiscoveryEvent.PeerFound(
                                DiscoveredPeer(
                                    deviceId = deviceId,
                                    displayName = sender.name ?: "Unknown",
                                    host = host,
                                    port = port,
                                    isTrusted = fingerprint != null,
                                    fingerprint = fingerprint
                                )
                            )
                        )
                    }

                    override fun netService(sender: NSNetService, didNotResolve: Map<*, *>) {
                        trySend(LanDiscoveryEvent.DiscoveryError("Resolve failed: $didNotResolve"))
                    }
                }
                service.resolveWithTimeout(5.0)
            }

            override fun netServiceBrowser(aBrowser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) {
                trySend(LanDiscoveryEvent.PeerLost(didRemoveService.name ?: "unknown"))
            }

            override fun netServiceBrowser(aBrowser: NSNetServiceBrowser, didNotSearch: Map<*, *>) {
                trySend(LanDiscoveryEvent.DiscoveryError("Search failed: $didNotSearch"))
            }
        }

        b.delegate = delegate
        b.searchForServicesOfType("_nepenthe._tcp", inDomain = "")

        awaitClose {
            b.stop()
            browser = null
        }
    }

    actual fun registerService(port: Int, deviceId: String, fingerprint: String) {
        val data = NSNetService.dictionaryFromTXTRecordData(
            mapOf<Any?, Any?>(
                "deviceId" to deviceId.encodeToByteArray(),
                "fingerprint" to fingerprint.encodeToByteArray()
            )
        )
        val service = NSNetService(
            domain = "",
            type = "_nepenthe._tcp",
            name = "Nepenthe Journal",
            port = port.toLong()
        )
        service.setTXTRecordData(data)
        service.publish()
    }

    actual fun unregisterService() {
        // NSNetServiceBrowser doesn't manage publishing; NSNetService handles its own lifecycle.
    }

    actual fun stop() {
        browser?.stop()
        browser = null
    }
}

private fun ByteArray.decodeToString(): String = StringBuilder().apply {
    for (b in this@decodeToString) append(b.toInt().toChar())
}.toString()
