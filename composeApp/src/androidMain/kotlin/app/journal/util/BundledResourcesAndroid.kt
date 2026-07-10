package app.journal.util

import app.journal.NepentheApp

actual fun readBundledResource(path: String): String? {
    // Strip leading slash for Android assets path
    val assetPath = path.trimStart('/')
    return try {
        NepentheApp.appContext.assets.open(assetPath).bufferedReader().readText()
    } catch (_: Exception) { null }
}
