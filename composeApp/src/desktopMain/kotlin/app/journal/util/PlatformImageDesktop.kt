package app.journal.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.io.encoding.Base64
import org.jetbrains.skia.Image

actual fun decodePngImage(base64: String): ImageBitmap? = runCatching {
    Image.makeFromEncoded(Base64.decode(base64)).toComposeImageBitmap()
}.getOrNull()
