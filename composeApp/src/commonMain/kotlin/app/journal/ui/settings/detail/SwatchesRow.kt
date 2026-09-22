package app.journal.ui.settings.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Row of preset color swatches shown under the theme color pickers.
 * Relocated out of the dead theme settings card file: ThemeContent.kt is
 * the live caller.
 */
@Composable
internal fun SwatchesRow(current: Long, onPick: (Long) -> Unit) {
    val swatches = listOf(
        0xFF4CAF50L, 0xFF2E7D32L, 0xFF81C784L, 0xFFAED581L, 0xFF1B5E20L,
        0xFF1565C0L, 0xFF0D47A1L, 0xFF7B1FA2L, 0xFF4A148CL, 0xFFFFCC80L
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        swatches.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { swatch ->
                    val isSelected = current == swatch
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(swatch))
                            .clickable { onPick(swatch) }
                            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                            .then(Modifier.border(1.dp, Color.Black.copy(alpha = 0.2f), CircleShape)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) Icon(Icons.Default.Check, null,
                            tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
