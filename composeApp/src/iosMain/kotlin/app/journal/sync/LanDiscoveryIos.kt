package app.journal.sync

import app.journal.log.Log
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Foundation.*

actual class LanDiscovery {
    private var browser: NSNetServiceBrowser? = null

    actual fun startDiscovery(): Flow<LanDiscoveryEvent> = callbackFlow {
        val b = NSNetServiceBrowser()
        browser = b

        @ObjCSignatureOverride
        val delegate = object : NSNetServiceBrowserDelegateProtocol {
            override fun netServiceBrowserWillSearch(aBrowser: NSNetServiceBrowser) {}
            override fun netServiceBrowserDidStopSearch(aBrowser: NSNetServiceBrowser) {}

            override fun netServiceBrowser(
                aBrowser: NSNetServiceBrowser,
                didFindService: NSNetService,
                moreComing: Boolean
            ) {
                val service = didFindService
                service.delegate = object : NSNetServiceDelegateProtocol {
                    override fun netServiceDidResolveAddress(sender: NSNetService) {
                        val host = sender.hostName ?: return
                        val port = sender.port.toInt()
                        val dict = sender.TXTRecordData()?.let { NSNetService.dictionaryFromTXTRecordData(it) }
                        val deviceId = dict?.get("deviceId".encodeToByteArray())
                            ?.let { (it as? ByteArray)?.let(::bytesToHexString) }
                        val fingerprint = dict?.get("fingerprint".encodeToByteArray())
                            ?.let { (it as? ByteArray)?.let(::bytesToHexString) }
                        trySend(LanDiscoveryEvent.PeerFound(
                            DiscoveredPeer(
                                deviceId = deviceId,
                                displayName = sender.name ?: "Unknown",
                                host = host,
                                port = port,
                                isTrusted = fingerprint != null,
                                fingerprint = fingerprint
                            )
                        ))
                    }

                    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
                        trySend(LanDiscoveryEvent.DiscoveryError("Resolve failed: $didNotResolve"))
                    }

                    override fun netServiceDidStop(sender: NSNetService) {}
                    override fun netServiceDidUpdateTXTRecordData(sender: NSNetService) {}
                }
                service.resolveWithTimeout(5.0)
            }

            override fun netServiceBrowser(
                aBrowser: NSNetServiceBrowser,
                didRemoveService: NSNetService,
                moreComing: Boolean
            ) {
                trySend(LanDiscoveryEvent.PeerLost(didRemoveService.name ?: "unknown"))
            }

            override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
                trySend(LanDiscoveryEvent.DiscoveryError("Search failed: $didNotSearch"))
            }
        }

        b.delegate = delegate
        b.searchForServicesOfType("_nepenthe._tcp", inDomain = "")

        awaitClose { b.stop() }
    }

    actual fun registerService(port: Int, deviceId: String, fingerprint: String) {
        val txtDict = mapOf<Any?, Any?>(
            "deviceId" to deviceId.encodeToByteArray(),
            "fingerprint" to fingerprint.encodeToByteArray()
        )
        val data = NSNetService.dataFromTXTRecordDictionary(txtDict)
        val service = NSNetService(domain = "", type = "_nepenthe._tcp", name = "Nepenthe Journal", port = port)
        if (data != null) service.setTXTRecordData(data)
        service.publish()
    }

    actual fun unregisterService() {}
    actual fun stop() {
        browser?.stop()
        browser = null
    }
}

private fun bytesToHexString(bytes: ByteArray): String =
    bytes.joinToString("") { b ->
        val v = b.toInt() and 0xFF
        val hex = "0123456789abcdef"
        "${hex[v shr 4]}${hex[v and 0xF]}"
    }
