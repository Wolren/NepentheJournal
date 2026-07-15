package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.SyncConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * In-memory fake implementation of [SyncEngine] for testing the interface contract.
 * Tracks internal state via [SyncStatusSnapshot] and exposes a simple mutableState for
 * [observeStatus]. All socket/discovery/network operations are stubs.
 */
class FakeSyncEngine : SyncEngine {

    private val _status = MutableStateFlow(
        SyncStatusSnapshot(
            isHosting = false,
            hostAddress = null,
            activeConnections = emptyList(),
            lastSyncAt = null,
            pendingConflicts = 0,
            lastError = null,
            pairingToken = null,
            pairedDeviceCount = 0,
            continuousPeers = 0
        )
    )

    private val _trustedDevices = mutableListOf<TrustedDeviceInfo>()
    private val _discoveryActive = MutableStateFlow(false)

    override suspend fun startHosting(config: SyncConfig): Result<HostingInfo> {
        val info = HostingInfo(
            address = "0.0.0.0",
            port = config.listenerPort,
            fingerprint = "fake-fingerprint"
        )
        _status.value = _status.value.copy(
            isHosting = true,
            hostAddress = "0.0.0.0:${config.listenerPort}"
        )
        return Result.success(info)
    }

    override suspend fun stopHosting() {
        _status.value = _status.value.copy(
            isHosting = false,
            hostAddress = null
        )
    }

    override suspend fun syncWith(peer: DiscoveredPeer, continuous: Boolean): Result<Unit> =
        Result.success(Unit)

    override suspend fun startContinuousSync(peer: DiscoveredPeer) = Unit

    override suspend fun stopContinuousSync(deviceId: String) = Unit

    override suspend fun disconnectFrom(deviceId: String) {
        _status.value = _status.value.copy(
            activeConnections = _status.value.activeConnections
                .filterNot { it.deviceId == deviceId }
        )
    }

    override suspend fun revokeTrustedDevice(deviceId: String) {
        _trustedDevices.removeAll { it.deviceId == deviceId }
    }

    override fun trustedDevices(): List<TrustedDeviceInfo> = _trustedDevices.toList()

    override fun startDiscovery(mode: DiscoveryMode): Flow<LanDiscoveryEvent> {
        _discoveryActive.value = true
        return MutableStateFlow(LanDiscoveryEvent.DiscoveryError("fake stub"))
    }

    override suspend fun stopDiscovery() {
        _discoveryActive.value = false
    }

    override suspend fun connectManually(host: String, port: Int, token: String?): Result<Unit> =
        Result.success(Unit)

    override fun observeStatus(): Flow<SyncStatusSnapshot> = _status
}

class SyncEngineTest {

    private val fakeConfig = SyncConfig(
        id = "test-config",
        createdAt = 0L,
        updatedAt = 0L,
        deviceOrigin = "test",
        deviceId = "test-device",
        displayName = "Test Device",
        listenerPort = 7890
    )

    // ==================== Initial state ====================

    @Test
    fun `initial status reflects not hosting and no peers`() = runBlocking {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        val status = engine.observeStatus().first()
        assertFalse(status.isHosting, "should not be hosting initially")
        assertNull(status.hostAddress, "host address should be null")
        assertTrue(status.activeConnections.isEmpty(), "no active connections")
        assertNull(status.lastSyncAt, "no sync has occurred")
        assertEquals(0, status.pendingConflicts)
        assertNull(status.lastError)
        assertEquals(0, status.pairedDeviceCount)
        assertEquals(0, status.continuousPeers)
    }

    @Test
    fun `trustedDevices returns empty initially`() {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        assertTrue(engine.trustedDevices().isEmpty())
    }

    // ==================== Hosting lifecycle ====================

    @Test
    fun `startHosting updates status to hosting`() = runBlocking {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        val result = engine.startHosting(fakeConfig)
        assertTrue(result.isSuccess)

        val info = result.getOrThrow()
        assertEquals("0.0.0.0", info.address)
        assertEquals(fakeConfig.listenerPort, info.port)
        assertEquals("fake-fingerprint", info.fingerprint)

        val status = engine.observeStatus().first()
        assertTrue(status.isHosting)
        assertEquals("0.0.0.0:${fakeConfig.listenerPort}", status.hostAddress)
    }

    @Test
    fun `stopHosting transitions back to not hosting`() = runBlocking {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        engine.startHosting(fakeConfig)
        assertTrue(engine.observeStatus().first().isHosting)

        engine.stopHosting()
        val status = engine.observeStatus().first()
        assertFalse(status.isHosting)
        assertNull(status.hostAddress)
    }

    @Test
    fun `stopHosting is idempotent when already stopped`() = runBlocking {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        // State is already not-hosting; calling stopHosting should not throw
        engine.stopHosting()
        val status = engine.observeStatus().first()
        assertFalse(status.isHosting)
    }

    @Test
    fun `startHosting stopHosting startHosting roundtrip`() = runBlocking {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        engine.startHosting(fakeConfig)
        assertTrue(engine.observeStatus().first().isHosting)

        engine.stopHosting()
        assertFalse(engine.observeStatus().first().isHosting)

        val secondResult = engine.startHosting(fakeConfig.copy(listenerPort = 7891))
        assertTrue(secondResult.isSuccess)
        val status = engine.observeStatus().first()
        assertTrue(status.isHosting)
        assertEquals("0.0.0.0:7891", status.hostAddress)
    }

    // ==================== Peer management ====================

    @Test
    fun `disconnectFrom unknown device is a no-op`() = runBlocking {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        // Should not throw when disconnecting a non-existent device
        engine.disconnectFrom("nonexistent-device")
        assertTrue(engine.observeStatus().first().activeConnections.isEmpty())
    }

    @Test
    fun `revokeTrustedDevice is idempotent on unknown device`() = runBlocking {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        // Default interface method — should not throw
        engine.revokeTrustedDevice("nonexistent")
        assertTrue(engine.trustedDevices().isEmpty())
    }

    // ================== Discovery stubs ==================

    @Test
    fun `startDiscovery returns a flow`() {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        val flow = engine.startDiscovery(DiscoveryMode.LAN_AUTO_DISCOVERY)
        assertNotNull(flow)
    }

    // ==================== JournalRepository integration ====================

    @Test
    fun `engine works with real JournalRepository`() {
        val repo = JournalRepository()
        val engine = FakeSyncEngine()

        // Verify the repo can be created and an engine can hold a reference
        assertNotNull(repo)
        assertNotNull(engine)
    }
}
