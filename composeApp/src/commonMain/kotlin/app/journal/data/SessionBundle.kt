package app.journal.data

import app.journal.model.Dose
import app.journal.model.Session

/**
 * A session paired with its doses for export.
 * Replaces raw [Pair] for type safety and named access.
 */
data class SessionBundle(
    val session: Session,
    val doses: List<Dose>,
)
