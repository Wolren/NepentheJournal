package app.journal.util

/**
 * Platform-agnostic file read/write that handles both filesystem paths and
 * content:// URIs (Android). Desktop actual delegates directly to java.io.File;
 * Android actual uses ContentResolver for content:// URIs.
 */
expect object PlatformFile {
    fun writeText(path: String, content: String)
    fun readText(path: String): String
    /**
     * Size of the file at [path] in bytes, or -1 when it does not exist / the
     * size cannot be determined. Lets callers refuse oversized files BEFORE
     * readText materializes them in memory (audit: import reads the whole file
     * ahead of ExportImport's 50 MB check).
     */
    fun size(path: String): Long
    /** Platform-specific app data directory path. */
    fun dataDir(): String
}
