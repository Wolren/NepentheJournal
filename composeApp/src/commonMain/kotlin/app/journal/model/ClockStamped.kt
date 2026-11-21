package app.journal.model

/**
 * A synced row that carries a hybrid logical clock stamp (see
 * app.journal.util.Hlc). Local writes take a new stamp; sync applies preserve the
 * peer's stamp so both sides converge on the same value. Blank means the row was
 * written before snapshot version 10 and is ordered by updatedAt instead.
 */
interface ClockStamped : VaultDocument {
    val clock: String
}
