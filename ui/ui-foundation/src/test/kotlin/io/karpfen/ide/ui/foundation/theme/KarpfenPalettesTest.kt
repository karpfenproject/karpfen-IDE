package io.karpfen.ide.ui.foundation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The light and dark palettes and the typography (spec §6): distinct, legible, stable values. */
class KarpfenPalettesTest {

    private val palettes = mapOf("light" to lightKarpfenColors(), "dark" to darkKarpfenColors())

    /** WCAG 2 contrast ratio of two opaque colours. */
    private fun contrast(a: Color, b: Color): Double {
        val (lighter, darker) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertContrast(minimum: Double, foreground: Color, background: Color, what: String) {
        val ratio = contrast(foreground, background)
        assertTrue(ratio >= minimum, "$what: contrast %.2f < %.1f".format(ratio, minimum))
    }

    @Test
    fun `the palettes are stable values`() {
        assertSame(lightKarpfenColors(), lightKarpfenColors())
        assertEquals(darkKarpfenColors(), darkKarpfenColors())
        assertNotEquals(lightKarpfenColors(), darkKarpfenColors())
    }

    @Test
    fun `the light palette is light and the dark palette is dark`() {
        val light = lightKarpfenColors()
        val dark = darkKarpfenColors()

        assertTrue(light.window.luminance() > 0.8f && light.panel.luminance() > 0.8f)
        assertTrue(dark.window.luminance() < 0.05f && dark.panel.luminance() < 0.05f)
        assertTrue(light.text.luminance() < 0.05f)
        assertTrue(dark.text.luminance() > 0.6f)
    }

    @Test
    fun `every colour is opaque`() {
        palettes.forEach { (name, colors) ->
            listOf(
                colors.window, colors.panel, colors.border, colors.text, colors.textMuted, colors.accent,
                colors.selection, colors.focus, colors.error, colors.warning, colors.info,
            ).forEach { assertEquals(1f, it.alpha, "$name: $it") }
        }
    }

    @Test
    fun `text is legible on window, panel and selection (WCAG AA)`() {
        palettes.forEach { (name, colors) ->
            for ((background, label) in listOf(colors.window to "window", colors.panel to "panel", colors.selection to "selection")) {
                assertContrast(4.5, colors.text, background, "$name text on $label")
                assertContrast(4.5, colors.textMuted, background, "$name muted text on $label")
            }
        }
    }

    @Test
    fun `severity, accent and focus colours stand out from the window (WCAG non-text contrast)`() {
        palettes.forEach { (name, colors) ->
            assertContrast(3.0, colors.error, colors.window, "$name error")
            assertContrast(3.0, colors.warning, colors.window, "$name warning")
            assertContrast(3.0, colors.info, colors.window, "$name info")
            assertContrast(3.0, colors.accent, colors.window, "$name accent")
            assertContrast(3.0, colors.focus, colors.window, "$name focus")
        }
    }

    @Test
    fun `selection and border are visible but quiet`() {
        palettes.forEach { (name, colors) ->
            val selection = contrast(colors.selection, colors.window)
            val border = contrast(colors.border, colors.panel)
            assertTrue(selection in 1.1..4.5, "$name selection contrast $selection")
            assertTrue(border in 1.1..4.5, "$name border contrast $border")
        }
    }

    @Test
    fun `typography uses the platform UI font and a monospace font for code`() {
        val typography = defaultKarpfenTypography()

        assertEquals(FontFamily.SansSerif, typography.ui.fontFamily)
        assertEquals(FontFamily.SansSerif, typography.uiSmall.fontFamily)
        assertEquals(FontFamily.Monospace, typography.monospace.fontFamily)
        assertTrue(typography.uiSmall.fontSize < typography.ui.fontSize)
        assertEquals(13.sp, typography.monospace.fontSize)
        assertSame(typography, defaultKarpfenTypography())
    }
}
