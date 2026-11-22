package app.journal.ui.substances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.journal.data.EffectLibrary
import app.journal.model.EffectArticle
import app.journal.ui.components.AnimatedListItem
import app.journal.ui.components.AppCard
import app.journal.ui.components.rememberEmptyStateVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Effect browsing shares the Substances tab, so it keeps the same list language. */
internal data class EffectGroup(val label: String, val articles: List<EffectArticle>)

internal val CATEGORY_KEYS = listOf("cognitive", "physical", "sensory")
private val SENSORY_KEYS = listOf("visual", "auditory", "tactile", "gustatory", "olfactory", "multisensory")

/** One entry of the effects category filter, shaped like the substance filter options. */
internal data class EffectFilterOption(
    val key: String,
    val label: String,
    val count: Int,
    val parent: String? = null,
)

/** Broads with their counts, then the sensory specifics, derived from the bundled articles. */
internal fun effectFilterOptions(articles: List<EffectArticle>): List<EffectFilterOption> {
    val broads = CATEGORY_KEYS.mapNotNull { key ->
        val count = articles.count { key in it.categories }
        if (count == 0) null else EffectFilterOption(key, categoryLabel(key), count)
    }
    val sensory = SENSORY_KEYS.mapNotNull { key ->
        val count = articles.count { key in it.categories }
        if (count == 0) null else EffectFilterOption(key, categoryLabel(key), count, parent = "sensory")
    }
    return broads + sensory
}

/**
 * Lowercased search text for one article, built once per loaded library.
 * A query match must not lowercase every article body on every keystroke.
 */
internal class EffectSearchText(articles: List<EffectArticle>) {
    internal var lowercaseCalls: Int = 0
        private set

    private val textByName: Map<String, String> = articles.associate { article ->
        lowercaseCalls++
        article.name to haystack(article)
    }

    fun matches(article: EffectArticle, needle: String): Boolean =
        (textByName[article.name] ?: haystack(article)).contains(needle)

    private fun haystack(article: EffectArticle): String =
        "${article.name}\n${article.summary}\n${article.body}".lowercase()
}

/**
 * Filter by free text, then group by the first curated category the article
 * carries. Order is fixed (cognitive, physical, sensory, other) so the list
 * does not reshuffle between visits.
 */
internal fun groupEffects(
    articles: List<EffectArticle>,
    query: String,
    broad: String? = null,
    specific: String? = null,
    searchText: EffectSearchText? = null,
): List<EffectGroup> {
    val needle = query.trim().lowercase()
    var matching = when {
        specific != null -> articles.filter { specific in it.categories }
        broad != null -> articles.filter { broad in it.categories }
        else -> articles
    }
    if (needle.isNotEmpty()) {
        matching = matching.filter { article ->
            if (searchText != null) searchText.matches(article, needle)
            else article.name.lowercase().contains(needle) ||
                article.summary.lowercase().contains(needle) ||
                article.body.lowercase().contains(needle)
        }
    }
    // An active filter names the list, so dual-category articles stay in the picked group.
    val filterLabel = specific?.let { categoryLabel(it) } ?: broad?.let { categoryLabel(it) }
    if (filterLabel != null) {
        return listOf(EffectGroup(filterLabel, matching.sortedBy { it.name.lowercase() }))
    }
    val buckets = mutableMapOf<String, MutableList<EffectArticle>>()
    matching.forEach { article ->
        val category = CATEGORY_KEYS.firstOrNull { it in article.categories } ?: "other"
        buckets.getOrPut(category) { mutableListOf() } += article
    }
    return (CATEGORY_KEYS + "other").mapNotNull { key ->
        buckets[key]?.let { list ->
            EffectGroup(label = categoryLabel(key), articles = list.sortedBy { it.name.lowercase() })
        }
    }
}

internal fun categoryLabel(key: String): String = when (key) {
    "cognitive" -> "Cognitive"
    "physical" -> "Physical"
    "sensory" -> "Sensory"
    "visual" -> "Visual"
    "auditory" -> "Auditory"
    "tactile" -> "Tactile"
    "gustatory" -> "Gustatory"
    "olfactory" -> "Olfactory"
    "multisensory" -> "Multisensory"
    else -> "Other"
}

internal fun sourceLabel(article: EffectArticle): String = when (article.source) {
    "psychonautwiki" -> "PsychonautWiki, CC BY-SA 4.0"
    else -> "DoseWiki, CC0"
}

@Composable
internal fun EffectsLibrary(
    query: String,
    listState: LazyListState,
    broad: String? = null,
    specific: String? = null,
    onEffectClick: (String) -> Unit = {},
) {
    val articles by EffectLibrary.articles.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { EffectLibrary.ensureLoaded() }
    }
    val searchText = remember(articles) { EffectSearchText(articles) }
    val filteredGroups = remember(articles, query, broad, specific, searchText) {
        groupEffects(articles, query, broad, specific, searchText)
    }
    val showEmptyState = rememberEmptyStateVisible(isEmpty = articles.isEmpty() || filteredGroups.isEmpty())

    if (filteredGroups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (showEmptyState) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = if (query.isNotBlank()) "No effects match \"$query\"" else "No effect articles bundled",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        filteredGroups.forEach { group ->
            item(key = "header:${group.label}") {
                Text(
                    text = "${group.label} (${group.articles.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(group.articles, key = { "effect:${it.name}" }) { article ->
                AnimatedListItem {
                    EffectCard(article = article, onClick = { onEffectClick(article.name) })
                }
            }
        }
        item(key = "effects-bottom") { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun EffectCard(article: EffectArticle, onClick: () -> Unit) {
    AppCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = article.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val text = article.summary.ifBlank { article.body }
        if (text.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = sourceLabel(article),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            val matched = article.matchedName
            if (matched != null && matched != article.name) {
                Text(
                    text = "about $matched",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
