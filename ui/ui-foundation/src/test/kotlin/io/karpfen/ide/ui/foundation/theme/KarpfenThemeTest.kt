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
package io.karpfen.ide.ui.foundation.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** [KarpfenTheme] provides the palette, the typography and a matching Material theme to its content. */
@OptIn(ExperimentalTestApi::class)
class KarpfenThemeTest {

    /** What a composable inside the theme sees. */
    private class Seen(
        val colors: KarpfenColors,
        val typography: KarpfenTypography,
        val scheme: ColorScheme,
        val materialTypography: Typography,
        val contentColor: Color,
        val textStyle: TextStyle,
        val scrollbar: ScrollbarStyle,
    )

    @Test
    fun `the light theme provides the light palette and the typography`() = runComposeUiTest {
        var seen: Seen? = null
        setContent { KarpfenTheme(darkTheme = false) { seen = see() } }

        val theme = assertNotNull(seen)
        assertEquals(lightKarpfenColors(), theme.colors)
        assertEquals(defaultKarpfenTypography(), theme.typography)
    }

    @Test
    fun `the dark theme provides the dark palette`() = runComposeUiTest {
        var seen: Seen? = null
        setContent { KarpfenTheme(darkTheme = true) { seen = see() } }

        assertEquals(darkKarpfenColors(), assertNotNull(seen).colors)
    }

    @Test
    fun `the Material theme follows the palette`() = runComposeUiTest {
        var darkTheme by mutableStateOf(false)
        var seen: Seen? = null
        setContent { KarpfenTheme(darkTheme = darkTheme) { seen = see() } }
        for (dark in listOf(false, true)) {
            darkTheme = dark
            waitForIdle()
            val theme = assertNotNull(seen)
            val colors = theme.colors

            assertEquals(colors.accent, theme.scheme.primary, "dark=$dark")
            assertEquals(colors.window, theme.scheme.background)
            assertEquals(colors.text, theme.scheme.onBackground)
            assertEquals(colors.panel, theme.scheme.surface)
            assertEquals(colors.text, theme.scheme.onSurface)
            assertEquals(colors.textMuted, theme.scheme.onSurfaceVariant)
            assertEquals(colors.border, theme.scheme.outline)
            assertEquals(colors.error, theme.scheme.error)
            assertEquals(if (dark) colors.panel else colors.window, theme.scheme.surfaceContainer, "menus")
            assertEquals(colors.text, theme.contentColor)
            assertEquals(theme.typography.ui, theme.textStyle)
            assertEquals(theme.typography.ui, theme.materialTypography.bodyMedium)
            assertEquals(theme.typography.uiSmall, theme.materialTypography.labelSmall)
            assertEquals(colors.text.copy(alpha = 0.22f), theme.scrollbar.unhoverColor)
        }
    }

    @Test
    fun `switching the theme recomposes the content with the other palette`() = runComposeUiTest {
        var dark by mutableStateOf(false)
        var seen: KarpfenColors? = null
        setContent { KarpfenTheme(darkTheme = dark) { seen = KarpfenTheme.colors } }
        assertEquals(lightKarpfenColors(), seen)

        dark = true
        waitForIdle()

        assertEquals(darkKarpfenColors(), seen)
    }

    @Composable
    private fun see() = Seen(
        colors = KarpfenTheme.colors,
        typography = KarpfenTheme.typography,
        scheme = MaterialTheme.colorScheme,
        materialTypography = MaterialTheme.typography,
        contentColor = LocalContentColor.current,
        textStyle = LocalTextStyle.current,
        scrollbar = LocalScrollbarStyle.current,
    )
}
