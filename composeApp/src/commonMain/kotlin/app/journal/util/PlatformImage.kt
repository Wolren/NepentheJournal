package app.journal.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a base64 PNG into an [ImageBitmap]. Platform-specific: Skia on
 * desktop, BitmapFactory on Android. Returns null when undecodable.
 */
expect fun decodePngImage(base64: String): ImageBitmap?
