package app.journal.ui.substances.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.journal.model.Substance
import app.journal.ui.components.BrandIcons

/**
 * Article buttons for one substance. A button exists only when the matching
 * record exists, so a tap never lands on a missing article.
 */
@Composable
internal fun ArticleLinkIcons(substance: Substance, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val links = buildList {
        substance.dosewikiSlug?.takeIf { it.isNotBlank() }?.let {
            add(Link("dosewiki", null, "Open DoseWiki article", "https://dose.wiki/$it"))
        }
        substance.pwikiId?.takeIf { it.isNotBlank() }?.let {
            add(Link("psychonautwiki", null, "Open PsychonautWiki article", "https://psychonautwiki.org/wiki/$it"))
        }
        if (substance.wikipediaData != null) {
            val query = substance.name.replace(" ", "+")
            add(Link("wikipedia", null, "Open Wikipedia article",
                "https://en.wikipedia.org/wiki/Special:Search?search=$query"))
        }
        substance.cid?.let {
            add(Link(null, Icons.Default.Science, "Open PubChem entry",
                "https://pubchem.ncbi.nlm.nih.gov/compound/$it"))
        }
        substance.drugbankId?.takeIf { it.isNotBlank() }?.let {
            add(Link(null, Icons.Default.Medication, "Open DrugBank entry", "https://go.drugbank.com/drugs/$it"))
        }
    }
    if (links.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        links.forEach { link ->
            IconButton(onClick = { uriHandler.openUri(link.url) }) {
                val brand = remember(link.brand) { link.brand?.let { BrandIcons.get(it) } }
                when {
                    brand != null -> Image(
                        brand,
                        contentDescription = link.description,
                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    link.icon != null -> Icon(
                        link.icon,
                        contentDescription = link.description,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

private data class Link(
    val brand: String?,
    val icon: ImageVector?,
    val description: String,
    val url: String,
)
