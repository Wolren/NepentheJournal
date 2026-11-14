package app.journal.data

import app.journal.log.Log
import app.journal.model.EffectArticle
import app.journal.util.readBundledResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Read-only library of bundled effect articles (/effects.json, built by
 * scripts/effects_build.py). Never part of the vault: articles are reference
 * data, so they are not saved and not synced.
 *
 * The bundle is parsed once. Call [ensureLoaded] off the main thread; 677 KB
 * of JSON takes tens of milliseconds and the UI only needs the result.
 */
object EffectLibrary {

    private const val RESOURCE_PATH = "/effects.json"

    @Serializable
    private data class Bundle(
        val generatedAt: String = "",
        val licenses: Map<String, String> = emptyMap(),
        val articles: List<EffectArticle> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val _articles = MutableStateFlow<List<EffectArticle>>(emptyList())
    val articles: StateFlow<List<EffectArticle>> = _articles.asStateFlow()

    private val _generatedAt = MutableStateFlow("")
    val generatedAt: StateFlow<String> = _generatedAt.asStateFlow()

    private val _licenses = MutableStateFlow<Map<String, String>>(emptyMap())
    val licenses: StateFlow<Map<String, String>> = _licenses.asStateFlow()

    private var byName: Map<String, EffectArticle>? = null

    /** Parses the bundle once; safe to call again. Returns true when articles are available. */
    fun ensureLoaded(): Boolean {
        byName?.let { return true }
        val text = readBundledResource(RESOURCE_PATH)
        if (text == null) {
            Log.withTag("Effects").w { "Resource $RESOURCE_PATH not found" }
            return false
        }
        return try {
            val bundle = json.decodeFromString<Bundle>(text)
            val articles = bundle.articles.filter { it.name.isNotBlank() }
            byName = articles.associateBy { key(it.name) }
            _articles.value = articles
            _generatedAt.value = bundle.generatedAt
            _licenses.value = bundle.licenses
            Log.withTag("Effects").i { "Loaded ${articles.size} effect articles (${bundle.generatedAt})" }
            true
        } catch (e: Exception) {
            Log.withTag("Effects").e { "Failed to parse $RESOURCE_PATH: ${e.message}" }
            false
        }
    }

    /** Article for an effect label as the substance data spells it. */
    fun article(name: String): EffectArticle? = byName?.get(key(name))

    /** Lookup form shared by the bundle builder and the app. */
    fun key(name: String): String = name.lowercase().replace(NON_ALNUM, " ").trim()

    private val NON_ALNUM = Regex("[^a-z0-9]+")
}
