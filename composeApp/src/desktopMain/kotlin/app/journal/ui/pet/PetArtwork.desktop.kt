package app.journal.ui.pet

import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image
import java.io.File

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

actual fun availablePets(): List<PetInfo> {
    val installed = petDirs()
        .distinctBy { slugOf(it) }
        .map { dir -> PetInfo(slugOf(dir), manifestOf(dir)?.displayName?.takeIf { it.isNotBlank() } ?: slugOf(dir)) }
    return (installed + listOfNotNull(BundledPet.info()))
        .distinctBy { it.slug }
        .sortedBy { it.slug }
}

actual fun loadPetSheet(slug: String): PetSheet? {
    val dir = petDirs().firstOrNull { slugOf(it) == slug }
    if (dir == null) return BundledPet.sheet()?.takeIf { slug == BundledPet.SLUG }
    val manifest = manifestOf(dir) ?: return null
    val file = File(dir, manifest.spritesheetPath)
    if (!file.isFile) return null
    val image = runCatching { Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        ?: return null
    return PetSheet(image, manifest.frameWidth, manifest.frameHeight, manifest.resolvedClips())
}
