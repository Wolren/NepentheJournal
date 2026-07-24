package app.journal.export.obsidian

import app.journal.log.Log
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

/**
 * iOS vault operations using NSFileManager.
 * Vault files are accessed via the filesystem directly — iOS can see
 * files in the Obsidian vault iCloud folder if the user grants access.
 */
@OptIn(ExperimentalForeignApi::class)
actual object ObsidianVaultOps {

    actual fun validateVaultPath(dirPath: String): Boolean {
        return try {
            val fileManager = NSFileManager.defaultManager
            val exists = fileManager.fileExistsAtPath(dirPath)
            val writable = fileManager.isWritableFileAtPath(dirPath)
            Log.withTag("Obsidian").w { "Vault path not valid: $dirPath (exists=$exists, writable=$writable)" }
            exists && writable
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error validating vault path: $dirPath" }
            false
        }
    }

    actual fun listMdFiles(dirPath: String): List<String> {
        return try {
            val fileManager = NSFileManager.defaultManager
            val contents = fileManager.contentsOfDirectoryAtPath(dirPath, error = null)
                ?: return emptyList()
            contents
                .filterIsInstance<NSString>()
                .filter { it.lastPathComponent.lowercase().endsWith(".md") }
                .map { "$dirPath/${it.lastPathComponent}" }
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error listing MD files in $dirPath" }
            emptyList()
        }
    }

    actual fun readFile(path: String): String {
        return try {
            NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
                ?: throw Error("Failed to read file: $path")
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error reading file: $path" }
            throw e
        }
    }

    actual fun writeFile(path: String, content: String) {
        try {
            val parentDir = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "." }
            ensureDir(parentDir)
            val written = (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            if (!written) {
                throw Error("writeToFile returned false for: $path")
            }
            Log.withTag("Obsidian").d { "Wrote ${content.length} chars to $path" }
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error writing file: $path" }
            throw e
        }
    }

    actual fun deleteFile(path: String): Boolean {
        return try {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
            true
        } catch (e: Exception) {
            Log.withTag("Obsidian").w { "deleteFile failed: $path — ${e.message}" }
            false
        }
    }

    actual fun ensureDir(dirPath: String): Boolean {
        return try {
            NSFileManager.defaultManager.createDirectoryAtPath(
                dirPath, withIntermediateDirectories = true, attributes = null, error = null
            )
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error creating directory: $dirPath" }
            false
        }
    }

    actual fun normalizePath(path: String): String {
        return try {
            val p = path.trimEnd('/')
            val isAbsolute = p.startsWith('/')
            val parts = p.split('/')
            val result = mutableListOf<String>()
            for (part in parts) {
                when (part) {
                    ".", "" -> { /* skip */ }
                    ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                    else -> result.add(part)
                }
            }
            val normalized = result.joinToString("/")
            if (isAbsolute) "/$normalized" else normalized
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error normalizing path: $path" }
            path
        }
    }
}
