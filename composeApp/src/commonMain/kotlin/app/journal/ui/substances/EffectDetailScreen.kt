package app.journal.ui.substances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.journal.data.EffectLibrary
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

    ScreenScaffold(title = article.name, onBack = onBack, selectable = true) {
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                if (article.categories.isNotEmpty()) {
                    Text(
                        text = article.categories.joinToString("  ·  ") { label ->
                            if (label in CATEGORY_KEYS) categoryLabel(label)
                            else label.replaceFirstChar { it.uppercase() }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                val matched = article.matchedName
                if (matched != null && matched != article.name) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "This text describes $matched.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                EffectBody(blocks)
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
