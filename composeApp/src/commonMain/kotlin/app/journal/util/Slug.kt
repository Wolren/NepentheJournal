package app.journal.util

/**
 * The ONE slugify implementation for the whole app (wave4 dedup: there were
 * two copies with different Unicode rules and different empty fallbacks).
 *
 * Two historic call shapes are preserved BYTE-FOR-BYTE; output invariance is
 * pinned by `commonTest/util/SlugDedupTest.kt` against verbatim copies of
 * both pre-dedup bodies, and by the pre-existing ObsidianNoteRendererTest /
 * DosewikiTaxonomyTest expectations:
 *
 * - filename form ([asciiOnly] = false, the Obsidian export shape): keeps any
 *   Unicode letter or digit ("cafe" with an accent survives as-is), appends
 *   it lowercased, collapses every run of other characters into a single '-',
 *   trims leading/trailing '-', and returns [fallback] ("untitled") when the
 *   result is empty.
 * - DoseWiki form ([asciiOnly] = true): lowercases the WHOLE string first,
 *   keeps only [a-z0-9], collapses everything else to '-', trims '-', and
 *   returns [fallback] (DoseWiki callers pass "" and filter empties
 *   themselves), so "cafe" with an accent becomes "caf" exactly as before.
 *
 * Rendered output must never change: slug strings land in exported Obsidian
 * file names and dw:{slug} entity IDs, both persisted contracts.
 */
fun slugify(name: String, asciiOnly: Boolean = false, fallback: String = "untitled"): String {
    if (asciiOnly) {
        val out = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (out.isEmpty()) fallback else out
    }
    val out = StringBuilder()
    var prevDash = false
    for (ch in name) {
        if (ch.isLetterOrDigit()) {
            out.append(ch.lowercaseChar())
            prevDash = false
        } else if (!prevDash && out.isNotEmpty()) {
            out.append('-')
            prevDash = true
        }
    }
    while (out.endsWith('-')) out.deleteAt(out.length - 1)
    return if (out.isEmpty()) fallback else out.toString()
}
