package app.journal.ui.keybind

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import app.journal.util.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Keys the shortcut editor accepts. A curated set keeps stored binds stable across platforms. */
enum class BindableKey(val label: String, val key: Key) {
    A("A", Key.A), B("B", Key.B), C("C", Key.C), D("D", Key.D), E("E", Key.E),
    F("F", Key.F), G("G", Key.G), H("H", Key.H), I("I", Key.I), J("J", Key.J),
    K("K", Key.K), L("L", Key.L), M("M", Key.M), N("N", Key.N), O("O", Key.O),
    P("P", Key.P), Q("Q", Key.Q), R("R", Key.R), S("S", Key.S), T("T", Key.T),
    U("U", Key.U), V("V", Key.V), W("W", Key.W), X("X", Key.X), Y("Y", Key.Y),
    Z("Z", Key.Z),
    F1("F1", Key.F1), F2("F2", Key.F2), F3("F3", Key.F3), F4("F4", Key.F4),
    F5("F5", Key.F5), F6("F6", Key.F6), F7("F7", Key.F7), F8("F8", Key.F8),
    F9("F9", Key.F9), F10("F10", Key.F10), F11("F11", Key.F11), F12("F12", Key.F12),
    SPACE("Space", Key.Spacebar),
}

@Serializable
data class Keybind(
    val key: BindableKey = BindableKey.K,
    val ctrl: Boolean = true,
    val shift: Boolean = false,
) {
    fun matches(event: KeyEvent): Boolean =
        event.key == key.key && event.isCtrlPressed == ctrl && event.isShiftPressed == shift

    fun label(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        append(key.label)
    }
}

@Serializable
data class KeybindSettings(
    val search: Keybind = Keybind(BindableKey.F),
    val palette: Keybind = Keybind(BindableKey.K),
    val liveSession: Keybind = Keybind(BindableKey.N),
    val fullscreen: Keybind = Keybind(BindableKey.F11, ctrl = false),
)

/** The four shortcuts the editor exposes, so the settings card and the handlers stay in step. */
enum class KeybindAction(val label: String) {
    SEARCH("Search"),
    PALETTE("Command palette"),
    LIVE_SESSION("Start live session"),
    FULLSCREEN("Full screen");

    fun of(settings: KeybindSettings): Keybind = when (this) {
        SEARCH -> settings.search
        PALETTE -> settings.palette
        LIVE_SESSION -> settings.liveSession
        FULLSCREEN -> settings.fullscreen
    }

    fun with(settings: KeybindSettings, bind: Keybind): KeybindSettings = when (this) {
        SEARCH -> settings.copy(search = bind)
        PALETTE -> settings.copy(palette = bind)
        LIVE_SESSION -> settings.copy(liveSession = bind)
        FULLSCREEN -> settings.copy(fullscreen = bind)
    }
}

object KeybindStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun path(): String = "${PlatformFile.dataDir()}/keybinds.json"

    fun load(): KeybindSettings = try {
        if (PlatformFile.size(path()) < 0) KeybindSettings()
        else json.decodeFromString(KeybindSettings.serializer(), PlatformFile.readText(path()))
    } catch (_: Exception) {
        KeybindSettings()
    }

    fun save(settings: KeybindSettings) {
        try {
            PlatformFile.writeText(path(), json.encodeToString(KeybindSettings.serializer(), settings))
        } catch (_: Exception) {
            // Best effort: a shortcut that cannot persist must not break the app.
        }
    }
}

class KeybindManager private constructor() {
    private val _settings = MutableStateFlow(KeybindStore.load())
    val settings: StateFlow<KeybindSettings> = _settings.asStateFlow()

    fun update(next: KeybindSettings) {
        _settings.value = next
        KeybindStore.save(next)
    }

    companion object {
        val instance: KeybindManager by lazy { KeybindManager() }
    }
}
