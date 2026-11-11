package app.journal.ui.pet

/** Petdex sets live on the desktop; the phone has no companion. */
actual fun availablePets(): List<PetInfo> = emptyList()

actual fun loadPetSheet(slug: String): PetSheet? = null
