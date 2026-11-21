package app.journal.sync

import app.journal.model.Dose
import app.journal.model.Session
import app.journal.util.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The WebSocket apply guard must accept exactly what the host accepts.
 *
 * The host rejects a delta with [validateWsDelta]. The client used to keep a
 * private subset of those checks, so a frame the host refused could still be
 * applied locally (over-cap counts, over-long titles, a disallowed risk level).
 * [planWsDeltaApply] is the client half of the same rule set: whole-payload
 * violations reject the frame, entity violations drop that entity.
 */
class SyncWsApplyGuardTest {

    private val now = currentTimeMillis()

    private fun session(id: String, title: String = "title") = Session(
        id = id,
        createdAt = now,
        updatedAt = now,
        deviceOrigin = "guard-test",
        title = title,
        startTime = now,
    )

    private fun dose(id: String, amount: Double = 10.0) = Dose(
        id = id,
        createdAt = now,
        updatedAt = now,
        deviceOrigin = "guard-test",
        sessionId = "s:1",
        substanceId = "cid:1",
        routeOfAdministration = "Oral",
        amount = amount,
        unit = "mg",
        timestamp = now,
    )

    @Test
    fun `an out of range since is rejected by server and client alike`() {
        val delta = WsDelta(seq = 1, since = now + 2 * EntityTimePolicy.FUTURE_MARGIN_MS)
        assertNotNull(validateWsDelta(delta), "the host must reject an out-of-window since")
        val plan = planWsDeltaApply(delta)
        assertNotNull(plan.rejected, "the client must reject the same frame, not apply it")
    }

    @Test
    fun `an over-cap delta is rejected by server and client alike`() {
        val delta = WsDelta(
            seq = 1,
            sessions = (1..SyncLimits.MAX_ITEMS_DEFAULT + 1).map { session("s:$it") },
        )
        assertNotNull(validateWsDelta(delta), "the host must reject over-cap counts")
        assertNotNull(planWsDeltaApply(delta).rejected, "the client must not half-apply it")
    }

    @Test
    fun `an over-long tombstone id is rejected by server and client alike`() {
        val delta = WsDelta(seq = 1, deletedSessionIds = listOf("s:".padEnd(SyncLimits.MAX_ID_LEN + 1, 'x')))
        assertNotNull(validateWsDelta(delta), "the host must reject an over-long deleted id")
        assertNotNull(planWsDeltaApply(delta).rejected, "the client must not apply a partial delete list")
    }

    @Test
    fun `an invalid entity is dropped and the remaining delta still validates`() {
        val good = session("s:good")
        val badTitle = session("s:bad", title = "t".repeat(SyncLimits.MAX_TITLE_LEN + 1))
        val delta = WsDelta(seq = 1, sessions = listOf(good, badTitle))

        assertNotNull(validateWsDelta(delta), "the raw frame fails the host rule")

        val plan = planWsDeltaApply(delta)
        assertNull(plan.rejected, "one bad entity does not poison a valid frame")
        assertEquals(1, plan.skippedEntities)
        assertEquals(listOf(good), plan.delta.sessions)
        assertNull(validateWsDelta(plan.delta), "everything the client keeps must pass the host rule")
    }

    @Test
    fun `an over-long interaction source is dropped like the host rejects it`() {
        val bad = app.journal.model.Interaction(
            id = "i:1",
            createdAt = now,
            updatedAt = now,
            deviceOrigin = "guard-test",
            substanceAId = "cid:1",
            substanceBId = "cid:2",
            riskLevel = app.journal.model.InteractionRisk.UNSAFE,
            description = "d",
            sources = listOf("s".repeat(SyncLimits.MAX_INTERACTION_SOURCE_LEN + 1)),
        )
        val delta = WsDelta(seq = 1, interactions = listOf(bad))
        assertNotNull(validateWsDelta(delta), "the host rule rejects the over-long source")
        val plan = planWsDeltaApply(delta)
        assertNull(plan.rejected)
        assertEquals(0, plan.delta.interactions.size, "a rejected entity must not reach the store")
        assertEquals(1, plan.skippedEntities)
    }

    @Test
    fun `a negative dose amount is dropped by the client plan`() {
        val delta = WsDelta(seq = 1, doses = listOf(dose("d:good"), dose("d:bad", amount = -1.0)))
        val plan = planWsDeltaApply(delta)
        assertNull(plan.rejected)
        assertEquals(listOf("d:good"), plan.delta.doses.map { it.id })
        assertEquals(1, plan.skippedEntities)
        assertNull(validateWsDelta(plan.delta))
    }

    @Test
    fun `a clean delta passes both rules unchanged`() {
        val delta = WsDelta(seq = 1, sessions = listOf(session("s:1")), doses = listOf(dose("d:1")))
        val plan = planWsDeltaApply(delta)
        assertNull(plan.rejected)
        assertEquals(0, plan.skippedEntities)
        assertEquals(delta, plan.delta)
        assertNull(validateWsDelta(delta))
        assertTrue(plan.delta.sessions.isNotEmpty())
    }
}
