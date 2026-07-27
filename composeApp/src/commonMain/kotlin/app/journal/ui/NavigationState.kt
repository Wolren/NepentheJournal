package app.journal.ui

/**
 * Navigation state for the overlay stack in [App].
 * Sealed interface ensures exhaustive [when] coverage at compile time.
 */
sealed interface NavigationState {
    /** Main tab screen (Dashboard / Sessions / Substances / Safer / Settings) */
    data object Main : NavigationState
    /** Search overlay */
    data object Search : NavigationState
    /** Calendar overlay */
    data object Calendar : NavigationState
    /** Session editor (new or edit) */
    data object EditorSession : NavigationState
    /** Live session tracker */
    data object LiveSession : NavigationState
    /** Session detail timeline */
    data object Timeline : NavigationState
    /** Substance detail screen */
    data object DetailSubstance : NavigationState
    /** Substance editor (new or edit) */
    data object EditorSubstance : NavigationState
    /** Substance companion dashboard */
    data object CompanionSubstance : NavigationState
}
