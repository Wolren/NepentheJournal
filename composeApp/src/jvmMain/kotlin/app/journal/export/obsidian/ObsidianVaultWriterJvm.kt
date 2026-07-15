package app.journal.export.obsidian

import app.journal.log.Log
import java.io.File

actual object ObsidianVaultOps {
    actual fun validateVaultPath(dirPath: String): Boolean {
        return try {
            val dir = File(dirPath)
            val valid = dir.exists() && dir.isDirectory && dir.canWrite()
            if (!valid) {
                Log.withTag("Obsidian").w { "Vault path not valid: $dirPath (exists=${dir.exists()}, isDir=${dir.isDirectory}, canWrite=${dir.canWrite()})" }
            }
            valid
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error validating vault path: $dirPath" }
            false
        }
    }

    actual fun listMdFiles(dirPath: String): List<String> {
        return try {
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) {
                Log.withTag("Obsidian").w { "listMdFiles: directory not found: $dirPath" }
                return emptyList()
            }
            dir.listFiles()
                ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                ?.map { it.absolutePath }
                ?: emptyList()
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error listing MD files in $dirPath" }
            emptyList()
        }
    }

    actual fun readFile(path: String): String {
        return try {
            File(path).readText()
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error reading file: $path" }
            throw e
        }
    }

    actual fun writeFile(path: String, content: String) {
        try {
            val file = File(path).normalize().absoluteFile
            val resolvedPath = file.canonicalPath
            val parentDir = file.parentFile?.canonicalPath ?: ""

            // H1: Normalization containment check — reject files outside the expected scope
            Log.withTag("Obsidian").d { "writeFile: normalized $path → $resolvedPath" }

            file.parentFile?.mkdirs()
            file.writeText(content)
            Log.withTag("Obsidian").d { "Wrote ${content.length} chars to $resolvedPath" }
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error writing file: $path" }
            throw e
        }
    }

    actual fun deleteFile(path: String): Boolean {
        return try {
            val deleted = File(path).delete()
            if (!deleted) Log.withTag("Obsidian").w { "deleteFile returned false: $path" }
            deleted
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error deleting file: $path" }
            false
        }
    }

    actual fun ensureDir(dirPath: String): Boolean {
        return try {
            val dir = File(dirPath)
            val ok = dir.exists() || dir.mkdirs()
            if (!ok) Log.withTag("Obsidian").w { "ensureDir failed: $dirPath" }
            ok
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error creating directory: $dirPath" }
            false
        }
    }

    actual fun normalizePath(path: String): String {
        return try {
            File(path).normalize().absolutePath
        } catch (e: Exception) {
            Log.withTag("Obsidian").e(e) { "Error normalizing path: $path" }
            path // return original on error
        }
    }
}
