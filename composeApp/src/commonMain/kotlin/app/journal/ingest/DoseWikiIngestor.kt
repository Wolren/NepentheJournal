package app.journal.ingest

import app.journal.data.AppJson
import app.journal.data.JournalRepository
import app.journal.log.Log
import app.journal.model.*
import app.journal.util.currentTimeMillis
import app.journal.util.readBundledResource
import kotlinx.serialization.json.Json

/**
 * Ingests the bundled DoseWiki slim data into the repository.
 *
 * This is the PRIMARY source for subjective effect descriptions.
 * Dosage, duration, and interaction data are supplementary to the
 * PsychonautWiki seed — fields are populated only when empty.
 *
 * Call from DataInitializer after the PW seed has been loaded.
 */
object DoseWikiIngestor {

    private const val RESOURCE_PATH = "/dosewiki_slim.json"

    private val json = AppJson.json

    private var ingested = false

    /**
     * Load and ingest DoseWiki data. Safe to call multiple times —
     * second call is a no-op.
     */
    fun ensureIngested(repo: JournalRepository) {
        if (ingested) return

        val text = readBundledResource(RESOURCE_PATH)
            ?: run {
                Log.withTag("DoseWiki").w { "Resource $RESOURCE_PATH not found" }
                return
            }
        val substances: List<DoseWikiSubstance> = try {
            json.decodeFromString(text)
        } catch (e: Exception) {
            Log.withTag("DoseWiki").e { "Failed to parse slim data: ${e.message}" }
            return
        }

        val now = currentTimeMillis()
        var totalEffectCount = 0
        var substanceUpdateCount = 0

        // Build O(1) name lookup index: lowercase name + aliases -> Substance
        val lookupByName = buildLookup(repo)

        for (dw in substances) {
            val name = dw.title
            val existing = findSubstance(lookupByName, name, dw.identification?.alternative_names.orEmpty())
            if (existing == null) continue

            val effectNames = mutableListOf<String>()
            var modified = false

            // --- Primary: ingest subjective effects ---
            if (dw.subjective_effects != null) {
                totalEffectCount += ingestEffectCategory(repo, dw.subjective_effects.cognitive, "cognitive", existing.id, now, effectNames)
                totalEffectCount += ingestEffectCategory(repo, dw.subjective_effects.physical, "physical", existing.id, now, effectNames)
                dw.subjective_effects.sensory?.let { sensory ->
                    listOfNotNull(
                        sensory.auditory, sensory.gustatory, sensory.tactile,
                        sensory.visual, sensory.olfactory, sensory.multisensory
                    ).forEach { cat ->
                        cat.subcategories?.forEach { (_, group) ->
                            group.effects?.forEach { eff ->
                                effectNames.add(eff.name)
                                upsertEffect(repo, eff, "sensory", existing.id, now)
                                totalEffectCount++
                            }
                        }
                    }
                }
            }

            // --- Supplementary: summary ---
            if (existing.summary.isNullOrBlank() && !dw.summary.isNullOrBlank()) {
                modified = true
            }

            // --- Supplementary: interaction classes from psychoactive_class ---
            val dwClasses = dw.classification?.psychoactive_class.orEmpty()
                .map { it.lowercase().replace(" ", "_") }
                .filter { it in InteractionClasses.ALL }

            if (dwClasses.isNotEmpty() && existing.interactionClasses.isEmpty()) {
                modified = true
            }

            // --- Apply updates to Substance ---
            if (modified || dwClasses.isNotEmpty()) {
                val updated = existing.copy(
                    summary = existing.summary ?: dw.summary ?: existing.summary,
                    interactionClasses = if (existing.interactionClasses.isEmpty()) {
                        existing.interactionClasses + dwClasses.filter { it !in existing.interactionClasses }
                    } else existing.interactionClasses,
                    updatedAt = now,
                )
                repo.upsertSubstance(updated)
                substanceUpdateCount++
            }
        }

        ingested = true
        Log.withTag("DoseWiki").i {
            "DoseWiki: ingested $totalEffectCount effects for $substanceUpdateCount substances " +
            "($RESOURCE_PATH)"
        }
    }

    /**
     * Build an O(1) name-to-substance lookup from name + aliases.
     */
    private fun buildLookup(repo: JournalRepository): Map<String, Substance> =
        buildMap {
            for (sub in repo.substances.value) {
                put(sub.name.lowercase(), sub)
                for (alias in sub.aliases) {
                    put(alias.lowercase(), sub)
                }
            }
        }

    /**
     * Find a substance by exact name or alias match using O(1) map lookup.
     */
    private fun findSubstance(
        lookup: Map<String, Substance>,
        name: String,
        aliases: List<String>,
    ): Substance? {
        lookup[name.lowercase()]?.let { return it }
        for (alias in aliases) {
            lookup[alias.lowercase()]?.let { return it }
        }
        return null
    }

    /**
     * Ingest effects from a single category (cognitive/physical) into the repo.
     * Returns the number of effects ingested.
     */
    private fun ingestEffectCategory(
        repo: JournalRepository,
        effects: Map<String, DoseWikiEffectGroup>?,
        category: String,
        substanceId: String,
        now: Long,
        effectNames: MutableList<String>,
    ): Int {
        var count = 0
        effects?.forEach { (_, group) ->
            group.effects?.forEach { eff ->
                effectNames.add(eff.name)
                upsertEffect(repo, eff, category, substanceId, now)
                count++
            }
        }
        return count
    }

    /**
     * Upsert an Effect document from a DoseWiki effect entry.
     */
    private fun upsertEffect(
        repo: JournalRepository,
        eff: DoseWikiEffect,
        category: String,
        substanceId: String,
        now: Long,
    ) {
        val id = "effect:dw:${substanceId}:${eff.name.hashCode().toLong() and 0x7FFFFFFF}"
        val effect = Effect(
            id = id,
            name = eff.name,
            description = eff.description?.takeIf { it.isNotBlank() },
            category = category,
            substanceIds = listOf(substanceId),
            url = null,
            createdAt = now,
            updatedAt = now,
            deviceOrigin = "system",
        )
        repo.upsertEffect(effect)
    }
}
