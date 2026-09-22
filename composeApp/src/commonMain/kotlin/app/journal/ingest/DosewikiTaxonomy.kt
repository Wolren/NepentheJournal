package app.journal.ingest

import app.journal.data.IJournalRepository
import app.journal.log.Log
import app.journal.model.CuratedSection
import app.journal.model.Substance
import app.journal.util.readBundledResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Curated DoseWiki taxonomy (content/taxonomy/psychoactiveIndexManual.json,
 * bundled as /dosewiki_taxonomy.json): 13 hand-maintained categories with
 * sections of drug slugs each. Applied as [CuratedSection] tags on every
 * substance at init; the substances browser prefers these curated section
 * labels in the level-two submenu and falls back to raw class labels
 * where the index has no entry.
 */
object DosewikiTaxonomy {

    private const val RESOURCE_PATH = "/dosewiki_taxonomy.json"

    @Serializable
    private data class IndexSection(
        val key: String = "",
        val label: String = "",
        val drugs: List<String> = emptyList(),
    )

    @Serializable
    private data class IndexCategory(
        val key: String = "",
        val label: String = "",
        val drugs: List<String> = emptyList(),
        val sections: List<IndexSection> = emptyList(),
    )

    @Serializable
    private data class IndexFile(
        val categories: List<IndexCategory> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses the bundled index into slug -> tags. Category-level drug lists
     * (no section) become a tag with section "all" and the category label.
     */
    internal fun parse(text: String): Map<String, List<CuratedSection>> {
        val file = json.decodeFromString<IndexFile>(text)
        val out = mutableMapOf<String, MutableList<CuratedSection>>()
        file.categories.forEach { category ->
            if (category.key.isBlank()) return@forEach
            category.drugs.forEach { slug ->
                out.getOrPut(slug) { mutableListOf() } +=
                    CuratedSection(category.key, "all", category.label.ifBlank { category.key })
            }
            category.sections.forEach { section ->
                if (section.key.isBlank()) return@forEach
                section.drugs.forEach { slug ->
                    out.getOrPut(slug) { mutableListOf() } +=
                        CuratedSection(category.key, section.key, section.label.ifBlank { section.key })
                }
            }
        }
        return out
    }

    /**
     * DoseWiki slug form: lowercase, non-alphanumerics collapse to hyphens.
     * Zero-logic delegate to the single util/Slug.kt implementation
     * (asciiOnly = true preserves this form byte-for-byte: "cafe" with an
     * accent strips to ASCII, empty stays "" so callers can filter empties).
     */
    internal fun slugify(name: String): String =
        app.journal.util.slugify(name, asciiOnly = true, fallback = "")

    /**
     * Tags for one substance: direct dw:{slug} id match first, then
     * slugified name and aliases.
     */
    internal fun tagsFor(
        id: String,
        name: String,
        aliases: List<String>,
        index: Map<String, List<CuratedSection>>,
    ): List<CuratedSection> {
        index[id.removePrefix("dw:")]?.let { return it }
        val candidates = (listOf(name) + aliases).map { slugify(it) }.filter { it.isNotEmpty() }
        val found = mutableListOf<CuratedSection>()
        candidates.forEach { slug ->
            index[slug]?.let { tags ->
                tags.forEach { tag -> if (tag !in found) found += tag }
            }
        }
        return found
    }

    /**
     * Tags every substance in the repo from the bundled index. Returns the
     * number of substances whose tags changed. Idempotent: steady-state
     * launches change nothing and skip the write. Changed rows accumulate
     * and flush with a single applyBatch plus one index rebuild instead of
     * one upsert per substance.
     */
    fun applyTags(repo: IJournalRepository): Int {
        val text = readBundledResource(RESOURCE_PATH)
        if (text == null) {
            Log.withTag("DoseWiki").w { "Resource $RESOURCE_PATH not found" }
            return 0
        }
        val index = try {
            parse(text)
        } catch (e: Exception) {
            Log.withTag("DoseWiki").w { "Failed to parse $RESOURCE_PATH: ${e.message}" }
            return 0
        }
        val updated = mutableListOf<Substance>()
        repo.substances.value.forEach { sub ->
            val tags = tagsFor(sub.id, sub.name, sub.aliases, index)
            if (tags != sub.curatedSections) {
                updated.add(sub.copy(curatedSections = tags))
            }
        }
        if (updated.isNotEmpty()) {
            repo.applyBatch(substances = updated)
            repo.rebuildIndices()
            Log.withTag("DoseWiki").i { "Applied curated taxonomy tags to ${updated.size} substances" }
        }
        return updated.size
    }
}
