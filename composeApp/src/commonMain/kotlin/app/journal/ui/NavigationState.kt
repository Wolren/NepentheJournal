package app.journal.ui

/**
 * Navigation state for the overlay stack in [App].
 * Sealed interface ensures exhaustive [when] coverage at compile time.
 */
sealed interface NavigationState {
    data object Main : NavigationState
    data object Search : NavigationState
    data object Calendar : NavigationState
    data object EditorSession : NavigationState
    data object LiveSession : NavigationState
    data object Timeline : NavigationState
    data object DetailSubstance : NavigationState
    data object EditorSubstance : NavigationState
    data object CompanionSubstance : NavigationState
}
