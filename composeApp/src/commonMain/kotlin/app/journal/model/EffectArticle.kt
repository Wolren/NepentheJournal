package app.journal.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One bundled effect article: what an effect is, for the reader, without a
 * network call. Bundled by scripts/effects_build.py from DoseWiki (CC0) and
 * PsychonautWiki (CC BY-SA 4.0) text.
 */
@Immutable
@Serializable
data class EffectArticle(
    val name: String,
    val slug: String = "",
    val categories: List<String> = emptyList(),
    val summary: String = "",
    val body: String = "",
    val source: String = "dosewiki",
    val url: String = "",
    /** Set when the article for this label came from a differently named entry. */
    val matchedName: String? = null,
) {
    val hasText: Boolean get() = summary.isNotBlank() || body.isNotBlank()
}
