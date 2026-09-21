package app.journal.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One curated DoseWiki taxonomy tag: the manual index category a substance
 * was listed under plus the section inside it.
 *
 * Example: category "stimulant", section "cathinone", label "Cathinone".
 * Category-level listings (no section) use section "all" and the category
 * label, e.g. label "A-typical Hallucinogen".
 */
@Immutable
@Serializable
data class CuratedSection(
    val category: String,
    val section: String,
    val label: String,
)
