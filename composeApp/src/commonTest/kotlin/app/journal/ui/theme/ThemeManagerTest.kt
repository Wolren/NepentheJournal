package app.journal.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ThemeManager cache-invalidation and pure contrast/blend math coverage
 * (audit section 4: ThemeManager lazy caches had zero tests).
 *
 * Uses the singleton on purpose: the cache lives there in production, so the
 * invalidation contract is only meaningful against the real instance. Every
 * test restores ThemeDefaults.Dark in @Before/@After.
 */
class ThemeManagerTest {

    private val manager get() = ThemeManager.instance

    @BeforeTest
    fun restoreDefault() = manager.update(ThemeDefaults.Dark)

    @AfterTest
    fun tearDown() = manager.update(ThemeDefaults.Dark)

    @Test
    fun colorSchemeIsCachedUntilConfigContentChanges() {
        val first = manager.colorScheme(true)
        assertTrue(first === manager.colorScheme(true),
            "identical config must be served from the cache (same instance)")

        manager.update(ThemeDefaults.Dark.copy(primaryColor = 0xFF112233))
        val second = manager.colorScheme(true)
        assertFalse(second === first, "a config change must invalidate the cache")
        assertEquals(Color(0xFF112233), second.primary,
            "recompute must pick up the new primary color")
        assertTrue(second === manager.colorScheme(true),
            "the recomputed scheme is cached again until the next change")
    }

    @Test
    fun cacheKeepsOneHashSlotAcrossArms() {
        // Prod keeps a SINGLE cachedConfigHash while storing both arms'
        // schemes: consecutive same-arm calls hit the cache, but flipping
        // isDark changes the hash and misses. Pinned as-is; if the cache is
        // ever split per arm (report-only observation, no prod change made),
        // this test flips loudly.
        val dark1 = manager.colorScheme(true)
        val light1 = manager.colorScheme(false)
        assertFalse(dark1 === light1, "different arms produce different schemes")
        assertTrue(light1 === manager.colorScheme(false),
            "consecutive light calls hit the shared hash slot")
        val dark2 = manager.colorScheme(true)
        assertFalse(dark2 === dark1,
            "flipping the arm misses: the single hash slot was overwritten by light")
        assertTrue(dark2 === manager.colorScheme(true),
            "consecutive dark calls hit the cache again")
        assertEquals(ThemeDefaults.Dark.baseTheme, manager.config.value.baseTheme,
            "fixture restored before the next test")
    }

    @Test
    fun updateReplacesTheConfigSnapshot() {
        manager.update(ThemeDefaults.Light)
        assertEquals(ThemeDefaults.Light, manager.config.value)
        manager.presetDark()
        assertEquals(ThemeDefaults.Dark, manager.config.value)
    }

    // ==================== pure contrast/blend math ====================

    @Test
    fun contrastColorPicksReadableForeground() {
        assertEquals(Color.Black, contrastColor(Color.White), "light backgrounds take black text")
        assertEquals(Color.White, contrastColor(Color.Black), "dark backgrounds take white text")
        assertEquals(Color.Black, contrastColor(Color(0xFF90CAF9)), "light accent stays readable")
        assertEquals(Color.White, contrastColor(Color(0xFF0E1318)), "dark background stays readable")
    }

    @Test
    fun blendIsLinearBoundedAndOpaque() {
        assertEquals(Color.Black, blend(Color.Black, Color.White, 0f), "ratio 0 keeps the base")
        assertEquals(Color.White, blend(Color.Black, Color.White, 1f), "ratio 1 keeps the overlay")
        assertEquals(Color.White, blend(Color.Black, Color.White, 5f), "ratios clamp to 1")

        val half = blend(Color.Black, Color.White, 0.5f)
        assertEquals(0.5f, half.red, 0.01f, "midpoint is the average of the channels")
        assertEquals(1f, half.alpha, 0.001f, "blend always returns an opaque color")
    }

    @Test
    fun luminanceOrdersBlackBelowWhite() {
        assertEquals(0f, Color.Black.luminance(), 0.001f, "black luminance is 0")
        assertTrue(Color.White.luminance() > 0.99f, "white luminance is ~1")
        assertTrue(Color.White.luminance() > Color.Black.luminance())
    }
}
