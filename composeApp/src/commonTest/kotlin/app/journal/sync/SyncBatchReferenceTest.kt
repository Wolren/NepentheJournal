package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Effect
import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.model.Substance
import app.journal.util.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The bundled catalog is installed, not synced. Only user-created reference rows
 * may enter a batch; otherwise every launch would push the whole catalog.
 */
class SyncBatchReferenceTest {

    private val now = currentTimeMillis()

    @Test
    fun referenceRowsStayOutOfTheBatch() {
        val repo = JournalRepository()
        repo.upsertSubstance(substance("dw:custom", "Custom", "desktop"))
        repo.upsertSubstance(substance("cid:5761", "LSD", "system"))
        repo.upsertEffect(effect("effect:custom", "Custom effect", "desktop"))
        repo.upsertEffect(effect("effect:dw:cid:5761:1", "Reference effect", "system"))
        repo.upsertInteraction(interaction("interaction:user", "desktop"))
        repo.upsertInteraction(interaction("interaction:catalog", "system"))

        val batch = assertNotNull(buildSyncBatch(repo, "device", "name", since = now - 1000))

        assertEquals(listOf("dw:custom"), batch.substances.map { it.id })
        assertEquals(listOf("effect:custom"), batch.effects.map { it.id })
        assertEquals(listOf("interaction:user"), batch.interactions.map { it.id })
    }

    @Test
    fun catalogOnlyJournalHasNothingToSend() {
        val repo = JournalRepository()
        repo.upsertSubstance(substance("cid:5761", "LSD", "system"))
        repo.upsertEffect(effect("effect:dw:cid:5761:1", "Reference effect", "system"))

        assertNull(buildSyncBatch(repo, "device", "name", since = now - 1000))
    }

    private fun substance(id: String, name: String, origin: String) = Substance(
        id = id,
        name = name,
        createdAt = now,
        updatedAt = now,
        deviceOrigin = origin,
        cachedAt = now,
        sourceVersion = "test",
    )

    private fun effect(id: String, name: String, origin: String) = Effect(
        id = id,
        createdAt = now,
        updatedAt = now,
        deviceOrigin = origin,
        name = name,
    )

    private fun interaction(id: String, origin: String) = Interaction(
        id = id,
        createdAt = now,
        updatedAt = now,
        deviceOrigin = origin,
        substanceAId = "cid:1",
        substanceBId = "cid:2",
        riskLevel = InteractionRisk.UNSAFE,
    )
}
