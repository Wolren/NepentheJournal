package app.journal.sync

import app.journal.log.Log
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ObjCClass
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
        b.delegate = NetServiceBrowserDelegate(this@callbackFlow)
        b.searchForServicesOfType("_nepenthe._tcp", inDomain = "")
        awaitClose { b.stop() }
    }

    private class NetServiceBrowserDelegate(
        private val flow: kotlinx.coroutines.channels.SendChannel<LanDiscoveryEvent>
    ) : NSNetServiceBrowserDelegateProtocol {
        override fun isEqual(object: Any?): Boolean = false
        override fun `class`(): ObjCClass? = null
        override fun performSelector(aSelector: CPointer<out CPointed>?): Any? = null
        override fun performSelector(aSelector: CPointer<out CPointed>?, withObject: Any?): Any? = null
        override fun performSelector(aSelector: CPointer<out CPointed>?, withObject: Any?, _withObject: Any?): Any? = null

        override fun netServiceBrowserWillSearch(aBrowser: NSNetServiceBrowser) {}
        override fun netServiceBrowserDidStopSearch(aBrowser: NSNetServiceBrowser) {}

        @ObjCSignatureOverride
        override fun netServiceBrowser(aBrowser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
            didFindService.delegate = NetServiceDelegate(flow)
            didFindService.resolveWithTimeout(5.0)
        }

        @ObjCSignatureOverride
        override fun netServiceBrowser(aBrowser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) {
            flow.trySend(LanDiscoveryEvent.PeerLost(didRemoveService.name ?: "unknown"))
        }

        override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
            flow.trySend(LanDiscoveryEvent.DiscoveryError("Search failed: $didNotSearch"))
        }
    }

    private class NetServiceDelegate(
        private val flow: kotlinx.coroutines.channels.SendChannel<LanDiscoveryEvent>
    ) : NSNetServiceDelegateProtocol {
        override fun isEqual(object: Any?): Boolean = false
        override fun `class`(): ObjCClass? = null
        override fun performSelector(aSelector: CPointer<out CPointed>?): Any? = null
        override fun performSelector(aSelector: CPointer<out CPointed>?, withObject: Any?): Any? = null
        override fun performSelector(aSelector: CPointer<out CPointed>?, withObject: Any?, _withObject: Any?): Any? = null

        override fun netServiceDidResolveAddress(sender: NSNetService) {
            val host = sender.hostName ?: return
            val port = sender.port.toInt()
            val dict = sender.TXTRecordData()?.let { NSNetService.dictionaryFromTXTRecordData(it) }
            val deviceId = dict?.get("deviceId".encodeToByteArray())
                ?.let { (it as? ByteArray)?.let(::bytesToHexString) }
            val fingerprint = dict?.get("fingerprint".encodeToByteArray())
                ?.let { (it as? ByteArray)?.let(::bytesToHexString) }
            flow.trySend(LanDiscoveryEvent.PeerFound(
                DiscoveredPeer(deviceId = deviceId, displayName = sender.name ?: "Unknown",
                    host = host, port = port, isTrusted = fingerprint != null, fingerprint = fingerprint)
            ))
        }

        override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
            flow.trySend(LanDiscoveryEvent.DiscoveryError("Resolve failed: $didNotResolve"))
        }

        override fun netServiceDidStop(sender: NSNetService) {}
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
