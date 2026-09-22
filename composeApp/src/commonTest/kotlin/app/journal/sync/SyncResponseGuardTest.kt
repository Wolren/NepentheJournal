package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.util.currentTimeMillis
import kotlin.test.*

/**
 * Tests for the client-response guard
 * (validateSyncResponse wired into applySyncResponse, contract:
 * client-response guard).
 *
 * A malicious or buggy peer must not be able to insert blank ids,
 * over-cap fields, or out-of-range timestamps into the local store.
 */
class SyncResponseGuardTest {

    private val repo = JournalRepository()

    private fun session(id: String, title: String = "Valid", createdAt: Long = NOW,
                        updatedAt: Long = NOW, startTime: Long = NOW) = Session(
        id = id, createdAt = createdAt, updatedAt = updatedAt,
        deviceOrigin = "remote", title = title, startTime = startTime
    )

    @Test
    fun `valid response applies with zero skips`() {
        val result = applySyncResponse(repo, SyncResponse(
            success = true,
            sessions = listOf(session("s:1")),
            notes = listOf(Note(
                id = "n:1", createdAt = NOW, updatedAt = NOW,
                deviceOrigin = "remote", sessionId = "s:1", body = "hello"
            ))
        ))
        assertEquals(0, result.skippedEntities)
        assertEquals(0, result.skippedTombstones)
        assertEquals(2, result.appliedEntities)
        assertEquals(1, repo.sessions.value.size)
        assertEquals(1, repo.notes.value.size)
    }

    @Test
    fun `blank id entities are rejected and counted`() {
        val result = applySyncResponse(repo, SyncResponse(
            success = true,
            sessions = listOf(session(""), session("s:ok"))
        ))
        assertEquals(1, result.skippedEntities)
        assertEquals(1, result.appliedEntities)
        assertEquals(1, repo.sessions.value.size)
        assertEquals("s:ok", repo.sessions.value.single().id)
    }

    @Test
    fun `over-cap field entities are rejected`() {
        val filter = validateSyncResponse(SyncResponse(
            success = true,
            sessions = listOf(session("s:long", title = "A".repeat(SyncLimits.MAX_TITLE_LEN + 1)))
        ))
        assertEquals(1, filter.skippedEntities)
        assertTrue(filter.response.sessions.isEmpty())
        // At the boundary the entity is accepted.
        val atCap = validateSyncResponse(SyncResponse(
            success = true,
            sessions = listOf(session("s:cap", title = "A".repeat(SyncLimits.MAX_TITLE_LEN)))
        ))
        assertEquals(0, atCap.skippedEntities)
        assertEquals(1, atCap.response.sessions.size)
    }

    @Test
    fun `timestamps below the year 2000 floor are rejected`() {
        val filter = validateSyncResponse(SyncResponse(
            success = true,
            sessions = listOf(
                session("s:old", createdAt = EntityTimePolicy.MIN_ENTITY_TIMESTAMP - 1),
                session("s:floor", createdAt = EntityTimePolicy.MIN_ENTITY_TIMESTAMP)
            )
        ))
        assertEquals(1, filter.skippedEntities)
        assertEquals(1, filter.response.sessions.size)
        assertEquals("s:floor", filter.response.sessions.single().id)
    }

    @Test
    fun `timestamps beyond the one day future margin are rejected`() {
        val now = currentTimeMillis()
        val filter = validateSyncResponse(SyncResponse(
            success = true,
            sessions = listOf(
                // +60s of slack: the validator reads its own currentTimeMillis(), so a +1ms fixture
                // drifts back inside the window whenever >=1ms elapses between the two reads.
                session("s:future", updatedAt = now + EntityTimePolicy.FUTURE_MARGIN_MS + 60_000),
                session("s:skew", updatedAt = now + EntityTimePolicy.FUTURE_MARGIN_MS - 60_000)
            )
        ))
        assertEquals(1, filter.skippedEntities, "more than 1 day in the future must be rejected")
        assertEquals(1, filter.response.sessions.size)
        assertEquals("s:skew", filter.response.sessions.single().id)
    }

    @Test
    fun `invalid tombstone ids are dropped and counted`() {
        val filter = validateSyncResponse(SyncResponse(
            success = true,
            deletedDoseIds = listOf("", "x".repeat(SyncLimits.MAX_ID_LEN + 1), "d:ok")
        ))
        assertEquals(0, filter.skippedEntities)
        assertEquals(2, filter.skippedTombstones)
        assertEquals(listOf("d:ok"), filter.response.deletedDoseIds)
    }

    @Test
    fun `invalid dose amounts are rejected`() {
        val dose = Dose(
            id = "d:nan", createdAt = NOW, updatedAt = NOW, deviceOrigin = "remote",
            sessionId = "s:1", substanceId = "cid:1",
            routeOfAdministration = "Oral", amount = Double.NaN, unit = "mg", timestamp = NOW
        )
        val filter = validateSyncResponse(SyncResponse(success = true, doses = listOf(dose)))
        assertEquals(1, filter.skippedEntities)
        assertTrue(filter.response.doses.isEmpty())
    }

    @Test
    fun `disallowed interaction risk levels are rejected`() {
        // The allow-list rejects unknown enum names at the batch level; at the
        // response level the same entity validator runs per interaction.
        val ok = Interaction(
            id = "i:1", createdAt = NOW, updatedAt = NOW, deviceOrigin = "remote",
            substanceAId = "cid:1", substanceBId = "cid:2", riskLevel = InteractionRisk.DANGEROUS
        )
        val filter = validateSyncResponse(SyncResponse(success = true, interactions = listOf(ok)))
        assertEquals(0, filter.skippedEntities)
        assertEquals(1, filter.response.interactions.size)
    }

    companion object {
        /** In-range timestamp (Nov 2023): above the floor, below the margin. */
        private const val NOW = 1_700_000_000_000L
    }
}
