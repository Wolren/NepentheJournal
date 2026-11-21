package app.journal.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces [file] with [bytes] atomically: a crash leaves the old bytes or the
 * new ones, never a truncated file.
 */
internal fun replaceDurably(file: File, bytes: ByteArray) {
    val dir = file.absoluteFile.parentFile
    dir?.mkdirs()
    val tmp = File(dir, "${file.name}.new")
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    try {
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: Exception) {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
