package app.journal.util

import androidx.compose.ui.graphics.ImageBitmap

// TODO: UIImage-backed PNG decode; brand icons are null (hidden) on iOS until then.
actual fun decodePngImage(base64: String): ImageBitmap? = null
