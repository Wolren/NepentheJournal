package app.journal.ui.keybind

import kotlin.test.Test
import kotlin.test.assertEquals

class KeybindTest {

    @Test
    fun labelCarriesModifiers() {
        assertEquals("Ctrl+K", Keybind(BindableKey.K).label())
        assertEquals("Ctrl+Shift+P", Keybind(BindableKey.P, shift = true).label())
        assertEquals("F11", Keybind(BindableKey.F11, ctrl = false).label())
    }

    @Test
    fun oneActionChangesOneBind() {
        val settings = KeybindSettings()
        val next = KeybindAction.PALETTE.with(settings, Keybind(BindableKey.P, shift = true))

        assertEquals(BindableKey.P, next.palette.key)
        assertEquals(BindableKey.K, settings.palette.key)
        assertEquals(BindableKey.F, next.search.key)
        assertEquals(BindableKey.F11, next.fullscreen.key)
    }

    @Test
    fun everyActionReadsBackItsOwnBind() {
        val settings = KeybindSettings(
            search = Keybind(BindableKey.S),
            palette = Keybind(BindableKey.P),
            liveSession = Keybind(BindableKey.L),
            fullscreen = Keybind(BindableKey.F12, ctrl = false),
        )

        assertEquals(BindableKey.S, KeybindAction.SEARCH.of(settings).key)
        assertEquals(BindableKey.P, KeybindAction.PALETTE.of(settings).key)
        assertEquals(BindableKey.L, KeybindAction.LIVE_SESSION.of(settings).key)
        assertEquals(BindableKey.F12, KeybindAction.FULLSCREEN.of(settings).key)
    }
}
