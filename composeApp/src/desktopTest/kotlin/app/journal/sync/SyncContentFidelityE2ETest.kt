package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.CheckIn
import app.journal.model.CustomUnit
import app.journal.model.CuratedSection
import app.journal.model.Dose
import app.journal.model.Effect
import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.model.Note
import app.journal.model.Person
import app.journal.model.PersonRole
import app.journal.model.Session
import app.journal.model.SessionProfile
import app.journal.model.StomachFullness
import app.journal.model.Substance
import app.journal.model.SyncConfig
import app.journal.model.TimelineEvent
import app.journal.model.TimelineEventType
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Content-level end-to-end sync: REAL KtorSyncServer on an ephemeral port,
 * REAL SyncTransport on both sides, production pairing + push/pull.
 *
 * SyncTransportLifecycleTest proves the transport moves *something*; this
 * file proves WHAT moves. Closes three honest gaps:
 *
 *  1. Fidelity — every syncable entity type (session, dose, substance,
 *     effect, interaction, note, timeline event, custom unit) must arrive on
 *     the peer as an EQUAL object, i.e. every field intact, and the
 *     device-local Person collection must NOT replicate.
 *  2. Convergence — offline edits on both sides must reach both sides, and a
 *     repeat cycle must be a no-op (eventual consistency + idempotence).
 *  3. Deletion — tombstones must travel in BOTH directions and the entity
 *     must stay deleted (no resurrection from a peer that still holds it).
 *
 * Plus the note-conflict contract: a divergent peer edit must never destroy
 * a body (contract section c).
 *
 * Cursor discipline used below: SyncTransport.lastSyncTime (internal) is the
 * client's pull cursor after each cycle. Edits that must be visible to the
 * NEXT cycle are stamped at `cursor + n` rather than "now", because a wall
 * clock read taken before the previous cycle finished would sit BELOW that
 * cursor and be silently skipped. That is not a workaround for a defect, it
 * is the documented cursor rule (contract section d).
 *
 * connectManually/syncWith are called with NO timeout wrapper, exactly as in
 * SyncTransportLifecycleTest: a re-introduced syncLock re-entrancy hang must
 * hang/kill the test rather than be softened into a timeout failure.
 */
class SyncContentFidelityE2ETest {

    private lateinit var hostDir: File
    private lateinit var clientDir: File
    private var host: SyncTransport? = null
    private var client: SyncTransport? = null

    @BeforeTest
    fun setUp() {
        val base = File(System.getProperty("java.io.tmpdir") ?: ".")
        hostDir = File(base, "nepenthe-fid-host-${System.nanoTime()}").apply { mkdirs() }
        clientDir = File(base, "nepenthe-fid-client-${System.nanoTime()}").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        runCatching { runBlocking { withTimeout(10_000) { client?.stopHosting() } } }
        runCatching { runBlocking { withTimeout(10_000) { host?.stopHosting() } } }
        host = null
        client = null
        hostDir.deleteRecursively()
        clientDir.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    private data class Pairing(
        val hostRepo: JournalRepository,
        val clientRepo: JournalRepository,
        val hostTransport: SyncTransport,
        val clientTransport: SyncTransport,
        val port: Int
    )

    private fun config(port: Int, name: String) = SyncConfig(
        id = "cfg:$name", createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(), deviceOrigin = "test",
        deviceId = "device-$name", displayName = name, listenerPort = port
    )

    private fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000); true }
    } catch (e: Exception) {
        false
    }

    /**
     * Bring up a REAL host, seed both repos, and pair the client through the
     * production manual-IP entry point (which pairs AND runs the first full
     * push/pull cycle in one call).
     */
    private fun pair(
        seedHost: (JournalRepository) -> Unit,
        seedClient: (JournalRepository) -> Unit = {}
    ): Pairing = runBlocking {
        val hRepo = JournalRepository()
        val cRepo = JournalRepository()
        seedHost(hRepo)
        seedClient(cRepo)

        val hTransport = SyncTransport(hRepo, hostDir.absolutePath)
        host = hTransport
        val info = withTimeout(60_000) { hTransport.startHosting(config(0, "host")) }
        assertTrue(info.isSuccess, "host must come up: ${info.exceptionOrNull()?.message}")
        val port = info.getOrThrow().port
        assertTrue(port in 1..65535, "host must bind an ephemeral port, got $port")
        assertTrue(canConnect(port), "host must accept connections before the client dials")
        val token = hTransport.observeStatus().first().pairingToken
        assertNotNull(token, "hosting must publish a pairing token")

        val cTransport = SyncTransport(cRepo, clientDir.absolutePath)
        client = cTransport
        val manual = cTransport.connectManually("127.0.0.1", port, token)
        assertTrue(
            manual.isSuccess,
            "connectManually must pair and finish the first cycle: ${manual.exceptionOrNull()?.message}"
        )
        assertNotNull(cTransport.lastSyncTime, "the first cycle must leave a pull cursor")

        Pairing(hRepo, cRepo, hTransport, cTransport, port)
    }

    /** One trusted client-initiated cycle: push client changes, pull host changes. */
    private fun Pairing.trustedCycle() = runBlocking {
        val trusted = clientTransport.trustedDevices()
        assertTrue(trusted.isNotEmpty(), "fixture: the client must already trust the host")
        val peer = trusted.first()
        val result = clientTransport.syncWith(
            DiscoveredPeer(
                deviceId = peer.deviceId,
                displayName = peer.displayName,
                host = "127.0.0.1",
                port = port,
                isTrusted = true,
                fingerprint = peer.fingerprint
            ),
            continuous = false
        )
        assertTrue(
            result.isSuccess,
            "a trusted sync cycle must succeed: ${result.exceptionOrNull()?.message}"
        )
    }

    /** Strictly greater than the client's current cursor: visible NEXT cycle. */
    private fun Pairing.nextCursorStamp(offsetMs: Long = 10L): Long {
        val cursor = assertNotNull(clientTransport.lastSyncTime, "fixture: a cursor must exist")
        return cursor + offsetMs
    }

    // ------------------------------------------------------------------
    // Entity builders — every field populated so a lost field breaks equality
    // ------------------------------------------------------------------

    private fun session(id: String, title: String, updatedAt: Long) = Session(
        id = id, createdAt = updatedAt, updatedAt = updatedAt, deviceOrigin = "test",
        title = title, startTime = updatedAt, endTime = updatedAt + 3_600_000L,
        set = "set of $id", setting = "setting of $id", intention = "intention of $id",
        outcome = "outcome of $id", notes = "notes of $id", rating = 7,
        shulginRating = "+++",
        checkins = listOf(
            CheckIn(
                timestamp = updatedAt + 60_000L, overallIntensity = 4.5f,
                effectScores = mapOf("warmth" to 0.75f, "music" to 1.0f),
                mood = "calm", notes = "checkin notes"
            )
        ),
        isArchived = false, isFavorite = true, consumerName = "tester",
        personId = null,
        profile = SessionProfile(age = 31, gender = "x", heightCm = 178, weightKg = 70),
        tags = listOf("alpha", "beta"), pausedMs = 9_000L, pausedAt = null
    )

    private fun substance(id: String, name: String, updatedAt: Long) = Substance(
        id = id, createdAt = updatedAt, updatedAt = updatedAt, deviceOrigin = "test",
        oldId = "pwiki:old-$id", cid = 2_244L, pwikiId = "pw-$id",
        name = name, aliases = listOf("alias of $name"), summary = "summary of $name",
        substanceClass = listOf("stimulant"),
        curatedSections = listOf(CuratedSection("stimulant", "cathinone", "Cathinone")),
        routesOfAdministration = listOf("oral", "insufflated"),
        dosageBands = mapOf("light" to "5-10 mg", "common" to "10-20 mg"),
        durationProfile = mapOf("total" to "3h"),
        addictionPotential = "moderate", toxicity = listOf("cardiotoxic"),
        crossTolerances = listOf("stimulant"), effects = listOf("euphoria"),
        interactionClasses = listOf("serotonergic"),
        chemblId = "CHEMBL1", drugbankId = "DB00001", iupharId = "1234",
        chemspiderId = "5555", unii = "UNII1", chebiId = "CHEBI:1",
        atcCode = "N06BA", sources = listOf("psychonautwiki", "dosewiki"),
        cachedAt = updatedAt, sourceVersion = "test-1"
    )

    private fun dose(id: String, sessionId: String, substanceId: String, updatedAt: Long) = Dose(
        id = id, createdAt = updatedAt, updatedAt = updatedAt, deviceOrigin = "test",
        sessionId = sessionId, substanceId = substanceId, routeOfAdministration = "oral",
        amount = 12.5, unit = "mg", timestamp = updatedAt, redosing = true,
        notes = "dose notes", isDoseEstimate = true,
        estimatedDoseStandardDeviation = 1.25, customUnitId = null,
        stomachFullness = StomachFullness.MODERATE
    )

    private fun effect(id: String, updatedAt: Long) = Effect(
        id = id, createdAt = updatedAt, updatedAt = updatedAt, deviceOrigin = "test",
        name = "Visual snow $id", url = "https://example.invalid/$id",
        description = "description of $id", category = "visual",
        substanceIds = listOf("sub:one")
    )

    private fun interaction(id: String, updatedAt: Long) = Interaction(
        id = id, createdAt = updatedAt, updatedAt = updatedAt, deviceOrigin = "test",
        substanceAId = "sub:one", substanceBId = "sub:two",
        riskLevel = InteractionRisk.DANGEROUS, description = "desc of $id",
        sources = listOf("https://example.invalid/interactions")
    )

    private fun note(id: String, sessionId: String, body: String, updatedAt: Long) = Note(
        id = id, createdAt = updatedAt, updatedAt = updatedAt, deviceOrigin = "test",
        sessionId = sessionId, doseId = null, title = "Note $id", body = body
    )

    private fun timelineEvent(id: String, sessionId: String, updatedAt: Long) = TimelineEvent(
        id = id, createdAt = updatedAt, updatedAt = updatedAt, deviceOrigin = "test",
        sessionId = sessionId, timestamp = updatedAt, eventType = TimelineEventType.PEAK,
        label = "Peak of $id", body = "event body", intensity = 0.8f
    )

    private fun customUnit(id: String, updatedAt: Long) = CustomUnit(
        id = id, createdAt = updatedAt, updatedAt = updatedAt, deviceOrigin = "test",
        substanceId = "sub:one", name = "Unit $id", description = "puffs",
        isEstimate = true
    )

    private fun person(id: String) = Person(
        id = id, createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
        deviceOrigin = "test", displayName = "Device-local person",
        role = PersonRole.SITTER, notes = "never syncs", linkedSessionIds = listOf("s:one"),
        age = 40, gender = "y", height = "5 ft 9 in", weight = "70 kg",
        medications = "None", contactEmail = "a@b.invalid", mayContact = true, isSelf = true
    )

    /** Seed one of every syncable entity plus a device-local Person. */
    private fun seedFullLibrary(repo: JournalRepository, updatedAt: Long) {
        repo.upsertSession(session("s:one", "Seeded session", updatedAt))
        repo.upsertSubstance(substance("sub:one", "Seeded substance", updatedAt))
        repo.upsertSubstance(substance("sub:two", "Second substance", updatedAt))
        repo.upsertDose(dose("d:one", "s:one", "sub:one", updatedAt))
        repo.upsertEffect(effect("fx:one", updatedAt))
        repo.upsertInteraction(interaction("ix:one", updatedAt))
        repo.upsertNote(note("n:one", "s:one", "seeded body", updatedAt))
        repo.upsertTimelineEvent(timelineEvent("ev:one", "s:one", updatedAt))
        repo.upsertCustomUnit(customUnit("cu:one", updatedAt))
        repo.upsertPerson(person("p:one"))
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private fun assertSameList(
        label: String,
        expected: List<Any>,
        actual: List<Any>,
        sortKey: (Any) -> String
    ) {
        assertEquals(
            expected.sortedBy(sortKey), actual.sortedBy(sortKey),
            "$label diverged between the two devices"
        )
    }

    /** Whole-library equality across the eight synced collections. */
    private fun assertSameLibrary(hostRepo: JournalRepository, clientRepo: JournalRepository) {
        assertSameList("sessions", hostRepo.sessions.value, clientRepo.sessions.value) { (it as Session).id }
        assertSameList("doses", hostRepo.doses.value, clientRepo.doses.value) { (it as Dose).id }
        assertSameList("substances", hostRepo.substances.value, clientRepo.substances.value) { (it as Substance).id }
        assertSameList("effects", hostRepo.effects.value, clientRepo.effects.value) { (it as Effect).id }
        assertSameList(
            "interactions", hostRepo.interactions.value, clientRepo.interactions.value
        ) { (it as Interaction).id }
        assertSameList("notes", hostRepo.notes.value, clientRepo.notes.value) { (it as Note).id }
        assertSameList(
            "timelineEvents", hostRepo.timelineEvents.value, clientRepo.timelineEvents.value
        ) { (it as TimelineEvent).id }
        assertSameList(
            "customUnits", hostRepo.customUnits.value, clientRepo.customUnits.value
        ) { (it as CustomUnit).id }
    }

    // ------------------------------------------------------------------
    // 1. Fidelity
    // ------------------------------------------------------------------

    @Test
    fun everySyncedEntityTypeCrossesTheWireFieldForField() {
        val seedAt = System.currentTimeMillis()
        val t = pair(seedHost = { seedFullLibrary(it, seedAt) })

        // --- sessions ---
        val gotSession = t.clientRepo.sessions.value.singleOrNull { it.id == "s:one" }
        assertNotNull(gotSession, "the session must arrive on the client")
        assertEquals(
            t.hostRepo.sessions.value.single { it.id == "s:one" }, gotSession,
            "every Session field must survive the round trip"
        )

        // --- substances ---
        for (id in listOf("sub:one", "sub:two")) {
            val expected = t.hostRepo.substances.value.single { it.id == id }
            assertEquals(
                expected, t.clientRepo.substances.value.singleOrNull { it.id == id },
                "Substance $id must arrive with every field intact"
            )
        }

        // --- doses (and their index linkage) ---
        assertEquals(
            t.hostRepo.doses.value.single { it.id == "d:one" },
            t.clientRepo.doses.value.singleOrNull { it.id == "d:one" },
            "every Dose field must survive the round trip"
        )
        assertEquals(
            listOf("d:one"), t.clientRepo.dosesForSession("s:one").map { it.id },
            "the client's session->dose index must be rebuilt from the pulled dose"
        )

        // --- effects / interactions / notes / timeline events / custom units ---
        assertEquals(
            t.hostRepo.effects.value.single { it.id == "fx:one" },
            t.clientRepo.effects.value.singleOrNull { it.id == "fx:one" },
            "every Effect field must survive the round trip"
        )
        assertEquals(
            t.hostRepo.interactions.value.single { it.id == "ix:one" },
            t.clientRepo.interactions.value.singleOrNull { it.id == "ix:one" },
            "every Interaction field must survive the round trip"
        )
        assertEquals(
            t.hostRepo.notes.value.single { it.id == "n:one" },
            t.clientRepo.notes.value.singleOrNull { it.id == "n:one" },
            "every Note field must survive the round trip"
        )
        assertEquals(
            t.hostRepo.timelineEvents.value.single { it.id == "ev:one" },
            t.clientRepo.timelineEvents.value.singleOrNull { it.id == "ev:one" },
            "every TimelineEvent field must survive the round trip"
        )
        assertEquals(
            t.hostRepo.customUnits.value.single { it.id == "cu:one" },
            t.clientRepo.customUnits.value.singleOrNull { it.id == "cu:one" },
            "every CustomUnit field must survive the round trip"
        )

        // Derived, not just raw storage: the pulled content must be usable.
        assertEquals(1, t.clientRepo.notesForSession("s:one").size,
            "the client's session->note index must be rebuilt from the pulled note")
        assertEquals(1, t.clientRepo.eventsForSession("s:one").size,
            "the client's session->timeline index must be rebuilt from the pulled events")

        // --- device-local data must NOT replicate ---
        assertEquals(
            emptyList<Person>(), t.clientRepo.persons.value,
            "Persons are device-local profile data and must never replicate to a peer"
        )

        // And the host must be untouched by its own seed.
        assertSameLibrary(t.hostRepo, t.clientRepo)
    }

    // ------------------------------------------------------------------
    // 2. Convergence
    // ------------------------------------------------------------------

    @Test
    fun offlineEditsOnBothSidesConvergeAndStayConverged() {
        val seedAt = System.currentTimeMillis()
        val t = pair(
            seedHost = { it.upsertSession(session("s:host", "HostSession", seedAt)) },
            seedClient = { it.upsertSession(session("s:client", "ClientSession", seedAt)) }
        )
        assertSameLibrary(t.hostRepo, t.clientRepo)

        // ---- offline window: both devices edit with no connection open ----
        val stamp = t.nextCursorStamp()
        t.clientRepo.upsertSession(session("s:client", "ClientSession v2", stamp + 10))
        t.clientRepo.upsertSession(session("s:new-client", "FromClient", stamp + 20))
        t.hostRepo.upsertSession(session("s:host", "HostSession v2", stamp + 30))
        t.hostRepo.upsertSession(session("s:new-host", "FromHost", stamp + 40))

        t.trustedCycle()

        // ONE cycle carries both directions: the client pushes its two edits
        // and pulls the host's two edits from the same cursor.
        assertSameLibrary(t.hostRepo, t.clientRepo)
        assertEquals(
            "ClientSession v2",
            t.hostRepo.sessions.value.single { it.id == "s:client" }.title,
            "a client edit must land on the host"
        )
        assertEquals(
            "HostSession v2",
            t.clientRepo.sessions.value.single { it.id == "s:host" }.title,
            "a host edit made while disconnected must land on the client"
        )

        // ---- a host edit made AFTER cycle 1 returned must reach cycle 2 ----
        val secondStamp = t.nextCursorStamp()
        t.hostRepo.upsertSession(session("s:host", "HostSession v3", secondStamp + 10))
        t.trustedCycle()
        assertEquals(
            "HostSession v3",
            t.clientRepo.sessions.value.single { it.id == "s:host" }.title,
            "an edit that landed on the host after the previous cycle start " +
                "must be picked up by the NEXT cycle"
        )
        assertSameLibrary(t.hostRepo, t.clientRepo)

        // ---- idempotence: a cycle with nothing new changes nothing ----
        t.trustedCycle()
        assertSameLibrary(t.hostRepo, t.clientRepo)
    }

    // ------------------------------------------------------------------
    // 3. Deletion
    // ------------------------------------------------------------------

    @Test
    fun deletesPropagateInBothDirectionsAndDoNotResurrect() {
        val seedAt = System.currentTimeMillis()
        val t = pair(
            seedHost = {
                it.upsertSession(session("s:keep", "Keep me", seedAt))
                it.upsertSession(session("s:host-del", "Deleted on host", seedAt))
                it.upsertSession(session("s:both", "Deleted on client", seedAt))
            }
        )
        assertEquals(3, t.clientRepo.sessions.value.size, "fixture: all three sessions arrive")
        assertEquals(3, t.hostRepo.sessions.value.size, "fixture")

        // Tombstones are compared against the client's cursor, so the delete
        // must happen after that cursor was taken. Pairing just finished a
        // network round trip, so the clock has advanced past it; the sleep
        // only guards the same-millisecond edge.
        Thread.sleep(5)

        // Fixture precondition, spelled out: the pull below is requested with
        // the client's CURRENT cursor, so the tombstone has to be newer than
        // that cursor or the host would legitimately omit it and the assertion
        // would fail for a clock reason rather than a propagation reason.
        val cursorBeforeHostDelete = assertNotNull(
            t.clientTransport.lastSyncTime, "fixture: pairing must have left a cursor"
        )

        // ---- host -> client (travels on the PULL half) ----
        t.hostRepo.deleteSession("s:host-del")
        assertTrue(
            t.hostRepo.deletedIdsSince(cursorBeforeHostDelete).deletedSessionIds
                .contains("s:host-del"),
            "fixture: the host tombstone must be visible at the client's cursor"
        )
        t.trustedCycle()
        assertEquals(
            emptyList<String>(), t.clientRepo.sessions.value.map { it.id }.filter { it == "s:host-del" },
            "a host delete must arrive on the client through the pull response"
        )
        assertTrue(t.clientRepo.sessions.value.any { it.id == "s:keep" }, "survivor intact on client")
        assertTrue(t.clientRepo.sessions.value.any { it.id == "s:both" }, "fixture intact on client")

        // ---- client -> host (travels on the PUSH half) ----
        Thread.sleep(5)
        t.clientRepo.deleteSession("s:both")
        t.trustedCycle()
        assertEquals(
            emptyList<String>(), t.hostRepo.sessions.value.map { it.id }.filter { it == "s:both" },
            "a client delete must arrive on the host through the push batch"
        )
        assertTrue(t.hostRepo.sessions.value.any { it.id == "s:keep" }, "survivor intact on host")

        // ---- no resurrection: an idle repeat cycle must not bring either back ----
        t.trustedCycle()
        assertSameLibrary(t.hostRepo, t.clientRepo)
        for (id in listOf("s:host-del", "s:both")) {
            assertTrue(
                t.hostRepo.sessions.value.none { it.id == id },
                "$id must stay deleted on the host after a repeat cycle"
            )
            assertTrue(
                t.clientRepo.sessions.value.none { it.id == id },
                "$id must stay deleted on the client after a repeat cycle"
            )
        }
        assertEquals(
            listOf("s:keep"), t.hostRepo.sessions.value.map { it.id }.sorted(),
            "only the survivor may remain on the host"
        )
        assertEquals(
            listOf("s:keep"), t.clientRepo.sessions.value.map { it.id }.sorted(),
            "only the survivor may remain on the client"
        )
    }

    // ------------------------------------------------------------------
    // 4. Note conflict: no user body may ever be destroyed
    // ------------------------------------------------------------------

    @Test
    fun divergentNoteEditsKeepBothBodiesAndSurfaceAPendingConflict() = runBlocking {
        val seedAt = System.currentTimeMillis()
        val t = pair(
            seedHost = {
                it.upsertSession(session("s:one", "Session", seedAt))
                it.upsertNote(note("n:one", "s:one", "host-original", seedAt))
            }
        )
        assertEquals("host-original", t.clientRepo.notes.value.single { it.id == "n:one" }.body,
            "fixture: the note arrives on the client")

        // Both devices edit the same note while offline. The client's edit is
        // strictly newer, so it wins on the host and the host's body must be
        // preserved as a conflict sibling rather than dropped.
        val stamp = t.nextCursorStamp()
        t.hostRepo.upsertNote(note("n:one", "s:one", "host-edit", stamp + 50))
        t.clientRepo.upsertNote(note("n:one", "s:one", "client-edit", stamp + 100))

        t.trustedCycle()

        val hostNote = t.hostRepo.notes.value.singleOrNull { it.id == "n:one" }
        assertNotNull(hostNote, "the note must still exist on the host")
        assertEquals(
            "client-edit", hostNote.body,
            "the newer (client) body wins last-writer-wins on the host"
        )
        assertTrue(
            hostNote.conflictSiblings.any { it.body == "host-edit" },
            "the losing host body must be preserved as a ConflictSibling, " +
                "got siblings=${hostNote.conflictSiblings.map { it.body }}"
        )
        assertEquals(
            1, t.hostRepo.pendingConflictCount.first(),
            "the host must surface exactly one pending conflict"
        )

        // The client converges on the winner without losing anything either.
        assertEquals(
            "client-edit", t.clientRepo.notes.value.single { it.id == "n:one" }.body,
            "the client must converge on the winning body"
        )

        // Repeat cycle: the merge is idempotent, the conflict count must not grow.
        t.trustedCycle()
        assertEquals(
            1, t.hostRepo.pendingConflictCount.first(),
            "replaying the same merge must not duplicate the conflict"
        )
        val replayed = t.hostRepo.notes.value.single { it.id == "n:one" }
        assertEquals(listOf("host-edit"), replayed.conflictSiblings.map { it.body },
            "replays must not append duplicate siblings")
    }
}
