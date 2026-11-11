package app.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Space the bar itself takes: the row height. */
val AppTopBarHeight = 48.dp

/** Space the shell reserves: the row, the hairline and the fade below it. */
val AppTopBarTotal = 59.dp

private val BarAlpha = 0.6f
private const val BarTint = 0.10f

/**
 * Translucent shell bar: the surface tint lets the window backdrop through,
 * a hairline closes it, and a short gradient softens the edge over content.
 */
@Composable
fun AppGlassTopBar(title: String, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    // A little primary in the glass separates the bar from a page that shares the surface tone.
    val glass = lerp(surface, MaterialTheme.colorScheme.primary, BarTint).copy(alpha = BarAlpha)
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppTopBarHeight)
                .background(glass)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = onSurface, maxLines = 1)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = onSurface)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Brush.verticalGradient(listOf(glass, Color.Transparent)))
        )
    }
}
