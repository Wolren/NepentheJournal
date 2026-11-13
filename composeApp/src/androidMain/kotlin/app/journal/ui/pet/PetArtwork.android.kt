package app.journal.ui.pet

/** Only the bundled pet exists on Android: there is no petdex folder to install one into. */
actual fun availablePets(): List<PetInfo> = listOfNotNull(BundledPet.info())

actual fun loadPetSheet(slug: String): PetSheet? =
    BundledPet.sheet()?.takeIf { slug == BundledPet.SLUG }
