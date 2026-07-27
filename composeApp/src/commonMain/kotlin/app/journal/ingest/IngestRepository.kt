package app.journal.ingest

import app.journal.model.Effect
import app.journal.model.Interaction
import app.journal.model.Substance

/**
 * Repository interface for data ingestion pipelines.
 *
 * Implementations provide thread-safe upsert operations for
 * substance, interaction, and effect entities, typically via
 * a JournalRepository.
 */
interface IngestRepository {
    fun upsertSubstance(substance: Substance)
    fun upsertInteraction(interaction: Interaction)
    fun upsertEffect(effect: Effect)
}
