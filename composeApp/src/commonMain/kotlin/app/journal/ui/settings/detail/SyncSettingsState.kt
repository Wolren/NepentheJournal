package app.journal.ui.settings.detail

import app.journal.sync.TrustedDeviceInfo

/**
 * UI state for the sync settings panel.
 * Groups all mutable state to reduce parameter count on [SyncSettingsContent].
 */
data class SyncSettingsUiState(
    val manualHost: String = "",
    val manualPort: String = "4984",
    val manualToken: String = "",
    val continuousSync: Boolean = false,
    val isSyncing: Boolean = false,
    val syncExpanded: Boolean = false,
    val isStartingHost: Boolean = false,
    val isStoppingHost: Boolean = false,
    val logLines: List<String> = emptyList(),
    val trustedDevices: List<TrustedDeviceInfo> = emptyList(),
) {
    val hostError: String? get() =
        if (manualHost.isNotBlank() && !manualHost.matches(Regex("^[\\d.]+$")))
            "Invalid IP format" else null

    val portError: String? get() = (manualPort.toIntOrNull() ?: 0)
        .let { if (manualPort.isNotBlank() && it !in 1..65535) "Port must be 1-65535" else null }

    val isPortValid: Boolean get() = (manualPort.toIntOrNull() ?: 0) in 1..65535
    val isIpValid: Boolean get() = manualHost.isBlank() || manualHost.matches(Regex("^[\\d.]+$"))
}

/**
 * Callbacks for the sync settings panel.
 * Groups callback lambdas to reduce parameter count on [SyncSettingsContent].
 */
class SyncSettingsCallbacks(
    val onManualHostChange: (String) -> Unit,
    val onManualPortChange: (String) -> Unit,
    val onManualTokenChange: (String) -> Unit,
    val onContinuousSyncChange: (Boolean) -> Unit,
    val onSyncExpanded: () -> Unit,
    val onLogLine: (String) -> Unit,
    val onTrustedDevicesChange: (List<TrustedDeviceInfo>) -> Unit,
    val onIsSyncingChange: (Boolean) -> Unit,
    val onIsStartingHostChange: (Boolean) -> Unit,
    val onIsStoppingHostChange: (Boolean) -> Unit,
)
