package app.journal.ui.pet

import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image
import java.io.File

@Serializable
private data class PetManifest(
    val id: String = "",
    val displayName: String = "",
    val spritesheetPath: String = "spritesheet.webp",
)

/** Frame counts per state row, identical in every petdex set: idle, two runs, wave, jump, fail, wait, run, review. */
private val RowFrames = listOf(6, 8, 8, 4, 5, 8, 6, 6, 6)

private const val FrameWidth = 192
private const val FrameHeight = 208

private fun petRoots(): List<File> {
    val home = System.getProperty("user.home") ?: return emptyList()
    return listOf(File(home, ".hermes/pets"), File(home, ".petdex/pets"))
}

private fun petDirs(): List<File> = petRoots().flatMap { root ->
    root.listFiles { file -> file.isDirectory && File(file, "pet.json").isFile }?.toList().orEmpty()
}

private fun manifestOf(dir: File): PetManifest? = runCatching {
    Json { ignoreUnknownKeys = true }
        .decodeFromString(PetManifest.serializer(), File(dir, "pet.json").readText())
}.getOrNull()

private fun slugOf(dir: File): String = dir.name

actual fun availablePets(): List<PetInfo> = petDirs()
    .distinctBy { slugOf(it) }
    .sortedBy { slugOf(it) }
    .map { dir ->
        val name = manifestOf(dir)?.displayName?.takeIf { it.isNotBlank() } ?: slugOf(dir)
        PetInfo(slug = slugOf(dir), displayName = name)
    }

actual fun loadPetSheet(slug: String): PetSheet? {
    val dir = petDirs().firstOrNull { slugOf(it) == slug } ?: return null
    val manifest = manifestOf(dir) ?: return null
    val file = File(dir, manifest.spritesheetPath)
    if (!file.isFile) return null
    val image = runCatching { Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        ?: return null
    return PetSheet(image, FrameWidth, FrameHeight, RowFrames)
}
