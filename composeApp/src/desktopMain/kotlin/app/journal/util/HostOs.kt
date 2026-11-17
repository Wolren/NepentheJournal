package app.journal.util

/** OS gate for the Win32 window calls: JNA cannot load User32 or Dwmapi elsewhere. */
internal object HostOs {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
