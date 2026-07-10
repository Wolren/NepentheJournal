package app.journal.ingest

import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.util.currentTimeMillis
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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private var ingested = false

    /**
     * Load and ingest DoseWiki data. Safe to call multiple times —
     * second call is a no-op.
     */
    fun ensureIngested(repo: JournalRepository) {
        if (ingested) return

        val stream = DoseWikiIngestor::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: run {
                println("DoseWiki: resource $RESOURCE_PATH not found on classpath")
                return
            }

        val text = stream.reader().readText()
        val substances: List<DoseWikiSubstance> = try {
            json.decodeFromString(text)
        } catch (e: Exception) {
            System.err.println("DoseWiki: failed to parse slim data: ${e.message}")
            return
        }

        val now = currentTimeMillis()
        var effectCount = 0
        var substanceUpdateCount = 0

        for (dw in substances) {
            val name = dw.title
            // Find matching substance in repo by name or alias
            val existing = findSubstance(repo, name, dw.identification?.alternative_names.orEmpty())
            if (existing == null) continue

            val updates = mutableMapOf<String, Any>()
            var modified = false

            // --- Primary: ingest subjective effects ---
            if (dw.subjective_effects != null) {
                val effectNames = mutableListOf<String>()

                // Cognitive effects
                dw.subjective_effects.cognitive?.forEach { (_, group) ->
                    group.effects?.forEach { eff ->
                        effectNames.add(eff.name)
                        upsertEffect(repo, eff, "cognitive", existing.id, now)
                        effectCount++
                    }
                }

                // Physical effects
                dw.subjective_effects.physical?.forEach { (_, group) ->
                    group.effects?.forEach { eff ->
                        effectNames.add(eff.name)
                        upsertEffect(repo, eff, "physical", existing.id, now)
                        effectCount++
                    }
                }

                // Sensory effects
                dw.subjective_effects.sensory?.let { sensory ->
                    listOfNotNull(
                        sensory.auditory, sensory.gustatory, sensory.tactile,
                        sensory.visual, sensory.olfactory, sensory.multisensory
                    ).forEach { cat ->
                        cat.subcategories?.forEach { (_, group) ->
                            group.effects?.forEach { eff ->
                                effectNames.add(eff.name)
                                upsertEffect(repo, eff, "sensory", existing.id, now)
                                effectCount++
                            }
                        }
                    }
                }

                // Update substance's effects list if it's currently empty
                if (existing.effects.isEmpty() && effectNames.isNotEmpty()) {
                    // We don't store effects on Substance directly anymore — use Effect model
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
        println(
            "DoseWiki: ingested $effectCount effects for $substanceUpdateCount substances " +
            "($RESOURCE_PATH)"
        )
    }

    /**
     * Find a substance by exact name or alias match.
     */
    private fun findSubstance(
        repo: JournalRepository,
        name: String,
        aliases: List<String>,
    ): Substance? {
        // Try exact name match first
        val byName = repo.substances.value.find {
            it.name.equals(name, ignoreCase = true)
        }
        if (byName != null) return byName

        // Try alias match
        val byAlias = repo.substances.value.find { sub ->
            sub.aliases.any { alias ->
                alias.equals(name, ignoreCase = true) ||
                aliases.any { it.equals(alias, ignoreCase = true) }
            }
        }
        if (byAlias != null) return byAlias

        // Try matching our name or alias against DoseWiki's aliases
        val byDwAlias = repo.substances.value.find { sub ->
            aliases.any { alias ->
                sub.name.equals(alias, ignoreCase = true)
            }
        }
        return byDwAlias
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
