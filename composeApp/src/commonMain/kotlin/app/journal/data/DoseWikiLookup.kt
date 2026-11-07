package app.journal.data

import app.journal.log.Log
import app.journal.model.DoseWikiDuration
import app.journal.model.DoseWikiSubstance
import app.journal.serde.AppJson
import app.journal.util.readBundledResource

/**
 * Simple on-demand lookup for bundled DoseWiki data.
 * Loads dosewiki_slim.json on first access and caches the parsed list.
 * Used by the UI to fetch structured duration/dosage data without
 * requiring the full ingestion pipeline.
 */
object DoseWikiLookup {

    private const val RESOURCE_PATH = "/dosewiki_slim.json"

    @kotlin.concurrent.Volatile
    private var substances: List<DoseWikiSubstance>? = null

    @kotlin.concurrent.Volatile
    private var byTitle: Map<String, DoseWikiSubstance>? = null

    internal var loadCount: Int = 0
        private set

    /**
     * Takes over the list the startup ingest already parsed. Without this the
     * first substance detail open re-reads and re-parses the 7.7 MB asset on
     * the UI thread.
     */
    internal fun adopt(parsed: List<DoseWikiSubstance>) {
        substances = parsed
        byTitle = parsed.associateBy { it.title.lowercase() }
    }

    private fun ensureLoaded(): List<DoseWikiSubstance>? {
        val cached = substances
        if (cached != null) return cached

        val text = readBundledResource(RESOURCE_PATH)
            ?: run {
                Log.withTag("DoseWikiLookup").w { "Resource $RESOURCE_PATH not found" }
                return null
            }

        val parsed: List<DoseWikiSubstance> = try {
            AppJson.json.decodeFromString(text)
        } catch (e: Exception) {
            Log.withTag("DoseWikiLookup").e { "Failed to parse slim data: ${e.message}" }
            return null
        }

        adopt(parsed)
        loadCount++
        Log.withTag("DoseWikiLookup").i { "Loaded ${parsed.size} substances" }
        return parsed
    }

    /**
     * Clears the parsed-data cache so the next lookup reloads the bundled
     * resource. Exists so tests cannot leak cache state into each other.
     */
    internal fun reset() {
        substances = null
        byTitle = null
    }

    /**
     * Look up a substance by lowercase exact title match.
     * Returns null if not found or data not loaded.
     */
    fun getSubstance(substanceName: String): DoseWikiSubstance? {
        val list = ensureLoaded() ?: return null
        val key = substanceName.lowercase()
        val index = byTitle ?: list.associateBy { it.title.lowercase() }.also { byTitle = it }
        return index[key]
    }

    /**
     * Look up the duration data for a substance by name.
     * Returns null if the substance has no duration data or is not found.
     */
    fun getDuration(substanceName: String): DoseWikiDuration? {
        return getSubstance(substanceName)?.duration
    }
}
