package app.journal.ui.pet

/** iOS ships the bundled pet; there are no petdex folders to install a set into. */
actual fun availablePets(): List<PetInfo> = listOfNotNull(BundledPet.info())

actual fun loadPetSheet(slug: String): PetSheet? =
    BundledPet.sheet()?.takeIf { slug == BundledPet.SLUG }
