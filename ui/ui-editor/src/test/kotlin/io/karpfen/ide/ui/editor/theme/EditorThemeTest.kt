/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.ui.editor.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.karpfen.ide.core.lang.HighlightStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The editor palettes: complete syntax styles and readable text in both themes. */
class EditorThemeTest {

    /** The light and the dark editor theme by name. */
    private val themes = mapOf("light" to lightEditorTheme(), "dark" to darkEditorTheme())

    /** The WCAG contrast ratio of [a] and [b], from 1 to 21. */
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
