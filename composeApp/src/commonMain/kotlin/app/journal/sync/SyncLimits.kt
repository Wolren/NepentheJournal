package app.journal.sync

import app.journal.util.currentTimeMillis

/**
 * Shared sync payload caps (commonMain).
 *
 * Single source of truth for every count and field-length limit enforced on
 * the sync wire. This replaces the former jvmMain SyncValidators.kt constants
 * and the iosMain IosSyncValidators.kt duplicates: values are the union of
 * both copies, with the JVM-strictest value winning on every conflict.
 *
 * Contract: docs/HARDENING-CONTRACTS-2026-09.md (sections d and e). Validators
 * and pull-page builders on every platform MUST read these constants instead
 * of redeclaring them.
 */
object SyncLimits {
    /** Generic per-collection count cap (sessions, doses, notes, events, and their tombstones). */
    const val MAX_ITEMS_DEFAULT = 500
    // Measured seed max is 325 substances; headroom for user customs.
    const val MAX_SUBSTANCES = 1000
    const val MAX_EFFECTS = 100
    const val MAX_INTERACTIONS = 100
    const val MAX_CUSTOM_UNITS = 100

    /** Free-text and identifier length caps. */
    const val MAX_FIELD_LEN = 65536
    const val MAX_ID_LEN = 128
    const val MAX_NAME_LEN = 200
    const val MAX_TITLE_LEN = 500
    const val MAX_UNIT_LEN = 20
    const val MAX_ROA_LEN = 50
    const val MAX_LABEL_LEN = 200
    const val MAX_UNIT_NAME_LEN = 100

    /** Nested list caps. */
    const val MAX_SUBSTANCE_ALIASES = 100
    const val MAX_INTERACTION_SOURCES = 50
    const val MAX_INTERACTION_SOURCE_LEN = 500
    const val MAX_EFFECT_SUBSTANCE_IDS = 500

    /** Value range caps. */
    const val MAX_DOSE_AMOUNT = 1_000_000
    const val MIN_SESSION_RATING = 1
    const val MAX_SESSION_RATING = 10
}

/**
 * Shared entity timestamp policy for sync and import validation.
 *
 * Contract section (e): these two constants are the ONLY accepted bounds.
 * The former iOS 2-year future margin is eliminated; ExportImport and the
 * ingest adapters must consume these same constants.
 */
object EntityTimePolicy {
    /** Earliest accepted entity timestamp: 2000-01-01T00:00:00Z (rejects epoch junk). */
    const val MIN_ENTITY_TIMESTAMP = 946684800000L

    /** Entity timestamps may be at most 1 day in the future (clock skew allowance). */
    const val FUTURE_MARGIN_MS = 86_400_000L

    /**
     * Entity timestamps must fall between 2000-01-01 and now plus 1 day.
     * [now] is injectable for tests; production callers use the default.
     */
    fun isReasonableEntityTime(ts: Long, now: Long = currentTimeMillis()): Boolean =
        ts in MIN_ENTITY_TIMESTAMP..(now + FUTURE_MARGIN_MS)
}
