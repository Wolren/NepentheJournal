package app.journal.ui.substances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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

private val CATEGORY_ORDER = listOf("cognitive", "physical", "sensory")

/**
 * Filter by free text, then group by the first curated category the article
 * carries. Order is fixed (cognitive, physical, sensory, other) so the list
 * does not reshuffle between visits.
 */
internal fun groupEffects(articles: List<EffectArticle>, query: String): List<EffectGroup> {
    val needle = query.trim().lowercase()
    val matching = if (needle.isEmpty()) {
        articles
    } else {
        articles.filter { article ->
            article.name.lowercase().contains(needle) ||
                article.summary.lowercase().contains(needle) ||
                article.body.lowercase().contains(needle)
        }
    }
    val buckets = mutableMapOf<String, MutableList<EffectArticle>>()
    matching.forEach { article ->
        val category = CATEGORY_ORDER.firstOrNull { it in article.categories } ?: "other"
        buckets.getOrPut(category) { mutableListOf() } += article
    }
    return (CATEGORY_ORDER + "other").mapNotNull { key ->
        buckets[key]?.let { list ->
            EffectGroup(label = categoryLabel(key), articles = list.sortedBy { it.name.lowercase() })
        }
    }
}

internal fun categoryLabel(key: String): String = when (key) {
    "cognitive" -> "Cognitive"
    "physical" -> "Physical"
    "sensory" -> "Sensory"
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
) {
    val articles by EffectLibrary.articles.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { EffectLibrary.ensureLoaded() }
    }
    val groups = remember(articles, query) { groupEffects(articles, query) }
    val showEmptyState = rememberEmptyStateVisible(isEmpty = articles.isEmpty() || groups.isEmpty())
    var selected by remember { mutableStateOf<EffectArticle?>(null) }

    if (groups.isEmpty()) {
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
    } else {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
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
                    AnimatedListItem { EffectCard(article = article, onClick = { selected = article }) }
                }
            }
            item(key = "effects-bottom") { Spacer(Modifier.height(72.dp)) }
        }
    }

    selected?.let { article ->
        EffectArticleDialog(article = article, onDismiss = { selected = null })
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

/** Full article, readable offline. The source link stays as a secondary action. */
@Composable
internal fun EffectArticleDialog(article: EffectArticle, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(article.name, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(article.body.ifBlank { article.summary }, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = sourceLabel(article),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = if (article.url.isNotBlank()) {
            {
                TextButton(onClick = { uriHandler.openUri(article.url) }) { Text("Open source") }
            }
        } else null,
    )
}
