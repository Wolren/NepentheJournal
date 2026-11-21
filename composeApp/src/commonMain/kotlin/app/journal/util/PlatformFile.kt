package app.journal.util

/**
 * Platform-agnostic file read/write that handles both filesystem paths and
 * content:// URIs (Android). Desktop actual delegates directly to java.io.File;
 * Android actual uses ContentResolver for content:// URIs.
 */
expect object PlatformFile {
    /**
     * Writes [content] to [path]. A plain path is replaced atomically: a crash
     * leaves the old file or the new one, never a truncated file. A content://
     * URI is written in place, because the provider owns that file.
     */
    fun writeText(path: String, content: String)
    fun readText(path: String): String
    fun writeBytes(path: String, content: ByteArray)
    fun readBytes(path: String): ByteArray
    /**
     * Size of the file at [path] in bytes, or -1 when it does not exist / the
     * size cannot be determined. Lets callers refuse oversized files BEFORE
     * readText materializes them in memory (audit: import reads the whole file
     * ahead of ExportImport's 50 MB check).
     */
    fun size(path: String): Long
    fun dataDir(): String
    /**
     * Copies [source] to [target], creating the parent directory, and returns
     * whether the copy landed. [source] may be a path or a content:// URI.
     */
    fun copyToLocal(source: String, target: String): Boolean
    /** Deletes [target]; true when the file is gone afterwards. */
    fun delete(target: String): Boolean
}
