package io.karpfen.ide.ui.editor.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.karpfen.ide.core.lang.HighlightStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The editor palettes: complete syntax styles and readable text in both themes. */
class EditorThemeTest {

    private val themes = mapOf("light" to lightEditorTheme(), "dark" to darkEditorTheme())

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `every highlight style has a span style in both themes`() {
        themes.forEach { (name, theme) ->
            assertEquals(HighlightStyle.entries.toSet(), theme.styles.keys, name)
        }
    }

    @Test
    fun `syntax colours and the foreground are readable on the editor background`() {
        themes.forEach { (name, theme) ->
            val colours = theme.styles.mapValues { it.value.color } + mapOf(null to theme.foreground)
            colours.forEach { (style, colour) ->
                val ratio = contrast(colour, theme.background)
                assertTrue(ratio >= 4.5f, "$name $style: contrast $ratio")
            }
            assertTrue(contrast(theme.caret, theme.background) >= 4.5f, "$name caret")
            assertTrue(contrast(theme.gutterForeground, theme.gutterBackground) >= 3f, "$name line numbers")
        }
    }
}
