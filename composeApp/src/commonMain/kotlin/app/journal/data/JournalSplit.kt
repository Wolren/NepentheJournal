package app.journal.data

import app.journal.catalog.CatalogSource
import app.journal.model.VaultDocument
import app.journal.serde.AppJson
import kotlinx.serialization.encodeToString

/**
 * At-rest layout for the split store: `journal-data.json` carries user data and
 * `journal-library.json` carries substances, interactions and effects. Both use the
 * JournalSnapshot JSON, so export, recovery and schema checks stay unchanged.
 */
internal object JournalSplit {

    /** Decoded user/library pair plus what the caller must do about the library file. */
    data class Loaded(
        val snapshot: JournalSnapshot,
        val userParseError: String?,
        val libraryParseError: String?,
        val needsLibraryWrite: Boolean,
    )

    /**
     * Merge a decoded user file with an optional library file. A legacy full user
     * file (library rows still inline) wins when no library file exists; the library
     * file wins otherwise. A missing or unreadable library forces a rewrite.
     */
    fun merge(userText: String, libraryText: String?): Loaded {
        val user = decodeSnapshotWithRecovery(userText)
        val userSnapshot = user.snapshot
        // A bundled catalog owns the reference rows; user-created rows survive from either file.
        if (CatalogSource.bundledFingerprint() != null) {
            val library = libraryText?.let { text ->
                decodeSnapshotWithRecovery(text).snapshot
            }
            fun <T : VaultDocument> keepUser(rows: List<T>): List<T> =
                rows.filter { it.deviceOrigin != "system" }
            val substances = keepUser(userSnapshot.substances + library?.substances.orEmpty())
            val interactions = keepUser(userSnapshot.interactions + library?.interactions.orEmpty())
            val effects = keepUser(userSnapshot.effects + library?.effects.orEmpty())
            return Loaded(
                snapshot = userSnapshot.copy(
                    substances = substances.distinctBy { it.id },
                    interactions = interactions.distinctBy { it.id },
                    effects = effects.distinctBy { it.id },
                ),
                userParseError = user.parseError,
                libraryParseError = null,
                // Rows rescued from a legacy file must reach the library file on the next save.
                needsLibraryWrite = substances.isNotEmpty() ||
                    interactions.isNotEmpty() || effects.isNotEmpty(),
            )
        }
        if (libraryText == null) {
            return Loaded(
                snapshot = userSnapshot,
                userParseError = user.parseError,
                libraryParseError = null,
                needsLibraryWrite = true,
            )
        }
        val library = decodeSnapshotWithRecovery(libraryText)
        val lib = library.snapshot
        val libraryHasRows =
            lib.substances.isNotEmpty() || lib.interactions.isNotEmpty() || lib.effects.isNotEmpty()
        val userHasRows = userSnapshot.substances.isNotEmpty() ||
            userSnapshot.interactions.isNotEmpty() ||
            userSnapshot.effects.isNotEmpty()
        if (!libraryHasRows && userHasRows) {
            // An empty library file next to a legacy full user file: keep the user copy.
            return Loaded(
                snapshot = userSnapshot,
                userParseError = user.parseError,
                libraryParseError = library.parseError,
                needsLibraryWrite = true,
            )
        }
        return Loaded(
            snapshot = userSnapshot.copy(
                substances = lib.substances,
                interactions = lib.interactions,
                effects = lib.effects,
            ),
            userParseError = user.parseError,
            libraryParseError = library.parseError,
            needsLibraryWrite = library.parseError != null,
        )
    }

    /** User file payload: the full snapshot minus the three library lists. */
    fun userText(snapshot: JournalSnapshot): String =
        AppJson.json.encodeToString(
            snapshot.copy(
                substances = emptyList(),
                interactions = emptyList(),
                effects = emptyList(),
            )
        )

    /** Library file payload: user-created rows only when a catalog owns the reference data. */
    fun libraryText(snapshot: JournalSnapshot): String {
        val referenceOwned = CatalogSource.bundledFingerprint() != null
        fun <T : app.journal.model.VaultDocument> List<T>.userRows(): List<T> =
            if (referenceOwned) filter { it.deviceOrigin != "system" } else this
        return AppJson.json.encodeToString(
            JournalSnapshot(
                savedAt = snapshot.savedAt,
                substances = snapshot.substances.userRows(),
                interactions = snapshot.interactions.userRows(),
                effects = snapshot.effects.userRows(),
            )
        )
    }
}
