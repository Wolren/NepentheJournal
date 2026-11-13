package app.journal.ui.pet

import app.journal.util.decodeImage

/** The pet that ships inside the app, so a phone has a companion without a folder to install. */
object BundledPet {
    const val SLUG = "lumen"
    const val DISPLAY_NAME = "Lumen"

    private const val FRAME_WIDTH = 96
    private const val FRAME_HEIGHT = 104

    /** Rows follow scripts/gen_bundled_pet.py; the generator and this list must change together. */
    private val clips = listOf(
        PetClip("idle", 0, 6, fps = 6.5f, restMs = 3400),
        PetClip("wave", 1, 4, fps = 6.5f, restMs = 2000),
        PetClip("run", 2, 6, fps = 11f, restMs = 1400),
        PetClip("fail", 3, 8, fps = 6f, loop = false),
        PetClip("checkin", 4, 6, fps = 6f, loop = false),
    )

    val manifest: PetManifest = PetManifest(
        id = SLUG,
        displayName = DISPLAY_NAME,
        frameWidth = FRAME_WIDTH,
        frameHeight = FRAME_HEIGHT,
        clips = clips,
    )

    private val sheet: PetSheet? by lazy {
        decodeImage(LUMEN_SHEET_BASE64)?.let { PetSheet(it, FRAME_WIDTH, FRAME_HEIGHT, clips) }
    }

    /** The catalog entry, or null where the platform cannot decode the embedded sheet. */
    fun info(): PetInfo? = if (sheet != null) PetInfo(SLUG, DISPLAY_NAME) else null

    fun sheet(): PetSheet? = sheet
}
