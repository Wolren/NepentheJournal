package app.journal.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure color-math coverage for ColorUtils (audit section 4, pure additions):
 * HSV -> ARGB conversion, hue recovery, and hex formatting/parsing round trips.
 */
class ColorUtilsTest {

    @Test
    fun hsvToArgbKnownPrimariesAndExtremes() {
        assertEquals(0xFFFF0000L, ColorUtils.hsvToArgb(0f, 1f, 1f, 255), "red")
        assertEquals(0xFF00FF00L, ColorUtils.hsvToArgb(120f, 1f, 1f, 255), "green")
        assertEquals(0xFF0000FFL, ColorUtils.hsvToArgb(240f, 1f, 1f, 255), "blue")
        assertEquals(0xFF000000L, ColorUtils.hsvToArgb(0f, 1f, 0f, 255), "value 0 is black")
        assertEquals(0xFFFFFFFFL, ColorUtils.hsvToArgb(0f, 0f, 1f, 255), "saturation 0 is white")
        assertEquals(0x00FFFFFFL, ColorUtils.hsvToArgb(0f, 0f, 1f, 0), "alpha 0 stays 0")
    }

    @Test
    fun toHsvHueRecoversPrimaryHues() {
        assertEquals(0f, ColorUtils.toHsvHue(Color.Red), 0.001f, "red hue is 0")
        assertEquals(120f, ColorUtils.toHsvHue(Color.Green), 0.001f, "green hue is 120")
        assertEquals(240f, ColorUtils.toHsvHue(Color.Blue), 0.001f, "blue hue is 240")
        assertEquals(0f, ColorUtils.toHsvHue(Color.White), 0.001f, "achromatic hue is 0")
    }

    @Test
    fun toHsvSaturationAndValue() {
        assertEquals(0f, ColorUtils.toHsvSat(Color.White), 0.001f, "white has no saturation")
        assertEquals(1f, ColorUtils.toHsvSat(Color(0f, 1f, 0f)), 0.001f, "pure green is fully saturated")
        assertEquals(0.75f, ColorUtils.toHsvVal(Color(0.25f, 0.5f, 0.75f)), 0.001f, "value = max channel")
        assertEquals(0f, ColorUtils.toHsvVal(Color.Black), 0.001f, "black value is 0")
    }

    @Test
    fun hexFormattingAndParsingRoundTrip() {
        assertEquals("#FFFF0000", ColorUtils.toHexColor(0xFFFF0000L), "uppercase 8-digit hex")
        assertEquals(0x80112233L, ColorUtils.parseArgb("#80112233"), "8-digit keeps its alpha")
        assertEquals(0xFF112233L, ColorUtils.parseArgb("#112233"), "6-digit implies opaque")
        assertEquals(0xFF112233L, ColorUtils.parseArgb("112233"), "leading # is optional")

        val color = 0xFF4287F5L
        assertEquals(color, ColorUtils.parseArgb(ColorUtils.toHexColor(color)),
            "parse(format(x)) == x for any ARGB value")

        assertNull(ColorUtils.parseArgb("#12345"), "wrong length is rejected")
        assertNull(ColorUtils.parseArgb("not-a-color"), "non-hex input is rejected")
        assertNull(ColorUtils.parseArgb("#GGHHII"), "non-hex digits are rejected")
    }
}
