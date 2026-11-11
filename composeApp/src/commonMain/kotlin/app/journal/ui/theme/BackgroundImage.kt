package app.journal.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Background image layer rendered behind app content: the image, a scrim in
 * the page colour, then the gradient that keeps the edges readable.
 */
@Composable
fun BackgroundImage(
    imagePath: String?,
    opacity: Float,
    gradient: BackdropGradient = BackdropGradient.NONE,
    scrim: Float = 0f,
    modifier: Modifier = Modifier
) {
    if (imagePath.isNullOrBlank()) return
    val page = MaterialTheme.colorScheme.background
    val gradientSpan = with(LocalDensity.current) { 420.dp.toPx() }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = backdropModel(imagePath),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = opacity
        )
        val scrimAlpha = scrim.coerceIn(0f, 0.92f)
        if (scrimAlpha > 0f) {
            Box(Modifier.fillMaxSize().background(page.copy(alpha = scrimAlpha)))
        }
        when (gradient) {
            BackdropGradient.NONE -> Unit
            BackdropGradient.TOP -> Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(page, Color.Transparent), startY = 0f, endY = gradientSpan)
                )
            )
            BackdropGradient.BOTTOM -> Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, page), startY = 0f, endY = gradientSpan * 2f)
                )
            )
            BackdropGradient.VIGNETTE -> Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, page),
                        center = Offset.Unspecified,
                        radius = gradientSpan * 2.4f
                    )
                )
            )
        }
    }
}

/** Coil needs a URI; a bare drive path such as D:/pictures/a.png is turned into a file URI. */
internal fun backdropModel(path: String): String {
    if (path.contains("://") || path.startsWith("data:")) return path
    val normalized = path.replace('\\', '/')
    return if (normalized.startsWith("/")) "file://$normalized" else "file:///$normalized"
}