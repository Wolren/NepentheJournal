package app.journal.util

/**
 * Platform-agnostic file read/write that handles both filesystem paths and
 * content:// URIs (Android). Desktop actual delegates directly to java.io.File;
 * Android actual uses ContentResolver for content:// URIs.
 */
expect object PlatformFile {
    fun writeText(path: String, content: String)
    fun readText(path: String): String
    /** Platform-specific app data directory path. */
    fun dataDir(): String
}
