package app.journal.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-off for `nepenthe://quicklog` links. The platform entry point (the
 * Android Activity today) drops the URI here; the UI consumes it once.
 */
object PendingQuickLog {

    private val _uri = MutableStateFlow<String?>(null)
    val uri: StateFlow<String?> = _uri.asStateFlow()

    fun offer(rawUri: String?) {
        if (rawUri.isNullOrBlank()) return
        _uri.value = rawUri
    }

    fun consume(): String? {
        val current = _uri.value
        _uri.value = null
        return current
    }
}
