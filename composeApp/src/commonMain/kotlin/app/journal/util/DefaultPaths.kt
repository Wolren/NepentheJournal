package app.journal.util

/**
 * Device-tailored default for the Obsidian vault location, shown as the
 * starting suggestion in Settings. Each platform points at its own
 * documents area; the user can still pick any folder or type a path.
 */
expect fun defaultVaultPath(): String
