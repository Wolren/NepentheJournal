package app.journal.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.io.encoding.Base64
import org.jetbrains.skia.Image

/** iOS draws through Skiko, so the Skia decode matches desktop; null means the bytes are not an image. */
actual fun decodeImage(base64: String): ImageBitmap? = runCatching {
    Image.makeFromEncoded(Base64.decode(base64)).toComposeImageBitmap()
}.getOrNull()
