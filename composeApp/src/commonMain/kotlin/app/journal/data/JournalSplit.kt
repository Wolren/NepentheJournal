package app.journal.data

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

    /** Library file payload: the three library lists plus the snapshot envelope. */
    fun libraryText(snapshot: JournalSnapshot): String =
        AppJson.json.encodeToString(
            JournalSnapshot(
                savedAt = snapshot.savedAt,
                substances = snapshot.substances,
                interactions = snapshot.interactions,
                effects = snapshot.effects,
            )
        )
}
