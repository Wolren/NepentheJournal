package app.journal.data

import app.journal.model.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wave4 task 2 pin: DataInitializer.migrateOldIds must route its ID remap
 * through the repository's batch API (one applyBatch) instead of upserting
 * dose-by-dose and interaction-by-interaction.
 *
 * A recording java.lang.reflect.Proxy around a REAL JournalRepository counts
 * the interface calls migrateOldIds makes while still executing them, so the
 * test proves both the routing (1 applyBatch carrying every remapped row,
 * 0 per-entity upserts = one emission per store instead of N) and the final
 * remapped state. desktopTest because Proxy is JVM-only (same pattern as
 * DataInitializerTest).
 */
class DataInitializerRemapBatchTest {

    /** Snapshot of what the proxy observed: (applyBatch, upsertDose, upsertInteraction, batches). */
    private data class Observed(
        val applyBatches: Int,
        val upsertDoses: Int,
        val upsertInteractions: Int,
        val batchSizes: List<Pair<Int, Int>>
    )

    /**
     * Counts the mutation calls that matter, then delegates every method to
     * the real repository so end state is genuine.
     */
    private fun recordingRepo(real: JournalRepository): IJournalRepository {
        var applyBatches = 0
        var upsertDoses = 0
        var upsertInteractions = 0
        val batchSizes = mutableListOf<Pair<Int, Int>>()
        observed = { Observed(applyBatches, upsertDoses, upsertInteractions, batchSizes.toList()) }
        val proxy = Proxy.newProxyInstance(
            IJournalRepository::class.java.classLoader,
            arrayOf(IJournalRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "applyBatch" -> {
                    applyBatches++
                    // Signature order: sessions(0), doses(1), substances(2),
                    // effects(3), interactions(4), ...; omitted defaults are
                    // filled by the Kotlin $default bridge before the proxy
                    // sees the call, so every slot is populated.
                    val doses = (args?.getOrNull(1) as? List<*>)?.size ?: 0
                    val interactions = (args?.getOrNull(4) as? List<*>)?.size ?: 0
                    batchSizes.add(doses to interactions)
                }
                "upsertDose" -> upsertDoses++
                "upsertInteraction" -> upsertInteractions++
            }
            val callArgs: Array<out Any> = args ?: emptyArray()
            try {
                method.invoke(real, *callArgs)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        } as IJournalRepository
        return proxy
    }

    /** Set by [recordingRepo]; read after the call under test. */
    private var observed: (() -> Observed)? = null

    private fun fixture(repo: JournalRepository) {
        repo.upsertSubstance(
            Substance(
                id = "cid:5761", oldId = "pwiki:lsd", name = "LSD",
                createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
                substanceClass = listOf("Classical Psychedelic"),
                cachedAt = 0L, sourceVersion = "test"
            )
        )
        repo.upsertSubstance(
            Substance(
                id = "cid:1615", oldId = "pwiki:psilocybin", name = "Psilocybin",
                createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
                substanceClass = listOf("Classical Psychedelic"),
                cachedAt = 0L, sourceVersion = "test"
            )
        )
        repeat(3) { i ->
            repo.upsertDose(
                Dose(
                    id = "d:$i", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                    sessionId = "s:1", substanceId = "pwiki:lsd",
                    routeOfAdministration = "Oral", amount = 100.0, unit = "µg",
                    timestamp = 1000L + i
                )
            )
        }
        repo.upsertInteraction(
            Interaction(
                id = "i:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                substanceAId = "pwiki:lsd", substanceBId = "pwiki:psilocybin",
                riskLevel = InteractionRisk.UNSAFE, description = "cross-tolerance",
                sources = listOf("psychonautwiki")
            )
        )
    }

    @Test
    fun remapRoutesThroughOneApplyBatchAndNotPerEntityUpserts() {
        val real = JournalRepository()
        fixture(real)
        val repo = recordingRepo(real)

        DataInitializer.migrateOldIds(repo)

        val seen = observed!!.invoke()
        assertEquals(1, seen.applyBatches, "the whole remap must be ONE applyBatch call")
        assertEquals(
            listOf(3 to 1), seen.batchSizes,
            "that single batch must carry all 3 remapped doses and 1 remapped interaction"
        )
        assertEquals(0, seen.upsertDoses, "no per-dose upsert during remap")
        assertEquals(0, seen.upsertInteractions, "no per-interaction upsert during remap")

        // Final state through the real repository: identical to the old
        // per-entity path (also pinned by DataMigrationTest).
        assertEquals(listOf("cid:5761"), real.doses.value.map { it.substanceId }.distinct())
        assertEquals(3, real.doses.value.size, "remap must not drop or duplicate doses")
        val interaction = real.interactions.value.single()
        // Substance IDs are stored SORTED as a pair (canonical pairing rule,
        // also pinned by DataMigrationTest): "cid:1615" < "cid:5761".
        assertEquals("cid:1615", interaction.substanceAId)
        assertEquals("cid:5761", interaction.substanceBId)
        assertEquals("i:1", interaction.id, "the original interaction ID is preserved")

        // A second run finds nothing to patch: no empty applyBatch is emitted.
        DataInitializer.migrateOldIds(repo)
        assertEquals(1, observed!!.invoke().applyBatches,
            "an idempotent re-run must not issue a batch at all")
        assertEquals(0, observed!!.invoke().upsertDoses)
    }
}
