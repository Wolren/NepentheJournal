package app.journal.util

/**
 * Read a bundled text resource (seed data, dose wiki, etc.).
 * Platform-specific: desktop uses Class.getResourceAsStream,
 * Android uses context.assets.open.
 *
 * @param path Resource path with leading slash, e.g. "/psychonautwiki_seed.json"
 * @return File contents, or null if resource not found
 */
expect fun readBundledResource(path: String): String?
