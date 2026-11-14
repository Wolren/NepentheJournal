package app.journal.ui.substances

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.EffectLibrary
import app.journal.data.IJournalRepository
import app.journal.ui.components.AppCard
import app.journal.ui.components.NotFoundBox
import app.journal.ui.components.ScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full effect article, shaped like the substance detail: cards on a scaffold
 * instead of a dialog, so long texts read properly on a phone.
 */
@Composable
internal fun EffectDetailScreen(
    effectName: String,
    onBack: () -> Unit,
    repo: IJournalRepository? = null,
    onOpenEffect: (String) -> Unit = {},
    onOpenSubstance: (String) -> Unit = {},
) {
    val articles by EffectLibrary.articles.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { EffectLibrary.ensureLoaded() }
    }
    val article = remember(articles, effectName) { EffectLibrary.article(effectName) }
    if (article == null) {
        NotFoundBox("Effect not found", onBack = onBack)
        return
    }
    val blocks = remember(article.body) {
        parseEffectBlocks(article.body.ifBlank { article.summary })
    }
    val uriHandler = LocalUriHandler.current

    // Effect to effect and external links both flow through one handler.
    val onLink: (String) -> Unit = { target ->
        when {
            target.startsWith("effect:") -> {
                val slug = target.removePrefix("effect:")
                val linked = EffectLibrary.articleBySlug(slug)
                if (linked != null) onOpenEffect(linked.name)
                else uriHandler.openUri("https://dose.wiki/effects/$slug")
            }
            target.startsWith("url:") -> uriHandler.openUri(target.removePrefix("url:"))
        }
    }

    val reporters = reportersFor(repo, article.name)

    ScreenScaffold(title = article.name, onBack = onBack, selectable = true) {
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                val matched = article.matchedName
                if (matched != null && matched != article.name) {
                    Text(
                        text = "This text describes $matched.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                EffectBody(blocks = blocks, onLinkClick = onLink)
            }
        }

        if (reporters.isNotEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Reported by ${reporters.size} substances",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinkChips(
                        links = reporters.map { it.second to "substance:${it.first}" },
                        onOpen = { target -> onOpenSubstance(target.removePrefix("substance:")) },
                    )
                }
            }
        }

        if (article.seeAlso.isNotEmpty()) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "See also",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinkChips(
                        links = article.seeAlso.map { it.label to it.target },
                        onOpen = onLink,
                    )
                }
            }
        }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Text from ${sourceLabel(article)}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (article.url.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { uriHandler.openUri(article.url) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("Open the source page")
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkChips(links: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        links.forEach { (label, target) ->
            val shape = RoundedCornerShape(6.dp)
            // A clickable Surface enforces a 48dp touch target, which doubles the row gap.
            Surface(
                modifier = Modifier.clip(shape).clickable { onOpen(target) },
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                ),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Substances that report this effect, from the ingested effect rows and the flat lists. */
@Composable
private fun reportersFor(repo: IJournalRepository?, effectName: String): List<Pair<String, String>> {
    if (repo == null) return emptyList()
    val effects by repo.effects.collectAsState()
    val substancesById by repo.substancesById.collectAsState(initial = emptyMap())
    return remember(effects, substancesById, effectName) {
        val ids = linkedSetOf<String>()
        effects.filter { it.name.equals(effectName, ignoreCase = true) }
            .forEach { record -> ids += record.substanceIds }
        substancesById.values
            .filter { substance -> substance.effects.any { it.equals(effectName, ignoreCase = true) } }
            .forEach { substance -> ids += substance.id }
        ids.mapNotNull { id -> substancesById[id]?.let { id to it.name } }
            .sortedBy { it.second.lowercase() }
    }
}
