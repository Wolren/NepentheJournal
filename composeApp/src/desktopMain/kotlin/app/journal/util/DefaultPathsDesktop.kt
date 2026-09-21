package app.journal.util

actual fun defaultVaultPath(): String =
    System.getProperty("user.home") + "/Obsidian Vault"
