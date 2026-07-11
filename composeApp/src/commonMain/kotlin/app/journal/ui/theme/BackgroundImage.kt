package app.journal.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Background image layer rendered behind app content.
 * Loads from a file path or URL with opacity control.
 * Only renders when [imagePath] is non-blank.
 */
@Composable
fun BackgroundImage(
    imagePath: String?,
    opacity: Float,
    modifier: Modifier = Modifier
) {
    if (imagePath.isNullOrBlank()) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imagePath,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = opacity
        )
    }
}
