package app.journal.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a base64 image (PNG or WebP) into an [ImageBitmap]. Platform-specific: Skia on
 * desktop, BitmapFactory on Android. Returns null when undecodable.
 */
expect fun decodeImage(base64: String): ImageBitmap?
