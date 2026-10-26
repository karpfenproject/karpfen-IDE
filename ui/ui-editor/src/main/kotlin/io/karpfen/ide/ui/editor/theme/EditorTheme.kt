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

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import io.karpfen.ide.core.lang.HighlightStyle

/**
 * Colours of the code editor and its popups. [styles] maps every [HighlightStyle] to a span style (colour, font
 * weight/style); a missing style renders with [foreground].
 *
 * @property background the text area's background.
 * @property foreground text without a highlight style.
 * @property gutterBackground the gutter's background.
 * @property gutterForeground the line numbers.
 * @property currentLine the background of the caret's line.
 * @property selection the background of selected text.
 * @property caret the vertical bar of every caret.
 * @property matchingBracket the boxes around a matching bracket pair.
 * @property findMatch the background of find matches.
 * @property findMatchCurrent the background of the current find match.
 * @property error underlines and markers of errors.
 * @property warning underlines and markers of warnings.
 * @property info underlines and markers of information.
 * @property popupBackground the background of the popups.
 * @property popupBorder the border of the popups.
 * @property popupSelection the highlighted row of a popup list.
 */
@Immutable
public data class EditorTheme(
    public val background: Color,
    public val foreground: Color,
    public val gutterBackground: Color,
    public val gutterForeground: Color,
    public val currentLine: Color,
    public val selection: Color,
    public val caret: Color,
    public val matchingBracket: Color,
    public val findMatch: Color,
    public val findMatchCurrent: Color,
    public val error: Color,
    public val warning: Color,
    public val info: Color,
    public val popupBackground: Color,
    public val popupBorder: Color,
    public val popupSelection: Color,
    public val styles: Map<HighlightStyle, SpanStyle>,
)

/** The light editor theme. */
public fun lightEditorTheme(): EditorTheme = EditorPalettes.light

/** The dark editor theme. */
public fun darkEditorTheme(): EditorTheme = EditorPalettes.dark

/** The editor theme for the current composition; the workbench provides light or dark. */
public val LocalEditorTheme: ProvidableCompositionLocal<EditorTheme> = staticCompositionLocalOf { lightEditorTheme() }
